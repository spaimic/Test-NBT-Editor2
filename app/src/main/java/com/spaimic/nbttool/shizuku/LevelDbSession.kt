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
}
