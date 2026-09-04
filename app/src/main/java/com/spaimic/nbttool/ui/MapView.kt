package com.spaimic.nbttool.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.spaimic.nbttool.selection.WorldSelection
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.spaimic.nbttool.R
import com.spaimic.nbttool.chunk.BlockColors
import com.spaimic.nbttool.chunk.ColumnInfo
import com.spaimic.nbttool.chunk.MapViewerRenderStyle
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A camera-based, lazy map. The View never assumes that all world chunks are loaded.
 * It asks the owner for the small rectangle currently visible on screen and renders
 * only the chunks that have arrived.
 */
class MapView(context: Context) : View(context) {

    data class MarkerDraw(val worldX: Int, val worldZ: Int, val iconRes: Int)

    var onVisibleChunksChanged: ((Set<Pair<Int, Int>>) -> Unit)? = null
    var onTap: ((worldX: Int, worldY: Int, worldZ: Int) -> Unit)? = null
    var selection: WorldSelection? = null
        private set
    var onSelectionChanged: ((WorldSelection?) -> Unit)? = null
    private var selectionHandle = 0 // 0 none, 1 top, 2 bottom, 3 left, 4 right
    private var selectionDragStartX = 0
    private var selectionDragStartZ = 0
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 40, 140, 255)
        style = Paint.Style.FILL
    }
    private val selectionBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 140, 255)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val selectionHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val selectionHandleOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(30, 100, 220)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    var preferredCenterBlock: Pair<Int, Int>? = null
        set(value) { field = value; centered = false; requestVisible() }

    private val chunks = ConcurrentHashMap<Pair<Int, Int>, Array<Array<ColumnInfo>>>()
    private val chunkBitmaps = ConcurrentHashMap<Pair<Int, Int>, Bitmap>()
    // Build 108: LevelDB loading state belongs to MapViewerActivity.
    // This view tracks only background bitmap renders, so database loading
    // can never block a render request.
    private val rendering = ConcurrentHashMap.newKeySet<Pair<Int, Int>>()

    // Build 107: expensive 512x512 chunk rendering must never run from onDraw().
    // A small pool keeps the UI thread responsive while still rendering a couple
    // of visible chunks in parallel.
    private val renderExecutor = Executors.newFixedThreadPool(4)
    private val renderGeneration = AtomicInteger(0)

    var entityMarkers: List<MarkerDraw> = emptyList()
        set(value) { field = value; invalidate() }
    var blockEntityMarkers: List<MarkerDraw> = emptyList()
        set(value) { field = value; invalidate() }
    var showEntityIcons = true
        set(value) { field = value; invalidate() }
    var showTileEntityIcons = true
        set(value) { field = value; invalidate() }
    var showGrid = true
        set(value) { field = value; invalidate() }

    private val markerBitmapCache = HashMap<Int, Bitmap>()
    private fun bitmapFor(resId: Int): Bitmap =
        markerBitmapCache.getOrPut(resId) { BitmapFactory.decodeResource(resources, resId) }

    private val steveBitmap: Bitmap by lazy { BitmapFactory.decodeResource(resources, R.drawable.steve) }
    private val tilePaint = Paint().apply {
        // Build 112: map tiles must stay crisp and pixel-aligned. Do not let
        // Android texture-filter the 12 px/block source bitmap while scaling it.
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 20, 20, 20)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val unloadedPaint = Paint().apply { color = BlockColors.fallbackUnloaded }
    private val coordinatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 24f
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
    private val maxZoom = 32f

    // Build 111: use a 12x12 source-pixel footprint per world block. This keeps
    // the Blocktopograph-style visual detail while cutting per-chunk pixel work
    // by about 7.1x compared with the previous 32x32 renderer. Zoom changes only
    // the camera transform; the source resolution stays fixed at every zoom.
    private val blockPixels = 12
    private val chunkPixels = 16 * blockPixels

    // A bounded cache prevents a large world viewport from retaining hundreds
    // of 512x512 bitmaps at once. The underlying chunk data remains available,
    // so evicted bitmaps are cheap to regenerate when they become visible again.
    private val bitmapAccessOrder = LinkedHashMap<Pair<Int, Int>, Unit>(32, 0.75f, true)
    // Positions currently inside the viewport. Updated before drawing so visible
    // bitmaps are protected from eviction during the same frame.
    private var visibleChunkPositions: Set<Pair<Int, Int>> = emptySet()
    // Keep a modest number of recently used off-screen bitmaps in addition to all
    // visible bitmaps. This avoids unbounded memory use without evicting visible tiles.
    private val maxCachedOffscreenBitmaps = 24

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
    // Build 112: after the second finger is lifted, the first one can remain
    // down for a frame. Ignore that first one-finger MOVE so its coordinate jump
    // cannot be interpreted as a pan.
    private var ignoreNextSinglePointerMove = false

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
            invalidateBitmapResolutionIfNeeded(oldZoom, newZoom)

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
            dragging = false
            // The next ACTION_MOVE may contain the surviving finger at a very
            // different screen coordinate. Ignore that first delta completely.
            ignoreNextSinglePointerMove = true
            requestVisible()
        }
    })

    fun setChunkData(newData: Map<Pair<Int, Int>, Array<Array<ColumnInfo>>>) {
        for ((pos, grid) in newData) {
            val previous = chunks.put(pos, grid)
            // Only invalidate the rendered tile when the actual grid object changed.
            // Repeated world-loading callbacks can otherwise recycle a perfectly good
            // bitmap on every callback and make chunks visibly disappear.
            if (previous !== grid) {
                chunkBitmaps.remove(pos)?.let { if (!it.isRecycled) it.recycle() }
                bitmapAccessOrder.remove(pos)
            }
        }
        invalidate()
    }

    fun clearChunkData() {
        renderGeneration.incrementAndGet()
        chunks.clear()
        chunkBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        chunkBitmaps.clear()
        invalidate()
    }

    fun zoomIn() { changeZoom(zoom * 1.5f, width / 2f, height / 2f) }
    fun zoomOut() { changeZoom(zoom / 1.5f, width / 2f, height / 2f) }

    private fun changeZoom(target: Float, focusX: Float, focusZ: Float) {
        val old = zoom
        val newZoom = target.coerceIn(minZoom, maxZoom)
        invalidateBitmapResolutionIfNeeded(old, newZoom)
        zoom = newZoom
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

    private fun invalidateBitmapResolutionIfNeeded(oldZoom: Float, newZoom: Float) {
        // Build 111: source resolution is fixed at 12x12 pixels per block for
        // every zoom level. No regeneration is needed just because the camera zooms.
    }

    private fun touchBitmap(pos: Pair<Int, Int>) {
        bitmapAccessOrder[pos] = Unit
    }

    /**
     * Evict only off-screen bitmaps. Never recycle a bitmap that belongs to the
     * current viewport; otherwise the next draw can see a missing/recycled tile
     * and schedule it again, producing the Build 107/108 disappearing-chunk loop.
     */
    private fun trimOffscreenBitmaps() {
        var offscreenCount = bitmapAccessOrder.keys.count { pos ->
            !visibleChunkPositions.contains(pos) &&
                chunkBitmaps[pos]?.isRecycled == false
        }
        if (offscreenCount <= maxCachedOffscreenBitmaps) return

        val iterator = bitmapAccessOrder.entries.iterator()
        while (iterator.hasNext() && offscreenCount > maxCachedOffscreenBitmaps) {
            val pos = iterator.next().key
            if (visibleChunkPositions.contains(pos)) continue

            iterator.remove()
            chunkBitmaps.remove(pos)?.let { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            offscreenCount--
        }
    }

    /**
     * Build a 192x192 map tile for every chunk (12 px/block). This method performs
     * the expensive work and MUST run off the UI thread.
     *
     * Build 112 intentionally uses clean, flat map pixels. Earlier builds added
     * per-pixel noise to imitate texture, which made large surfaces look like
     * concrete powder. Instead, each block gets a small deterministic palette of
     * solid shades arranged in clean 3x3 pixel cells. This keeps the richer
     * Blocktopograph-like variation without texture grain or filtering artifacts.
     */
    /**
     * Build a 192x192 map tile (12 px/block). Build 114 deliberately uses
     * clean map colors: a flat world must look flat. Elevation is the only
     * source of terrain shading, and neighboring chunks are consulted at
     * borders so a hill does not suddenly change shading at a chunk edge.
     */
    private fun renderChunkBitmap(pos: Pair<Int, Int>, grid: Array<Array<ColumnInfo>>): Bitmap {
        val existing = chunkBitmaps[pos]
        if (existing != null && !existing.isRecycled && existing.width == chunkPixels) {
            return existing
        }

        val bitmap = Bitmap.createBitmap(chunkPixels, chunkPixels, Bitmap.Config.RGB_565)
        val pixels = IntArray(chunkPixels * chunkPixels)
        val westGrid = chunks[pos.first - 1 to pos.second]
        val eastGrid = chunks[pos.first + 1 to pos.second]
        val northGrid = chunks[pos.first to pos.second - 1]
        val southGrid = chunks[pos.first to pos.second + 1]

        for (x in 0 until 16) for (z in 0 until 16) {
            val info = grid[x][z]
            val west = when {
                x > 0 -> grid[x - 1][z].surfaceY
                westGrid != null -> westGrid[15][z].surfaceY
                else -> info.surfaceY
            }
            val east = when {
                x < 15 -> grid[x + 1][z].surfaceY
                eastGrid != null -> eastGrid[0][z].surfaceY
                else -> info.surfaceY
            }
            val north = when {
                z > 0 -> grid[x][z - 1].surfaceY
                northGrid != null -> northGrid[x][15].surfaceY
                else -> info.surfaceY
            }
            val south = when {
                z < 15 -> grid[x][z + 1].surfaceY
                southGrid != null -> southGrid[x][0].surfaceY
                else -> info.surfaceY
            }

            // Keep the on-screen map and exported map pixels on one renderer.
            // This must stay the exact same shading function used by the export
            // path so screenshots cannot drift visually from the 2D Map Viewer.
            val color = MapViewerRenderStyle.shadeColumn(
                info, west, east, north, south
            )

            for (py in 0 until blockPixels) {
                val row = (z * blockPixels + py) * chunkPixels + x * blockPixels
                java.util.Arrays.fill(pixels, row, row + blockPixels, color)
            }
        }

        bitmap.setPixels(pixels, 0, chunkPixels, 0, 0, chunkPixels, chunkPixels)
        return bitmap
    }

    /** Schedule a render if the visible chunk has no ready bitmap. UI-thread safe. */
    private fun requestChunkBitmap(pos: Pair<Int, Int>, grid: Array<Array<ColumnInfo>>) {
        val existing = chunkBitmaps[pos]
        if (existing != null && !existing.isRecycled && existing.width == chunkPixels) {
            touchBitmap(pos)
            return
        }
        if (!rendering.add(pos)) return

        val generation = renderGeneration.get()
        renderExecutor.execute {
            var rendered: Bitmap? = null
            try {
                rendered = renderChunkBitmap(pos, grid)
                if (generation != renderGeneration.get() || chunks[pos] !== grid) {
                    rendered?.recycle()
                    return@execute
                }
                val old = chunkBitmaps.put(pos, rendered!!)
                if (old != null && old !== rendered && !old.isRecycled) old.recycle()
                rendered = null
                post {
                    if (generation == renderGeneration.get()) {
                        touchBitmap(pos)
                        invalidate()
                    }
                }
            } catch (_: Throwable) {
                rendered?.let { if (!it.isRecycled) it.recycle() }
            } finally {
                rendering.remove(pos)
            }
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

        // Protect the entire viewport BEFORE drawing. The previous Build 109
        // implementation updated this only after drawing, allowing trimOffscreenBitmaps()
        // to evict a bitmap that was about to be drawn.
        val visibleNow = HashSet<Pair<Int, Int>>()
        for (cx in minCx..maxCx) {
            for (cz in minCz..maxCz) {
                val left = screenX(cx * 16f)
                val top = screenZ(cz * 16f)
                val right = screenX((cx + 1) * 16f)
                val bottom = screenZ((cz + 1) * 16f)
                if (right < 0 || left > width || bottom < 0 || top > height) continue
                visibleNow.add(cx to cz)
            }
        }
        visibleChunkPositions = visibleNow

        for (cx in minCx..maxCx) {
            for (cz in minCz..maxCz) {
                val left = screenX(cx * 16f)
                val top = screenZ(cz * 16f)
                val right = screenX((cx + 1) * 16f)
                val bottom = screenZ((cz + 1) * 16f)
                if (right < 0 || left > width || bottom < 0 || top > height) continue

                val pos = cx to cz
                visibleNow.add(pos)
                val grid = chunks[pos]
                if (grid != null) {
                    val bitmap = chunkBitmaps[pos]
                    if (bitmap != null && !bitmap.isRecycled) {
                        touchBitmap(pos)
                        canvas.drawBitmap(bitmap, null, RectF(left, top, right, bottom), tilePaint)
                    } else {
                        requestChunkBitmap(pos, grid)
                        canvas.drawRect(left, top, right, bottom, unloadedPaint)
                    }
                    // Chunk borders can be hidden from the Map Options drawer.
                    if (showGrid && zoom >= 6f) {
                        canvas.drawRect(left, top, right, bottom, gridPaint)
                    }
                } else {
                    canvas.drawRect(left, top, right, bottom, unloadedPaint)
                }
            }
        }

        trimOffscreenBitmaps()

        // At the widest zoom MineTuner visually merges 4x4 chunks.  We keep the
        // underlying 16x16 terrain data, but suppress individual chunk borders and
        // draw a 64x64-block grid instead.  This makes the overview read as large
        // terrain tiles while preserving the exact terrain underneath.
        if (showGrid && zoom < 6f) {
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

        selection?.let { s ->
            val left = screenX(s.minX.toFloat())
            val top = screenZ(s.minZ.toFloat())
            val right = screenX((s.maxX + 1).toFloat())
            val bottom = screenZ((s.maxZ + 1).toFloat())
            canvas.drawRect(left, top, right, bottom, selectionPaint)
            canvas.drawRect(left, top, right, bottom, selectionBorderPaint)
            val r = 22f
            val points = arrayOf(
                left + (right-left)/2f to top,
                left + (right-left)/2f to bottom,
                left to top + (bottom-top)/2f,
                right to top + (bottom-top)/2f
            )
            for ((x,y) in points) {
                canvas.drawCircle(x,y,r,selectionHandlePaint)
                canvas.drawCircle(x,y,r,selectionHandleOutlinePaint)
            }
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
                downX=event.x; downY=event.y
                lastX=event.x; lastY=event.y
                dragging=false; pinching=false; hadMultiTouch=false
                selectionHandle = if (selection != null) hitSelectionHandle(event.x,event.y) else 0
                if (selectionHandle != 0) {
                    val s=selection!!
                    selectionDragStartX = when(selectionHandle) {
                        3,4 -> if (selectionHandle==3) s.maxX else s.minX
                        else -> s.minX
                    }
                    selectionDragStartZ = when(selectionHandle) {
                        1,2 -> if (selectionHandle==1) s.maxZ else s.minZ
                        else -> s.minZ
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                hadMultiTouch=true; pinching=true; dragging=false
                selectionHandle=0
                ignoreNextSinglePointerMove=true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (selectionHandle != 0 && event.pointerCount==1 && !scaleDetector.isInProgress) {
                    val wx=floor(cameraX+(event.x-width/2f)/zoom).toInt()
                    val wz=floor(cameraZ+(event.y-height/2f)/zoom).toInt()
                    val s=selection!!
                    selection = when(selectionHandle) {
                        1 -> { // top: move minZ, keep maxZ fixed
                            val newMin=minOf(s.maxZ, maxOf(s.maxZ-WorldSelection.MAX_SIZE+1, wz))
                            WorldSelection(s.minX,newMin,s.maxX,s.maxZ)
                        }
                        2 -> { // bottom: move maxZ, keep minZ fixed
                            val newMax=maxOf(s.minZ, minOf(s.minZ+WorldSelection.MAX_SIZE-1, wz))
                            WorldSelection(s.minX,s.minZ,s.maxX,newMax)
                        }
                        3 -> { // left: move minX, keep maxX fixed
                            val newMin=minOf(s.maxX, maxOf(s.maxX-WorldSelection.MAX_SIZE+1, wx))
                            WorldSelection(newMin,s.minZ,s.maxX,s.maxZ)
                        }
                        4 -> { // right: move maxX, keep minX fixed
                            val newMax=maxOf(s.minX, minOf(s.minX+WorldSelection.MAX_SIZE-1, wx))
                            WorldSelection(s.minX,s.minZ,newMax,s.maxZ)
                        }
                        else -> s
                    }
                    onSelectionChanged?.invoke(selection)
                    invalidate()
                    return true
                }
                if (!pinching && !scaleDetector.isInProgress && event.pointerCount==1) {
                    if(ignoreNextSinglePointerMove){lastX=event.x;lastY=event.y;ignoreNextSinglePointerMove=false;return true}
                    val dx=event.x-lastX; val dz=event.y-lastY
                    if(kotlin.math.abs(event.x-downX)>12 || kotlin.math.abs(event.y-downY)>12) dragging=true
                    cameraX-=dx/zoom; cameraZ-=dz/zoom
                    lastX=event.x;lastY=event.y;requestVisible()
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                hadMultiTouch=true; pinching=true; ignoreNextSinglePointerMove=true; selectionHandle=0; return true
            }
            MotionEvent.ACTION_UP -> {
                val wasGesture=hadMultiTouch||pinching
                pinching=false; ignoreNextSinglePointerMove=false
                if(selectionHandle!=0){ selectionHandle=0; return true }
                if(!wasGesture && !dragging) handleTap(event.x,event.y)
                hadMultiTouch=false
                return true
            }
            MotionEvent.ACTION_CANCEL -> { selectionHandle=0;pinching=false;hadMultiTouch=false;ignoreNextSinglePointerMove=false;return true }
        }
        return true
    }

    private fun hitSelectionHandle(x:Float,y:Float):Int {
        val s=selection ?: return 0
        val left=screenX(s.minX.toFloat()); val top=screenZ(s.minZ.toFloat())
        val right=screenX((s.maxX+1).toFloat()); val bottom=screenZ((s.maxZ+1).toFloat())
        val pts=arrayOf(
            left+(right-left)/2f to top, left+(right-left)/2f to bottom,
            left to top+(bottom-top)/2f, right to top+(bottom-top)/2f
        )
        val r=36f
        for(i in pts.indices) {
            val dx=x-pts[i].first; val dy=y-pts[i].second
            if(dx*dx+dy*dy<=r*r) return i+1
        }
        return 0
    }

    fun startSelection(worldX:Int, worldZ:Int) {
        if(selection!=null) return
        selection=WorldSelection.centeredAt(worldX,worldZ)
        onSelectionChanged?.invoke(selection)
        invalidate()
    }

    fun clearSelection() {
        selection=null
        onSelectionChanged?.invoke(null)
        invalidate()
    }

    fun currentCameraBlockX(): Int = floor(cameraX).toInt()
    fun currentCameraBlockZ(): Int = floor(cameraZ).toInt()

    fun captureChunkPreviewPng(chunkX: Int, chunkZ: Int): ByteArray? {
        val grid = chunks[chunkX to chunkZ] ?: return null
        val pos = chunkX to chunkZ
        val source = chunkBitmaps[pos] ?: renderChunkBitmap(pos, grid).also {
            val old = chunkBitmaps.put(pos, it)
            if (old != null && old !== it && !old.isRecycled) old.recycle()
        }
        val preview = Bitmap.createScaledBitmap(source, 128, 128, false)
        return java.io.ByteArrayOutputStream().use { out ->
            preview.compress(Bitmap.CompressFormat.PNG, 100, out)
            preview.recycle()
            out.toByteArray()
        }
    }

    fun refreshVisibleChunks() {
        renderGeneration.incrementAndGet()
        chunkBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        chunkBitmaps.clear()
        bitmapAccessOrder.clear()
        requestVisible()
    }

    private fun handleTap(screenX: Float, screenY: Float) {
        val worldX = floor(cameraX + (screenX - width / 2f) / zoom).toInt()
        val worldZ = floor(cameraZ + (screenY - height / 2f) / zoom).toInt()
        onTap?.invoke(worldX, 0, worldZ)
    }

    override fun onDetachedFromWindow() {
        renderGeneration.incrementAndGet()
        renderExecutor.shutdownNow()
        super.onDetachedFromWindow()
        chunkBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        chunkBitmaps.clear()
        bitmapAccessOrder.clear()
    }
}
