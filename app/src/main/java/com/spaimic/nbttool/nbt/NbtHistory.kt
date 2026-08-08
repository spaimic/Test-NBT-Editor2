package com.spaimic.nbttool.nbt

import java.util.WeakHashMap

/** Per-NBT-root undo/redo history. Snapshots are deep copies and are capped to avoid
 * unbounded memory use while editing large trees. */
object NbtHistory {
    private const val MAX = 50
    private data class Stack(val undo: ArrayDeque<NbtTag.Compound> = ArrayDeque(),
                             val redo: ArrayDeque<NbtTag.Compound> = ArrayDeque())
    private val histories = WeakHashMap<NbtTag.Compound, Stack>()

    private fun stack(root: NbtTag.Compound) = histories.getOrPut(root) { Stack() }

    fun recordBefore(root: NbtTag.Compound) {
        val s = stack(root)
        s.undo.addLast(copy(root))
        while (s.undo.size > MAX) s.undo.removeFirst()
        s.redo.clear()
    }

    fun canUndo(root: NbtTag.Compound) = stack(root).undo.isNotEmpty()
    fun canRedo(root: NbtTag.Compound) = stack(root).redo.isNotEmpty()

    fun undo(root: NbtTag.Compound): Boolean {
        val s = stack(root)
        if (s.undo.isEmpty()) return false
        s.redo.addLast(copy(root))
        restore(root, s.undo.removeLast())
        return true
    }

    fun redo(root: NbtTag.Compound): Boolean {
        val s = stack(root)
        if (s.redo.isEmpty()) return false
        s.undo.addLast(copy(root))
        restore(root, s.redo.removeLast())
        return true
    }

    private fun copy(root: NbtTag.Compound): NbtTag.Compound =
        NbtTag.Compound(LinkedHashMap(root.entries.mapValues { com.spaimic.nbttool.clipboard.NbtClipboard.deepCopy(it.value) }))

    private fun restore(target: NbtTag.Compound, snapshot: NbtTag.Compound) {
        target.entries.clear()
        target.entries.putAll(snapshot.entries.mapValues { com.spaimic.nbttool.clipboard.NbtClipboard.deepCopy(it.value) })
    }
}
