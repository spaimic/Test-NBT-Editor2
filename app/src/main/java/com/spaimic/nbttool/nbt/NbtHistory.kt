package com.spaimic.nbttool.nbt

import java.util.IdentityHashMap

/**
 * Per-NBT-root undo/redo history.
 *
 * IMPORTANT: the root is mutable, so history MUST be keyed by object identity
 * rather than equals()/hashCode(). A mutable Compound can change its hash code
 * every time a child is edited, which made the old WeakHashMap lose access to
 * its history after edits.
 *
 * Every mutation records a complete deep snapshot of the root. Because all
 * nested tags (compounds, lists, primitives and arrays) live underneath that
 * root, this gives Undo/Redo coverage for the entire NBT tree, not just the
 * root compound itself.
 */
object NbtHistory {
    private const val MAX = 50

    private data class Stack(
        val undo: ArrayDeque<NbtTag.Compound> = ArrayDeque(),
        val redo: ArrayDeque<NbtTag.Compound> = ArrayDeque()
    )

    /** Identity-based: never use mutable NBT contents as the map key. */
    private val histories = IdentityHashMap<NbtTag.Compound, Stack>()

    private fun stack(root: NbtTag.Compound): Stack =
        histories.getOrPut(root) { Stack() }

    /** Record the state immediately BEFORE a mutation. */
    fun recordBefore(root: NbtTag.Compound) {
        val s = stack(root)
        s.undo.addLast(copy(root))
        while (s.undo.size > MAX) s.undo.removeFirst()
        s.redo.clear()
    }

    fun canUndo(root: NbtTag.Compound): Boolean = stack(root).undo.isNotEmpty()

    fun canRedo(root: NbtTag.Compound): Boolean = stack(root).redo.isNotEmpty()

    /** Restore the previous complete tree state. */
    fun undo(root: NbtTag.Compound): Boolean {
        val s = stack(root)
        if (s.undo.isEmpty()) return false

        // Current state becomes the redo snapshot.
        s.redo.addLast(copy(root))
        restore(root, s.undo.removeLast())
        return true
    }

    /** Restore the next complete tree state. */
    fun redo(root: NbtTag.Compound): Boolean {
        val s = stack(root)
        if (s.redo.isEmpty()) return false

        // Current state becomes the undo snapshot.
        s.undo.addLast(copy(root))
        restore(root, s.redo.removeLast())
        return true
    }

    /** Deep-copy every supported NBT tag recursively. */
    private fun copy(root: NbtTag.Compound): NbtTag.Compound =
        NbtTag.Compound(
            LinkedHashMap(
                root.entries.mapValues {
                    com.spaimic.nbttool.clipboard.NbtClipboard.deepCopy(it.value)
                }
            )
        )

    /** Replace the target root's contents while keeping the root object itself. */
    private fun restore(target: NbtTag.Compound, snapshot: NbtTag.Compound) {
        target.entries.clear()
        target.entries.putAll(
            snapshot.entries.mapValues {
                com.spaimic.nbttool.clipboard.NbtClipboard.deepCopy(it.value)
            }
        )
    }
}
