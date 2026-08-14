package com.spaimic.nbttool.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.spaimic.nbttool.chunk.ChunkKeys
import com.spaimic.nbttool.chunk.SubChunkEditor
import com.spaimic.nbttool.chunk.SubChunkParser
import com.spaimic.nbttool.shizuku.LevelDbBridge
import java.io.File

class ChunkBlockEditorActivity : AppCompatActivity() {
    private lateinit var worldDir: File
    private var chunkX = 0
    private var chunkZ = 0
    private var dimension = 0
    private var bridge: LevelDbBridge? = null
    private val blocks = HashMap<Triple<Int,Int,Int>, String>()
    private val original = HashMap<Int, ByteArray>()
    private var selectedX = -1
    private var selectedZ = -1
    private var selectedColumn: LinearLayout? = null
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        worldDir = File(intent.getStringExtra(EXTRA_WORLD_PATH) ?: return finish())
        title = "Chunk Block Editor"
        chunkX = intent.getIntExtra(EXTRA_CHUNK_X, 0)
        chunkZ = intent.getIntExtra(EXTRA_CHUNK_Z, 0)
        dimension = intent.getIntExtra(EXTRA_DIMENSION, 0)
        loadChunk()
    }

    private fun loadChunk() {
        Thread {
            try {
                val b=LevelDbBridge.open(worldDir); bridge=b
                val subs=b.readSubchunks(chunkX,chunkZ,dimension)
                for ((sy,bytes) in subs) {
                    original[sy]=bytes.copyOf()
                    when(val parsed=SubChunkParser.parse(bytes)) {
                        is SubChunkParser.ParseResult.Success -> for(x in 0..15) for(z in 0..15) for(y in 0..15)
                            blocks[Triple(x,z,sy*16+y)] = parsed.blocks[x][z][y]
                        else -> Unit
                    }
                }
                runOnUiThread { showGrid() }
            } catch(e:Throwable) { runOnUiThread { showErrorDialog(this,"Failed to load chunk",e) } }
        }.start()
    }

    private fun showGrid() {
        val scroll=ScrollView(this)
        val grid=GridLayout(this).apply { columnCount=16; rowCount=16; setPadding(16,16,16,16) }
        for(z in 0..15) for(x in 0..15) {
            val top=highestY(x,z)
            val button=Button(this).apply {
                text="$x,$z\n${if(top==Int.MIN_VALUE) "Air" else "Y $top"}"
                textSize=11f
                setPadding(2,2,2,2)
                layoutParams=ViewGroup.LayoutParams(150,90)
                setOnClickListener { openColumn(x,z) }
            }
            grid.addView(button)
        }
        scroll.addView(grid)
        setContentView(scroll)
    }

    private fun highestY(x:Int,z:Int):Int {
        for(y in 320 downTo -64) {
            val n=blocks[Triple(x,z,y)] ?: "minecraft:air"
            if(n!="minecraft:air") return y
        }
        return Int.MIN_VALUE
    }

    private fun openColumn(x:Int,z:Int) {
        selectedX=x; selectedZ=z
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(16,16,16,16) }
        val header=TextView(this).apply { text="Chunk ($chunkX, $chunkZ)  •  X=$x Z=$z"; textSize=18f; setPadding(8,8,8,16) }
        root.addView(header)
        val scroll=ScrollView(this)
        val list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        for(y in 320 downTo -64) {
            val name=blocks[Triple(x,z,y)] ?: "minecraft:air"
            val row=TextView(this).apply {
                text="Y $y   $name"
                textSize=15f; setPadding(16,14,16,14)
                setOnClickListener { editBlock(y,name) }
            }
            list.addView(row)
        }
        scroll.addView(list); root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val buttons=LinearLayout(this).apply { gravity=Gravity.CENTER }
        val back=Button(this).apply { text="Back"; setOnClickListener { showGrid() } }
        val save=Button(this).apply { text="Save"; setOnClickListener { saveChanges() } }
        buttons.addView(back); buttons.addView(save); root.addView(buttons)
        setContentView(root)
    }

    private fun editBlock(y:Int,current:String) {
        val input=EditText(this).apply { setText(current); hint="minecraft:block_name"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Edit block • Y=$y").setMessage("Enter a Minecraft block identifier.")
            .setView(input).setPositiveButton("Set") { _,_ ->
                val name=input.text.toString().trim()
                if(name.isBlank() || !name.startsWith("minecraft:")) {
                    Toast.makeText(this,"Use a full minecraft:* block identifier.",Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                blocks[Triple(selectedX,selectedZ,y)]=name; dirty=true
                openColumn(selectedX,selectedZ)
            }.setNegativeButton("Cancel",null).show()
    }

    private fun saveChanges() {
        val b=bridge ?: return
        Thread {
            try {
                val writes=ArrayList<Pair<ByteArray,ByteArray>>()
                for((sy,old) in original) {
                    var changed=false
                    val parsed=SubChunkParser.parse(old)
                    if(parsed is SubChunkParser.ParseResult.Success) {
                        for(x in 0..15) for(z in 0..15) for(y in 0..15) {
                            val before=parsed.blocks[x][z][y]
                            val after=blocks[Triple(x,z,sy*16+y)] ?: before
                            if(before!=after) { changed=true; break }
                        }
                    }
                    if(!changed) continue
                    // Apply all changed blocks one by one to the same raw subchunk, preserving palette/state data.
                    var bytes=old
                    for(x in 0..15) for(z in 0..15) for(y in 0..15) {
                        val before=(SubChunkParser.parse(bytes) as? SubChunkParser.ParseResult.Success)?.blocks?.get(x)?.get(z)?.get(y) ?: continue
                        val after=blocks[Triple(x,z,sy*16+y)] ?: before
                        if(before!=after) bytes=SubChunkEditor.replaceBlock(bytes,x,z,y,after)
                    }
                    writes += ChunkKeys.subChunkKey(chunkX,chunkZ,sy,dimension) to bytes
                }
                b.writeBatch(writes); dirty=false
                runOnUiThread { Toast.makeText(this,"Saved ${writes.size} changed subchunk(s).",Toast.LENGTH_LONG).show(); showGrid() }
            } catch(e:Throwable) { runOnUiThread { showErrorDialog(this,"Save failed — no changes were intentionally discarded",e) } }
        }.start()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0,MENU_SEARCH,0,"Search block")
        menu.add(0,MENU_REPLACE,0,"Replace block")
        return true
    }
    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if(item.itemId==MENU_SEARCH) { Toast.makeText(this,"Search will be expanded in a future build.",Toast.LENGTH_SHORT).show(); return true }
        if(item.itemId==MENU_REPLACE) { Toast.makeText(this,"Replace / Replace All will be expanded in a future build.",Toast.LENGTH_SHORT).show(); return true }
        return super.onOptionsItemSelected(item)
    }
    override fun onBackPressed() { if(dirty) AlertDialog.Builder(this).setTitle("Unsaved changes").setMessage("Discard block edits?").setPositiveButton("Discard") {_,_-> super.onBackPressed()}.setNegativeButton("Cancel",null).show() else super.onBackPressed() }
    companion object {
        const val EXTRA_WORLD_PATH="world_path"
        const val EXTRA_CHUNK_X="chunk_x"
        const val EXTRA_CHUNK_Z="chunk_z"
        const val EXTRA_DIMENSION="dimension"
        private const val MENU_SEARCH=1
        private const val MENU_REPLACE=2
    }
}
