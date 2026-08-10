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

    /**
     * Records an undo snapshot only when the tree is reasonably small. A full
     * deep copy of a ~local_player containing hundreds of thousands or millions
     * of book pages can temporarily duplicate hundreds of MB and defeat the
     * large-file importer. Huge imports therefore deliberately skip the undo
     * snapshot; the imported data itself remains lossless.
     *
     * @return true when an undo snapshot was recorded, false when skipped.
     */
    fun recordBeforeIfSafe(root: NbtTag.Compound, maxNodes: Int = 100_000): Boolean {
        if (countNodes(root, maxNodes + 1) > maxNodes) {
            val s = stack(root)
            s.redo.clear()
            return false
        }
        recordBefore(root)
        return true
    }

    private fun countNodes(root: NbtTag.Compound, limit: Int): Int {
        var count = 0
        fun walk(tag: NbtTag) {
            if (count >= limit) return
            count++
            when (tag) {
                is NbtTag.Compound -> tag.entries.values.forEach(::walk)
                is NbtTag.ListTag -> tag.items.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return count
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
