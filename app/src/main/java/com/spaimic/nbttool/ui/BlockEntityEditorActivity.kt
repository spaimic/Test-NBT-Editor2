package com.spaimic.nbttool.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.BlockEntitySession

class BlockEntityEditorActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val index = intent.getIntExtra(EXTRA_INDEX, -1)
        val compound = BlockEntitySession.compounds.getOrNull(index) ?: return finish()
        title = (compound.entries["id"] as? NbtTag.Str)?.value ?: "Block entity"

        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@BlockEntityEditorActivity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        setContentView(recycler)

        lateinit var adapter: NbtTreeAdapter
        adapter = NbtTreeAdapter(this, compound) { row -> onLeafTapped(row) { adapter.refresh() } }
        recycler.adapter = adapter
    }

    private fun onLeafTapped(row: NbtRow, onSaved: () -> Unit) {
        val tag = row.tag
        val current = when (tag) {
            is NbtTag.Byte -> tag.value.toString()
            is NbtTag.Short -> tag.value.toString()
            is NbtTag.Int -> tag.value.toString()
            is NbtTag.Long -> tag.value.toString()
            is NbtTag.Float -> tag.value.toString()
            is NbtTag.Double -> tag.value.toString()
            is NbtTag.Str -> tag.value
            else -> return
        }
        val input = EditText(this).apply { setText(current) }
        AlertDialog.Builder(this)
            .setTitle(row.label)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                try {
                    val newTag: NbtTag = when (tag) {
                        is NbtTag.Byte -> NbtTag.Byte(input.text.toString().toByte())
                        is NbtTag.Short -> NbtTag.Short(input.text.toString().toShort())
                        is NbtTag.Int -> NbtTag.Int(input.text.toString().toInt())
                        is NbtTag.Long -> NbtTag.Long(input.text.toString().toLong())
                        is NbtTag.Float -> NbtTag.Float(input.text.toString().toFloat())
                        is NbtTag.Double -> NbtTag.Double(input.text.toString().toDouble())
                        is NbtTag.Str -> NbtTag.Str(input.text.toString())
                        else -> tag
                    }
                    row.setValue(newTag)
                    onSaved()
                } catch (e: NumberFormatException) {
                    Toast.makeText(this, "Invalid value for ${NbtTag.typeName(tag.id)}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        const val EXTRA_INDEX = "index"
    }
}
