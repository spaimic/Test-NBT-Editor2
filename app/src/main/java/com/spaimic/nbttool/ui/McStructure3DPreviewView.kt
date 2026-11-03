package com.spaimic.nbttool.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Interactive software-rendered 3D MCStructure preview. Read-only. */
class McStructure3DPreviewView(context: Context) : View(context) {
    private data class P(val x: Float, val y: Float, val z: Float)
    private data class Face(val points: List<P>, val depth: Float, val colorFactor: Float)
    private data class FaceGroupKey(val face: Int, val plane: Int, val color: Int)
    private data class ColoredFace(val face: Face, val color: Int)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var analysis: McStructureAnalysis? = null
    private var layerOnly = false
    private var selectedLayer = 0
    private var yaw = Math.toRadians(35.0).toFloat()
    private var pitch = Math.toRadians(28.0).toFloat()
    private var zoom = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var lastDistance = 0f
    private var rotating = false
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    var onBlockSelected: ((McStructureBlock) -> Unit)? = null

    fun setAnalysis(value: McStructureAnalysis) { analysis = value; invalidate() }
    fun setLayer(layer: Int) { selectedLayer = layer; layerOnly = true; invalidate() }
    fun showAllLayers() { layerOnly = false; invalidate() }
    fun resetCamera() {
        yaw = Math.toRadians(35.0).toFloat()
        pitch = Math.toRadians(28.0).toFloat()
        zoom = 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        val a = analysis ?: return
        val blocks = a.blocks.filter { !layerOnly || it.y == selectedLayer }
        if (blocks.isEmpty()) return

        val maxDim = max(1, max(a.sizeX, max(a.sizeY, a.sizeZ)))
        val baseScale = min(width, height).toFloat() / (maxDim * 2.25f + 4f)
        val scale = baseScale * zoom.coerceIn(0.35f, 4f)
        val cx = (a.sizeX - 1) / 2f
        val cy = (a.sizeY - 1) / 2f
        val cz = (a.sizeZ - 1) / 2f

        // Build a compact occupancy lookup once. Opaque full cubes hide the
        // shared faces between adjacent blocks, so those faces are never
        // transformed, sorted, or submitted to Canvas.
        val mergedFaces = buildMergedFaces(blocks, cx, cy, cz, scale)
        mergedFaces.sortBy { it.face.depth }
        for (entry in mergedFaces) drawFace(canvas, entry.face, entry.color)
    }

    /** Greedily merge coplanar exposed block faces with the same color. */
    private fun buildMergedFaces(
        blocks: List<McStructureBlock>, cx: Float, cy: Float, cz: Float, scale: Float
    ): ArrayList<ColoredFace> {
        val occupied = HashSet<Long>(blocks.size * 2)
        for (b in blocks) if (isOpaqueFullCube(b.name)) occupied.add(packPos(b.x, b.y, b.z))
        val groups = HashMap<FaceGroupKey, HashSet<Long>>()
        val neighbors = arrayOf(
            intArrayOf(0, 1, 0), intArrayOf(-1, 0, 0), intArrayOf(1, 0, 0),
            intArrayOf(0, 0, -1), intArrayOf(0, 0, 1), intArrayOf(0, -1, 0)
        )
        for (b in blocks) {
            val color = McStructureDecoder.blockColor(b.name)
            for (face in 0..5) {
                val d = neighbors[face]
                if (occupied.contains(packPos(b.x + d[0], b.y + d[1], b.z + d[2]))) continue
                val plane: Int
                val u: Int
                val v: Int
                when (face) {
                    0 -> { plane = b.y; u = b.x; v = b.z }
                    1 -> { plane = b.x; u = b.y; v = b.z }
                    2 -> { plane = b.x; u = b.y; v = b.z }
                    3 -> { plane = b.z; u = b.x; v = b.y }
                    4 -> { plane = b.z; u = b.x; v = b.y }
                    else -> { plane = b.y; u = b.x; v = b.z }
                }
                groups.getOrPut(FaceGroupKey(face, plane, color)) { HashSet() }.add(packCell(u, v))
            }
        }

        val result = ArrayList<ColoredFace>()
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
                    for (u in u0..u1) if (!cells.contains(packCell(u, nextV))) {
                        canGrow = false
                        break
                    }
                    if (canGrow) v1 = nextV
                }
                for (v in v0..v1) for (u in u0..u1) cells.remove(packCell(u, v))
                val face = makeMergedFace(key.face, key.plane, u0, v0, u1, v1, cx, cy, cz, scale)
                if (face != null) result.add(ColoredFace(face, key.color))
            }
        }
        return result
    }

    private fun packCell(u: Int, v: Int): Long =
        (u.toLong() shl 32) xor (v.toLong() and 0xffffffffL)
    private fun unpackU(value: Long): Int = (value shr 32).toInt()
    private fun unpackV(value: Long): Int = value.toInt()

    private fun makeMergedFace(
        face: Int, plane: Int, u0: Int, v0: Int, u1: Int, v1: Int,
        cx: Float, cy: Float, cz: Float, scale: Float
    ): Face? {
        val x0: Float; val x1: Float; val y0: Float; val y1: Float; val z0: Float; val z1: Float
        when (face) {
            0, 5 -> {
                x0 = u0 - .5f; x1 = u1 + .5f
                z0 = v0 - .5f; z1 = v1 + .5f
                y0 = if (face == 0) plane + .5f else plane - .5f
                y1 = y0
            }
            1, 2 -> {
                y0 = u0 - .5f; y1 = u1 + .5f
                z0 = v0 - .5f; z1 = v1 + .5f
                x0 = if (face == 1) plane - .5f else plane + .5f
                x1 = x0
            }
            else -> {
                x0 = u0 - .5f; x1 = u1 + .5f
                y0 = v0 - .5f; y1 = v1 + .5f
                z0 = if (face == 3) plane - .5f else plane + .5f
                z1 = z0
            }
        }
        val coords = when (face) {
            0 -> arrayOf(floatArrayOf(x0,y0,z0), floatArrayOf(x1,y0,z0), floatArrayOf(x1,y0,z1), floatArrayOf(x0,y0,z1))
            1 -> arrayOf(floatArrayOf(x0,y0,z0), floatArrayOf(x0,y0,z1), floatArrayOf(x0,y1,z1), floatArrayOf(x0,y1,z0))
            2 -> arrayOf(floatArrayOf(x0,y0,z0), floatArrayOf(x0,y1,z0), floatArrayOf(x0,y1,z1), floatArrayOf(x0,y0,z1))
            3 -> arrayOf(floatArrayOf(x0,y0,z0), floatArrayOf(x1,y0,z0), floatArrayOf(x1,y1,z0), floatArrayOf(x0,y1,z0))
            4 -> arrayOf(floatArrayOf(x0,y0,z0), floatArrayOf(x1,y0,z0), floatArrayOf(x1,y1,z0), floatArrayOf(x0,y1,z0))
            else -> arrayOf(floatArrayOf(x0,y0,z0), floatArrayOf(x1,y0,z0), floatArrayOf(x1,y0,z1), floatArrayOf(x0,y0,z1))
        }
        val points = coords.map { transform(it[0], it[1], it[2], cx, cy, cz, scale) }
        val cross = (points[1].x-points[0].x)*(points[2].y-points[0].y) -
            (points[1].y-points[0].y)*(points[2].x-points[0].x)
        if (cross < 0f) return null
        val depth = points.map { it.z }.average().toFloat()
        val factor = when (face) { 0 -> .92f; 1 -> .82f; 2 -> .78f; 3 -> .82f; 4 -> .68f; else -> .55f }
        return Face(points, depth, factor)
    }

    private fun transform(x: Float, y: Float, z: Float, cx: Float, cy: Float, cz: Float, scale: Float): P {
        var px = x - cx
        var py = y - cy
        var pz = z - cz
        val cyaw = cos(yaw); val syaw = sin(yaw)
        val rx = px * cyaw - pz * syaw
        val rz = px * syaw + pz * cyaw
        px = rx; pz = rz
        val cp = cos(pitch); val sp = sin(pitch)
        val ry = py * cp - pz * sp
        val rz2 = py * sp + pz * cp
        return P(width / 2f + px * scale, height / 2f - ry * scale, rz2)
    }

    private fun packPos(x: Int, y: Int, z: Int): Long =
        (x.toLong() shl 42) xor ((y.toLong() and 0x1fffffL) shl 21) xor (z.toLong() and 0x1fffffL)

    private fun isOpaqueFullCube(name: String): Boolean {
        val n = name.lowercase()
        if (n.contains("glass") || n.contains("leaves") || n.contains("water") || n.contains("ice") ||
            n.contains("plant") || n.contains("flower") || n.contains("sapling") || n.contains("torch") ||
            n.contains("pane") || n.contains("fence") || n.contains("wall") || n.contains("stairs") ||
            n.contains("slab") || n.contains("door") || n.contains("trapdoor") || n.contains("carpet") ||
            n.contains("rail") || n.contains("ladder") || n.contains("vine") || n.contains("chain") ||
            n.contains("bar") || n.contains("button") || n.contains("pressure_plate") || n.contains("sign") ||
            n.contains("skull") || n.contains("head") || n.contains("banner") || n.contains("bed") ||
            n.contains("chest") || n.contains("hopper") || n.contains("portal") || n.contains("fire") ||
            n.contains("coral") || n.contains("kelp") || n.contains("seagrass") || n.contains("bamboo") ||
            n.contains("cactus") || n.contains("sugar_cane") || n.contains("mushroom") || n.contains("web")) return false
        return true
    }

    private fun makeFaces(
        b: McStructureBlock, cx: Float, cy: Float, cz: Float, scale: Float,
        opaqueOccupied: Set<Long>
    ): List<Face> {
        val x=b.x.toFloat(); val y=b.y.toFloat(); val z=b.z.toFloat()
        val v = arrayOf(
            transform(x-.5f,y-.5f,z-.5f,cx,cy,cz,scale), transform(x+.5f,y-.5f,z-.5f,cx,cy,cz,scale),
            transform(x+.5f,y-.5f,z+.5f,cx,cy,cz,scale), transform(x-.5f,y-.5f,z+.5f,cx,cy,cz,scale),
            transform(x-.5f,y+.5f,z-.5f,cx,cy,cz,scale), transform(x+.5f,y+.5f,z-.5f,cx,cy,cz,scale),
            transform(x+.5f,y+.5f,z+.5f,cx,cy,cz,scale), transform(x-.5f,y+.5f,z+.5f,cx,cy,cz,scale)
        )
        val defs = listOf(
            intArrayOf(4,5,6,7, 1, 1), // top
            intArrayOf(0,3,7,4, 2, 0), // west
            intArrayOf(1,5,6,2, 3, 2), // east
            intArrayOf(0,1,5,4, 4, 0), // north
            intArrayOf(3,2,6,7, 5, 3), // south
            intArrayOf(0,1,2,3, 6, 0)  // bottom
        )
        val result = ArrayList<Face>(3)
        val neighbors = arrayOf(
            intArrayOf(0, 1, 0), intArrayOf(-1, 0, 0), intArrayOf(1, 0, 0),
            intArrayOf(0, 0, -1), intArrayOf(0, 0, 1), intArrayOf(0, -1, 0)
        )
        for (faceIndex in defs.indices) {
            val d = defs[faceIndex]
            val neighbor = neighbors[faceIndex]
            if (opaqueOccupied.contains(packPos(b.x + neighbor[0], b.y + neighbor[1], b.z + neighbor[2]))) continue
            val p = listOf(v[d[0]],v[d[1]],v[d[2]],v[d[3]])
            // Screen-space winding tells us which side is facing the viewer.
            val cross = (p[1].x-p[0].x)*(p[2].y-p[0].y) - (p[1].y-p[0].y)*(p[2].x-p[0].x)
            if (cross >= 0f) {
                val depth = p.map { it.z }.average().toFloat()
                result.add(Face(p, depth, when(d[5]) { 1 -> .92f; 2 -> .78f; 3 -> .68f; 0 -> .82f else -> .55f }))
            }
        }
        return result
    }

    private fun drawFace(canvas: Canvas, f: Face, color: Int) {
        val path=Path().apply { moveTo(f.points[0].x,f.points[0].y); for(i in 1 until f.points.size) lineTo(f.points[i].x,f.points[i].y); close() }
        val c = if (Color.alpha(color)==0) Color.argb(100,220,220,220) else color
        paint.style=Paint.Style.FILL; paint.color=shade(c,f.colorFactor); canvas.drawPath(path,paint)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=1f; paint.color=Color.argb(65,0,0,0); canvas.drawPath(path,paint)
    }

    private fun shade(c:Int,f:Float)=Color.rgb((Color.red(c)*f).toInt().coerceIn(0,255),(Color.green(c)*f).toInt().coerceIn(0,255),(Color.blue(c)*f).toInt().coerceIn(0,255))

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX=event.x; downY=event.y; lastX=event.x; lastY=event.y; rotating=true; moved=false; return true }
            MotionEvent.ACTION_POINTER_DOWN -> { lastDistance=distance(event); rotating=false; return true }
            MotionEvent.ACTION_MOVE -> {
                if(event.pointerCount>=2) {
                    val d=distance(event); if(lastDistance>0f) zoom=(zoom*d/lastDistance).coerceIn(.35f,4f); lastDistance=d; invalidate()
                } else if(rotating) {
                    val dx=event.x-lastX; val dy=event.y-lastY
                    if(abs(event.x-downX)+abs(event.y-downY)>12f) moved=true
                    yaw += dx*.012f
                    pitch = (pitch + dy*.012f).coerceIn(-1.35f,1.35f)
                    lastX=event.x; lastY=event.y; invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                rotating=false
                if(!moved && event.pointerCount==1) performClickAt(event.x,event.y)
                return true
            }
            MotionEvent.ACTION_CANCEL -> { rotating=false; return true }
        }
        return true
    }

    private fun distance(e:MotionEvent):Float { if(e.pointerCount<2)return 0f; val dx=e.getX(0)-e.getX(1); val dy=e.getY(0)-e.getY(1); return sqrt(dx*dx+dy*dy) }

    private fun performClickAt(sx:Float, sy:Float) {
        val a=analysis ?: return
        val blocks=a.blocks.filter { !layerOnly || it.y==selectedLayer }
        if(blocks.isEmpty())return
        val maxDim=max(1,max(a.sizeX,max(a.sizeY,a.sizeZ)))
        val scale=min(width,height).toFloat()/(maxDim*2.25f+4f)*zoom.coerceIn(.35f,4f)
        val cx=(a.sizeX-1)/2f; val cy=(a.sizeY-1)/2f; val cz=(a.sizeZ-1)/2f
        val occupied = HashSet<Long>(blocks.size * 2)
        for (b in blocks) if (isOpaqueFullCube(b.name)) occupied.add(packPos(b.x, b.y, b.z))
        var best:McStructureBlock?=null; var bestDepth=Float.NEGATIVE_INFINITY
        for(b in blocks) {
            val faces=makeFaces(b,cx,cy,cz,scale,occupied)
            for(f in faces) if(pointInPolygon(sx,sy,f.points)) {
                if(f.depth>bestDepth){best=b;bestDepth=f.depth}
            }
        }
        best?.let { onBlockSelected?.invoke(it) }
    }

    private fun pointInPolygon(x:Float,y:Float,p:List<P>):Boolean {
        var inside=false; var j=p.lastIndex
        for(i in p.indices){ val xi=p[i].x; val yi=p[i].y; val xj=p[j].x; val yj=p[j].y; val hit=((yi>y)!=(yj>y)) && x < (xj-xi)*(y-yi)/(yj-yi+0.00001f)+xi; if(hit)inside=!inside; j=i }
        return inside
    }

    override fun performClick():Boolean { super.performClick(); return true }
}
