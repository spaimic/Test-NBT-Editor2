package com.spaimic.nbttool.ui

import android.app.AlertDialog
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.worlds.FlatLayer
import com.spaimic.nbttool.worlds.FlatWorldGenerator
import com.spaimic.nbttool.worlds.LocalWorldStore
import java.io.File

/**
 * Lets you build a custom flat-world layer stack (bottom to top) and write it
 * into level.dat as the "FlatWorldLayers" JSON string Bedrock expects, along
 * with Generator=2. Only affects newly-generated chunks — it can't reshape
 * terrain that's already been explored.
 */
class FlatWorldGeneratorActivity : AppCompatActivity() {

    private lateinit var worldDir: File
    private lateinit var levelDatFile: File
    private var levelDat: NbtIO.LevelDat? = null
    private lateinit var listView: ListView
    private val layers = ArrayList<FlatLayer>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        worldDir = File(intent.getStringExtra(EXTRA_WORLD_PATH) ?: return finish())
        levelDatFile = File(worldDir, "level.dat")
        title = "Flat World Generator"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val addButton = Button(this).apply {
            text = "Add layer"
            setOnClickListener { promptAddLayer() }
        }
        root.addView(listView)
        root.addView(addButton)
        setContentView(root)

        listView.setOnItemClickListener { _, _, position, _ -> promptEditLayer(position) }
        listView.setOnItemLongClickListener { _, _, position, _ -> showLayerMenu(position); true }

        load()
    }

    private fun load() {
        try {
            levelDat = NbtIO.readLevelDat(levelDatFile.readBytes())
            val existingJson = (levelDat!!.root.entries["FlatWorldLayers"] as? NbtTag.Str)?.value
            val existing = existingJson?.let { FlatWorldGenerator.fromJson(it) }
            layers.clear()
            if (existing != null) {
                layers.addAll(existing)
            } else {
                // classic default preset as a starting point: bedrock, dirt×2, grass on top
                layers.addAll(listOf(
                    FlatLayer("minecraft:bedrock", 1),
                    FlatLayer("minecraft:dirt", 2),
                    FlatLayer("minecraft:grass_block", 1)
                ))
            }
            refresh()
        } catch (e: Exception) {
            showErrorDialog(this, "Failed to load level.dat", e)
        }
    }

    private fun refresh() {
        val labels = layers.mapIndexed { i, l -> "${i + 1}. ${l.blockName} × ${l.count}" }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun promptAddLayer() {
        val nameInput = EditText(this).apply { hint = "block name, e.g. minecraft:stone" }
        val countInput = EditText(this).apply {
            hint = "count"
            setText("1")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(nameInput)
            addView(countInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Add layer (added on top)")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text.toString().trim().ifBlank { return@setPositiveButton }
                val count = countInput.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
                layers.add(FlatLayer(name, count))
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptEditLayer(index: Int) {
        val layer = layers[index]
        val nameInput = EditText(this).apply { setText(layer.blockName) }
        val countInput = EditText(this).apply {
            setText(layer.count.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(nameInput)
            addView(countInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Edit layer ${index + 1}")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                layer.blockName = nameInput.text.toString().trim().ifBlank { layer.blockName }
                layer.count = countInput.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: layer.count
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLayerMenu(index: Int) {
        val options = buildList {
            add("Edit")
            if (index > 0) add("Move down (toward bedrock)")
            if (index < layers.size - 1) add("Move up (toward surface)")
            add("Delete")
        }
        AlertDialog.Builder(this)
            .setTitle("Layer ${index + 1}: ${layers[index].blockName}")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Edit" -> promptEditLayer(index)
                    "Move down (toward bedrock)" -> { swap(index, index - 1) }
                    "Move up (toward surface)" -> { swap(index, index + 1) }
                    "Delete" -> { layers.removeAt(index); refresh() }
                }
            }
            .show()
    }

    private fun swap(a: Int, b: Int) {
        val tmp = layers[a]; layers[a] = layers[b]; layers[b] = tmp
        refresh()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, MENU_GENERATE, 0, "Generate")
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == MENU_GENERATE) {
            generate()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun generate() {
        val dat = levelDat ?: return
        if (layers.isEmpty()) {
            Toast.makeText(this, "Add at least one layer first", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val backupFile = File(LocalWorldStore.backupsDir(this), "level_${worldDir.name}_${System.currentTimeMillis()}.dat.bak")
            levelDatFile.copyTo(backupFile, overwrite = true)

            val json = FlatWorldGenerator.toJson(layers)
            dat.root.entries["FlatWorldLayers"] = NbtTag.Str(json)
            dat.root.entries["Generator"] = NbtTag.Int(FlatWorldGenerator.GENERATOR_FLAT)
            levelDatFile.writeBytes(NbtIO.writeLevelDat(dat))

            AlertDialog.Builder(this)
                .setTitle("Flat world settings saved")
                .setMessage(
                    "level.dat now generates new chunks using your custom layers.\n\n" +
                        "This only affects chunks that haven't been generated yet — it can't " +
                        "reshape terrain that already exists in an explored world. For a clean " +
                        "result, use this on a fresh/unexplored world."
                )
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Exception) {
            showErrorDialog(this, "Failed to save", e)
        }
    }

    companion object {
        const val EXTRA_WORLD_PATH = "world_path"
        private const val MENU_GENERATE = 1
    }
}
