package com.spaimic.nbttool.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.spaimic.nbttool.R
import com.spaimic.nbttool.chunk.BlockColors
import com.spaimic.nbttool.chunk.ColumnInfo
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A camera-based, lazy map. The View never assumes that all world chunks are loaded.
 * It asks the owner for the small rectangle currently visible on screen and renders
 * only the chunks that have arrived.
 */
class MapView(context: Context) : View(context) {

    data class MarkerDraw(val worldX: Int, val worldZ: Int, val iconRes: Int)

    var onVisibleChunksChanged: ((Set<Pair<Int, Int>>) -> Unit)? = null
    var onTap: ((worldX: Int, worldY: Int, worldZ: Int) -> Unit)? = null

    var preferredCenterBlock: Pair<Int, Int>? = null
        set(value) { field = value; centered = false; requestVisible() }

    private val chunks = HashMap<Pair<Int, Int>, Array<Array<ColumnInfo>>>()
    private val chunkBitmaps = HashMap<Pair<Int, Int>, Bitmap>()
    private val loading = HashSet<Pair<Int, Int>>()

    var entityMarkers: List<MarkerDraw> = emptyList()
        set(value) { field = value; invalidate() }
    var blockEntityMarkers: List<MarkerDraw> = emptyList()
        set(value) { field = value; invalidate() }
    var showEntityIcons = true
        set(value) { field = value; invalidate() }
    var showTileEntityIcons = true
        set(value) { field = value; invalidate() }

    private val markerBitmapCache = HashMap<Int, Bitmap>()
    private fun bitmapFor(resId: Int): Bitmap =
        markerBitmapCache.getOrPut(resId) { BitmapFactory.decodeResource(resources, resId) }

    private val steveBitmap: Bitmap by lazy { BitmapFactory.decodeResource(resources, R.drawable.steve) }
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 20, 20, 20)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val unloadedPaint = Paint().apply { color = BlockColors.fallbackUnloaded }
    private val coordinatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        textSize = 24f
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }

    /** pixels per block. Chunk size on screen is zoom * 16. */
    // Keep the whole-world overview useful: at the minimum zoom a tablet-sized
    // viewport shows roughly a few hundred blocks, not thousands. This also keeps
    // the initial visible-chunk set around the same scale as MineTuner.
    // Camera scale is pixels per world block.  These values are chosen from the
    // measured MineTuner viewport on the target tablet: roughly 235x350 blocks
    // at the widest view and roughly 46x70 blocks at the closest view.
    private var zoom = 3.85f
    private val minZoom = 3.75f
    private val maxZoom = 20f

    /** World block coordinate at the center of the viewport. */
    private var cameraX = 0f
    private var cameraZ = 0f
    private var centered = false

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var pinching = false
    private var hadMultiTouch = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinching = true
            hadMultiTouch = true
            dragging = false
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldZoom = zoom
            val newZoom = (zoom * detector.scaleFactor).coerceIn(minZoom, maxZoom)
            if (newZoom == oldZoom) return true

            // Keep the point under the fingers fixed while zooming.
            val focusWorldX = cameraX + (detector.focusX - width / 2f) / oldZoom
            val focusWorldZ = cameraZ + (detector.focusY - height / 2f) / oldZoom
            zoom = newZoom
            cameraX = focusWorldX - (detector.focusX - width / 2f) / zoom
            cameraZ = focusWorldZ - (detector.focusY - height / 2f) / zoom
            requestVisible()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            // Do not synthesize a tap when the pinch ends. The camera position
            // calculated in onScale is already the final pinch position.
            pinching = false
            requestVisible()
        }
    })

    fun setChunkData(newData: Map<Pair<Int, Int>, Array<Array<ColumnInfo>>>) {
        for ((pos, grid) in newData) {
            chunks[pos] = grid
            chunkBitmaps.remove(pos)?.recycle()
        }
        invalidate()
    }

    fun clearChunkData() {
        chunks.clear()
        chunkBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        chunkBitmaps.clear()
        invalidate()
    }

    fun markLoading(positions: Set<Pair<Int, Int>>) {
        loading.addAll(positions)
    }

    fun markLoaded(positions: Set<Pair<Int, Int>>) {
        loading.removeAll(positions)
        invalidate()
    }

    fun zoomIn() { changeZoom(zoom * 1.5f, width / 2f, height / 2f) }
    fun zoomOut() { changeZoom(zoom / 1.5f, width / 2f, height / 2f) }

    private fun changeZoom(target: Float, focusX: Float, focusZ: Float) {
        val old = zoom
        zoom = target.coerceIn(minZoom, maxZoom)
        val wx = cameraX + (focusX - width / 2f) / old
        val wz = cameraZ + (focusZ - height / 2f) / old
        cameraX = wx - (focusX - width / 2f) / zoom
        cameraZ = wz - (focusZ - height / 2f) / zoom
        requestVisible()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!centered) centerOnPreferred()
        requestVisible()
    }

    private fun centerOnPreferred() {
        val p = preferredCenterBlock
        if (p != null) {
            cameraX = p.first + 0.5f
            cameraZ = p.second + 0.5f
        }
        centered = true
    }

    private fun requestVisible() {
        if (width <= 0 || height <= 0) return
        if (!centered) centerOnPreferred()
        val halfWorldW = width / zoom / 2f
        val halfWorldH = height / zoom / 2f
        // Request exactly the viewport. Unlike the old renderer, we don't pre-load
        // an extra ring of chunks at startup; newly visible chunks are requested
        // immediately as the camera moves.
        val minCx = floor((cameraX - halfWorldW) / 16f).toInt()
        val maxCx = floor((cameraX + halfWorldW) / 16f).toInt()
        val minCz = floor((cameraZ - halfWorldH) / 16f).toInt()
        val maxCz = floor((cameraZ + halfWorldH) / 16f).toInt()
        val requested = HashSet<Pair<Int, Int>>()
        for (cx in minCx..maxCx) for (cz in minCz..maxCz) requested.add(cx to cz)

        // A small one-chunk border makes fast panning feel continuous without
        // turning a 7,000-chunk world into a startup scan.
        onVisibleChunksChanged?.invoke(requested)
        invalidate()
    }

    private fun screenX(worldX: Float): Float = width / 2f + (worldX - cameraX) * zoom
    private fun screenZ(worldZ: Float): Float = height / 2f + (worldZ - cameraZ) * zoom

    private fun bitmapForChunk(pos: Pair<Int, Int>, grid: Array<Array<ColumnInfo>>): Bitmap {
        return chunkBitmaps.getOrPut(pos) {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(256)
            for (x in 0 until 16) for (z in 0 until 16) pixels[z * 16 + x] = grid[x][z].color
            bitmap.setPixels(pixels, 0, 16, 0, 0, 16, 16)
            bitmap
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!centered) centerOnPreferred()

        canvas.drawColor(BlockColors.fallbackUnloaded)
        val halfWorldW = width / zoom / 2f
        val halfWorldH = height / zoom / 2f
        // Request exactly the viewport. Unlike the old renderer, we don't pre-load
        // an extra ring of chunks at startup; newly visible chunks are requested
        // immediately as the camera moves.
        val minCx = floor((cameraX - halfWorldW) / 16f).toInt()
        val maxCx = floor((cameraX + halfWorldW) / 16f).toInt()
        val minCz = floor((cameraZ - halfWorldH) / 16f).toInt()
        val maxCz = floor((cameraZ + halfWorldH) / 16f).toInt()

        for (cx in minCx..maxCx) {
            for (cz in minCz..maxCz) {
                val left = screenX(cx * 16f)
                val top = screenZ(cz * 16f)
                val right = screenX((cx + 1) * 16f)
                val bottom = screenZ((cz + 1) * 16f)
                if (right < 0 || left > width || bottom < 0 || top > height) continue

                val pos = cx to cz
                val grid = chunks[pos]
                if (grid != null) {
                    canvas.drawBitmap(bitmapForChunk(pos, grid), null, RectF(left, top, right, bottom), tilePaint)
                    // At very small chunk sizes, borders visually merge like MineTuner.
                    if (zoom >= 6f) {
                        canvas.drawRect(left, top, right, bottom, gridPaint)
                    }
                } else {
                    canvas.drawRect(left, top, right, bottom, unloadedPaint)
                }
            }
        }

        // At the widest zoom MineTuner visually merges 4x4 chunks.  We keep the
        // underlying 16x16 terrain data, but suppress individual chunk borders and
        // draw a 64x64-block grid instead.  This makes the overview read as large
        // terrain tiles while preserving the exact terrain underneath.
        if (zoom < 6f) {
            val regionMinX = floor((cameraX - halfWorldW) / 64f).toInt() * 64
            val regionMaxX = floor((cameraX + halfWorldW) / 64f).toInt() * 64
            val regionMinZ = floor((cameraZ - halfWorldH) / 64f).toInt() * 64
            val regionMaxZ = floor((cameraZ + halfWorldH) / 64f).toInt() * 64
            for (wx in regionMinX..regionMaxX step 64) {
                val sx = screenX(wx.toFloat())
                if (sx >= -1f && sx <= width + 1f) canvas.drawLine(sx, 0f, sx, height.toFloat(), gridPaint)
            }
            for (wz in regionMinZ..regionMaxZ step 64) {
                val sz = screenZ(wz.toFloat())
                if (sz >= -1f && sz <= height + 1f) canvas.drawLine(0f, sz, width.toFloat(), sz, gridPaint)
            }
        }

        fun drawMarker(m: MarkerDraw, size: Int) {
            val sx = screenX(m.worldX + 0.5f)
            val sz = screenZ(m.worldZ + 0.5f)
            if (sx < -size || sx > width + size || sz < -size || sz > height + size) return
            val half = size / 2f
            canvas.drawBitmap(bitmapFor(m.iconRes), null,
                RectF(sx - half, sz - size, sx + half, sz), null)
        }

        if (showTileEntityIcons) blockEntityMarkers.forEach { drawMarker(it, 32) }
        if (showEntityIcons) entityMarkers.forEach { drawMarker(it, 32) }

        preferredCenterBlock?.let { (px, pz) ->
            val sx = screenX(px + 0.5f)
            val sz = screenZ(pz + 0.5f)
            val size = 48f
            canvas.drawBitmap(steveBitmap, null, RectF(sx - size / 2, sz - size, sx + size / 2, sz), null)
        }

        // A lightweight coordinate readout is useful even before tapping.
        val cx = floor(cameraX).toInt()
        val cz = floor(cameraZ).toInt()
        canvas.drawText("X: $cx  Z: $cz   Zoom: ${"%.2f".format(zoom)}×", 20f, height - 24f, coordinatePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                dragging = false
                pinching = false
                hadMultiTouch = false
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                hadMultiTouch = true
                pinching = true
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Never pan with a two-finger gesture. This was the source of the
                // camera "jump" after pinch-zoom and made the final position differ
                // from where the fingers actually stopped.
                if (!pinching && !scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dz = event.y - lastY
                    if (kotlin.math.abs(event.x - downX) > 12 || kotlin.math.abs(event.y - downY) > 12) dragging = true
                    cameraX -= dx / zoom
                    cameraZ -= dz / zoom
                    lastX = event.x; lastY = event.y
                    requestVisible()
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                hadMultiTouch = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasGesture = hadMultiTouch || pinching
                pinching = false
                if (!wasGesture && !dragging) handleTap(event.x, event.y)
                hadMultiTouch = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pinching = false
                hadMultiTouch = false
                return true
            }
        }
        return true
    }

    fun currentCameraBlockX(): Int = floor(cameraX).toInt()
    fun currentCameraBlockZ(): Int = floor(cameraZ).toInt()

    fun refreshVisibleChunks() {
        chunkBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        chunkBitmaps.clear()
        requestVisible()
    }

    private fun handleTap(screenX: Float, screenY: Float) {
        val worldX = floor(cameraX + (screenX - width / 2f) / zoom).toInt()
        val worldZ = floor(cameraZ + (screenY - height / 2f) / zoom).toInt()
        onTap?.invoke(worldX, 0, worldZ)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        chunkBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        chunkBitmaps.clear()
    }
}
