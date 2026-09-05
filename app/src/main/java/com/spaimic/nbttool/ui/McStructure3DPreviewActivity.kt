package com.spaimic.nbttool.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.LevelDbSession

/** Full-screen interactive 3D MCStructure viewer. It never writes to the world. */
class McStructure3DPreviewActivity : AppCompatActivity() {
    private lateinit var preview: McStructure3DPreviewView
    private lateinit var layerLabel: TextView
    private lateinit var layerSeek: SeekBar
    private var analysis: McStructureAnalysis? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: "MCStructure 3D Preview"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(8),dp(6),dp(8),dp(6)) }
        val reset = Button(this).apply { text = "Reset View"; setOnClickListener { preview.resetCamera() } }
        toolbar.addView(reset, LinearLayout.LayoutParams(-2, dp(44)))
        val hint = TextView(this).apply {
            text = "  Drag = rotate   •   2 fingers = zoom   •   Tap block = inspect"
            textSize = 12f; setTextColor(Color.DKGRAY)
        }
        toolbar.addView(hint, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(toolbar)

        preview = McStructure3DPreviewView(this)
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))

        layerLabel = TextView(this).apply { textSize=14f; setTextColor(Color.BLACK); setPadding(dp(14),dp(6),dp(14),0) }
        root.addView(layerLabel)
        layerSeek = SeekBar(this)
        root.addView(layerSeek, LinearLayout.LayoutParams(-1,dp(44)).apply { leftMargin=dp(8); rightMargin=dp(8) })
        val all = Button(this).apply { text="Show All Layers"; setOnClickListener { analysis?.let { layerLabel.text="Y layers: ALL"; preview.showAllLayers() } } }
        root.addView(all, LinearLayout.LayoutParams(-1,dp(46)).apply { leftMargin=dp(12); rightMargin=dp(12); bottomMargin=dp(8) })
        setContentView(root)

        preview.onBlockSelected = { showBlockDetails(it) }
        load()
    }

    private fun load() {
        val displayName=intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: "MCStructure"
        val sourceUri=intent.getStringExtra(EXTRA_SOURCE_URI)
        if (sourceUri.isNullOrBlank() && LevelDbSession.bridge == null) {
            Toast.makeText(this,"World database isn't open.",Toast.LENGTH_LONG).show(); finish(); return
        }
        Thread {
            try {
                val bytes = if (!sourceUri.isNullOrBlank()) {
                    contentResolver.openInputStream(android.net.Uri.parse(sourceUri))?.use { it.readBytes() }
                        ?: throw IllegalStateException("Couldn't open the imported .mcstructure file.")
                } else {
                    val rawKey=LevelDbSession.keyBytesByName[displayName] ?: displayName.toByteArray(Charsets.UTF_8)
                    LevelDbSession.bridge?.read(rawKey) ?: throw IllegalStateException("The structure record no longer exists.")
                }
                val decoded=McStructureDecoder.decode(NbtIO.readRootCompoundNoHeader(bytes))
                analysis=decoded
                runOnUiThread {
                    preview.setAnalysis(decoded)
                    layerSeek.max=maxOf(0,decoded.sizeY-1)
                    layerSeek.progress=0
                    layerLabel.text="Y layers: ALL"
                    layerSeek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                        override fun onProgressChanged(s:SeekBar?,p:Int,fromUser:Boolean){ layerLabel.text="Y layer: $p"; preview.setLayer(p) }
                        override fun onStartTrackingTouch(s:SeekBar?){ }
                        override fun onStopTrackingTouch(s:SeekBar?){ }
                    })
                }
            } catch(e:Throwable) {
                runOnUiThread { AlertDialog.Builder(this).setTitle("Couldn't load MCStructure").setMessage(e.message ?: e.javaClass.simpleName).setPositiveButton("OK",null).show() }
            }
        }.start()
    }

    private fun showBlockDetails(block: McStructureBlock) {
        val a=analysis
        val index=if(a==null) -1 else block.z + a.sizeZ * (block.y + a.sizeY * block.x)
        val palette=a?.palette?.getOrNull(block.paletteIndex)
        val sb=StringBuilder()
        sb.append("Local position: (${block.x}, ${block.y}, ${block.z})\n")
        sb.append("Linear index (ZYX): $index\n\n")
        sb.append("PALETTE ENTRY #${block.paletteIndex}\n")
        sb.append("name = ${block.name}\n")
        sb.append("version = ${block.version ?: "<missing>"}\n")
        sb.append("\nSTATES\n")
        sb.append(pretty(block.states ?: palette?.states, 0))
        sb.append("\n\nBLOCK POSITION DATA\n")
        sb.append(pretty(block.blockPositionData, 0))
        sb.append("\n\nPALETTE NBT\n")
        sb.append(prettyCompound(palette, 0))
        AlertDialog.Builder(this).setTitle("Block (${block.x}, ${block.y}, ${block.z})").setMessage(sb.toString()).setPositiveButton("OK",null).show()
    }

    private fun prettyCompound(entry: McStructurePaletteEntry?, depth:Int):String =
        entry?.rawTag?.let { pretty(it, depth) } ?: "<missing>"

    private fun pretty(tag:NbtTag?,depth:Int):String {
        if(tag==null)return "<none>"
        val ind="  ".repeat(depth)
        return when(tag){
            is NbtTag.Compound -> if(tag.entries.isEmpty()) "{}" else buildString { append("{\n"); tag.entries.forEach { (k,v)->append(ind).append("  ").append(k).append(": ").append(pretty(v,depth+1)).append("\n") }; append(ind).append("}") }
            is NbtTag.ListTag -> if(tag.items.isEmpty()) "[]" else buildString { append("[\n"); tag.items.forEach { append(ind).append("  ").append(pretty(it,depth+1)).append("\n") }; append(ind).append("]") }
            is NbtTag.Byte -> "${tag.value}b"
            is NbtTag.Short -> "${tag.value}s"
            is NbtTag.Int -> tag.value.toString()
            is NbtTag.Long -> "${tag.value}L"
            is NbtTag.Float -> "${tag.value}f"
            is NbtTag.Double -> tag.value.toString()
            is NbtTag.Str -> "\"${tag.value.replace("\\","\\\\").replace("\"","\\\"")}\""
            is NbtTag.ByteArray -> "ByteArray(${tag.value.size})"
            is NbtTag.IntArray -> tag.value.contentToString()
            is NbtTag.LongArray -> tag.value.contentToString()
            is NbtTag.End -> "End"
        }
    }
    private fun dp(v:Int)= (v*resources.displayMetrics.density+0.5f).toInt()
    companion object { const val EXTRA_DISPLAY_NAME="display_name"; const val EXTRA_SOURCE_URI="source_uri" }
}
