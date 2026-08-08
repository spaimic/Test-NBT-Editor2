package com.spaimic.nbttool.ui

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.spaimic.nbttool.chunk.EntityIcons
import com.spaimic.nbttool.chunk.EntityKeys
import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.LevelDbSession

/**
 * Lists every entity in one chunk (via Bedrock's digp/actorprefix key scheme —
 * see EntityKeys). Each entry is registered into LevelDbSession the same way
 * PlayerDbActivity registers regular keys, so tapping one can reuse
 * DbEntryEditorActivity unchanged, and "Save changes" back on the map screen
 * persists entity edits the same way it does everything else.
 */
class EntityListActivity : AppCompatActivity() {

    private data class EntityRow(val label: String, val sessionKey: String, val iconRes: Int)

    private lateinit var listView: ListView
    private var rows: List<EntityRow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chunkX = intent.getIntExtra(EXTRA_CHUNK_X, 0)
        val chunkZ = intent.getIntExtra(EXTRA_CHUNK_Z, 0)
        val dimension = intent.getIntExtra(EXTRA_DIMENSION, 0)
        title = "Entities in chunk ($chunkX, $chunkZ) — ${dimensionName(dimension)}"

        listView = ListView(this)
        setContentView(listView)
        listView.setOnItemClickListener { _, _, position, _ ->
            val i = Intent(this, DbEntryEditorActivity::class.java)
            i.putExtra(DbEntryEditorActivity.EXTRA_DISPLAY_NAME, rows[position].sessionKey)
            startActivity(i)
        }

        loadEntities(chunkX, chunkZ, dimension)
    }

    private fun loadEntities(chunkX: Int, chunkZ: Int, dimension: Int) {
        val bridge = LevelDbSession.bridge
        if (bridge == null) {
            Toast.makeText(this, "World database isn't open", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            try {
                val digpValue = bridge.read(EntityKeys.digpKey(chunkX, chunkZ, dimension))
                if (digpValue == null || digpValue.isEmpty()) {
                    runOnUiThread { showNoEntitiesDialog() }
                    return@Thread
                }
                val actorIds = EntityKeys.splitActorIds(digpValue)
                val built = ArrayList<EntityRow>()
                for (actorId in actorIds) {
                    val key = EntityKeys.actorPrefixKey(actorId)
                    val bytes = bridge.read(key) ?: continue
                    val compound = try { NbtIO.readRootCompoundNoHeader(bytes) } catch (e: Exception) { continue }
                    val identifier = (compound.entries["identifier"] as? NbtTag.Str)?.value ?: "minecraft:unknown"
                    val idHex = actorId.joinToString("") { "%02x".format(it) }
                    val displayName = "entity:${identifier.substringAfter(':')}:$idHex"
                    LevelDbSession.keyBytesByName[displayName] = key
                    LevelDbSession.loadedEntries[displayName] = compound
                    val niceName = identifier.substringAfter(':').replace('_', ' ')
                        .replaceFirstChar { it.uppercase() }
                    built.add(EntityRow("$niceName ($idHex)", displayName, EntityIcons.iconFor(identifier)))
                }
                runOnUiThread {
                    if (built.isEmpty()) {
                        showNoEntitiesDialog()
                    } else {
                        rows = built
                        listView.adapter = EntityRowAdapter(this, rows)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Failed to load entities", e) }
            }
        }.start()
    }

    private fun showNoEntitiesDialog() {
        android.app.AlertDialog.Builder(this)
            .setMessage("No entities found in this chunk")
            .setPositiveButton("OK") { _, _ -> finish() }
            .show()
    }

    companion object {
        const val EXTRA_CHUNK_X = "chunk_x"
        const val EXTRA_CHUNK_Z = "chunk_z"
        const val EXTRA_DIMENSION = "dimension"

        private fun dimensionName(dimension: Int) = when (dimension) {
            1 -> "Nether"
            2 -> "The End"
            else -> "Overworld"
        }
    }

    private class EntityRowAdapter(context: android.content.Context, private val rows: List<EntityRow>) :
        ArrayAdapter<EntityRow>(context, 0, rows) {
        override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
            val row = rows[position]
            val icon = ImageView(context).apply {
                setImageResource(row.iconRes)
                layoutParams = LinearLayout.LayoutParams(72, 72).apply { rightMargin = 24 }
            }
            val text = TextView(context).apply {
                text = row.label
                textSize = 16f
            }
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(32, 24, 32, 24)
                addView(icon)
                addView(text)
            }
        }
    }
}
