package com.spaimic.nbttool.ui

import android.app.AlertDialog
import android.content.Context
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.Typeface
import androidx.recyclerview.widget.RecyclerView
import com.spaimic.nbttool.clipboard.NbtClipboard
import com.spaimic.nbttool.nbt.NbtIcons
import com.spaimic.nbttool.nbt.NbtHistory
import com.spaimic.nbttool.nbt.NbtTag

/** Where a row's tag actually lives — a named slot in a compound, or an indexed slot in a list. */
sealed class NbtContainer {
    data class InCompound(val compound: NbtTag.Compound, val key: String) : NbtContainer()
    data class InList(val list: NbtTag.ListTag, val index: Int) : NbtContainer()

    // Array elements are scalar values, but they still need a writable location
    // so the normal Byte/Int/Long leaf editors can update them in place.
    data class InByteArray(val array: NbtTag.ByteArray, val index: Int, val owner: NbtContainer) : NbtContainer()
    data class InIntArray(val array: NbtTag.IntArray, val index: Int, val owner: NbtContainer) : NbtContainer()
    data class InLongArray(val array: NbtTag.LongArray, val index: Int, val owner: NbtContainer) : NbtContainer()
}

/** One flattened, indented row of the tree currently on screen. */
data class NbtRow(
    val label: String,
    val tag: NbtTag,
    val depth: Int,
    val container: NbtContainer
) {
    fun setValue(newTag: NbtTag) {
        when (val c = container) {
            is NbtContainer.InCompound -> c.compound.entries[c.key] = newTag
            is NbtContainer.InList -> c.list.items[c.index] = newTag
            is NbtContainer.InByteArray -> {
                require(newTag is NbtTag.Byte) { "ByteArray element requires a Byte tag." }
                c.array.value[c.index] = newTag.value
            }
            is NbtContainer.InIntArray -> {
                require(newTag is NbtTag.Int) { "IntArray element requires an Int tag." }
                c.array.value[c.index] = newTag.value
            }
            is NbtContainer.InLongArray -> {
                require(newTag is NbtTag.Long) { "LongArray element requires a Long tag." }
                c.array.value[c.index] = newTag.value
            }
        }
    }
}

/**
 * Flattens a (possibly nested) compound into an indented list — both Compounds
 * AND Lists expand in place now. Tapping a container expands/collapses it;
 * tapping a primitive edits its value; long-pressing any row opens a menu for
 * delete / rename (compound entries only) / add child / copy / paste.
 */
class NbtTreeAdapter(
    private val context: Context,
    private val root: NbtTag.Compound,
    private val onLeafTapped: (NbtRow) -> Unit,
    private val onChanged: () -> Unit = {}
) : RecyclerView.Adapter<NbtTreeAdapter.VH>() {

    private val expanded = HashSet<String>()
    private var rows: List<Pair<String, NbtRow>> = emptyList()
    private var attachedRecyclerView: RecyclerView? = null
    // Build 200: animate the children revealed by a Compound/List expansion.
    private var animationRootPath: String? = null
    private var animationGeneration = 0
    private val animatedPaths = HashSet<String>()

    init { rebuild() }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
        resizeForContent()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        attachedRecyclerView = null
    }

    /** Widens the RecyclerView to fit the deepest currently-visible nesting, so a
     * HorizontalScrollView parent can actually scroll to reveal content that
     * would otherwise run off the right edge on a heavily-nested tree. */
    private fun resizeForContent() {
        val rv = attachedRecyclerView ?: return
        val maxDepth = rows.maxOfOrNull { it.second.depth } ?: 0
        val screenWidth = context.resources.displayMetrics.widthPixels
        val neededWidth = 24 + (maxDepth + 1) * 48 + 900 // indent budget + icon/text budget
        val newWidth = maxOf(screenWidth, neededWidth)
        val lp = rv.layoutParams
        if (lp != null && lp.width != newWidth) {
            lp.width = newWidth
            rv.layoutParams = lp
        }
    }

    private fun rebuild() {
        val out = ArrayList<Pair<String, NbtRow>>()

        fun isExpandable(tag: NbtTag): Boolean = when (tag) {
            is NbtTag.Compound, is NbtTag.ListTag,
            is NbtTag.ByteArray, is NbtTag.IntArray, is NbtTag.LongArray -> true
            else -> false
        }

        fun walk(container: NbtTag, path: String, depth: Int, owner: NbtContainer? = null) {
            when (container) {
                is NbtTag.Compound -> {
                    for ((key, tag) in container.entries) {
                        val childPath = if (path.isEmpty()) key else "$path/$key"
                        val rowContainer = NbtContainer.InCompound(container, key)
                        out.add(childPath to NbtRow(key, tag, depth, rowContainer))
                        if (childPath in expanded && isExpandable(tag)) {
                            walk(tag, childPath, depth + 1, rowContainer)
                        }
                    }
                }
                is NbtTag.ListTag -> {
                    for ((index, tag) in container.items.withIndex()) {
                        val childPath = "$path[$index]"
                        val rowContainer = NbtContainer.InList(container, index)
                        out.add(childPath to NbtRow("[$index]", tag, depth, rowContainer))
                        if (childPath in expanded && isExpandable(tag)) {
                            walk(tag, childPath, depth + 1, rowContainer)
                        }
                    }
                }
                is NbtTag.ByteArray -> {
                    val parent = requireNotNull(owner)
                    for ((index, value) in container.value.withIndex()) {
                        val childPath = "$path[$index]"
                        out.add(childPath to NbtRow(
                            "[$index]", NbtTag.Byte(value), depth,
                            NbtContainer.InByteArray(container, index, parent)
                        ))
                    }
                }
                is NbtTag.IntArray -> {
                    val parent = requireNotNull(owner)
                    for ((index, value) in container.value.withIndex()) {
                        val childPath = "$path[$index]"
                        out.add(childPath to NbtRow(
                            "[$index]", NbtTag.Int(value), depth,
                            NbtContainer.InIntArray(container, index, parent)
                        ))
                    }
                }
                is NbtTag.LongArray -> {
                    val parent = requireNotNull(owner)
                    for ((index, value) in container.value.withIndex()) {
                        val childPath = "$path[$index]"
                        out.add(childPath to NbtRow(
                            "[$index]", NbtTag.Long(value), depth,
                            NbtContainer.InLongArray(container, index, parent)
                        ))
                    }
                }
                else -> {}
            }
        }

        walk(root, "", 0)
        rows = out
    }

    fun refresh() {
        rebuild()
        notifyDataSetChanged()
        resizeForContent()
    }

    /**
     * Global search walks the complete NBT object graph, even through collapsed
     * containers. Each result carries the exact tree path AND the exact list of
     * container paths that must be expanded to make that result visible.
     *
     * The old implementation tried to reconstruct those ancestors from the
     * flattened RecyclerView rows. That cannot work when an ancestor is itself
     * hidden, because hidden rows do not exist in `rows`. Build 93 therefore
     * keeps navigation data from the source tree instead of from the current
     * UI state.
     */
    /** A lossless path component. String keys are never encoded into a slash-delimited
     * string, so unusual NBT names containing '/', '[', or ']' remain unambiguous. */
    private sealed class PathPart {
        data class Key(val value: String) : PathPart()
        data class Index(val value: Int) : PathPart()
    }

    private data class SearchMatch(
        val path: List<PathPart>,
        val displayPath: String,
        val label: String,
        val ancestors: List<List<PathPart>>
    )

    private fun pathEquals(a: List<PathPart>, b: List<PathPart>): Boolean = a == b

    private fun displayPath(path: List<PathPart>): String = buildString {
        path.forEachIndexed { i, part ->
            when (part) {
                is PathPart.Key -> {
                    if (i > 0) append('/')
                    append(part.value)
                }
                is PathPart.Index -> append('[').append(part.value).append(']')
            }
        }
    }

    fun showGlobalSearch() {
        val input = EditText(context).apply { hint = "Tag name or value" }

        AlertDialog.Builder(context)
            .setTitle("Global NBT Search")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isEmpty()) return@setPositiveButton

                val matches = ArrayList<SearchMatch>()

                fun arrayMatches(
                    path: List<PathPart>,
                    ancestors: List<List<PathPart>>,
                    size: Int,
                    typeName: String,
                    valueAt: (Int) -> String
                ) {
                    for (i in 0 until size) {
                        val childPath = path + PathPart.Index(i)
                        val value = valueAt(i)
                        if (value.contains(query, true)) {
                            matches += SearchMatch(
                                path = childPath,
                                displayPath = displayPath(childPath),
                                label = "${displayPath(childPath)}  —  $typeName = $value",
                                ancestors = ancestors
                            )
                        }
                    }
                }

                fun walk(tag: NbtTag, path: List<PathPart>, ancestors: List<List<PathPart>>) {
                    when (tag) {
                        is NbtTag.Compound -> {
                            for ((key, child) in tag.entries) {
                                val childPath = path + PathPart.Key(key)
                                val isMatch = key.contains(query, true) || summaryOf(child).contains(query, true)
                                if (isMatch) {
                                    matches += SearchMatch(
                                        path = childPath,
                                        displayPath = displayPath(childPath),
                                        label = "${displayPath(childPath)}  —  ${NbtTag.typeName(child.id)} = ${summaryOf(child)}",
                                        ancestors = ancestors
                                    )
                                }
                                if (child is NbtTag.Compound || child is NbtTag.ListTag ||
                                    child is NbtTag.ByteArray || child is NbtTag.IntArray || child is NbtTag.LongArray) {
                                    walk(child, childPath, ancestors + listOf(childPath))
                                }
                            }
                        }
                        is NbtTag.ListTag -> {
                            tag.items.forEachIndexed { i, child ->
                                val childPath = path + PathPart.Index(i)
                                val isMatch = summaryOf(child).contains(query, true)
                                if (isMatch) {
                                    matches += SearchMatch(
                                        path = childPath,
                                        displayPath = displayPath(childPath),
                                        label = "${displayPath(childPath)}  —  ${NbtTag.typeName(child.id)} = ${summaryOf(child)}",
                                        ancestors = ancestors
                                    )
                                }
                                if (child is NbtTag.Compound || child is NbtTag.ListTag ||
                                    child is NbtTag.ByteArray || child is NbtTag.IntArray || child is NbtTag.LongArray) {
                                    walk(child, childPath, ancestors + listOf(childPath))
                                }
                            }
                        }
                        is NbtTag.ByteArray -> arrayMatches(path, ancestors, tag.value.size, "Byte") { tag.value[it].toString() }
                        is NbtTag.IntArray -> arrayMatches(path, ancestors, tag.value.size, "Int") { tag.value[it].toString() }
                        is NbtTag.LongArray -> arrayMatches(path, ancestors, tag.value.size, "Long") { tag.value[it].toString() }
                        else -> Unit
                    }
                }

                walk(root, emptyList(), emptyList())

                if (matches.isEmpty()) {
                    AlertDialog.Builder(context)
                        .setTitle("Global NBT Search")
                        .setMessage("No matching tags found.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@setPositiveButton
                }

                val shown = matches.take(500)
                val message = "Found ${matches.size} match${if (matches.size == 1) "" else "es"}" +
                    (if (matches.size > 500) " (showing first 500)" else "")

                AlertDialog.Builder(context)
                    .setTitle(message)
                    .setItems(shown.map { it.label }.toTypedArray()) { _, which ->
                        jumpToSearchMatch(shown[which])
                    }
                    .setPositiveButton("Close", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Navigate by the actual NBT path, not by a stale RecyclerView position.
     * The operation deliberately has several phases because notifyDataSetChanged()
     * does not synchronously rebuild/layout RecyclerView children.
     */
    private fun jumpToSearchMatch(match: SearchMatch) {
        // Expand every container on the path. Do not rely on the old visible rows.
        for (ancestor in match.ancestors) {
            val pathString = displayPath(ancestor)
            // rows use the same canonical display path internally.
            expanded.add(pathString)
        }

        // rows currently use canonical strings. Rebuild after all expansions are set.
        rebuild()
        notifyDataSetChanged()
        resizeForContent()

        val rv = attachedRecyclerView
        if (rv == null) {
            Toast.makeText(context, "Cannot navigate: NBT tree is not attached.", Toast.LENGTH_LONG).show()
            return
        }

        // Repeatedly resolve the target after layout. This is intentionally not
        // based on an adapter position captured before the tree was expanded.
        var attempts = 0
        fun resolveAndScroll() {
            attempts++
            rebuild()
            val targetPath = displayPath(match.path)
            val index = rows.indexOfFirst { it.first == targetPath }
            if (index >= 0) {
                notifyDataSetChanged()
                rv.post {
                    ((rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager))?.scrollToPositionWithOffset(index, 80)
                    rv.post {
                        ((rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager))?.scrollToPositionWithOffset(index, 80)
                        highlightPath = targetPath
                        notifyDataSetChanged()
                    }
                }
                return
            }
            if (attempts < 8) {
                rv.postDelayed({ resolveAndScroll() }, 50L)
            } else {
                Toast.makeText(
                    context,
                    "Could not locate search result after expanding:\n$targetPath",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        rv.post { resolveAndScroll() }
    }

    private var highlightPath: String? = null

    inner class VH(
        val row: LinearLayout,
        val branch: TextView,
        val icon: ImageView,
        val text: TextView
    ) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val branch = TextView(parent.context).apply {
            textSize = 18f
            setTextColor(android.graphics.Color.BLACK)
            alpha = 0.30f
            gravity = android.view.Gravity.CENTER
            typeface = Typeface.DEFAULT
            layoutParams = LinearLayout.LayoutParams(34, 56)
        }
        val icon = ImageView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(56, 56).apply { rightMargin = 16 }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        val text = TextView(parent.context).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.BLACK)
        }
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(24, 20, 24, 20)
            addView(branch)
            addView(icon)
            addView(text)
        }
        return VH(row, branch, icon, text)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (path, row) = rows[position]
        val summary = summaryOf(row.tag)
        holder.icon.setImageResource(NbtIcons.iconFor(row.tag))
        holder.text.text = "${row.label}: ${NbtTag.typeName(row.tag.id)} = $summary"
        holder.row.setPadding(24 + row.depth * 48, 20, 24, 20)
        if (row.depth > 0) {
            holder.branch.text = "└─"
            holder.branch.visibility = android.view.View.VISIBLE
        } else {
            holder.branch.text = ""
            holder.branch.visibility = android.view.View.VISIBLE
        }
        if (path == highlightPath) {
            holder.row.alpha = 1.0f
            holder.row.setBackgroundColor(0x66FFCC33)
        } else {
            holder.row.alpha = 1.0f
            holder.row.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        // Animate rows that became visible because their parent Compound/List was opened.
        val animationRoot = animationRootPath
        val isRevealedChild = animationRoot != null &&
            (path.startsWith("$animationRoot/") || path.startsWith("$animationRoot["))
        if (isRevealedChild && animatedPaths.add("$animationGeneration:$path")) {
            holder.row.alpha = 0f
            holder.row.translationX = -dp(context, 10)
            val delay = (row.depth * 18L).coerceAtMost(90L)
            holder.row.animate()
                .alpha(1f)
                .translationX(0f)
                .setStartDelay(delay)
                .setDuration(180L)
                .start()
        } else {
            holder.row.animate().cancel()
            holder.row.alpha = 1f
            holder.row.translationX = 0f
        }

        val isContainer = row.tag is NbtTag.Compound || row.tag is NbtTag.ListTag ||
            row.tag is NbtTag.ByteArray || row.tag is NbtTag.IntArray || row.tag is NbtTag.LongArray
        holder.row.setOnClickListener {
            if (isContainer) {
                if (path in expanded) {
                    expanded.remove(path)
                    animationRootPath = null
                } else {
                    expanded.add(path)
                    animationRootPath = path
                    animationGeneration++
                    animatedPaths.clear()
                }
                refresh()
            } else {
                onLeafTapped(row)
            }
        }
        holder.row.setOnLongClickListener {
            showRowMenu(row)
            true
        }
    }

    override fun getItemCount(): Int = rows.size

    private fun dp(context: Context, value: Int): Float = value * context.resources.displayMetrics.density

    private fun summaryOf(t: NbtTag): String = when (t) {
        is NbtTag.Compound -> "{${t.entries.size} entries}"
        is NbtTag.ListTag -> "[${t.items.size} × ${NbtTag.typeName(t.elementTypeId)}]"
        is NbtTag.Byte -> t.value.toString()
        is NbtTag.Short -> t.value.toString()
        is NbtTag.Int -> t.value.toString()
        is NbtTag.Long -> t.value.toString()
        is NbtTag.Float -> t.value.toString()
        is NbtTag.Double -> t.value.toString()
        is NbtTag.Str -> "\"${t.value}\""
        is NbtTag.ByteArray -> "byte[${t.value.size}]"
        is NbtTag.IntArray -> "int[${t.value.size}]"
        is NbtTag.LongArray -> "long[${t.value.size}]"
        NbtTag.End -> ""
    }

    // ---- long-press menu: delete / rename / add child/element / copy / paste ----

    private fun isArrayTag(tag: NbtTag): Boolean =
        tag is NbtTag.ByteArray || tag is NbtTag.IntArray || tag is NbtTag.LongArray

    private fun showRowMenu(row: NbtRow) {
        val canHoldChildren = row.tag is NbtTag.Compound || row.tag is NbtTag.ListTag || isArrayTag(row.tag)
        val canRename = row.container is NbtContainer.InCompound
        val options = buildList {
            add("Delete")
            if (canRename) add("Rename")
            add("Copy to NBT clipboard")
            if (isArrayTag(row.tag)) add("Add element")
            else if (canHoldChildren) add("Add child")
            if (canHoldChildren && NbtClipboard.listNames(context).isNotEmpty()) add("Paste from NBT clipboard")
        }
        AlertDialog.Builder(context)
            .setTitle(row.label)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Delete" -> deleteRow(row)
                    "Rename" -> promptRename(row.container as NbtContainer.InCompound)
                    "Copy to NBT clipboard" -> promptCopy(row)
                    "Add element" -> promptAddArrayElement(row)
                    "Add child" -> promptAddChild(row.tag, row)
                    "Paste from NBT clipboard" -> promptPasteMenu(row)
                }
            }
            .show()
    }

    private fun replaceOwnedValue(owner: NbtContainer, newTag: NbtTag) {
        when (owner) {
            is NbtContainer.InCompound -> owner.compound.entries[owner.key] = newTag
            is NbtContainer.InList -> owner.list.items[owner.index] = newTag
            is NbtContainer.InByteArray, is NbtContainer.InIntArray, is NbtContainer.InLongArray ->
                error("Array elements cannot own nested arrays")
        }
    }

    private fun deleteRow(row: NbtRow) {
        NbtHistory.recordBefore(root)
        when (val c = row.container) {
            is NbtContainer.InCompound -> c.compound.entries.remove(c.key)
            is NbtContainer.InList -> c.list.items.removeAt(c.index)
            is NbtContainer.InByteArray -> {
                val old = c.array.value
                replaceOwnedValue(c.owner, NbtTag.ByteArray(ByteArray(old.size - 1) { i -> if (i < c.index) old[i] else old[i + 1] }))
            }
            is NbtContainer.InIntArray -> {
                val old = c.array.value
                replaceOwnedValue(c.owner, NbtTag.IntArray(IntArray(old.size - 1) { i -> if (i < c.index) old[i] else old[i + 1] }))
            }
            is NbtContainer.InLongArray -> {
                val old = c.array.value
                replaceOwnedValue(c.owner, NbtTag.LongArray(LongArray(old.size - 1) { i -> if (i < c.index) old[i] else old[i + 1] }))
            }
        }
        refresh()
        onChanged()
    }

    private fun promptRename(container: NbtContainer.InCompound) {
        val input = EditText(context).apply { setText(container.key) }
        AlertDialog.Builder(context)
            .setTitle("Rename key")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newKey = input.text.toString().ifBlank { container.key }
                if (newKey != container.key) {
                    NbtHistory.recordBefore(root)
                    // rebuild in place (LinkedHashMap.put on an existing key wouldn't move it,
                    // but remove+put would jump it to the end) so key order stays intact
                    val rebuilt = LinkedHashMap<String, NbtTag>()
                    for ((k, v) in container.compound.entries) {
                        rebuilt[if (k == container.key) newKey else k] = v
                    }
                    container.compound.entries.clear()
                    container.compound.entries.putAll(rebuilt)
                }
                refresh()
                onChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptCopy(row: NbtRow) {
        val input = EditText(context).apply { setText(row.label) }
        AlertDialog.Builder(context)
            .setTitle("Save to NBT clipboard as…")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().ifBlank { row.label }
                NbtClipboard.save(context, name, row.tag)
                NbtClipboard.selectedName = name // so "paste" elsewhere defaults to what you just copied
                Toast.makeText(context, "Copied \"$name\"", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** "Select first" paste flow: if something's already selected (from the Clipboard screen or
     * a recent copy), offer a one-tap paste of it; otherwise fall back to choosing one. */
    private fun promptPasteMenu(row: NbtRow) {
        val selected = NbtClipboard.selectedName
        val options = buildList {
            if (selected != null) add("Paste \"$selected\"")
            add("Choose from clipboard…")
        }
        AlertDialog.Builder(context)
            .setItems(options.toTypedArray()) { _, which ->
                if (options[which].startsWith("Paste \"") && selected != null) {
                    val tag = NbtClipboard.load(context, selected)
                    if (tag != null) pasteInto(row, tag)
                    else Toast.makeText(context, "That clipboard entry no longer exists", Toast.LENGTH_SHORT).show()
                } else {
                    val names = NbtClipboard.listNames(context)
                    AlertDialog.Builder(context)
                        .setTitle("Paste which clipboard entry?")
                        .setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, names)) { _, idx ->
                            NbtClipboard.selectedName = names[idx]
                            val tag = NbtClipboard.load(context, names[idx])
                            if (tag != null) pasteInto(row, tag)
                        }
                        .show()
                }
            }
            .show()
    }

    private fun pasteInto(row: NbtRow, clipboardTag: NbtTag) {
        val copy = NbtClipboard.deepCopy(clipboardTag)
        when (val target = row.tag) {
            is NbtTag.Compound -> {
                val keyInput = EditText(context).apply { setText(NbtClipboard.selectedName ?: "value") }
                AlertDialog.Builder(context)
                    .setTitle("Key name for pasted value")
                    .setView(keyInput)
                    .setPositiveButton("Paste") { _, _ ->
                        NbtHistory.recordBefore(root)
                        target.entries[keyInput.text.toString().ifBlank { "value" }] = copy
                        refresh()
                        onChanged()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            is NbtTag.ListTag -> {
                if (target.items.isEmpty()) {
                    NbtHistory.recordBefore(root)
                    row.setValue(NbtTag.ListTag(copy.id, mutableListOf(copy)))
                } else if (target.elementTypeId != copy.id) {
                    Toast.makeText(
                        context,
                        "Type mismatch: list holds ${NbtTag.typeName(target.elementTypeId)}, clipboard is ${NbtTag.typeName(copy.id)}",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                } else {
                    NbtHistory.recordBefore(root)
                    target.items.add(copy)
                }
                refresh()
                onChanged()
            }
            is NbtTag.ByteArray -> pasteIntoByteArray(row, target, copy)
            is NbtTag.IntArray -> pasteIntoIntArray(row, target, copy)
            is NbtTag.LongArray -> pasteIntoLongArray(row, target, copy)
            else -> Unit
        }
    }

    private fun pasteIntoByteArray(row: NbtRow, target: NbtTag.ByteArray, copy: NbtTag) {
        val values = when (copy) {
            is NbtTag.Byte -> byteArrayOf(copy.value)
            is NbtTag.ByteArray -> copy.value
            else -> { showArrayTypeMismatch("ByteArray", copy.id); return }
        }
        NbtHistory.recordBefore(root)
        row.setValue(NbtTag.ByteArray(target.value + values))
        refresh()
        onChanged()
    }

    private fun pasteIntoIntArray(row: NbtRow, target: NbtTag.IntArray, copy: NbtTag) {
        val values = when (copy) {
            is NbtTag.Int -> intArrayOf(copy.value)
            is NbtTag.IntArray -> copy.value
            else -> { showArrayTypeMismatch("IntArray", copy.id); return }
        }
        NbtHistory.recordBefore(root)
        row.setValue(NbtTag.IntArray(target.value + values))
        refresh()
        onChanged()
    }

    private fun pasteIntoLongArray(row: NbtRow, target: NbtTag.LongArray, copy: NbtTag) {
        val values = when (copy) {
            is NbtTag.Long -> longArrayOf(copy.value)
            is NbtTag.LongArray -> copy.value
            else -> { showArrayTypeMismatch("LongArray", copy.id); return }
        }
        NbtHistory.recordBefore(root)
        row.setValue(NbtTag.LongArray(target.value + values))
        refresh()
        onChanged()
    }

    private fun showArrayTypeMismatch(arrayName: String, clipboardType: Int) {
        Toast.makeText(
            context,
            "Type mismatch: $arrayName accepts only its matching scalar tag or array type; clipboard is ${NbtTag.typeName(clipboardType)}",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun promptAddArrayElement(row: NbtRow) {
        val newTag = when (val target = row.tag) {
            is NbtTag.ByteArray -> NbtTag.ByteArray(target.value.copyOf(target.value.size + 1))
            is NbtTag.IntArray -> NbtTag.IntArray(target.value.copyOf(target.value.size + 1))
            is NbtTag.LongArray -> NbtTag.LongArray(target.value.copyOf(target.value.size + 1))
            else -> return
        }
        NbtHistory.recordBefore(root)
        row.setValue(newTag)
        val path = rows.firstOrNull { it.second === row }?.first
        if (path != null) expanded.add(path)
        refresh()
        onChanged()
    }

    /** Public entry point for adding a brand-new top-level tag directly to the root
     * compound — the long-press menu only reaches the root through this method. */
    fun promptAddChildToRoot() = promptAddChild(root, null)

    private fun promptAddChild(target: NbtTag, ownerRow: NbtRow?) {
        val typeNames = listOf(
            "Byte" to 1, "Short" to 2, "Int" to 3, "Long" to 4, "Float" to 5,
            "Double" to 6, "ByteArray" to 7, "String" to 8, "List" to 9,
            "Compound" to 10, "IntArray" to 11, "LongArray" to 12
        )
        AlertDialog.Builder(context)
            .setTitle("Add child — type?")
            .setItems(typeNames.map { it.first }.toTypedArray()) { _, which ->
                val typeId = typeNames[which].second
                val newTag: NbtTag = when (typeId) {
                    1 -> NbtTag.Byte(0)
                    2 -> NbtTag.Short(0)
                    3 -> NbtTag.Int(0)
                    4 -> NbtTag.Long(0)
                    5 -> NbtTag.Float(0f)
                    6 -> NbtTag.Double(0.0)
                    7 -> NbtTag.ByteArray(byteArrayOf())
                    8 -> NbtTag.Str("")
                    9 -> NbtTag.ListTag(10, mutableListOf())
                    10 -> NbtTag.Compound(LinkedHashMap())
                    11 -> NbtTag.IntArray(intArrayOf())
                    12 -> NbtTag.LongArray(longArrayOf())
                    else -> NbtTag.Str("")
                }
                when (target) {
                    is NbtTag.Compound -> {
                        val keyInput = EditText(context).apply { hint = "key name" }
                        AlertDialog.Builder(context)
                            .setTitle("Key name")
                            .setView(keyInput)
                            .setPositiveButton("Add") { _, _ ->
                                NbtHistory.recordBefore(root)
                                target.entries[keyInput.text.toString().ifBlank { "new_key" }] = newTag
                                refresh()
                                onChanged()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    is NbtTag.ListTag -> {
                        if (target.items.isNotEmpty() && target.elementTypeId != newTag.id) {
                            Toast.makeText(
                                context,
                                "Type mismatch: list holds ${NbtTag.typeName(target.elementTypeId)}, new tag is ${NbtTag.typeName(newTag.id)}",
                                Toast.LENGTH_LONG
                            ).show()
                            return@setItems
                        }
                        NbtHistory.recordBefore(root)
                        if (target.items.isEmpty()) {
                            val owner = ownerRow ?: run {
                                Toast.makeText(context, "This empty list has no editable owner.", Toast.LENGTH_LONG).show()
                                return@setItems
                            }
                            owner.setValue(NbtTag.ListTag(newTag.id, mutableListOf(newTag)))
                        } else {
                            target.items.add(newTag)
                        }
                        refresh()
                        onChanged()
                    }
                    else -> Unit
                }
            }
            .show()
    }

}
