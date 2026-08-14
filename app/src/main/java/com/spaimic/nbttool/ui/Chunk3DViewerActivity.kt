package com.spaimic.nbttool.ui

import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.spaimic.nbttool.chunk.BlockColors
import com.spaimic.nbttool.chunk.SubChunkParser
import com.spaimic.nbttool.shizuku.LevelDbBridge
import com.spaimic.nbttool.shizuku.LevelDbSession
import java.io.File
import kotlin.math.*

class Chunk3DViewerActivity : AppCompatActivity() {
    private lateinit var worldDir: File
    private var chunkX = 0
    private var chunkZ = 0
    private var dimension = 0
    private var bridge: LevelDbBridge? = null
    private var ownsBridge = false
    private val blocks = HashMap<Pos, String>()
    private var selectedX = -1
    private var selectedZ = -1
    private var selectionEnabled = true
    private var hideUnselected = false
    private lateinit var view: Chunk3DView
    private lateinit var infoText: TextView
    private var openButton: Button? = null

    data class Pos(val x: Int, val y: Int, val z: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        worldDir = File(intent.getStringExtra(EXTRA_WORLD_PATH) ?: return finish())
        chunkX = intent.getIntExtra(EXTRA_CHUNK_X, 0)
        chunkZ = intent.getIntExtra(EXTRA_CHUNK_Z, 0)
        dimension = intent.getIntExtra(EXTRA_DIMENSION, 0)
        selectionEnabled = intent.getBooleanExtra(EXTRA_SELECTION_ENABLED, true)
        selectedX = intent.getIntExtra(EXTRA_SELECTED_X, -1)
        selectedZ = intent.getIntExtra(EXTRA_SELECTED_Z, -1)
        title = "3D Chunk (${chunkX}, ${chunkZ})"
        loadChunk()
    }

    private fun loadChunk() {
        Thread {
            try {
                val sessionBridge = LevelDbSession.bridge
                val b = if (sessionBridge != null && LevelDbSession.worldDir?.canonicalFile == worldDir.canonicalFile) {
                    ownsBridge = false; sessionBridge
                } else {
                    ownsBridge = true; LevelDbBridge.open(worldDir)
                }
                bridge = b
                for ((sy, bytes) in b.readSubchunks(chunkX, chunkZ, dimension)) {
                    val parsed = SubChunkParser.parse(bytes)
                    if (parsed is SubChunkParser.ParseResult.Success) {
                        for (x in 0..15) for (z in 0..15) for (y in 0..15) {
                            val name = parsed.blocks[x][z][y]
                            if (name != "minecraft:air" && name != "air") blocks[Pos(x, sy * 16 + y, z)] = name
                        }
                    }
                }
                runOnUiThread { showViewer() }
            } catch (e: Throwable) {
                runOnUiThread { showErrorDialog(this, "Failed to load 3D chunk", e) }
            }
        }.start()
    }

    private fun showViewer() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        infoText = TextView(this).apply {
            setPadding(16, 10, 16, 10); textSize = 15f
            updateInfoText()
        }
        root.addView(infoText)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (selectionEnabled) {
            openButton = Button(this).apply { text = "Open Selected Area"; isEnabled = selectedX in 0..15 && selectedZ in 0..15; setOnClickListener { openSelected() } }
            controls.addView(openButton)
        }
        val hide = CheckBox(this).apply {
            text = "Hide unselected areas"; isEnabled = selectedX in 0..15 && selectedZ in 0..15; isChecked = hideUnselected
            setOnCheckedChangeListener { _, checked -> hideUnselected = checked; view.hideUnselected = checked; view.invalidate() }
        }
        controls.addView(hide)
        val reset = Button(this).apply { text = "Reset View"; setOnClickListener { view.resetView() } }
        controls.addView(reset)
        root.addView(controls)
        view = Chunk3DView()
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun updateInfoText() {
        if (!::infoText.isInitialized) return
        infoText.text = if (selectionEnabled && selectedX !in 0..15) {
            "Chunk Coordinates: ($chunkX, $chunkZ)\nTap a BlockChunk to select it."
        } else {
            val exactX = chunkX * 16 + selectedX
            val exactZ = chunkZ * 16 + selectedZ
            "Chunk Coordinates: ($chunkX, $chunkZ)\nBlockChunk Coordinates: ($selectedX, $selectedZ)\nExact Coordinates: ($exactX, $exactZ)"
        }
    }

    private fun openSelected() {
        if (selectedX !in 0..15 || selectedZ !in 0..15) return
        val i = Intent(this, ChunkBlockEditorActivity::class.java)
        i.putExtra(ChunkBlockEditorActivity.EXTRA_WORLD_PATH, worldDir.absolutePath)
        i.putExtra(ChunkBlockEditorActivity.EXTRA_CHUNK_X, chunkX)
        i.putExtra(ChunkBlockEditorActivity.EXTRA_CHUNK_Z, chunkZ)
        i.putExtra(ChunkBlockEditorActivity.EXTRA_DIMENSION, dimension)
        startActivity(i)
        finish()
    }

    private inner class Chunk3DView : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.rgb(255, 196, 0) }
        var hideUnselected = false
        private var rotX = 0.65f
        private var rotY = -0.7f
        private var zoom = 1f
        private var panX = 0f
        private var panY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var lastDist = 0f
        private var mode = 0
        private var moved = false

        init { setBackgroundColor(Color.rgb(18, 18, 18)); isFocusable = true }

        fun resetView() { rotX = 0.65f; rotY = -0.7f; zoom = 1f; panX = 0f; panY = 0f; invalidate() }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; moved = false; mode = 1; return true }
                MotionEvent.ACTION_POINTER_DOWN -> { lastDist = distance(e); mode = 2; return true }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 2 && e.pointerCount >= 2) {
                        val d = distance(e); if (lastDist > 0f) zoom = (zoom * (d / lastDist)).coerceIn(0.25f, 6f); lastDist = d; invalidate(); return true
                    }
                    if (mode == 1) {
                        val dx = e.x - lastX; val dy = e.y - lastY
                        if (abs(dx) > 3f || abs(dy) > 3f) moved = true
                        rotY += dx * 0.009f; rotX = (rotX + dy * 0.006f).coerceIn(-1.45f, 1.45f)
                        lastX = e.x; lastY = e.y; invalidate(); return true
                    }
                }
                MotionEvent.ACTION_UP -> { if (mode == 1 && selectionEnabled && !moved) selectAt(e.x, e.y); mode = 0; return true }
                MotionEvent.ACTION_POINTER_UP -> { mode = 1; return true }
            }
            return true
        }

        private fun distance(e: MotionEvent): Float {
            if (e.pointerCount < 2) return 0f
            val dx = e.getX(0) - e.getX(1); val dy = e.getY(0) - e.getY(1)
            return hypot(dx, dy)
        }

        private fun project(x: Float, y: Float, z: Float): Triple<Float, Float, Float> {
            val cy = cos(rotY); val sy = sin(rotY); val cx = cos(rotX); val sx = sin(rotX)
            val rx = x * cy - z * sy
            val rz = x * sy + z * cy
            val ry = y * cx - rz * sx
            val rz2 = y * sx + rz * cx
            val scale = min(width, height) * 0.055f * zoom
            return Triple(width / 2f + rx * scale + panX, height / 2f - ry * scale + panY, rz2)
        }

        private fun selectAt(px: Float, py: Float) {
            var bestX = -1
            var bestZ = -1
            var bestD = Float.MAX_VALUE
            for (z in 0..15) for (x in 0..15) {
                val top = blocks.keys.asSequence().filter { it.x == x && it.z == z }.maxOfOrNull { it.y } ?: continue
                val p = project(x - 7.5f, top - 64f, z - 7.5f)
                val d = hypot(px - p.first, py - p.second)
                if (d < bestD) { bestD = d; bestX = x; bestZ = z }
            }
            if (bestX in 0..15 && bestZ in 0..15 && bestD < max(55f, min(width, height) * 0.10f)) {
                selectedX = bestX; selectedZ = bestZ
                updateInfoText()
                openButton?.isEnabled = true
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (blocks.isEmpty()) { canvas.drawText("No non-air blocks in this chunk", 20f, 40f, paint); return }
            val visible = if (hideUnselected && selectedX in 0..15 && selectedZ in 0..15) {
                blocks.entries.filter { it.key.x == selectedX && it.key.z == selectedZ }.map { it.toPair() }
            } else {
                val neighbors = HashSet<Pos>(blocks.keys)
                blocks.entries.filter { (p, _) ->
                    p.x == 0 || p.x == 15 || p.z == 0 || p.z == 15 ||
                    !neighbors.contains(Pos(p.x + 1, p.y, p.z)) || !neighbors.contains(Pos(p.x - 1, p.y, p.z)) ||
                    !neighbors.contains(Pos(p.x, p.y + 1, p.z)) || !neighbors.contains(Pos(p.x, p.y - 1, p.z)) ||
                    !neighbors.contains(Pos(p.x, p.y, p.z + 1)) || !neighbors.contains(Pos(p.x, p.y, p.z - 1))
                }.map { it.toPair() }
            }
            val projected = ArrayList<Pair<Pos, FloatArray>>(visible.size)
            for ((p, _) in visible) {
                val a = project(p.x-8f,p.y-64f,p.z-8f)
                projected.add(p to floatArrayOf(a.first,a.second,a.third))
            }
            projected.sortBy { it.second[2] }
            for ((p, _) in projected) drawCube(canvas, p)
            if (selectedX in 0..15 && selectedZ in 0..15) drawSelection(canvas)
        }

        private fun drawCube(canvas: Canvas, p: Pos) {
            val x = p.x - 8f; val y = p.y - 64f; val z = p.z - 8f
            val pts = arrayOf(
                project(x,y,z), project(x+1,y,z), project(x+1,y+1,z), project(x,y+1,z),
                project(x,y,z+1), project(x+1,y,z+1), project(x+1,y+1,z+1), project(x,y+1,z+1)
            )
            val base = BlockColors.colorForDimension(blocks[p] ?: "minecraft:stone", dimension)
            val topColor = shade(base, 1.12f); val sideColor = shade(base, 0.88f); val frontColor = shade(base, 0.98f)
            paint.style = Paint.Style.FILL
            paint.color = topColor; polygon(canvas, pts, intArrayOf(3,2,6,7))
            paint.color = sideColor; polygon(canvas, pts, intArrayOf(1,5,6,2))
            paint.color = frontColor; polygon(canvas, pts, intArrayOf(0,1,2,3))
        }

        private fun drawSelection(canvas: Canvas) {
            val ys = blocks.keys.filter { it.x == selectedX && it.z == selectedZ }.map { it.y }
            if (ys.isEmpty()) return
            val minY = ys.minOrNull() ?: return; val maxY = ys.maxOrNull() ?: return
            val x = selectedX - 8f; val z = selectedZ - 8f
            val pts = arrayOf(project(x,minY-64f,z), project(x+1,minY-64f,z), project(x+1,maxY+1-64f,z), project(x,maxY+1-64f,z), project(x,minY-64f,z+1), project(x+1,minY-64f,z+1), project(x+1,maxY+1-64f,z+1), project(x,maxY+1-64f,z+1))
            val edges = arrayOf(intArrayOf(0,1,2,3,0), intArrayOf(4,5,6,7,4), intArrayOf(0,4), intArrayOf(1,5), intArrayOf(2,6), intArrayOf(3,7))
            outline.style = Paint.Style.STROKE; outline.strokeWidth = 5f
            for (edge in edges) { val path=Path(); path.moveTo(pts[edge[0]].first, pts[edge[0]].second); for(i in 1 until edge.size) path.lineTo(pts[edge[i]].first, pts[edge[i]].second); canvas.drawPath(path, outline) }
        }

        private fun polygon(canvas: Canvas, pts: Array<Triple<Float,Float,Float>>, idx: IntArray) {
            val path = Path(); path.moveTo(pts[idx[0]].first, pts[idx[0]].second); for (i in 1 until idx.size) path.lineTo(pts[idx[i]].first, pts[idx[i]].second); path.close(); canvas.drawPath(path, paint)
        }

        private fun shade(c: Int, factor: Float): Int = Color.rgb((Color.red(c)*factor).roundToInt().coerceIn(0,255), (Color.green(c)*factor).roundToInt().coerceIn(0,255), (Color.blue(c)*factor).roundToInt().coerceIn(0,255))
    }

    override fun onDestroy() { if (ownsBridge) try { bridge?.close() } catch (_: Exception) {}; bridge=null; super.onDestroy() }

    companion object {
        const val EXTRA_WORLD_PATH = "world_path"
        const val EXTRA_CHUNK_X = "chunk_x"
        const val EXTRA_CHUNK_Z = "chunk_z"
        const val EXTRA_DIMENSION = "dimension"
        const val EXTRA_SELECTION_ENABLED = "selection_enabled"
        const val EXTRA_SELECTED_X = "selected_x"
        const val EXTRA_SELECTED_Z = "selected_z"
    }
}
