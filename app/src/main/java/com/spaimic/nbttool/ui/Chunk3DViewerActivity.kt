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
import java.util.ArrayDeque
import kotlin.math.*

/**
 * Lightweight 3D chunk viewer.
 *
 * Build 144 uses a surface-only greedy mesh: exposed coplanar faces with the same
 * rendered color are merged into larger rectangular quads. The mesh is built once
 * when the chunk loads; frames only transform and draw the already-built quads.
 */
class Chunk3DViewerActivity : AppCompatActivity() {
    private lateinit var worldDir: File
    private var chunkX = 0
    private var chunkZ = 0
    private var dimension = 0
    private var bridge: LevelDbBridge? = null
    private var ownsBridge = false
    private val blocks = HashMap<Pos, String>()
    private var mesh = emptyList<MeshQuad>()
    private var selectedX = -1
    private var selectedZ = -1
    private var selectionEnabled = true
    private var hideUnselected = false
    private lateinit var view: Chunk3DView
    private lateinit var infoText: TextView
    private var openButton: Button? = null

    data class Pos(val x: Int, val y: Int, val z: Int)

    private data class V3(val x: Float, val y: Float, val z: Float)
    private data class MeshQuad(val a: V3, val b: V3, val c: V3, val d: V3, val color: Int, val depth: Float)
    private data class GroupKey(val face: Int, val plane: Int, val color: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        worldDir = File(intent.getStringExtra(EXTRA_WORLD_PATH) ?: return finish())
        chunkX = intent.getIntExtra(EXTRA_CHUNK_X, 0)
        chunkZ = intent.getIntExtra(EXTRA_CHUNK_Z, 0)
        dimension = intent.getIntExtra(EXTRA_DIMENSION, 0)
        selectionEnabled = intent.getBooleanExtra(EXTRA_SELECTION_ENABLED, true)
        selectedX = intent.getIntExtra(EXTRA_SELECTED_X, -1)
        selectedZ = intent.getIntExtra(EXTRA_SELECTED_Z, -1)
        title = "3D Chunk ($chunkX, $chunkZ)"
        loadChunk()
    }

    private fun loadChunk() {
        Thread {
            try {
                val sessionBridge = LevelDbSession.bridge
                val b = if (sessionBridge != null && LevelDbSession.worldDir?.canonicalFile == worldDir.canonicalFile) {
                    ownsBridge = false
                    sessionBridge
                } else {
                    ownsBridge = true
                    LevelDbBridge.open(worldDir)
                }
                bridge = b

                for ((sy, bytes) in b.readSubchunks(chunkX, chunkZ, dimension)) {
                    val parsed = SubChunkParser.parse(bytes)
                    if (parsed is SubChunkParser.ParseResult.Success) {
                        for (x in 0..15) for (z in 0..15) for (y in 0..15) {
                            val name = parsed.blocks[x][z][y]
                            if (!isAir(name)) blocks[Pos(x, sy * 16 + y, z)] = name
                        }
                    }
                }

                mesh = buildGreedySurfaceMesh()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) showViewer()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) showErrorDialog(this, "Failed to load 3D chunk", e)
                }
            }
        }.start()
    }

    private fun isAir(name: String): Boolean {
        return name == "minecraft:air" || name == "air" || name.endsWith(":air")
    }

    // Only known full, opaque cubes may hide a neighboring face. Glass, leaves,
    // fluids, plants, and partial blocks remain non-occluding in the mesh.
    private fun isOpaqueFullCube(name: String): Boolean {
        val n = name.lowercase()
        val nonCubes = arrayOf(
            "glass", "leaves", "water", "lava", "ice", "plant", "flower", "sapling",
            "torch", "pane", "fence", "wall", "stairs", "slab", "door", "trapdoor",
            "carpet", "rail", "ladder", "vine", "chain", "bars", "button",
            "pressure_plate", "sign", "skull", "head", "banner", "bed", "chest",
            "hopper", "portal", "fire", "coral", "kelp", "seagrass", "bamboo",
            "cactus", "sugar_cane", "mushroom", "web", "snow_layer", "farmland",
            "path", "repeater", "comparator", "lever", "tripwire", "scaffolding",
            "short_grass", "tall_grass", "fern", "dead_bush", "azalea", "moss",
            "crop", "wheat", "carrots", "potatoes", "beetroot", "sweet_berry",
            "bush", "lichen", "dripleaf", "spore_blossom", "lily_pad", "turtle_egg",
            "frogspawn", "dripstone", "amethyst_cluster", "end_rod", "flower_pot",
            "candle", "cake", "composter", "hanging_roots", "glow_lichen"
        )
        return nonCubes.none { n.contains(it) }
    }

    /**
     * Finds air reachable from outside the solid volume, then builds only faces
     * touching that reachable air. This intentionally does not expose enclosed
     * caves/interiors. Faces are greedily merged per orientation/plane/color.
     */
    private fun buildGreedySurfaceMesh(): List<MeshQuad> {
        if (blocks.isEmpty()) return emptyList()

        val minY = blocks.keys.minOf { it.y }
        val maxY = blocks.keys.maxOf { it.y }
        val h = maxY - minY + 3
        val w = 18
        val occupied = BooleanArray(w * h * w)

        fun ix(x: Int, y: Int, z: Int): Int = ((y - (minY - 1)) * w + (z + 1)) * w + (x + 1)
        fun inside(x: Int, y: Int, z: Int): Boolean = x in -1..16 && z in -1..16 && y in (minY - 1)..(maxY + 1)
        fun setOccupied(x: Int, y: Int, z: Int) { if (x in 0..15 && z in 0..15 && y in minY..maxY) occupied[ix(x, y, z)] = true }
        for ((p, name) in blocks) if (isOpaqueFullCube(name)) setOccupied(p.x, p.y, p.z)

        val reachable = BooleanArray(occupied.size)
        val q = ArrayDeque<Pos>()
        fun addAir(x: Int, y: Int, z: Int) {
            if (!inside(x, y, z)) return
            val i = ix(x, y, z)
            if (occupied[i] || reachable[i]) return
            reachable[i] = true
            q.addLast(Pos(x, y, z))
        }

        // Seed the entire outside boundary of the bounded volume.
        for (x in -1..16) for (z in -1..16) {
            addAir(x, minY - 1, z)
            addAir(x, maxY + 1, z)
        }
        for (y in minY..maxY) {
            for (x in -1..16) {
                addAir(x, y, -1)
                addAir(x, y, 16)
            }
            for (z in -1..16) {
                addAir(-1, y, z)
                addAir(16, y, z)
            }
        }

        val dirs = arrayOf(
            intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0),
            intArrayOf(0, 1, 0), intArrayOf(0, -1, 0),
            intArrayOf(0, 0, 1), intArrayOf(0, 0, -1)
        )
        while (q.isNotEmpty()) {
            val a = q.removeFirst()
            for (d in dirs) addAir(a.x + d[0], a.y + d[1], a.z + d[2])
        }

        // A group stores unit face cells as packed (u,v). Greedy rectangle merging
        // turns large flat walls into a tiny number of quads.
        val groups = HashMap<GroupKey, HashSet<Long>>()
        fun addCell(face: Int, plane: Int, u: Int, v: Int, color: Int) {
            val key = GroupKey(face, plane, color)
            groups.getOrPut(key) { HashSet() }.add(packCell(u, v))
        }

        for ((p, name) in blocks) {
            val color = BlockColors.colorForDimension(name, dimension)
            val opaque = isOpaqueFullCube(name)
            // +X, -X, +Y, -Y, +Z, -Z. Greedy merging is retained for opaque
            // cubes; transparent/special blocks never occlude neighboring faces.
            if (!opaque || airReachable(reachable, ix(p.x + 1, p.y, p.z), p.x + 1, p.y, p.z, minY, maxY)) addCell(0, p.x + 1, p.y, p.z, color)
            if (!opaque || airReachable(reachable, ix(p.x - 1, p.y, p.z), p.x - 1, p.y, p.z, minY, maxY)) addCell(1, p.x, p.y, p.z, color)
            if (!opaque || airReachable(reachable, ix(p.x, p.y + 1, p.z), p.x, p.y + 1, p.z, minY, maxY)) addCell(2, p.y + 1, p.x, p.z, color)
            if (!opaque || airReachable(reachable, ix(p.x, p.y - 1, p.z), p.x, p.y - 1, p.z, minY, maxY)) addCell(3, p.y, p.x, p.z, color)
            if (!opaque || airReachable(reachable, ix(p.x, p.y, p.z + 1), p.x, p.y, p.z + 1, minY, maxY)) addCell(4, p.z + 1, p.x, p.y, color)
            if (!opaque || airReachable(reachable, ix(p.x, p.y, p.z - 1), p.x, p.y, p.z - 1, minY, maxY)) addCell(5, p.z, p.x, p.y, color)
        }

        val result = ArrayList<MeshQuad>()
        for ((key, cells) in groups) {
            while (cells.isNotEmpty()) {
                val first = cells.first()
                val u0 = unpackU(first)
                val v0 = unpackV(first)
                var u1 = u0
                while (cells.contains(packCell(u1 + 1, v0))) u1++
                var v1 = v0
                var canGrow = true
                while (canGrow) {
                    val nextV = v1 + 1
                    for (u in u0..u1) {
                        if (!cells.contains(packCell(u, nextV))) { canGrow = false; break }
                    }
                    if (canGrow) v1 = nextV
                }
                for (v in v0..v1) for (u in u0..u1) cells.remove(packCell(u, v))
                result.add(makeQuad(key.face, key.plane, u0, v0, u1 + 1, v1 + 1, key.color, minY))
            }
        }
        return result
    }

    private fun airReachable(reachable: BooleanArray, index: Int, x: Int, y: Int, z: Int, minY: Int, maxY: Int): Boolean {
        if (x !in -1..16 || z !in -1..16 || y < minY - 1 || y > maxY + 1) return true
        return reachable[index]
    }

    private fun packCell(u: Int, v: Int): Long = (u.toLong() shl 32) xor (v.toLong() and 0xffffffffL)
    private fun unpackU(v: Long): Int = (v shr 32).toInt()
    private fun unpackV(v: Long): Int = v.toInt()

    private fun makeQuad(face: Int, plane: Int, u0: Int, v0: Int, u1: Int, v1: Int, color: Int, minY: Int): MeshQuad {
        val a: V3
        val b: V3
        val c: V3
        val d: V3
        when (face) {
            0 -> { // +X, yz plane
                a = V3(plane.toFloat(), u0.toFloat(), v0.toFloat()); b = V3(plane.toFloat(), u1.toFloat(), v0.toFloat()); c = V3(plane.toFloat(), u1.toFloat(), v1.toFloat()); d = V3(plane.toFloat(), u0.toFloat(), v1.toFloat())
            }
            1 -> { // -X
                a = V3(plane.toFloat(), u0.toFloat(), v0.toFloat()); b = V3(plane.toFloat(), u0.toFloat(), v1.toFloat()); c = V3(plane.toFloat(), u1.toFloat(), v1.toFloat()); d = V3(plane.toFloat(), u1.toFloat(), v0.toFloat())
            }
            2 -> { // +Y, xz plane
                a = V3(u0.toFloat(), plane.toFloat(), v0.toFloat()); b = V3(u0.toFloat(), plane.toFloat(), v1.toFloat()); c = V3(u1.toFloat(), plane.toFloat(), v1.toFloat()); d = V3(u1.toFloat(), plane.toFloat(), v0.toFloat())
            }
            3 -> { // -Y
                a = V3(u0.toFloat(), plane.toFloat(), v0.toFloat()); b = V3(u1.toFloat(), plane.toFloat(), v0.toFloat()); c = V3(u1.toFloat(), plane.toFloat(), v1.toFloat()); d = V3(u0.toFloat(), plane.toFloat(), v1.toFloat())
            }
            4 -> { // +Z, xy plane
                a = V3(u0.toFloat(), v0.toFloat(), plane.toFloat()); b = V3(u1.toFloat(), v0.toFloat(), plane.toFloat()); c = V3(u1.toFloat(), v1.toFloat(), plane.toFloat()); d = V3(u0.toFloat(), v1.toFloat(), plane.toFloat())
            }
            else -> { // -Z
                a = V3(u0.toFloat(), v0.toFloat(), plane.toFloat()); b = V3(u0.toFloat(), v1.toFloat(), plane.toFloat()); c = V3(u1.toFloat(), v1.toFloat(), plane.toFloat()); d = V3(u1.toFloat(), v0.toFloat(), plane.toFloat())
            }
        }
        return MeshQuad(a, b, c, d, color, 0f)
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
            openButton = Button(this).apply {
                text = "Open Selected Area"
                isEnabled = selectedX in 0..15 && selectedZ in 0..15
                setOnClickListener { openSelected() }
            }
            controls.addView(openButton)
        }
        val hide = CheckBox(this).apply {
            text = "Hide unselected areas"
            isEnabled = selectedX in 0..15 && selectedZ in 0..15
            isChecked = hideUnselected
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
        // Close a viewer-owned LevelDB handle before opening the block editor.
        // Otherwise the editor can collide with the still-held file lock.
        if (ownsBridge) {
            try { bridge?.close() } catch (_: Exception) {}
            bridge = null
            ownsBridge = false
        }
        val i = Intent(this, ChunkBlockEditorActivity::class.java)
        i.putExtra(ChunkBlockEditorActivity.EXTRA_WORLD_PATH, worldDir.absolutePath)
        i.putExtra(ChunkBlockEditorActivity.EXTRA_CHUNK_X, chunkX)
        i.putExtra(ChunkBlockEditorActivity.EXTRA_CHUNK_Z, chunkZ)
        i.putExtra(ChunkBlockEditorActivity.EXTRA_DIMENSION, dimension)
        // Pass the exact selected BlockChunk so the editor opens it immediately.
        i.putExtra(ChunkBlockEditorActivity.EXTRA_SELECTED_X, selectedX)
        i.putExtra(ChunkBlockEditorActivity.EXTRA_SELECTED_Z, selectedZ)
        startActivity(i)
        finish()
    }

    private inner class Chunk3DView : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.rgb(255, 196, 0); isAntiAlias = true
        }
        var hideUnselected = false
        private var rotX = 0.65f
        private var rotY = -0.7f
        private var zoom = 1f
        private var panX = 0f
        private var panY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var lastMidX = 0f
        private var lastMidY = 0f
        private var lastDist = 0f
        private var activePointerId = MotionEvent.INVALID_POINTER_ID
        private var mode = 0
        private var moved = false

        init { setBackgroundColor(Color.rgb(18, 18, 18)); isFocusable = true }

        fun resetView() { rotX = 0.65f; rotY = -0.7f; zoom = 1f; panX = 0f; panY = 0f; invalidate() }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = e.getPointerId(0)
                    lastX = e.x
                    lastY = e.y
                    moved = false
                    mode = 1
                    return true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (e.pointerCount >= 2) {
                        lastDist = distance(e)
                        lastMidX = (e.getX(0) + e.getX(1)) * 0.5f
                        lastMidY = (e.getY(0) + e.getY(1)) * 0.5f
                        mode = 2
                        moved = true
                    }
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (mode == 2 && e.pointerCount >= 2) {
                        val d = distance(e)
                        if (lastDist > 0f && d > 0f) {
                            zoom = (zoom * (d / lastDist)).coerceIn(0.25f, 6f)
                        }

                        // Two-finger movement pans the model instead of making it jump.
                        val midX = (e.getX(0) + e.getX(1)) * 0.5f
                        val midY = (e.getY(0) + e.getY(1)) * 0.5f
                        panX += midX - lastMidX
                        panY += midY - lastMidY
                        lastMidX = midX
                        lastMidY = midY
                        lastDist = d
                        invalidate()
                        return true
                    }

                    if (mode == 1 && activePointerId != MotionEvent.INVALID_POINTER_ID) {
                        val index = e.findPointerIndex(activePointerId)
                        if (index >= 0) {
                            val x = e.getX(index)
                            val y = e.getY(index)
                            val dx = x - lastX
                            val dy = y - lastY
                            if (abs(dx) > 2f || abs(dy) > 2f) moved = true
                            rotY -= dx * 0.009f
                            rotX = (rotX + dy * 0.006f).coerceIn(-1.45f, 1.45f)
                            lastX = x
                            lastY = y
                            invalidate()
                        }
                        return true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (e.actionMasked == MotionEvent.ACTION_UP &&
                        mode == 1 && selectionEnabled && !moved) {
                        selectAt(e.x, e.y)
                    }
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    mode = 0
                    lastDist = 0f
                    return true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    val liftedId = e.getPointerId(e.actionIndex)
                    if (liftedId == activePointerId) {
                        val remaining = if (e.actionIndex == 0) 1 else 0
                        if (remaining < e.pointerCount) {
                            activePointerId = e.getPointerId(remaining)
                            lastX = e.getX(remaining)
                            lastY = e.getY(remaining)
                            mode = 1
                        }
                    } else if (mode == 2 && e.pointerCount - 1 >= 1) {
                        // Continue smoothly with the pointer that remains.
                        val remaining = if (e.actionIndex == 0) 1 else 0
                        activePointerId = e.getPointerId(remaining)
                        lastX = e.getX(remaining)
                        lastY = e.getY(remaining)
                        mode = 1
                    }
                    lastDist = 0f
                    return true
                }
            }
            return true
        }

        private fun distance(e: MotionEvent): Float {
            if (e.pointerCount < 2) return 0f
            val dx = e.getX(0) - e.getX(1); val dy = e.getY(0) - e.getY(1)
            return hypot(dx.toDouble(), dy.toDouble()).toFloat()
        }

        private fun project(v: V3): Triple<Float, Float, Float> {
            val cy = cos(rotY); val sy = sin(rotY); val cx = cos(rotX); val sx = sin(rotX)
            val rx = v.x * cy - v.z * sy
            val rz = v.x * sy + v.z * cy
            val ry = v.y * cx - rz * sx
            val rz2 = v.y * sx + rz * cx
            val scale = min(width, height) * 0.055f * zoom
            return Triple(width / 2f + rx * scale + panX, height / 2f - ry * scale + panY, rz2)
        }

        private fun selectAt(px: Float, py: Float) {
            var bestX = -1; var bestZ = -1; var bestD = Float.MAX_VALUE
            for (z in 0..15) for (x in 0..15) {
                val top = blocks.keys.asSequence().filter { it.x == x && it.z == z }.maxOfOrNull { it.y } ?: continue
                val p = project(V3(x + 0.5f, top + 1f, z + 0.5f))
                val d = hypot(px - p.first, py - p.second)
                if (d < bestD) { bestD = d; bestX = x; bestZ = z }
            }
            if (bestX in 0..15 && bestZ in 0..15 && bestD < max(55f, min(width, height) * 0.10f)) {
                selectedX = bestX; selectedZ = bestZ
                updateInfoText(); openButton?.isEnabled = true; invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (blocks.isEmpty()) {
                paint.color = Color.LTGRAY; paint.textSize = 18f; canvas.drawText("No non-air blocks in this chunk", 20f, 40f, paint); return
            }
            if (hideUnselected && selectedX in 0..15 && selectedZ in 0..15) {
                drawSelectedColumn(canvas)
            } else {
                drawMesh(canvas)
            }
            if (selectedX in 0..15 && selectedZ in 0..15) drawSelection(canvas)
        }

        private fun drawMesh(canvas: Canvas) {
            val projected = ArrayList<Pair<MeshQuad, Float>>(mesh.size)
            for (q in mesh) {
                val a = project(q.a); val b = project(q.b); val c = project(q.c); val d = project(q.d)
                val depth = (a.third + b.third + c.third + d.third) * 0.25f
                projected.add(q to depth)
            }
            projected.sortBy { it.second }
            for ((q, _) in projected) {
                val a = project(q.a); val b = project(q.b); val c = project(q.c); val d = project(q.d)
                paint.style = Paint.Style.FILL
                paint.color = q.color
                val path = Path()
                path.moveTo(a.first, a.second); path.lineTo(b.first, b.second); path.lineTo(c.first, c.second); path.lineTo(d.first, d.second); path.close()
                canvas.drawPath(path, paint)
            }
        }

        private fun drawSelectedColumn(canvas: Canvas) {
            val visible = blocks.keys.filter { it.x == selectedX && it.z == selectedZ }
            val projected = ArrayList<Pair<Pos, Float>>(visible.size)
            for (p in visible) {
                val a = project(V3(p.x.toFloat(), p.y.toFloat(), p.z.toFloat()))
                projected.add(p to a.third)
            }
            projected.sortBy { it.second }
            for ((p, _) in projected) drawColumnBlock(canvas, p)
        }

        private fun drawColumnBlock(canvas: Canvas, p: Pos) {
            val x = p.x.toFloat(); val y = p.y.toFloat(); val z = p.z.toFloat()
            val pts = arrayOf(
                project(V3(x, y, z)), project(V3(x + 1f, y, z)), project(V3(x + 1f, y + 1f, z)), project(V3(x, y + 1f, z)),
                project(V3(x, y, z + 1f)), project(V3(x + 1f, y, z + 1f)), project(V3(x + 1f, y + 1f, z + 1f)), project(V3(x, y + 1f, z + 1f))
            )
            val base = BlockColors.colorForDimension(blocks[p] ?: "minecraft:stone", dimension)
            paint.style = Paint.Style.FILL
            paint.color = shade(base, 1.12f); polygon(canvas, pts, intArrayOf(3, 2, 6, 7))
            paint.color = shade(base, 0.88f); polygon(canvas, pts, intArrayOf(1, 5, 6, 2))
            paint.color = shade(base, 0.98f); polygon(canvas, pts, intArrayOf(0, 1, 2, 3))
        }

        private fun drawSelection(canvas: Canvas) {
            if (selectedX !in 0..15 || selectedZ !in 0..15) return
            val ys = blocks.keys.filter { it.x == selectedX && it.z == selectedZ }.map { it.y }
            if (ys.isEmpty()) return
            val minY = ys.minOrNull() ?: return; val maxY = ys.maxOrNull() ?: return
            val x = selectedX.toFloat(); val z = selectedZ.toFloat()
            val pts = arrayOf(
                project(V3(x, minY.toFloat(), z)), project(V3(x + 1f, minY.toFloat(), z)), project(V3(x + 1f, maxY + 1f, z)), project(V3(x, maxY + 1f, z)),
                project(V3(x, minY.toFloat(), z + 1f)), project(V3(x + 1f, minY.toFloat(), z + 1f)), project(V3(x + 1f, maxY + 1f, z + 1f)), project(V3(x, maxY + 1f, z + 1f))
            )
            val edges = arrayOf(intArrayOf(0,1,2,3,0), intArrayOf(4,5,6,7,4), intArrayOf(0,4), intArrayOf(1,5), intArrayOf(2,6), intArrayOf(3,7))
            for (edge in edges) {
                val path = Path(); path.moveTo(pts[edge[0]].first, pts[edge[0]].second)
                for (i in 1 until edge.size) path.lineTo(pts[edge[i]].first, pts[edge[i]].second)
                canvas.drawPath(path, outline)
            }
        }

        private fun polygon(canvas: Canvas, pts: Array<Triple<Float, Float, Float>>, idx: IntArray) {
            val path = Path(); path.moveTo(pts[idx[0]].first, pts[idx[0]].second)
            for (i in 1 until idx.size) path.lineTo(pts[idx[i]].first, pts[idx[i]].second)
            path.close(); canvas.drawPath(path, paint)
        }

        private fun shade(c: Int, factor: Float): Int = Color.rgb(
            (Color.red(c) * factor).roundToInt().coerceIn(0, 255),
            (Color.green(c) * factor).roundToInt().coerceIn(0, 255),
            (Color.blue(c) * factor).roundToInt().coerceIn(0, 255)
        )
    }

    override fun onDestroy() {
        if (ownsBridge) try { bridge?.close() } catch (_: Exception) {}
        bridge = null
        super.onDestroy()
    }

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
