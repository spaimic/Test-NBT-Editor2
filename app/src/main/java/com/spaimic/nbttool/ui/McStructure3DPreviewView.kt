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
    private data class DrawBlock(val block: McStructureBlock, val faces: List<Face>)

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

        val draws = ArrayList<DrawBlock>(blocks.size)
        for (b in blocks) draws.add(DrawBlock(b, makeFaces(b, cx, cy, cz, scale)))
        draws.sortBy { it.faces.minOfOrNull { f -> f.depth } ?: 0f }

        for (d in draws) {
            for (f in d.faces.sortedBy { it.depth }) drawFace(canvas, f, McStructureDecoder.blockColor(d.block.name))
        }
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

    private fun makeFaces(b: McStructureBlock, cx: Float, cy: Float, cz: Float, scale: Float): List<Face> {
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
        for (d in defs) {
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
        var best:McStructureBlock?=null; var bestDepth=Float.NEGATIVE_INFINITY
        for(b in blocks) {
            val faces=makeFaces(b,cx,cy,cz,scale)
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
