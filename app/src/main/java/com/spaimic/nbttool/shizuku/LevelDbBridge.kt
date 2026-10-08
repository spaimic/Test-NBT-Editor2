package com.spaimic.nbttool.shizuku

import com.spaimic.nbttool.chunk.ChunkKeys

import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.DB
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import java.io.File

/**
 * Opens a LOCAL world's `db/` folder directly — since imported worlds live in
 * this app's own sandbox now, this is plain file access, no Shizuku involved.
 * Still uses the vendored, zlib-patched LevelDB (see table/FileChannelTable.java)
 * since Bedrock's compression format is the same regardless of where the files
 * physically live.
 *
 * Known Bedrock key conventions:
 *   ~local_player          — the local player's data
 *   player_server_<id>     — a remote/visited player's data
 *   player_<uuid>          — an offline/legacy player entry
 *   map_<id>               — a saved map item
 *   village_<uuid>_*       — village records (several sub-keys per village)
 */
class LevelDbBridge private constructor(private val db: DB, val dbDir: File) {

    data class KeyEntry(val displayName: String, val rawKey: ByteArray)

    fun listKeys(): List<KeyEntry> {
        val out = ArrayList<KeyEntry>()
        db.iterator().use {
            it.seekToFirst()
            while (it.hasNext()) {
                val entry = it.next()
                out.add(KeyEntry(decodeKeyName(entry.key), entry.key))
            }
        }
        return out.sortedBy { it.displayName }
    }

    fun read(rawKey: ByteArray): ByteArray? = db.get(rawKey)

    /**
     * Returns only keys beginning with [prefix].
     *
     * LevelDB keeps keys sorted, so seeking directly to the prefix means we do
     * NOT walk the entire database. This is the important fast path for the
     * multi-key lazy editors (players, structures, maps, villages).
     */
    fun listKeysByPrefix(prefix: String): List<KeyEntry> {
        val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
        val out = ArrayList<KeyEntry>()
        db.iterator().use { iterator ->
            iterator.seek(prefixBytes)
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val key = entry.key
                if (!key.startsWithBytes(prefixBytes)) break
                out.add(KeyEntry(decodeKeyName(key), key))
            }
        }
        return out.sortedBy { it.displayName }
    }

    /** Fast lookup for several related prefixes (for example VILLAGE_ records).
     * Each prefix gets its own LevelDB seek, so unrelated keys are never walked. */
    fun listKeysByPrefixes(prefixes: Collection<String>): List<KeyEntry> {
        val merged = LinkedHashMap<String, KeyEntry>()
        for (prefix in prefixes) {
            for (entry in listKeysByPrefix(prefix)) {
                // Preserve distinct raw keys even if two prefixes happen to decode
                // to the same display name.
                val identity = entry.displayName + ":" + entry.rawKey.contentToString()
                merged[identity] = entry
            }
        }
        return merged.values.sortedBy { it.displayName }
    }

    /** Full key listing with Minecraft chunk-storage records removed.
     * Chunk records are binary coordinate/tag keys and are not standalone NBT
     * documents. This is intentionally an explicit browser operation, not startup work. */
    fun listNonChunkKeys(): List<KeyEntry> {
        val out = ArrayList<KeyEntry>()
        db.iterator().use { iterator ->
            iterator.seekToFirst()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (com.spaimic.nbttool.chunk.ChunkKeys.parse(entry.key) != null) continue
                // Entity/actor records are already exposed through the per-chunk Entity Editor.
                // Their raw LevelDB keys are not useful as standalone browser entries:
                //   actorprefix + 8-byte actor ID
                //   digp + chunk coordinates (+ optional dimension)
                // Keep them out of the non-chunk NBT browser.
                if (com.spaimic.nbttool.chunk.EntityKeys.isActorPrefixKey(entry.key)) continue
                if (com.spaimic.nbttool.chunk.EntityKeys.isOverworldDigpKey(entry.key)) continue
                if (entry.key.size >= 4 &&
                    entry.key[0] == 'd'.code.toByte() && entry.key[1] == 'i'.code.toByte() &&
                    entry.key[2] == 'g'.code.toByte() && entry.key[3] == 'p'.code.toByte()) continue
                out.add(KeyEntry(decodeKeyName(entry.key), entry.key))
            }
        }
        return out.sortedBy { it.displayName }
    }

    private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }


    /** Direct lookup for printable Bedrock keys such as ~local_player, map_* and village_*.
     * This does not enumerate the database. Use it for lazy/on-demand access. */
    fun readName(name: String): ByteArray? = db.get(name.toByteArray(Charsets.UTF_8))

    /** Returns whether a printable key exists, without scanning the database. */
    fun containsName(name: String): Boolean = readName(name) != null

    fun write(rawKey: ByteArray, value: ByteArray) = db.put(rawKey, value)

    /** Atomically applies a group of LevelDB writes as one batch. */
    fun writeBatch(entries: Collection<Pair<ByteArray, ByteArray>>) {
        if (entries.isEmpty()) return
        db.createWriteBatch().use { batch ->
            for ((key, value) in entries) batch.put(key, value)
            db.write(batch)
        }
    }

    /**
     * Writes entries in ordered, bounded-size WriteBatch operations. The limit is
     * based on key + value bytes plus a small per-entry allowance for LevelDB's
     * batch record framing. Individual entries are never split across batches.
     *
     * This is intentionally separate from [writeBatch] so existing editor flows
     * keep their current atomic-batch behavior while large MCStructure placement
     * can avoid constructing one multi-megabyte WriteBatch.
     */
    fun writeBatchChunked(
        entries: Collection<Pair<ByteArray, ByteArray>>,
        maxBytes: Int = 64 * 1024
    ): BatchWriteStats {
        require(maxBytes > 0) { "maxBytes must be greater than zero." }
        if (entries.isEmpty()) return BatchWriteStats(0, 0L, 0L)

        val perEntryOverhead = 16L
        var batchCount = 0L
        var totalBytes = 0L
        var currentBytes = 0L
        var currentCount = 0
        var batch = db.createWriteBatch()

        fun flush() {
            if (currentCount == 0) return
            db.write(batch)
            batch.close()
            batchCount++
            currentBytes = 0L
            currentCount = 0
            batch = db.createWriteBatch()
        }

        try {
            for ((key, value) in entries) {
                val entryBytes = key.size.toLong() + value.size.toLong() + perEntryOverhead
                if (currentCount > 0 && currentBytes + entryBytes > maxBytes.toLong()) {
                    flush()
                }

                // Never split an individual LevelDB entry. A record larger than
                // maxBytes is committed as its own batch.
                batch.put(key, value)
                currentBytes += entryBytes
                currentCount++
                totalBytes += entryBytes
            }
            flush()
        } finally {
            batch.close()
        }

        return BatchWriteStats(batchCount, totalBytes, entries.size.toLong())
    }

    data class BatchWriteStats(
        val batchCount: Long,
        val estimatedBytes: Long,
        val entryCount: Long
    )

    fun delete(rawKey: ByteArray) = db.delete(rawKey)

    /** Full raw scan of every key/value pair. Kept for advanced/raw tooling. */
    fun forEachRawEntry(action: (key: ByteArray, value: ByteArray) -> Unit) {
        db.iterator().use {
            it.seekToFirst()
            while (it.hasNext()) {
                val entry = it.next()
                action(entry.key, entry.value)
            }
        }
    }

    /** Fast key-only scan. Unlike listKeys(), this does not decode, sort, or allocate KeyEntry objects. */
    fun forEachRawKey(action: (key: ByteArray) -> Unit) {
        db.iterator().use {
            it.seekToFirst()
            while (it.hasNext()) {
                action(it.next().key)
            }
        }
    }

    /** Reads chunk-local records by seeking to the binary X/Z prefix instead of scanning the world. */
    fun listChunkRawEntries(chunkX: Int, chunkZ: Int, dimension: Int = 0): List<Pair<ByteArray, ByteArray>> {
        val prefix = ByteArray(8)
        fun putI32LE(offset: Int, v: Int) {
            prefix[offset] = (v and 0xFF).toByte()
            prefix[offset + 1] = ((v ushr 8) and 0xFF).toByte()
            prefix[offset + 2] = ((v ushr 16) and 0xFF).toByte()
            prefix[offset + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        putI32LE(0, chunkX)
        putI32LE(4, chunkZ)
        val result = ArrayList<Pair<ByteArray, ByteArray>>()
        db.iterator().use { iterator ->
            iterator.seek(prefix)
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val key = entry.key
                if (key.size < prefix.size || (0 until prefix.size).any { key[it] != prefix[it] }) break
                val info = ChunkKeys.parse(key) ?: continue
                if (info.chunkX == chunkX && info.chunkZ == chunkZ && info.dimension == dimension) {
                    result += key.copyOf() to entry.value.copyOf()
                }
            }
        }
        return result
    }

    /** Reads all subchunk records for one chunk in the selected dimension using one LevelDB seek. */
    fun readSubchunks(chunkX: Int, chunkZ: Int, dimension: Int = 0): List<Pair<Int, ByteArray>> {
        val prefix = if (dimension == 0) ByteArray(9) else ByteArray(13)
        fun putI32LE(offset: Int, v: Int) {
            prefix[offset] = (v and 0xFF).toByte()
            prefix[offset + 1] = ((v ushr 8) and 0xFF).toByte()
            prefix[offset + 2] = ((v ushr 16) and 0xFF).toByte()
            prefix[offset + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        putI32LE(0, chunkX)
        putI32LE(4, chunkZ)
        if (dimension != 0) putI32LE(8, dimension)
        prefix[prefix.lastIndex] = 0x2F

        val result = ArrayList<Pair<Int, ByteArray>>()
        db.iterator().use { iterator ->
            iterator.seek(prefix)
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val key = entry.key
                if (key.size != prefix.size + 1) break
                if (!key.copyOfRange(0, prefix.size).contentEquals(prefix)) break
                result += key.last().toInt() to entry.value
            }
        }
        result.sortByDescending { it.first }
        return result
    }

    fun readOverworldSubchunks(chunkX: Int, chunkZ: Int): List<Pair<Int, ByteArray>> =
        readSubchunks(chunkX, chunkZ, 0)

    /**
     * One sequential pass over the whole database, emitting each chunk's
     * subchunk + biome records to [onChunk] as soon as that chunk's block of
     * keys ends. LevelDB keys sharing the same chunk-coordinate prefix are
     * always contiguous under byte-wise comparison (that's how sorted-key
     * stores compare byte strings), so this never needs to buffer more than
     * one chunk's raw bytes at a time — unlike materializing every chunk's
     * bytes into a map up front, which OOMs on large worlds.
     *
     * This is the memory-safe counterpart to a per-chunk readSubchunks()/
     * read() call: still only ONE iterator/snapshot setup for the whole
     * region (see [forEachRawEntry]), just without ever holding the whole
     * export region's raw bytes in memory at once. See
     * WholeMapExporter.renderRows, which additionally bounds how far this
     * scan can run ahead of the chunks it has actually consumed.
     */
    /**
     * Single sequential LevelDB pass for whole-map capture.
     *
     * Keys for one exact (x,z,dimension) share a common byte prefix in the
     * LevelDB sort order, so all relevant records for a chunk arrive together.
     * We parse the binary key inline to avoid allocating ChunkKeys.Info for every
     * database entry. This is intentionally read-only and uses one iterator.
     */
    fun scanChunkRecordsStreaming(
        dimension: Int,
        minChunkX: Int, maxChunkX: Int,
        minChunkZ: Int, maxChunkZ: Int,
        onProgress: (Int) -> Unit = {},
        onChunk: (chunkX: Int, chunkZ: Int, subchunks: List<Pair<Int, ByteArray>>, biome: ByteArray?) -> Unit
    ) {
        fun i32le(key: ByteArray, o: Int): Int =
            (key[o].toInt() and 0xFF) or
                ((key[o + 1].toInt() and 0xFF) shl 8) or
                ((key[o + 2].toInt() and 0xFF) shl 16) or
                ((key[o + 3].toInt() and 0xFF) shl 24)

        var curX = 0
        var curZ = 0
        var curStarted = false
        var curInBounds = false
        var curSubchunks = ArrayList<Pair<Int, ByteArray>>(8)
        var curBiome: ByteArray? = null
        var discovered = 0L
        val totalExpected =
            ((maxChunkX.toLong() - minChunkX.toLong() + 1L).coerceAtLeast(1L) *
                (maxChunkZ.toLong() - minChunkZ.toLong() + 1L).coerceAtLeast(1L))
        var lastProgress = -1

        fun reportProgress() {
            val p = (discovered * 100L / totalExpected)
                .toInt().coerceIn(0, 100)
            if (p != lastProgress) {
                lastProgress = p
                onProgress(p)
            }
        }

        fun flush() {
            if (curStarted && curInBounds && (curSubchunks.isNotEmpty() || curBiome != null)) {
                onChunk(curX, curZ, curSubchunks, curBiome)
                discovered++
                reportProgress()
            }
        }

        db.iterator().use { iterator ->
            iterator.seekToFirst()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val key = entry.key
                val value = entry.value

                val isOverworld = key.size == 9 || key.size == 10
                val isDimension = key.size == 13 || key.size == 14
                if (!isOverworld && !isDimension) continue

                val x = i32le(key, 0)
                val z = i32le(key, 4)
                val dim = if (isOverworld) 0 else i32le(key, 8)
                if (dim != dimension) continue

                val tagOffset = if (isOverworld) 8 else 12
                val tag = key[tagOffset].toInt() and 0xFF

                // Only records used by map rendering. Ignoring all other tags
                // also avoids needless allocations/dispatch for entities etc.
                if (tag != ChunkKeys.TAG_SUBCHUNK_PREFIX && tag != ChunkKeys.TAG_DATA_3D) {
                    continue
                }

                if (!curStarted || x != curX || z != curZ) {
                    flush()
                    curX = x
                    curZ = z
                    curInBounds = x in minChunkX..maxChunkX && z in minChunkZ..maxChunkZ
                    curSubchunks = ArrayList(8)
                    curBiome = null
                    curStarted = true
                }

                if (!curInBounds) continue

                if (tag == ChunkKeys.TAG_SUBCHUNK_PREFIX) {
                    if (key.size != tagOffset + 2) continue
                    curSubchunks.add(key[tagOffset + 1].toInt() to value)
                } else {
                    curBiome = value
                }
            }
        }

        flush()
        onProgress(100)
    }

    fun close() = db.close()

    fun decodeKeyName(key: ByteArray): String {
        val isPrintable = key.all { (it.toInt() and 0xFF) in 0x20..0x7E }
        return if (isPrintable) String(key, Charsets.US_ASCII)
               else key.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    companion object {
        /** Opens `<worldDir>/db` in place. */
        fun open(worldDir: File): LevelDbBridge {
            // Iq80DBFactory.USE_MMAP is a `static final` decided from this system property at
            // class-load time; force it off so reads go through FileChannelTable, the only one
            // of the two Table implementations patched with zlib support.
            System.setProperty("leveldb.mmap", "false")

            val dbDir = File(worldDir, "db")
            val options = Options().apply {
                // true so a brand-new world (level.dat only, no db/ yet — see
                // FlatWorldGenerator.buildNewWorldLevelDat) gets a fresh empty
                // database instead of throwing; existing worlds already have a
                // db/ folder, so this never actually triggers for them
                createIfMissing(true)
                // Bedrock's own C++ engine expects NONE or zlib — never write Snappy-compressed
                // tables back, or Minecraft itself may fail to read data we wrote.
                compressionType(CompressionType.NONE)
            }
            val db = Iq80DBFactory.factory.open(dbDir, options)
            return LevelDbBridge(db, dbDir)
        }

        /** Copies `db/` to a local backup folder before you start editing — plain file copy, no Shizuku. */
        fun backup(worldDir: File, backupsRoot: File): File {
            val dest = File(backupsRoot, "db_${worldDir.name}_${System.currentTimeMillis()}")
            File(worldDir, "db").copyRecursively(dest, overwrite = true)
            return dest
        }
    }
}
