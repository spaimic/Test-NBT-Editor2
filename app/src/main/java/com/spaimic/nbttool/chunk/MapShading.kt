package com.spaimic.nbttool.chunk

import android.graphics.Color

/** Small Minecraft-map-like elevation shading pass. */
object MapShading {
    fun apply(grid: Array<Array<ColumnInfo>>) {
        val copy = Array(16) { x -> Array(16) { z -> grid[x][z] } }
        for (x in 0 until 16) for (z in 0 until 16) {
            val current = copy[x][z]
            val north = if (z > 0) copy[x][z - 1].surfaceY else current.surfaceY
            val west = if (x > 0) copy[x - 1][z].surfaceY else current.surfaceY
            val delta = (current.surfaceY - maxOf(north, west)).coerceIn(-8, 8)
            if (delta == 0) continue
            val factor = 1f + delta * 0.018f
            grid[x][z] = current.copy(color = shade(current.color, factor))
        }
    }
    private fun shade(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255)
    )
}
