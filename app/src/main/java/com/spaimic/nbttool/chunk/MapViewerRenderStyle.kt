package com.spaimic.nbttool.chunk

import android.graphics.Color

/**
 * Single source of truth for the flat-map pixels used by MapView.
 *
 * The normal 2D Map Viewer renders each world block as a solid cell whose base
 * color comes from ColumnInfo and whose final RGB value is adjusted by the
 * surrounding surface heights. Whole-map exports must use this exact function
 * instead of maintaining a second approximation.
 */
object MapViewerRenderStyle {
    fun shadeColumn(
        info: ColumnInfo,
        west: Int,
        east: Int,
        north: Int,
        south: Int
    ): Int {
        val relief = reliefShading(info.surfaceY, west, east, north, south)
        val base = info.color
        val r = (Color.red(base) * relief).toInt().coerceIn(0, 255)
        val g = (Color.green(base) * relief).toInt().coerceIn(0, 255)
        val b = (Color.blue(base) * relief).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    /** Exact relief formula used by the on-screen MapView. */
    fun reliefShading(
        height: Int,
        west: Int,
        east: Int,
        north: Int,
        south: Int
    ): Float {
        val leftSlope = height - west
        val rightSlope = height - east
        val northSlope = height - north
        val southSlope = height - south
        val slope = ((leftSlope - rightSlope) + (northSlope - southSlope)) * 0.035f
        return (1f + slope.coerceIn(-0.14f, 0.14f)).coerceIn(0.86f, 1.14f)
    }
}
