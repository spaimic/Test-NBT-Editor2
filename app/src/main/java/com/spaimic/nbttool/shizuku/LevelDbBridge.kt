package com.spaimic.nbttool.shizuku

import org.iq80.leveldb.DB
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import java.io.File

/**
 * Bedrock stores player, map, and village data as key/value pairs in the world's
 * `db/` LevelDB folder (separate from level.dat, which only holds top-level world
 * settings). LevelDB needs random access to a whole set of files (.ldb, MANIFEST,
 * CURRENT, LOCK, LOG) — not just one file — so we pull/push the entire folder via
 * Shizuku and open it locally with a pure-Java LevelDB implementation.
 *
 * Known Bedrock key conventions:
 *   ~local_player          — the local player's data
 *   player_server_<id>     — a remote/visited player's data
 *   player_<uuid>          — an offline/legacy player entry
 *   map_<id>               — a saved map item
 *   village_<uuid>_*       — village records (several sub-keys per village)
 */
class LevelDbBridge private constructor(private val db: DB, val localDbDir: File) {

    data class KeyEntry(val displayName: String, val rawKey: ByteArray)

    fun listKeys(): List<KeyEntry> {
        val out = ArrayList<KeyEntry>()
        db.iterator().use { it ->
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

    fun close() = db.close()

    private fun decodeKeyName(key: ByteArray): String {
        // Most player/map/village keys are plain ASCII; anything with unprintable
        // bytes (chunk data, etc.) falls back to a hex dump so it's still visible.
        val isPrintable = key.all { (it.toInt() and 0xFF) in 0x20..0x7E }
        return if (isPrintable) String(key, Charsets.US_ASCII)
               else key.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    companion object {
        /** Pulls `<worldsPath>/<worldDir>/db` to app-private storage and opens it. */
        fun open(worldsPath: String, worldDir: String, localStagingRoot: File): LevelDbBridge {
            val remoteDbDir = "$worldsPath/$worldDir/db"
            val localDbDir = File(localStagingRoot, "db_$worldDir")
            ShizukuShell.pullDir(remoteDbDir, localDbDir.absolutePath)
            val options = Options().apply { createIfMissing(false) }
            val db = Iq80DBFactory.factory.open(localDbDir, options)
            return LevelDbBridge(db, localDbDir)
        }

        /** Closes (call before this!) then pushes the edited local db/ folder back into the sandbox, backing up the original. */
        fun pushBack(worldsPath: String, worldDir: String, localDbDir: File) {
            val remoteDbDir = "$worldsPath/$worldDir/db"
            ShizukuShell.pushDir(localDbDir.absolutePath, remoteDbDir)
        }
    }
}
