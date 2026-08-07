package com.spaimic.nbttool.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import com.spaimic.nbttool.R
import com.spaimic.nbttool.chunk.ChunkMapData
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor

/** Fast, pannable and pinch-zoomable top-down chunk map. */
class MapView(context: Context) : View(context) {

    var chunkData: Map<Pair<Int, Int>, ChunkMapData> = emptyMap()
        set(value) {
            field = value
            bitmapCache.clear()
            invalidate()
        }

    data class MarkerDraw(val worldX: Int, val worldZ: Int, val iconRes: Int)

    var entityMarkers: List<MarkerDraw> = emptyList()
        set(value) { field = value; invalidate() }
    var blockEntityMarkers: List<MarkerDraw> = emptyList()
        set(value) { field = value; invalidate() }
    var showEntityIcons = true
        set(value) { field = value; invalidate() }
    var showTileEntityIcons = true
        set(value) { field = value; invalidate() }

    private val markerBitmapCache = HashMap<Int, Bitmap>()
    private val bitmapCache = HashMap<Pair<Int, Int>, Bitmap>()
    private fun bitmapFor(resId: Int): Bitmap = markerBitmapCache.getOrPut(resId) { BitmapFactory.decodeResource(resources, resId) }

    var preferredCenterBlock: Pair<Int, Int>? = null
    var onTap: ((worldX: Int, worldY: Int, worldZ: Int) -> Unit)? = null

    private var blockPx = 3f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var centered = false
    private var pinchStartDistance = 0f
    private var pinchStartScale = 3f

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 0, 0, 0)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val unloadedPaint = Paint().apply { color = Color.rgb(55, 55, 55) }
    private val steveBitmap: Bitmap by lazy { BitmapFactory.decodeResource(resources, R.drawable.steve) }
    private val chunkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (chunkData.isEmpty()) return

        val minCx = chunkData.keys.minOf { it.first }
        val maxCx = chunkData.keys.maxOf { it.first }
        val minCz = chunkData.keys.minOf { it.second }
        val maxCz = chunkData.keys.maxOf { it.second }

        if (!centered) {
            val preferred = preferredCenterBlock
            if (preferred != null) {
                val (px, pz) = preferred
                val sx = offsetX + (px - minCx * 16) * blockPx
                val sz = offsetY + (pz - minCz * 16) * blockPx
                offsetX += width / 2f - sx
                offsetY += height / 2f - sz
            } else {
                val totalWidthPx = (maxCx - minCx + 1) * 16 * blockPx
                val totalHeightPx = (maxCz - minCz + 1) * 16 * blockPx
                offsetX = (width - totalWidthPx) / 2f
                offsetY = (height - totalHeightPx) / 2f
            }
            centered = true
        }

        val visibleMinCx = floorDiv(((-offsetX) / blockPx).toInt(), 16) + minCx - 1
        val visibleMaxCx = floorDiv(((width - offsetX) / blockPx).toInt(), 16) + minCx + 1
        val visibleMinCz = floorDiv(((-offsetY) / blockPx).toInt(), 16) + minCz - 1
        val visibleMaxCz = floorDiv(((height - offsetY) / blockPx).toInt(), 16) + minCz + 1

        for (cx in max(minCx, visibleMinCx)..min(maxCx, visibleMaxCx)) {
            for (cz in max(minCz, visibleMinCz)..min(maxCz, visibleMaxCz)) {
                val screenX = offsetX + (cx - minCx) * 16 * blockPx
                val screenY = offsetY + (cz - minCz) * 16 * blockPx
                val size = 16f * blockPx
                if (screenX + size < 0 || screenX > width || screenY + size < 0 || screenY > height) continue
                val data = chunkData[cx to cz]
                if (data == null) {
                    canvas.drawRect(screenX, screenY, screenX + size, screenY + size, unloadedPaint)
                } else {
                    val bmp = bitmapCache.getOrPut(cx to cz) { buildChunkBitmap(data) }
                    canvas.drawBitmap(bmp, null, Rect(screenX.toInt(), screenY.toInt(), (screenX + size).toInt(), (screenY + size).toInt()), chunkPaint)
                }
                canvas.drawRect(screenX, screenY, screenX + size, screenY + size, gridPaint)

                // Coordinates at chunk corners, like a lightweight world-map grid.
                if (blockPx >= 1.5f) {
                    val wx = cx * 16
                    val wz = cz * 16
                    canvas.drawText("($wx, $wz)", screenX + 4f, screenY + labelPaint.textSize, labelPaint)
                }
            }
        }

        fun screenPos(worldX: Int, worldZ: Int): Pair<Float, Float> =
            offsetX + (worldX - minCx * 16) * blockPx to offsetY + (worldZ - minCz * 16) * blockPx

        fun drawMarker(worldX: Int, worldZ: Int, iconRes: Int, sizePx: Int) {
            val (sx, sz) = screenPos(worldX, worldZ)
            if (sx < -sizePx || sx > width + sizePx || sz < -sizePx || sz > height + sizePx) return
            val dest = Rect((sx - sizePx / 2).toInt(), (sz - sizePx).toInt(), (sx + sizePx / 2).toInt(), sz.toInt())
            canvas.drawBitmap(bitmapFor(iconRes), null, dest, null)
        }
        if (showTileEntityIcons) for (m in blockEntityMarkers) drawMarker(m.worldX, m.worldZ, m.iconRes, 32)
        if (showEntityIcons) for (m in entityMarkers) drawMarker(m.worldX, m.worldZ, m.iconRes, 32)

        preferredCenterBlock?.let { (px, pz) ->
            val (sx, sz) = screenPos(px, pz)
            val markerSize = 48
            canvas.drawBitmap(steveBitmap, null, Rect((sx - markerSize / 2).toInt(), (sz - markerSize).toInt(), (sx + markerSize / 2).toInt(), sz.toInt()), null)
        }
    }

    private fun buildChunkBitmap(data: ChunkMapData): Bitmap {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(256)
        for (x in 0 until 16) {
            for (z in 0 until 16) {
                val i = x * 16 + z
                val base = data.colors[i]
                val left = if (x > 0) data.surfaceY[(x - 1) * 16 + z] else data.surfaceY[i]
                val right = if (x < 15) data.surfaceY[(x + 1) * 16 + z] else data.surfaceY[i]
                val up = if (z > 0) data.surfaceY[x * 16 + z - 1] else data.surfaceY[i]
                val down = if (z < 15) data.surfaceY[x * 16 + z + 1] else data.surfaceY[i]
                val shade = ((data.surfaceY[i] - ((left + right + up + down) / 4)) * 3).coerceIn(-36, 36)
                pixels[i] = shade(base, shade)
            }
        }
        bitmap.setPixels(pixels, 0, 16, 0, 0, 16, 16)
        return bitmap
    }

    private fun shade(color: Int, delta: Int): Int {
        val r = (Color.red(color) + delta).coerceIn(0, 255)
        val g = (Color.green(color) + delta).coerceIn(0, 255)
        val b = (Color.blue(color) + delta).coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x; lastTouchY = event.y
                downX = event.x; downY = event.y
                dragging = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) {
                pinchStartDistance = distance(event)
                pinchStartScale = blockPx
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && pinchStartDistance > 0f) {
                    val factor = distance(event) / pinchStartDistance
                    val newScale = (pinchStartScale * factor).coerceIn(0.75f, 12f)
                    val focusX = (event.getX(0) + event.getX(1)) / 2f
                    val focusY = (event.getY(0) + event.getY(1)) / 2f
                    val old = blockPx
                    if (newScale != old) {
                        val worldX = (focusX - offsetX) / old
                        val worldZ = (focusY - offsetY) / old
                        blockPx = newScale
                        offsetX = focusX - worldX * blockPx
                        offsetY = focusY - worldZ * blockPx
                        invalidate()
                    }
                } else {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (abs(event.x - downX) > 12 || abs(event.y - downY) > 12) dragging = true
                    offsetX += dx; offsetY += dy
                    lastTouchX = event.x; lastTouchY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.pointerCount <= 2) pinchStartDistance = 0f
            MotionEvent.ACTION_UP -> {
                pinchStartDistance = 0f
                if (!dragging) handleTap(event.x, event.y)
            }
            MotionEvent.ACTION_CANCEL -> pinchStartDistance = 0f
        }
        return true
    }

    private fun handleTap(screenX: Float, screenY: Float) {
        if (chunkData.isEmpty()) return
        val minCx = chunkData.keys.minOf { it.first }
        val minCz = chunkData.keys.minOf { it.second }
        val worldBlockX = floor(((screenX - offsetX) / blockPx)).toInt() + minCx * 16
        val worldBlockZ = floor(((screenY - offsetY) / blockPx)).toInt() + minCz * 16
        val cx = Math.floorDiv(worldBlockX, 16)
        val cz = Math.floorDiv(worldBlockZ, 16)
        val bx = Math.floorMod(worldBlockX, 16)
        val bz = Math.floorMod(worldBlockZ, 16)
        val y = chunkData[cx to cz]?.surfaceY?.get(bx * 16 + bz) ?: 0
        onTap?.invoke(worldBlockX, y, worldBlockZ)
    }

    private fun distance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun floorDiv(a: Int, b: Int): Int = Math.floorDiv(a, b)
}
