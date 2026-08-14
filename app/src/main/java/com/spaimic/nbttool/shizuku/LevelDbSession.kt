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

    /** Entries explicitly changed by the current editor operation. */
    val dirtyEntries = LinkedHashSet<String>()

    fun markDirty(name: String) { dirtyEntries.add(name) }

    fun clearDirty(name: String) { dirtyEntries.remove(name) }

    fun reset() {
        try { bridge?.close() } catch (_: Exception) { /* already closed or never fully opened */ }
        bridge = null
        keyBytesByName.clear()
        loadedEntries.clear()
        dirtyEntries.clear()
    }

    /** Writes every currently-loaded entry back to the DB. Deliberately does NOT
     * close/reopen the bridge — that dance was only ever needed to work around
     * Shizuku's remote-write quirks from the old architecture; now that worlds
     * live entirely in this app's own sandbox, a plain write is enough. */
    fun saveEntries(names: Collection<String>) {
        val b = bridge ?: throw IllegalStateException("World database isn't open.")
        // Deliberately write only the explicitly requested entries. Never serialize every
        // parsed entry just because it happens to be cached in memory. This is especially
        // important for image-map generation, where ~local_player and one map_<id> are the
        // only records that should change.
        val writes = names.distinct().mapNotNull { name ->
            val compound = loadedEntries[name] ?: return@mapNotNull null
            val rawKey = keyBytesByName[name] ?: name.toByteArray(Charsets.UTF_8)
            rawKey to com.spaimic.nbttool.nbt.NbtIO.writeRootCompoundNoHeader(compound)
        }
        // Serialize everything before touching LevelDB. If serialization fails, no partial
        // database update is performed. The final write is one LevelDB batch.
        b.writeBatch(writes)
        for (name in names.distinct()) {
            if (loadedEntries.containsKey(name)) dirtyEntries.remove(name)
        }
    }

    fun saveDirtyEntries() {
        saveEntries(dirtyEntries.toList())
    }

    fun saveAllLoadedEntries() {
        // Legacy callers retain their old behavior. New editor/image paths should use
        // saveEntries/saveDirtyEntries so unrelated cached records are never rewritten.
        saveEntries(loadedEntries.keys)
    }
}
