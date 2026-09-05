package com.spaimic.nbttool.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.spaimic.nbttool.R
import com.spaimic.nbttool.shizuku.LevelDbSession

/** Build 203 read-only browser for structuretemplate_ LevelDB records. */
class McStructureBrowserActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "MCStructure Analyzer"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val header = TextView(this).apply {
            text = "MCStructure Analyzer\nSelect a structuretemplate_ record to inspect its format. Build 203 is read-only."
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(dp(18), dp(18), dp(18), dp(14))
        }
        root.addView(header)
        val importButton = Button(this).apply {
            text = "Import .mcstructure File"
            setTextColor(Color.BLACK)
            setOnClickListener {
                startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "application/octet-stream"
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                }, REQUEST_IMPORT_STRUCTURE)
            }
        }
        root.addView(importButton, LinearLayout.LayoutParams(-1, dp(48)).apply {
            leftMargin = dp(14); rightMargin = dp(14); bottomMargin = dp(6)
        })
        progress = ProgressBar(this).apply { visibility = View.VISIBLE }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val scroll = android.widget.ScrollView(this)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        loadKeys()
    }

    private fun loadKeys() {
        val bridge = LevelDbSession.bridge
        if (bridge == null) {
            progress.visibility = View.GONE
            list.addView(TextView(this).apply {
                text = "World database isn't open."
                textSize = 16f
                setTextColor(Color.BLACK)
                setPadding(dp(18), dp(18), dp(18), dp(18))
            })
            return
        }
        Thread {
            try {
                val entries = bridge.listKeysByPrefix("structuretemplate_")
                synchronized(LevelDbSession.keyBytesByName) {
                    for (entry in entries) {
                        LevelDbSession.keyBytesByName[entry.displayName] = entry.rawKey
                    }
                }
                runOnUiThread {
                    progress.visibility = View.GONE
                    if (entries.isEmpty()) {
                        list.addView(TextView(this).apply {
                            text = "No MCStructure records were found in this world."
                            textSize = 16f
                            setTextColor(Color.DKGRAY)
                            setPadding(dp(18), dp(18), dp(18), dp(18))
                        })
                    } else {
                        for (entry in entries) addRow(entry.displayName, entry.rawKey)
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    AlertDialog.Builder(this)
                        .setTitle("Couldn't list MCStructures")
                        .setMessage(e.message ?: e.javaClass.simpleName)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun addRow(name: String, rawKey: ByteArray) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(android.content.Intent(this@McStructureBrowserActivity, McStructureAnalyzerActivity::class.java).apply {
                    putExtra(McStructureAnalyzerActivity.EXTRA_DISPLAY_NAME, name)
                })
            }
        }
        val icon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.mcstructure)
            setColorFilter(Color.BLACK, android.graphics.PorterDuff.Mode.SRC_IN)
        }
        val label = TextView(this).apply {
            text = name
            textSize = 15f
            setTextColor(Color.BLACK)
            setPadding(dp(12), 0, 0, 0)
            maxLines = 2
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        list.addView(row, LinearLayout.LayoutParams(-1, -2))
        val divider = View(this).apply { setBackgroundColor(0xFFE0E0E0.toInt()) }
        list.addView(divider, LinearLayout.LayoutParams(-1, 1))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMPORT_STRUCTURE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            startActivity(android.content.Intent(this, McStructureAnalyzerActivity::class.java).apply {
                putExtra(McStructureAnalyzerActivity.EXTRA_SOURCE_URI, uri.toString())
                putExtra(McStructureAnalyzerActivity.EXTRA_DISPLAY_NAME, uri.lastPathSegment ?: "Imported .mcstructure")
            })
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val REQUEST_IMPORT_STRUCTURE = 20301
    }
}
