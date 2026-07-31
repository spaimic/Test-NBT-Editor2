package com.spaimic.nbttool.shizuku

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

    fun write(rawKey: ByteArray, value: ByteArray) = db.put(rawKey, value)

    fun delete(rawKey: ByteArray) = db.delete(rawKey)

    /** Full raw scan of every key/value pair — used by the chunk map renderer to read subchunk data. */
    fun forEachRawEntry(action: (key: ByteArray, value: ByteArray) -> Unit) {
        db.iterator().use {
            it.seekToFirst()
            while (it.hasNext()) {
                val entry = it.next()
                action(entry.key, entry.value)
            }
        }
    }

    fun close() = db.close()

    private fun decodeKeyName(key: ByteArray): String {
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
                createIfMissing(false)
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
