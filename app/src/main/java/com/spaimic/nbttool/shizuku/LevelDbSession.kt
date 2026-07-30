package com.spaimic.nbttool.shizuku

import com.spaimic.nbttool.nbt.NbtTag

/**
 * Holds one open LevelDB bridge plus whichever entries have been parsed/edited so
 * far, so PlayerDbActivity (key list) and DbEntryEditorActivity (single-key tree
 * editor) can share the same in-memory NbtTag.Compound objects — editing a tree in
 * one activity is immediately reflected when we serialize it all back on save.
 */
object LevelDbSession {
    var bridge: LevelDbBridge? = null
    var worldsPath: String = ""
    var worldDir: String = ""

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
}
