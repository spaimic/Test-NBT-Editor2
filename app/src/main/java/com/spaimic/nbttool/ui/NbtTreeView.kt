package com.spaimic.nbttool.ui

import android.app.AlertDialog
import android.content.Context
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.spaimic.nbttool.clipboard.NbtClipboard
import com.spaimic.nbttool.nbt.NbtTag

/** One flattened, indented row of the tree currently on screen. */
data class NbtRow(
    val key: String,
    val tag: NbtTag,
    val depth: Int,
    val parent: NbtTag.Compound
)

/**
 * Flattens a (possibly nested) compound into an indented list. Tapping a compound
 * expands/collapses it; tapping a primitive edits its value; long-pressing any row
 * opens a menu for delete / add child / copy-to-clipboard / paste-from-clipboard.
 */
class NbtTreeAdapter(
    private val context: Context,
    private val root: NbtTag.Compound,
    private val onLeafTapped: (NbtRow) -> Unit
) : RecyclerView.Adapter<NbtTreeAdapter.VH>() {

    private val expanded = HashSet<String>()
    private var rows: List<Pair<String, NbtRow>> = emptyList()

    init { rebuild() }

    private fun rebuild() {
        val out = ArrayList<Pair<String, NbtRow>>()
        fun walk(compound: NbtTag.Compound, path: String, depth: Int) {
            for ((key, tag) in compound.entries) {
                val childPath = "$path/$key"
                out.add(childPath to NbtRow(key, tag, depth, compound))
                if (tag is NbtTag.Compound && childPath in expanded) {
                    walk(tag, childPath, depth + 1)
                }
            }
        }
        walk(root, "", 0)
        rows = out
    }

    fun refresh() {
        rebuild()
        notifyDataSetChanged()
    }

    inner class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply {
            setPadding(24, 20, 24, 20)
            textSize = 15f
        }
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (path, row) = rows[position]
        val indent = "  ".repeat(row.depth)
        val summary = summaryOf(row.tag)
        holder.text.text = "$indent${row.key}: ${NbtTag.typeName(row.tag.id)} = $summary"

        holder.text.setOnClickListener {
            if (row.tag is NbtTag.Compound) {
                if (path in expanded) expanded.remove(path) else expanded.add(path)
                refresh()
            } else {
                onLeafTapped(row)
            }
        }
        holder.text.setOnLongClickListener {
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

    // ---- long-press menu: delete / add child / copy / paste ----

    private fun showRowMenu(row: NbtRow) {
        val canHoldChildren = row.tag is NbtTag.Compound || row.tag is NbtTag.ListTag
        val options = buildList {
            add("Delete")
            add("Copy to NBT clipboard")
            if (canHoldChildren) add("Add child")
            if (canHoldChildren && NbtClipboard.listNames(context).isNotEmpty()) add("Paste from NBT clipboard")
        }
        AlertDialog.Builder(context)
            .setTitle(row.key)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Delete" -> deleteRow(row)
                    "Copy to NBT clipboard" -> promptCopy(row)
                    "Add child" -> promptAddChild(row)
                    "Paste from NBT clipboard" -> promptPaste(row)
                }
            }
            .show()
    }

    private fun deleteRow(row: NbtRow) {
        row.parent.entries.remove(row.key)
        refresh()
    }

    private fun promptCopy(row: NbtRow) {
        val input = EditText(context).apply { setText(row.key) }
        AlertDialog.Builder(context)
            .setTitle("Save to NBT clipboard as…")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().ifBlank { row.key }
                NbtClipboard.save(context, name, row.tag)
                Toast.makeText(context, "Copied \"$name\"", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptPaste(row: NbtRow) {
        val names = NbtClipboard.listNames(context)
        AlertDialog.Builder(context)
            .setTitle("Paste which clipboard entry?")
            .setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, names)) { _, which ->
                val clip = NbtClipboard.load(context, names[which]) ?: return@setAdapter
                val copy = NbtClipboard.deepCopy(clip)
                when (val target = row.tag) {
                    is NbtTag.Compound -> {
                        val keyInput = EditText(context).apply { setText(names[which]) }
                        AlertDialog.Builder(context)
                            .setTitle("Key name for pasted value")
                            .setView(keyInput)
                            .setPositiveButton("Paste") { _, _ ->
                                target.entries[keyInput.text.toString().ifBlank { names[which] }] = copy
                                refresh()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    is NbtTag.ListTag -> {
                        if (target.items.isEmpty()) {
                            // an empty list has no established element type yet — adopt the pasted tag's type
                            row.parent.entries[row.key] = NbtTag.ListTag(copy.id, mutableListOf(copy))
                        } else if (target.elementTypeId != copy.id) {
                            Toast.makeText(
                                context,
                                "Type mismatch: list holds ${NbtTag.typeName(target.elementTypeId)}, clipboard is ${NbtTag.typeName(copy.id)}",
                                Toast.LENGTH_LONG
                            ).show()
                            return@setAdapter
                        } else {
                            target.items.add(copy)
                        }
                        refresh()
                    }
                    else -> {}
                }
            }
            .show()
    }

    private fun promptAddChild(row: NbtRow) {
        val typeNames = listOf(
            "Byte" to 1, "Short" to 2, "Int" to 3, "Long" to 4, "Float" to 5,
            "Double" to 6, "String" to 8, "Compound" to 10, "List" to 9
        )
        AlertDialog.Builder(context)
            .setTitle("Add child — type?")
            .setItems(typeNames.map { it.first }.toTypedArray()) { _, which ->
                val typeId = typeNames[which].second
                val keyInput = EditText(context).apply { hint = "key name" }
                AlertDialog.Builder(context)
                    .setTitle("Key name")
                    .setView(keyInput)
                    .setPositiveButton("Add") { _, _ ->
                        val key = keyInput.text.toString().ifBlank { "new_key" }
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
                        when (val target = row.tag) {
                            is NbtTag.Compound -> target.entries[key] = newTag
                            is NbtTag.ListTag -> target.items.add(newTag)
                            else -> {}
                        }
                        refresh()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .show()
    }
}
