package com.spaimic.nbttool.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.spaimic.nbttool.chunk.BlockColors
import com.spaimic.nbttool.chunk.ColumnInfo
import kotlin.math.abs

/**
 * Draws loaded chunks as 16x16 grids of column colors with borders between
 * chunks; unloaded chunks (nothing in the DB for that position at all) are
 * flat gray. Drag to pan; tap a spot to report its exact block X/Y/Z via
 * [onTap] — no pinch-zoom in this first version, one screen pixel-per-block
 * setting is fixed via [blockPx].
 */
class MapView(context: Context) : View(context) {

    var chunkData: Map<Pair<Int, Int>, Array<Array<ColumnInfo>>> = emptyMap()
        set(value) {
            field = value
            invalidate()
        }

    /** World block X/Z to center on the first time the map draws — typically the
     * player's real position. Falls back to the bounding-box center if left null. */
    var preferredCenterBlock: Pair<Int, Int>? = null

    var onTap: ((worldX: Int, worldY: Int, worldZ: Int) -> Unit)? = null

    private val blockPx = 3f // on-screen pixels per block
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var centered = false

    private val gridPaint = Paint().apply { color = Color.rgb(25, 25, 25); strokeWidth = 2f; style = Paint.Style.STROKE }
    private val unloadedPaint = Paint().apply { color = BlockColors.fallbackUnloaded }
    private val blockPaint = Paint()

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
                // put the player's exact block position in the middle of the screen
                val (px, pz) = preferred
                val screenPx = offsetX + (Math.floorDiv(px, 16) - minCx) * 16 * blockPx + Math.floorMod(px, 16) * blockPx
                val screenPz = offsetY + (Math.floorDiv(pz, 16) - minCz) * 16 * blockPx + Math.floorMod(pz, 16) * blockPx
                offsetX += width / 2f - screenPx
                offsetY += height / 2f - screenPz
            } else {
                // no known player position — fall back to centering the whole explored area
                val totalWidthPx = (maxCx - minCx + 1) * 16 * blockPx
                val totalHeightPx = (maxCz - minCz + 1) * 16 * blockPx
                offsetX = (width - totalWidthPx) / 2f
                offsetY = (height - totalHeightPx) / 2f
            }
            centered = true
        }

        for (cx in minCx..maxCx) {
            for (cz in minCz..maxCz) {
                val screenX = offsetX + (cx - minCx) * 16 * blockPx
                val screenY = offsetY + (cz - minCz) * 16 * blockPx
                if (screenX + 16 * blockPx < 0 || screenX > width || screenY + 16 * blockPx < 0 || screenY > height) continue

                val grid = chunkData[cx to cz]
                if (grid == null) {
                    canvas.drawRect(screenX, screenY, screenX + 16 * blockPx, screenY + 16 * blockPx, unloadedPaint)
                } else {
                    for (bx in 0 until 16) {
                        for (bz in 0 until 16) {
                            blockPaint.color = grid[bx][bz].color
                            val px = screenX + bx * blockPx
                            val py = screenY + bz * blockPx
                            canvas.drawRect(px, py, px + blockPx, py + blockPx, blockPaint)
                        }
                    }
                }
                canvas.drawRect(screenX, screenY, screenX + 16 * blockPx, screenY + 16 * blockPx, gridPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x; lastTouchY = event.y
                downX = event.x; downY = event.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                if (abs(event.x - downX) > 12 || abs(event.y - downY) > 12) dragging = true
                offsetX += dx
                offsetY += dy
                lastTouchX = event.x
                lastTouchY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) handleTap(event.x, event.y)
            }
        }
        return true
    }

    private fun handleTap(screenX: Float, screenY: Float) {
        if (chunkData.isEmpty()) return
        val minCx = chunkData.keys.minOf { it.first }
        val minCz = chunkData.keys.minOf { it.second }

        val worldBlockX = ((screenX - offsetX) / blockPx).toInt() + minCx * 16
        val worldBlockZ = ((screenY - offsetY) / blockPx).toInt() + minCz * 16
        val cx = Math.floorDiv(worldBlockX, 16)
        val cz = Math.floorDiv(worldBlockZ, 16)
        val bx = Math.floorMod(worldBlockX, 16)
        val bz = Math.floorMod(worldBlockZ, 16)
        val y = chunkData[cx to cz]?.get(bx)?.get(bz)?.surfaceY ?: 0
        onTap?.invoke(worldBlockX, y, worldBlockZ)
    }
}
