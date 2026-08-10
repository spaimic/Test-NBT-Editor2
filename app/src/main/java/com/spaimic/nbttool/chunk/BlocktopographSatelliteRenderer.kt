package com.spaimic.nbttool.chunk

import android.graphics.Color
import kotlin.math.atan
import kotlin.math.pow

/**
 * Build 118 map renderer adapter.
 *
 * This class follows the satellite-map rendering model used by Blocktopograph:
 * the visible surface color is kept flat and the terrain depth is produced from
 * neighboring surface heights. The Android/UI tile machinery remains NBT Tool's
 * own implementation; this class is the compatibility boundary for the
 * Blocktopograph-style satellite rendering behavior.
 *
 * See BLOCKTOPOGRAPH_NOTICE.md for the third-party attribution and license.
 */
object BlocktopographSatelliteRenderer {
    private const val SHADING_AMP = 0.8f

    fun shade(
        baseColor: Int,
        height: Int,
        west: Int,
        north: Int
    ): Int {
        if (height == Int.MIN_VALUE) return baseColor

        var samples = 0
        var heightDiff = 0f
        if (west != Int.MIN_VALUE && west >= 0) {
            heightDiff += height - west
            samples++
        }
        if (north != Int.MIN_VALUE && north >= 0) {
            heightDiff += height - north
            samples++
        }
        if (samples == 0) return baseColor

        // Blocktopograph's satellite renderer emphasizes small terrain-height
        // differences while compressing very large cliffs with atan().
        heightDiff *= 1.05f.pow(samples.toFloat())
        val shading = (atan(heightDiff.toDouble()).toFloat() / Math.PI.toFloat()) * SHADING_AMP + 1f

        return Color.rgb(
            (Color.red(baseColor) * shading).toInt().coerceIn(0, 255),
            (Color.green(baseColor) * shading).toInt().coerceIn(0, 255),
            (Color.blue(baseColor) * shading).toInt().coerceIn(0, 255)
        )
    }
}
