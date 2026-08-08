package com.spaimic.nbttool.ui

import android.app.AlertDialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.spaimic.nbttool.clipboard.NbtClipboard
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.nbt.NbtJson

/**
 * Shows every NBT snippet you've copied. Tapping one marks it "selected" — the
 * tree editor's Paste menu then offers a one-tap paste of whatever's selected,
 * instead of making you re-pick from a list every time.
 */
class ClipboardActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private var names: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "NBT Clipboard"
        listView = ListView(this)
        setContentView(listView)

        listView.setOnItemClickListener { _, _, position, _ ->
            NbtClipboard.selectedName = names[position]
            Toast.makeText(this, "Selected \"${names[position]}\" for pasting", Toast.LENGTH_SHORT).show()
            refresh()
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            showEntryMenu(names[position])
            true
        }
        refresh()
    }

    private fun refresh() {
        names = NbtClipboard.listNames(this)
        val labels = names.map { name ->
            val tag = NbtClipboard.load(this, name)
            val typeLabel = tag?.let { NbtTag.typeName(it.id) } ?: "?"
            val contents = tag?.let { NbtJson.toJson(it).toString() } ?: "(unreadable)"
            val marker = if (name == NbtClipboard.selectedName) "✓ " else "   "
            "$marker$name — $typeLabel\n${contents.take(1000)}${if (contents.length > 1000) "…" else ""}"
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        if (names.isEmpty()) {
            Toast.makeText(this, "Clipboard is empty — long-press a tag in the tree editor to copy one", Toast.LENGTH_LONG).show()
        }
    }

    private fun showEntryMenu(name: String) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf("Select for pasting", "Rename", "Delete")) { _, which ->
                when (which) {
                    0 -> { NbtClipboard.selectedName = name; refresh() }
                    1 -> promptRename(name)
                    2 -> {
                        NbtClipboard.delete(this, name)
                        if (NbtClipboard.selectedName == name) NbtClipboard.selectedName = null
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun promptRename(oldName: String) {
        val input = EditText(this).apply { setText(oldName) }
        AlertDialog.Builder(this)
            .setTitle("Rename clipboard entry")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().ifBlank { oldName }
                val tag = NbtClipboard.load(this, oldName)
                if (tag != null && newName != oldName) {
                    NbtClipboard.save(this, newName, tag)
                    NbtClipboard.delete(this, oldName)
                    if (NbtClipboard.selectedName == oldName) NbtClipboard.selectedName = newName
                }
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
