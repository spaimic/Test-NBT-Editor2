package com.spaimic.nbttool.shizuku

import com.spaimic.nbttool.nbt.NbtTag
import java.io.File

/**
 * Holds one open LevelDB bridge plus whichever entries have been parsed/edited so
 * far, so PlayerDbActivity (key list) and DbEntryEditorActivity (single-key tree
 * editor) can share the same in-memory NbtTag.Compound objects.
 */
object LevelDbSession {
    var bridge: LevelDbBridge? = null
    var worldDir: File? = null

    /** display name -> the raw LevelDB key bytes */
    val keyBytesByName = LinkedHashMap<String, ByteArray>()

    /** display name -> parsed (and possibly since-edited) compound */
    val loadedEntries = LinkedHashMap<String, NbtTag.Compound>()

    fun reset() {
        try { bridge?.close() } catch (_: Exception) { /* already closed or never fully opened */ }
        bridge = null
        keyBytesByName.clear()
        loadedEntries.clear()
    }

    /** Writes every currently-loaded entry back to the DB. Deliberately does NOT
     * close/reopen the bridge — that dance was only ever needed to work around
     * Shizuku's remote-write quirks from the old architecture; now that worlds
     * live entirely in this app's own sandbox, a plain write is enough. */
    fun saveAllLoadedEntries() {
        val b = bridge ?: return
        for ((name, compound) in loadedEntries) {
            val rawKey = keyBytesByName[name] ?: continue
            b.write(rawKey, com.spaimic.nbttool.nbt.NbtIO.writeRootCompoundNoHeader(compound))
        }
    }
}
