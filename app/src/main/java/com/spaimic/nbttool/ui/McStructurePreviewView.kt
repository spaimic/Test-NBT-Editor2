package com.spaimic.nbttool.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Read-only isometric/cube preview for Build 203. No world data is changed. */
class McStructurePreviewView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var analysis: McStructureAnalysis? = null
    private var layerOnly = false
    private var selectedLayer = 0
    private var angleOffset = 0f
    private var lastX = 0f

    var onBlockSelected: ((McStructureBlock?) -> Unit)? = null

    fun setAnalysis(value: McStructureAnalysis) {
        analysis = value
        invalidate()
    }

    fun setSelectedLayer(layer: Int, onlyLayer: Boolean) {
        selectedLayer = layer
        layerOnly = onlyLayer
        invalidate()
    }

    fun clearLayerFilter() {
        layerOnly = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        val a = analysis ?: return
        if (a.blocks.isEmpty()) return

        val maxDim = max(1, max(a.sizeX, a.sizeZ))
        val cell = min(width, height).toFloat() / (maxDim + a.sizeY * 0.55f + 4f)
        val tileW = cell.coerceAtLeast(8f)
        val tileH = tileW * 0.5f
        val cubeH = tileW * 0.9f
        val centerX = width * 0.5f + angleOffset
        val baseY = height * 0.74f

        val visible = a.blocks.asSequence()
            .filter { !layerOnly || it.y == selectedLayer }
            .sortedWith(compareBy<McStructureBlock> { it.y }.thenBy { it.z }.thenBy { it.x })
            .toList()

        for (b in visible) {
            val px = centerX + (b.x - b.z) * tileW * 0.5f
            val py = baseY + (b.x + b.z) * tileH * 0.5f - b.y * cubeH
            drawCube(canvas, px, py, tileW, tileH, cubeH, McStructureDecoder.blockColor(b.name), b.name)
        }
    }

    private fun drawCube(canvas: Canvas, cx: Float, top: Float, w: Float, h: Float, d: Float, color: Int, name: String) {
        val alphaColor = if (Color.alpha(color) == 0) Color.argb(100, 220, 220, 220) else color
        val left = android.graphics.Path().apply {
            moveTo(cx, top)
            lineTo(cx - w * 0.5f, top + h * 0.5f)
            lineTo(cx - w * 0.5f, top + h * 0.5f + d)
            lineTo(cx, top + h + d)
            lineTo(cx, top + h)
            close()
        }
        val right = android.graphics.Path().apply {
            moveTo(cx, top)
            lineTo(cx + w * 0.5f, top + h * 0.5f)
            lineTo(cx + w * 0.5f, top + h * 0.5f + d)
            lineTo(cx, top + h + d)
            lineTo(cx, top + h)
            close()
        }
        val topFace = android.graphics.Path().apply {
            moveTo(cx, top)
            lineTo(cx + w * 0.5f, top + h * 0.5f)
            lineTo(cx, top + h)
            lineTo(cx - w * 0.5f, top + h * 0.5f)
            close()
        }

        paint.style = Paint.Style.FILL
        paint.color = shade(alphaColor, 0.68f)
        canvas.drawPath(left, paint)
        paint.color = shade(alphaColor, 0.82f)
        canvas.drawPath(right, paint)
        paint.color = alphaColor
        canvas.drawPath(topFace, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.argb(70, 0, 0, 0)
        canvas.drawPath(left, paint)
        canvas.drawPath(right, paint)
        canvas.drawPath(topFace, paint)
    }

    private fun shade(c: Int, factor: Float): Int = Color.rgb(
        (Color.red(c) * factor).toInt().coerceIn(0, 255),
        (Color.green(c) * factor).toInt().coerceIn(0, 255),
        (Color.blue(c) * factor).toInt().coerceIn(0, 255)
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                angleOffset += dx
                angleOffset = angleOffset.coerceIn(-width * 0.35f, width * 0.35f)
                lastX = event.x
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        val a = analysis ?: return true
        // Build 203 prioritizes inspection over precise picking: report the first
        // visible block nearest the center as a useful quick diagnostic.
        val center = a.blocks.asSequence()
            .filter { !layerOnly || it.y == selectedLayer }
            .minByOrNull { kotlin.math.abs(it.x - a.sizeX / 2) + kotlin.math.abs(it.z - a.sizeZ / 2) }
        onBlockSelected?.invoke(center)
        return true
    }
}
