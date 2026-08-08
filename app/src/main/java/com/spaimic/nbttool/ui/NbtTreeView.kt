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
import androidx.recyclerview.widget.RecyclerView
import com.spaimic.nbttool.clipboard.NbtClipboard
import com.spaimic.nbttool.nbt.NbtIcons
import com.spaimic.nbttool.nbt.NbtHistory
import com.spaimic.nbttool.nbt.NbtTag

/** Where a row's tag actually lives — a named slot in a compound, or an indexed slot in a list. */
sealed class NbtContainer {
    data class InCompound(val compound: NbtTag.Compound, val key: String) : NbtContainer()
    data class InList(val list: NbtTag.ListTag, val index: Int) : NbtContainer()
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
    private val onLeafTapped: (NbtRow) -> Unit
) : RecyclerView.Adapter<NbtTreeAdapter.VH>() {

    private val expanded = HashSet<String>()
    private var rows: List<Pair<String, NbtRow>> = emptyList()
    private var attachedRecyclerView: RecyclerView? = null

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

        fun walk(container: NbtTag, path: String, depth: Int) {
            when (container) {
                is NbtTag.Compound -> {
                    for ((key, tag) in container.entries) {
                        val childPath = "$path/$key"
                        out.add(childPath to NbtRow(key, tag, depth, NbtContainer.InCompound(container, key)))
                        if (childPath in expanded && (tag is NbtTag.Compound || tag is NbtTag.ListTag)) {
                            walk(tag, childPath, depth + 1)
                        }
                    }
                }
                is NbtTag.ListTag -> {
                    for ((index, tag) in container.items.withIndex()) {
                        val childPath = "$path[$index]"
                        out.add(childPath to NbtRow("[$index]", tag, depth, NbtContainer.InList(container, index)))
                        if (childPath in expanded && (tag is NbtTag.Compound || tag is NbtTag.ListTag)) {
                            walk(tag, childPath, depth + 1)
                        }
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

    fun showGlobalSearch() {
        val input = EditText(context).apply { hint = "Tag name or value" }
        AlertDialog.Builder(context)
            .setTitle("Global NBT Search")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isEmpty()) return@setPositiveButton
                val matches = ArrayList<String>()
                fun walk(tag: NbtTag, path: String) {
                    when (tag) {
                        is NbtTag.Compound -> {
                            for ((key, child) in tag.entries) {
                                val p = if (path.isEmpty()) key else "$path/$key"
                                if (key.contains(query, true) || summaryOf(child).contains(query, true))
                                    matches += "$p  —  ${NbtTag.typeName(child.id)} = ${summaryOf(child)}"
                                walk(child, p)
                            }
                        }
                        is NbtTag.ListTag -> tag.items.forEachIndexed { i, child ->
                            val p = "$path[$i]"
                            if (summaryOf(child).contains(query, true))
                                matches += "$p  —  ${NbtTag.typeName(child.id)} = ${summaryOf(child)}"
                            walk(child, p)
                        }
                        else -> {}
                    }
                }
                walk(root, "")
                val shown = matches.take(500)
                val message = if (matches.isEmpty()) "No matching tags found." else
                    "Found ${matches.size} match${if (matches.size == 1) "" else "es"}" +
                    (if (matches.size > 500) " (showing first 500)" else "")
                AlertDialog.Builder(context)
                    .setTitle(message)
                    .setItems(shown.toTypedArray(), null)
                    .setPositiveButton("OK", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class VH(val row: LinearLayout, val icon: ImageView, val text: TextView) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val icon = ImageView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(56, 56).apply { rightMargin = 16 }
        }
        val text = TextView(parent.context).apply {
            textSize = 15f
        }
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(24, 20, 24, 20)
            addView(icon)
            addView(text)
        }
        return VH(row, icon, text)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (path, row) = rows[position]
        val summary = summaryOf(row.tag)
        holder.icon.setImageResource(NbtIcons.iconFor(row.tag))
        holder.text.text = "${row.label}: ${NbtTag.typeName(row.tag.id)} = $summary"
        holder.row.setPadding(24 + row.depth * 48, 20, 24, 20)

        val isContainer = row.tag is NbtTag.Compound || row.tag is NbtTag.ListTag
        holder.row.setOnClickListener {
            if (isContainer) {
                if (path in expanded) expanded.remove(path) else expanded.add(path)
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

    // ---- long-press menu: delete / rename / add child / copy / paste ----

    private fun showRowMenu(row: NbtRow) {
        val canHoldChildren = row.tag is NbtTag.Compound || row.tag is NbtTag.ListTag
        val canRename = row.container is NbtContainer.InCompound
        val options = buildList {
            add("Delete")
            if (canRename) add("Rename")
            add("Copy to NBT clipboard")
            if (canHoldChildren) add("Add child")
            if (canHoldChildren && NbtClipboard.listNames(context).isNotEmpty()) add("Paste from NBT clipboard")
        }
        AlertDialog.Builder(context)
            .setTitle(row.label)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Delete" -> deleteRow(row)
                    "Rename" -> promptRename(row.container as NbtContainer.InCompound)
                    "Copy to NBT clipboard" -> promptCopy(row)
                    "Add child" -> promptAddChild(row.tag)
                    "Paste from NBT clipboard" -> promptPasteMenu(row)
                }
            }
            .show()
    }

    private fun deleteRow(row: NbtRow) {
        NbtHistory.recordBefore(root)
        when (val c = row.container) {
            is NbtContainer.InCompound -> c.compound.entries.remove(c.key)
            is NbtContainer.InList -> c.list.items.removeAt(c.index)
        }
        refresh()
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
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            is NbtTag.ListTag -> {
                if (target.items.isEmpty()) {
                    // an empty list has no established element type yet — adopt the pasted tag's type
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
            }
            else -> {}
        }
    }

    /** Public entry point for adding a brand-new top-level tag directly to the root
     * compound — the long-press "Add child" menu only works on an existing
     * compound/list row, which can't reach the root itself since it has no row. */
    fun promptAddChildToRoot() = promptAddChild(root)

    private fun promptAddChild(target: NbtTag) {
        val typeNames = listOf(
            "Byte" to 1, "Short" to 2, "Int" to 3, "Long" to 4, "Float" to 5,
            "Double" to 6, "String" to 8, "Compound" to 10, "List" to 9
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
                    8 -> NbtTag.Str("")
                    10 -> NbtTag.Compound(LinkedHashMap())
                    9 -> NbtTag.ListTag(10, mutableListOf())
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
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    is NbtTag.ListTag -> {
                        NbtHistory.recordBefore(root)
                        target.items.add(newTag)
                        refresh()
                    }
                    else -> {}
                }
            }
            .show()
    }
}
