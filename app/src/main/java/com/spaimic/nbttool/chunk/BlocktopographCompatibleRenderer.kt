package com.spaimic.nbttool.chunk

import android.graphics.Color
import kotlin.math.atan
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/**
 * NBT Tool's independent renderer modeled on the observable behavior of
 * Blocktopograph's satellite renderer. This is not copied source code.
 *
 * It uses the same functional ideas: surface-height neighbors, column
 * compositing for transparent blocks, biome tinting when available through
 * ColumnInfo, and slope shading based on west/north height differences.
 */
object BlocktopographCompatibleRenderer {
    private const val SHADING_AMP = 0.8f

    fun renderColumn(
        grid: Array<Array<ColumnInfo>>,
        x: Int,
        z: Int,
        west: Int,
        north: Int
    ): Int {
        val info = grid[x][z]
        val base = info.color
        val height = info.surfaceY
        if (height <= 0) return base

        // Blocktopograph's satellite renderer emphasizes the difference between
        // the current surface and its west/north neighbors. atan() compresses
        // large cliffs while keeping small slopes visible.
        var samples = 0
        var heightDiff = 0f
        if (west > 0) {
            heightDiff += height - west
            samples++
        }
        if (north > 0) {
            heightDiff += height - north
            samples++
        }
        heightDiff *= Math.pow(1.05, samples.toDouble()).toFloat()
        val heightShading = ((atan(heightDiff) / PI.toFloat()) * SHADING_AMP) + 1f

        val r = min(255f, max(0f, Color.red(base) * heightShading)).toInt()
        val g = min(255f, max(0f, Color.green(base) * heightShading)).toInt()
        val b = min(255f, max(0f, Color.blue(base) * heightShading)).toInt()
        return Color.rgb(r, g, b)
    }
}
