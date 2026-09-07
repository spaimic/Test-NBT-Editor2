package com.spaimic.nbttool.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.LevelDbSession

/** Detailed, read-only Build 205 MCStructure decoder. */
class McStructureAnalyzerActivity : AppCompatActivity() {
    private lateinit var infoText: TextView
    private var analysis: McStructureAnalysis? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: "MCStructure"
        title = displayName

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(20))
            setBackgroundColor(Color.WHITE)
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val loading = TextView(this).apply {
            text = "Reading MCStructure data…"
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(0, dp(12), 0, dp(12))
        }
        content.addView(loading)
        val previewButton = Button(this).apply {
            text = "Open Full 3D Preview"
            setOnClickListener {
                val i = android.content.Intent(this@McStructureAnalyzerActivity, McStructure3DPreviewActivity::class.java)
                i.putExtra(McStructure3DPreviewActivity.EXTRA_DISPLAY_NAME, displayName)
                intent.getStringExtra(EXTRA_SOURCE_URI)?.let { i.putExtra(McStructure3DPreviewActivity.EXTRA_SOURCE_URI, it) }
                startActivity(i)
            }
        }
        content.addView(previewButton, LinearLayout.LayoutParams(-1, dp(52)))

        val placementButton = Button(this).apply {
            text = "🏗️ Place Structure in World (Prototype)"
            setOnClickListener {
                if (!ExperimentMode.isEnabled(this@McStructureAnalyzerActivity)) {
                    AlertDialog.Builder(this@McStructureAnalyzerActivity)
                        .setTitle("Experiment Mode Required")
                        .setMessage("Direct MCStructure placement can modify and potentially corrupt a world. Activate Experiment Mode and make a backup before using this prototype.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@setOnClickListener
                }
                val i = android.content.Intent(this@McStructureAnalyzerActivity, McStructurePlacementActivity::class.java)
                i.putExtra(McStructurePlacementActivity.EXTRA_DISPLAY_NAME, displayName)
                intent.getStringExtra(EXTRA_SOURCE_URI)?.let { i.putExtra(McStructurePlacementActivity.EXTRA_SOURCE_URI, it) }
                startActivity(i)
            }
        }
        placementButton.setTextColor(Color.BLACK)
        content.addView(placementButton, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(6) })
        infoText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(0, dp(12), 0, dp(12))
        }
        content.addView(infoText)
        load(displayName, loading, content)
    }

    private fun load(displayName: String, loading: TextView, content: LinearLayout) {
        val bridge = LevelDbSession.bridge
        val sourceUri = intent.getStringExtra(EXTRA_SOURCE_URI)
        if (sourceUri.isNullOrBlank() && bridge == null) {
            loading.text = "World database isn't open."
            return
        }
        Thread {
            try {
                val bytes = if (!sourceUri.isNullOrBlank()) {
                    contentResolver.openInputStream(android.net.Uri.parse(sourceUri))?.use { it.readBytes() }
                        ?: throw IllegalStateException("Couldn't open the imported .mcstructure file.")
                } else {
                    val rawKey = LevelDbSession.keyBytesByName[displayName] ?: displayName.toByteArray(Charsets.UTF_8)
                    bridge!!.read(rawKey) ?: throw IllegalStateException("The LevelDB value no longer exists.")
                }
                val root = NbtIO.readRootCompoundNoHeader(bytes)
                val decoded = McStructureDecoder.decode(root)
                analysis = decoded
                runOnUiThread {
                    loading.visibility = View.GONE
                    bind(decoded, content)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    loading.text = "Couldn't decode this MCStructure."
                    infoText.text = "\n${e.message ?: e.javaClass.simpleName}"
                }
            }
        }.start()
    }

    private fun bind(a: McStructureAnalysis, content: LinearLayout) {
        infoText.text = buildString {
            append("FORMAT / ROOT\n")
            append("format_version = ${McStructureDecoder.valueText(a.raw.entries["format_version"])}\n")
            append("size = [${a.sizeX}, ${a.sizeY}, ${a.sizeZ}]\n")
            append("structure_world_origin = ${a.origin?.contentToString() ?: "<missing>"}\n")
            append("\nDECODED COUNTS\n")
            append(McStructureDecoder.summaryText(a))
            append("\n\nINDEXING MODEL\n")
            append("Build 205 decodes the Bedrock structure compound as:\n")
            append("structure.block_indices / structure.entities / structure.palette\n")
            append("Index order is ZYX: z + sizeZ * (y + sizeY * x)\n")
            append("block_indices layer 0 length = ${a.layer0.size}\n")
            append("block_indices layer 1 length = ${a.layer1.size}\n")
            append("Expected positions = ${a.volume}\n")
            append("Two block-index layers are supported; layer 1 overrides layer 0 when its entry is >= 0.\n")
            append("\nROOT KEYS\n")
            append(a.raw.entries.keys.joinToString(", "))
            if (a.unknownRootEntries.isNotEmpty()) {
                append("\n\nUNRECOGNIZED ROOT KEYS\n")
                append(a.unknownRootEntries.joinToString(", "))
            }
        }

        val paletteTitle = sectionTitle("PALETTE (${a.palette.size})")
        content.addView(paletteTitle)
        a.palette.forEach { entry ->
            val row = TextView(this).apply {
                text = "#${entry.index}  ${entry.name}\n    states: ${entry.states?.entries?.size ?: 0}   version: ${entry.version ?: "<missing>"}"
                textSize = 14f
                setTextColor(Color.BLACK)
                setPadding(dp(8), dp(7), dp(8), dp(7))
            }
            content.addView(row)
        }

        val entitiesTitle = sectionTitle("ENTITIES (${a.entities?.items?.size ?: 0})")
        content.addView(entitiesTitle)
        val entityItems = a.entities?.items.orEmpty()
        if (entityItems.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "No entities stored in structure.entities."
                textSize = 13f
                setTextColor(Color.DKGRAY)
                setPadding(dp(8), dp(4), dp(8), dp(8))
            })
        } else {
            entityItems.forEachIndexed { index, tag ->
                val compound = tag as? NbtTag.Compound
                val id = compound?.entries?.get("identifier") as? NbtTag.Str
                    ?: compound?.entries?.get("id") as? NbtTag.Str
                val pos = compound?.entries?.get("Pos")
                val row = TextView(this).apply {
                    text = buildString {
                        append("#$index  ")
                        append(id?.value ?: "<entity>")
                        if (pos != null) append("\n    Pos = ${McStructureDecoder.valueText(pos)}")
                    }
                    textSize = 13f
                    setTextColor(Color.BLACK)
                    setPadding(dp(8), dp(5), dp(8), dp(5))
                    setOnClickListener {
                        AlertDialog.Builder(this@McStructureAnalyzerActivity)
                            .setTitle("Entity #$index")
                            .setMessage(McStructureDecoder.valueText(tag))
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
                content.addView(row)
            }
        }

        val blocksTitle = sectionTitle("BLOCKS (${a.blocks.size}) — first 300 shown")
        content.addView(blocksTitle)
        a.blocks.take(300).forEach { b ->
            val row = TextView(this).apply {
                text = "(${b.x}, ${b.y}, ${b.z})  index=${b.paletteIndex}  ${b.name}"
                textSize = 13f
                setTextColor(Color.BLACK)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { showBlockDetails(b) }
            }
            content.addView(row)
        }
        if (a.blocks.size > 300) {
            content.addView(TextView(this).apply {
                text = "… ${a.blocks.size - 300} more blocks are present."
                setTextColor(Color.DKGRAY)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
        }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(Color.BLACK)
        setPadding(dp(0), dp(16), dp(0), dp(7))
    }

    private fun showBlockDetails(block: McStructureBlock) {
        val a = analysis
        val index = if (a == null) -1 else block.z + a.sizeZ * (block.y + a.sizeY * block.x)
        val palette = a?.palette?.getOrNull(block.paletteIndex)
        val message = buildString {
            append("Local position: (${block.x}, ${block.y}, ${block.z})\n")
            append("Linear index (ZYX): $index\n")
            append("Palette index: ${block.paletteIndex}\n")
            append("Palette name: ${block.name}\n")
            append("Version: ${block.version ?: "<missing>"}\n\n")
            append("STATES\n")
            append(prettyNbt(block.states ?: palette?.states))
            append("\n\nBLOCK POSITION DATA\n")
            append(prettyNbt(block.blockPositionData))
            append("\n\nPALETTE ENTRY (RAW)\n")
            append(prettyNbt(palette?.rawTag))
        }
        AlertDialog.Builder(this).setTitle("Block (${block.x}, ${block.y}, ${block.z})").setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun prettyNbt(tag: NbtTag?): String {
        if (tag == null) return "<none>"
        return when (tag) {
            is NbtTag.Compound -> if (tag.entries.isEmpty()) "{}" else tag.entries.entries.joinToString("\n") { "  ${it.key}: ${prettyNbt(it.value)}" }
            is NbtTag.ListTag -> "[${tag.items.joinToString(", ") { prettyNbt(it) }}]"
            is NbtTag.Byte -> "${tag.value}b"
            is NbtTag.Short -> "${tag.value}s"
            is NbtTag.Int -> tag.value.toString()
            is NbtTag.Long -> "${tag.value}L"
            is NbtTag.Float -> "${tag.value}f"
            is NbtTag.Double -> tag.value.toString()
            is NbtTag.Str -> "\"${tag.value}\""
            is NbtTag.ByteArray -> "ByteArray(${tag.value.size})"
            is NbtTag.IntArray -> tag.value.contentToString()
            is NbtTag.LongArray -> tag.value.contentToString()
            is NbtTag.End -> "End"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val EXTRA_DISPLAY_NAME = "display_name"
        const val EXTRA_SOURCE_URI = "source_uri"
    }
}
