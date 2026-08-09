package com.spaimic.nbttool.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.worlds.FlatLayer
import com.spaimic.nbttool.worlds.FlatWorldGenerator
import com.spaimic.nbttool.worlds.LocalWorldStore
import java.io.File

/**
 * Custom flat-world generator. With an existing world path it edits that world's
 * level.dat; without one it creates a brand-new world directly from this screen.
 * Layers are always displayed and stored bottom-to-top (first row = lowest layer).
 */
class FlatWorldGeneratorActivity : AppCompatActivity() {

    private var worldDir: File? = null
    private var levelDatFile: File? = null
    private var levelDat: NbtIO.LevelDat? = null
    private lateinit var listView: ListView
    private val layers = ArrayList<FlatLayer>()
    private lateinit var biomeSpinner: Spinner
    private lateinit var preventSlimes: CheckBox
    private lateinit var worldNameInput: EditText
    private var createMode = false

    private val biomeOptions = listOf(
        "Plains" to "minecraft:plains",
        "Forest" to "minecraft:forest",
        "Taiga" to "minecraft:taiga",
        "Desert" to "minecraft:desert",
        "Savanna" to "minecraft:savanna",
        "Jungle" to "minecraft:jungle",
        "Swamp" to "minecraft:swamp",
        "Snowy Plains" to "minecraft:snowy_plains",
        "Cherry Grove" to "minecraft:cherry_grove",
        "Badlands" to "minecraft:badlands",
        "Mushroom Fields" to "minecraft:mushroom_fields",
        "Ocean" to "minecraft:ocean"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_WORLD_PATH)
        createMode = path.isNullOrBlank()
        worldDir = path?.let(::File)
        levelDatFile = worldDir?.let { File(it, "level.dat") }
        title = if (createMode) "Custom Flat World Generator" else "Flat World Generator"

        buildUi()
        if (createMode) {
            layers.addAll(defaultLayers())
            refresh()
        } else {
            loadExistingWorld()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
        }

        if (createMode) {
            worldNameInput = EditText(this).apply {
                hint = "World name"
                setText("Custom Flat World")
                setSingleLine(true)
            }
            root.addView(worldNameInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            worldNameInput = EditText(this).apply { visibility = android.view.View.GONE }
        }

        val biomeLabel = TextView(this).apply { text = "Biome"; setPadding(4, 8, 4, 4) }
        root.addView(biomeLabel)
        biomeSpinner = Spinner(this)
        biomeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, biomeOptions.map { it.first })
        root.addView(biomeSpinner)

        preventSlimes = CheckBox(this).apply {
            text = "Prevent slimes from spawning"
            setOnCheckedChangeListener { _, checked ->
                // The checkbox only changes the generated stack when Generate is pressed.
                // Keeping the editable list unchanged makes it obvious what the user chose.
            }
        }
        root.addView(preventSlimes)

        val help = TextView(this).apply {
            text = "Layers are bottom → top. Layer 1 is the lowest layer. The world starts at Y -64."
            setPadding(4, 2, 4, 10)
        }
        root.addView(help)

        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(listView)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val addButton = Button(this).apply {
            text = "Add layer"
            setOnClickListener { promptAddLayer() }
        }
        val generateButton = Button(this).apply {
            text = if (createMode) "Create World" else "Save"
            setOnClickListener { generate() }
        }
        buttons.addView(addButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(generateButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        setContentView(root)
        listView.setOnItemClickListener { _, _, position, _ -> promptEditLayer(position) }
        listView.setOnItemLongClickListener { _, _, position, _ -> showLayerMenu(position); true }
    }

    private fun defaultLayers() = listOf(
        FlatLayer("minecraft:bedrock", 1),
        FlatLayer("minecraft:dirt", 2),
        FlatLayer("minecraft:grass_block", 1)
    )

    private fun loadExistingWorld() {
        try {
            val file = levelDatFile ?: error("Missing level.dat")
            levelDat = NbtIO.readLevelDat(file.readBytes())
            val dat = levelDat!!
            val existingJson = (dat.root.entries["FlatWorldLayers"] as? NbtTag.Str)?.value
            val existing = existingJson?.let { FlatWorldGenerator.fromJson(it) }
            layers.clear()
            layers.addAll(existing ?: defaultLayers())
            val biome = (dat.root.entries["BiomeOverride"] as? NbtTag.Str)?.value
            val index = biomeOptions.indexOfFirst { it.second == biome }
            if (index >= 0) biomeSpinner.setSelection(index)
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
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(nameInput); addView(countInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Add layer (on top)")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text.toString().trim().ifBlank { return@setPositiveButton }
                val count = countInput.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
                layers.add(FlatLayer(name, count)); refresh()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun promptEditLayer(index: Int) {
        val layer = layers[index]
        val nameInput = EditText(this).apply { setText(layer.blockName) }
        val countInput = EditText(this).apply { setText(layer.count.toString()); inputType = InputType.TYPE_CLASS_NUMBER }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0); addView(nameInput); addView(countInput) }
        AlertDialog.Builder(this).setTitle("Edit layer ${index + 1}").setView(box)
            .setPositiveButton("Save") { _, _ ->
                layer.blockName = nameInput.text.toString().trim().ifBlank { layer.blockName }
                layer.count = countInput.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: layer.count
                refresh()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showLayerMenu(index: Int) {
        val options = buildList {
            add("Edit")
            if (index > 0) add("Move down (toward bottom)")
            if (index < layers.size - 1) add("Move up (toward top)")
            add("Delete")
        }
        AlertDialog.Builder(this).setTitle("Layer ${index + 1}: ${layers[index].blockName}")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Edit" -> promptEditLayer(index)
                    "Move down (toward bottom)" -> swap(index, index - 1)
                    "Move up (toward top)" -> swap(index, index + 1)
                    "Delete" -> { layers.removeAt(index); refresh() }
                }
            }.show()
    }

    private fun swap(a: Int, b: Int) {
        val tmp = layers[a]; layers[a] = layers[b]; layers[b] = tmp; refresh()
    }

    private fun selectedBiomeId(): String = biomeOptions[biomeSpinner.selectedItemPosition].second

    /** Adds air below the chosen stack so its top is Y=39, leaving no spawnable space below Y=40. */
    private fun preparedLayers(): List<FlatLayer> {
        val base = layers.map { FlatLayer(it.blockName, it.count) }
        if (!preventSlimes.isChecked) return base
        val solidHeight = base.sumOf { it.count }
        val maxSlimeSafeHeight = 104 // Y -64..39 inclusive
        if (solidHeight > maxSlimeSafeHeight) {
            throw IllegalArgumentException("Slime prevention needs the selected layers to fit between Y -64 and Y 39 (maximum 104 blocks).")
        }
        val bottom = 40 - solidHeight
        val airCount = bottom - (-64)
        if (airCount <= 0) return base
        return listOf(FlatLayer("minecraft:air", airCount)) + base
    }

    private fun generate() {
        if (layers.isEmpty()) {
            Toast.makeText(this, "Add at least one layer first", Toast.LENGTH_SHORT).show(); return
        }
        try {
            val finalLayers = preparedLayers()
            if (finalLayers.sumOf { it.count } > 384) {
                throw IllegalArgumentException("The layer stack is too tall for the Bedrock world height range.")
            }
            val biomeId = selectedBiomeId()
            if (createMode) createNewWorld(finalLayers, biomeId) else saveExistingWorld(finalLayers, biomeId)
        } catch (e: Exception) {
            showErrorDialog(this, "Couldn't generate flat world", e)
        }
    }

    private fun createNewWorld(finalLayers: List<FlatLayer>, biomeId: String) {
        val name = worldNameInput.text.toString().trim().ifBlank { "Custom Flat World" }
        Thread {
            try {
                val localName = LocalWorldStore.uniqueLocalName(this, name)
                val dir = File(LocalWorldStore.rootDir(this), localName).apply { mkdirs() }
                val dat = FlatWorldGenerator.buildNewWorldLevelDat(name, finalLayers, biomeId)
                File(dir, "level.dat").writeBytes(NbtIO.writeLevelDat(dat))
                LocalWorldStore.writeDisplayName(dir, name)
                runOnUiThread {
                    Toast.makeText(this, "Created \"$localName\"", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Failed to create world", e) }
            }
        }.start()
    }

    private fun saveExistingWorld(finalLayers: List<FlatLayer>, biomeId: String) {
        val dat = levelDat ?: return
        val file = levelDatFile ?: return
        try {
            val backupFile = File(LocalWorldStore.backupsDir(this), "level_${worldDir!!.name}_${System.currentTimeMillis()}.dat.bak")
            file.copyTo(backupFile, overwrite = true)
            dat.root.entries["FlatWorldLayers"] = NbtTag.Str(FlatWorldGenerator.toJson(finalLayers))
            dat.root.entries["Generator"] = NbtTag.Int(FlatWorldGenerator.GENERATOR_FLAT)
            dat.root.entries["BiomeOverride"] = NbtTag.Str(biomeId)
            file.writeBytes(NbtIO.writeLevelDat(dat))
            AlertDialog.Builder(this).setTitle("Flat world settings saved")
                .setMessage("New chunks will use the custom layers. Existing generated terrain is unchanged.\n\nBiomeOverride: $biomeId")
                .setPositiveButton("OK") { _, _ -> finish() }.show()
        } catch (e: Exception) {
            showErrorDialog(this, "Failed to save", e)
        }
    }

    companion object {
        const val EXTRA_WORLD_PATH = "world_path"
    }
}
