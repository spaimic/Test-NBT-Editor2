package com.spaimic.nbttool.ui

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.spaimic.nbttool.chunk.ChunkClipboard

class ChunkClipboardActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Chunk Clipboard"
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20,20,20,20) }
        scroll.addView(list)
        setContentView(scroll)
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val entries = ChunkClipboard.list(this)
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply { text = "Chunk clipboard is empty."; textSize = 18f })
            return
        }
        entries.forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(8, 12, 8, 12)
            }
            entry.previewPng?.let {
                val image = ImageView(this).apply {
                    setImageBitmap(BitmapFactory.decodeByteArray(it, 0, it.size))
                    layoutParams = LinearLayout.LayoutParams(128,128).apply { rightMargin = 20 }
                    scaleType = ImageView.ScaleType.FIT_XY
                }
                row.addView(image)
            }
            val text = TextView(this).apply {
                text = "Chunk (${entry.chunkX}, ${entry.chunkZ})\n${entry.records.size} LevelDB records"
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(text)
            row.setOnClickListener {
                ChunkClipboard.selectedId = entry.id
                Toast.makeText(this, "Selected chunk (${entry.chunkX}, ${entry.chunkZ})", Toast.LENGTH_SHORT).show()
                refresh()
            }
            row.setOnLongClickListener {
                AlertDialog.Builder(this).setTitle("Chunk (${entry.chunkX}, ${entry.chunkZ})")
                    .setItems(arrayOf("Select for pasting", "Delete")) { _, which ->
                        if (which == 0) ChunkClipboard.selectedId = entry.id
                        else ChunkClipboard.delete(this, entry.id)
                        refresh()
                    }.show()
                true
            }
            if (entry.id == ChunkClipboard.selectedId) row.setBackgroundColor(0x223388FF)
            list.addView(row)
        }
    }
}
