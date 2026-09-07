package com.spaimic.nbttool.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.spaimic.nbttool.chunk.ColumnInfo
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Build 210 placement preview. Terrain is represented by surface columns from
 * the target chunk area. The MCStructure itself is not written to the world;
 * its occupied bounding box is rendered with a gold wireframe and follows the
 * placement origin immediately when the coordinate buttons are pressed.
 */
class McStructurePlacementPreviewView(context: Context) : View(context) {
    private data class P(val x: Float, val y: Float, val z: Float)
    private data class Q(val points: List<P>, val depth: Float, val color: Int, val stroke: Boolean = false)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.rgb(255, 196, 0)
        isAntiAlias = true
    }

    private var structure: McStructureAnalysis? = null
    private var columns: Map<Pair<Int, Int>, Array<Array<ColumnInfo>>> = emptyMap()
    private var originX = 0
    private var originY = 0
    private var originZ = 0
    private var dimension = 0
    private var yaw = -0.72f
    private var pitch = 0.68f
    private var zoom = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var lastDistance = 0f
    private var rotating = false

    fun setStructure(value: McStructureAnalysis) { structure = value; invalidate() }
    fun setColumns(value: Map<Pair<Int, Int>, Array<Array<ColumnInfo>>>) { columns = value; invalidate() }
    fun setPlacement(x: Int, y: Int, z: Int, dim: Int) {
        originX = x; originY = y; originZ = z; dimension = dim; invalidate()
    }
    fun resetCamera() { yaw = -0.72f; pitch = 0.68f; zoom = 1f; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        val a = structure ?: return
        if (width <= 0 || height <= 0) return

        val worldMinX = columns.keys.minOfOrNull { it.first * 16 } ?: originX
        val worldMaxX = columns.keys.maxOfOrNull { it.first * 16 + 15 } ?: (originX + a.sizeX)
        val worldMinZ = columns.keys.minOfOrNull { it.second * 16 } ?: originZ
        val worldMaxZ = columns.keys.maxOfOrNull { it.second * 16 + 15 } ?: (originZ + a.sizeZ)

        var terrainMinY = originY
        var terrainMaxY = originY
        for (grid in columns.values) {
            for (x in 0..15) for (z in 0..15) {
                terrainMinY = min(terrainMinY, grid[x][z].surfaceY)
                terrainMaxY = max(terrainMaxY, grid[x][z].surfaceY)
            }
        }
        val minY = min(originY - 8, terrainMinY)
        val maxY = max(originY + a.sizeY + 8, terrainMaxY)
        val centerX = (worldMinX + worldMaxX) * 0.5f
        val centerZ = (worldMinZ + worldMaxZ) * 0.5f
        val centerY = (minY + maxY) * 0.5f
        val span = max(16, max(worldMaxX - worldMinX + 1, max(worldMaxZ - worldMinZ + 1, maxY - minY + 1)))
        val scale = min(width, height).toFloat() / (span * 1.85f + 12f) * zoom.coerceIn(0.35f, 3.5f)

        val quads = ArrayList<Q>()
        for ((chunk, grid) in columns) {
            val baseX = chunk.first * 16
            val baseZ = chunk.second * 16
            for (x in 0..15) for (z in 0..15) {
                val info = grid[x][z]
                val wx = baseX + x
                val wz = baseZ + z
                val sy = info.surfaceY
                val p0 = project(wx.toFloat(), sy.toFloat(), wz.toFloat(), centerX, centerY, centerZ, scale)
                val p1 = project((wx + 1).toFloat(), sy.toFloat(), wz.toFloat(), centerX, centerY, centerZ, scale)
                val p2 = project((wx + 1).toFloat(), sy.toFloat(), (wz + 1).toFloat(), centerX, centerY, centerZ, scale)
                val p3 = project(wx.toFloat(), sy.toFloat(), (wz + 1).toFloat(), centerX, centerY, centerZ, scale)
                val avgDepth = (p0.z + p1.z + p2.z + p3.z) * .25f
                quads.add(Q(listOf(p0, p1, p2, p3), avgDepth, info.color))

                // A short side skirt makes the surface read as actual terrain
                // instead of a flat top-down patch while keeping rendering light.
                val skirt = min(8, max(1, sy - (sy - 8)))
                val b0 = project(wx.toFloat(), (sy - skirt).toFloat(), wz.toFloat(), centerX, centerY, centerZ, scale)
                val b1 = project((wx + 1).toFloat(), (sy - skirt).toFloat(), wz.toFloat(), centerX, centerY, centerZ, scale)
                val b3 = project(wx.toFloat(), (sy - skirt).toFloat(), (wz + 1).toFloat(), centerX, centerY, centerZ, scale)
                quads.add(Q(listOf(p0, p1, b1, b0), avgDepth + .01f, darken(info.color, .72f)))
                quads.add(Q(listOf(p3, p0, b0, b3), avgDepth + .015f, darken(info.color, .60f)))
            }
        }
        quads.sortBy { it.depth }
        for (q in quads) drawQuad(canvas, q)

        drawChunkGrid(canvas, worldMinX, worldMaxX, worldMinZ, worldMaxZ, centerX, centerY, centerZ, scale)
        drawStructureBox(canvas, a, centerX, centerY, centerZ, scale)

        fill.style = Paint.Style.FILL
        fill.color = Color.BLACK
        fill.textSize = 13f
        canvas.drawText("Placement: ($originX, $originY, $originZ)  •  Drag = rotate  •  2 fingers = zoom", 12f, 22f, fill)
    }

    private fun drawChunkGrid(canvas: Canvas, minX: Int, maxX: Int, minZ: Int, maxZ: Int, cx: Float, cy: Float, cz: Float, scale: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f; color = Color.argb(120, 80, 80, 80) }
        var x = Math.floorDiv(minX, 16) * 16
        while (x <= maxX + 1) {
            val a = project(x.toFloat(), cy, minZ.toFloat(), cx, cy, cz, scale)
            val b = project(x.toFloat(), cy, (maxZ + 1).toFloat(), cx, cy, cz, scale)
            canvas.drawLine(a.x, a.y, b.x, b.y, p)
            x += 16
        }
        var z = Math.floorDiv(minZ, 16) * 16
        while (z <= maxZ + 1) {
            val a = project(minX.toFloat(), cy, z.toFloat(), cx, cy, cz, scale)
            val b = project((maxX + 1).toFloat(), cy, z.toFloat(), cx, cy, cz, scale)
            canvas.drawLine(a.x, a.y, b.x, b.y, p)
            z += 16
        }
    }

    private fun drawStructureBox(canvas: Canvas, a: McStructureAnalysis, cx: Float, cy: Float, cz: Float, scale: Float) {
        val x0 = originX.toFloat(); val y0 = originY.toFloat(); val z0 = originZ.toFloat()
        val x1 = originX + a.sizeX.toFloat(); val y1 = originY + a.sizeY.toFloat(); val z1 = originZ + a.sizeZ.toFloat()
        val v = arrayOf(
            project(x0,y0,z0,cx,cy,cz,scale), project(x1,y0,z0,cx,cy,cz,scale),
            project(x1,y0,z1,cx,cy,cz,scale), project(x0,y0,z1,cx,cy,cz,scale),
            project(x0,y1,z0,cx,cy,cz,scale), project(x1,y1,z0,cx,cy,cz,scale),
            project(x1,y1,z1,cx,cy,cz,scale), project(x0,y1,z1,cx,cy,cz,scale)
        )
        val edges = arrayOf(
            intArrayOf(0,1), intArrayOf(1,2), intArrayOf(2,3), intArrayOf(3,0),
            intArrayOf(4,5), intArrayOf(5,6), intArrayOf(6,7), intArrayOf(7,4),
            intArrayOf(0,4), intArrayOf(1,5), intArrayOf(2,6), intArrayOf(3,7)
        )
        outline.color = Color.rgb(255, 196, 0)
        outline.strokeWidth = 3.5f
        for (e in edges) canvas.drawLine(v[e[0]].x, v[e[0]].y, v[e[1]].x, v[e[1]].y, outline)
    }

    private fun drawQuad(canvas: Canvas, q: Q) {
        val path = Path().apply {
            moveTo(q.points[0].x, q.points[0].y)
            for (i in 1 until q.points.size) lineTo(q.points[i].x, q.points[i].y)
            close()
        }
        fill.style = Paint.Style.FILL
        fill.color = if (Color.alpha(q.color) == 0) Color.rgb(205,205,205) else q.color
        canvas.drawPath(path, fill)
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = .6f
        fill.color = Color.argb(45, 0, 0, 0)
        canvas.drawPath(path, fill)
    }

    private fun project(x: Float, y: Float, z: Float, cx: Float, cy: Float, cz: Float, scale: Float): P {
        var px = x - cx; var py = y - cy; var pz = z - cz
        val c = cos(yaw); val s = sin(yaw)
        val rx = px * c - pz * s
        val rz = px * s + pz * c
        px = rx; pz = rz
        val cp = cos(pitch); val sp = sin(pitch)
        val ry = py * cp - pz * sp
        val rz2 = py * sp + pz * cp
        return P(width / 2f + px * scale, height / 2f - ry * scale, rz2)
    }

    private fun darken(c: Int, factor: Float): Int = Color.rgb(
        (Color.red(c) * factor).toInt().coerceIn(0,255),
        (Color.green(c) * factor).toInt().coerceIn(0,255),
        (Color.blue(c) * factor).toInt().coerceIn(0,255)
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; rotating = true; return true }
            MotionEvent.ACTION_POINTER_DOWN -> { lastDistance = distance(event); rotating = false; return true }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val d = distance(event)
                    if (lastDistance > 0f) zoom = (zoom * d / lastDistance).coerceIn(.35f, 3.5f)
                    lastDistance = d
                } else if (rotating) {
                    val dx = event.x - lastX; val dy = event.y - lastY
                    yaw += dx * .012f
                    pitch = (pitch + dy * .012f).coerceIn(-1.25f, 1.25f)
                    lastX = event.x; lastY = event.y
                }
                invalidate(); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { rotating = false; return true }
        }
        return true
    }

    private fun distance(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(0) - e.getX(1); val dy = e.getY(0) - e.getY(1)
        return sqrt(dx*dx + dy*dy)
    }
}
