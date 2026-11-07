package com.spaimic.nbttool.chunk

import android.graphics.Color
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.min

/**
 * Nether-specific top-down renderer based on the layered cave renderer used by
 * the legacy Blocktopograph Bedrock world viewer.
 *
 * Unlike the Overworld/End renderer, the Nether is not rendered from the
 * highest block in each X/Z column.  It walks downward through every air pocket
 * and composites the solid terrain forming each cave layer.
 */
object NetherRenderer {
    private const val HEIGHT = 256
    private const val AIR = "minecraft:air"

    fun render(blocks: Array<Array<Array<String>>>): Array<Array<ColumnInfo>> {
        val grid = Array(16) { Array(16) { ColumnInfo(BlockColors.fallbackUnparsed, 0) } }

        // Height map: highest non-air block in each X/Z column.
        val heightMap = Array(16) { IntArray(16) }
        for (x in 0 until 16) for (z in 0 until 16) {
            var y = HEIGHT - 1
            while (y >= 0 && isAir(blocks[x][z][y])) y--
            heightMap[x][z] = y
        }

        for (x in 0 until 16) for (z in 0 until 16) {
            var caveFloorStart = heightMap[x][z]
            var worth = 0
            var shadingSum = 0f
            var alpha = 0f
            var sumR = 0f
            var sumG = 0f
            var sumB = 0f
            var layers = 1
            var lastSurfaceY = caveFloorStart
            var lastSurfaceName = "minecraft:netherrack"

            while (caveFloorStart > 0) {
                val caveCeil = findAirUnder(blocks, x, z, caveFloorStart - 1)
                if (caveCeil < 0) break
                val caveFloor = findSolidUnder(blocks, x, z, caveCeil - 1)
                if (caveFloor < 0) break

                val west = if (x > 0) findSolidUnder(blocks, x - 1, z, caveCeil - 1) else caveFloor
                val north = if (z > 0) findSolidUnder(blocks, x, z - 1, caveCeil - 1) else caveFloor
                val heightShading = getHeightShading(caveFloor, west, north)

                // Modern SubChunkParser does not expose Bedrock block-light palettes.
                // Match Blocktopograph's non-light-aware fallback for this format.
                val lightShading = 2.0f
                val thickness = (caveCeil - caveFloor).toFloat() / HEIGHT + 0.5f
                val shading = heightShading * lightShading * thickness + 0.5f
                shadingSum += shading

                var remainingAlpha = 1f
                var y = caveCeil
                while (y >= caveFloor) {
                    val name = blocks[x][z][y]
                    if (!isAir(name) && !BlockColors.isMapTransparent(name)) {
                        val color = BlockColors.colorForDimension(name, 1)
                        val af = Color.alpha(color) / 255f
                        if (af > 0f) {
                            val rf = Color.red(color) / 255f
                            val gf = Color.green(color) / 255f
                            val bf = Color.blue(color) / 255f
                            val contribution = remainingAlpha * af
                            sumR += contribution * rf * shading
                            sumG += contribution * gf * shading
                            sumB += contribution * bf * shading
                            remainingAlpha *= 1f - af
                            if (af >= 1f) break
                        }
                    }
                    y--
                }

                lastSurfaceY = caveFloor
                val layerName = blocks[x][z][caveFloor]
                if (!isAir(layerName)) lastSurfaceName = layerName
                caveFloorStart = caveFloor
                layers++
            }

            val avgShading = if (layers > 0) shadingSum / layers else 1f
            var r = ((avgShading * alpha) / layers * 255f).toInt()
            var g = ((avgShading * sumR) / layers * 255f).toInt()
            var b = ((avgShading * sumG) / layers * 255f).toInt()

            // The legacy renderer's accumulators are effectively RGB channels after
            // the first slice. Keep the final result useful for our opaque map palette.
            // If no alpha was accumulated, derive the color from the most useful layer.
            if (sumR == 0f && sumG == 0f && sumB == 0f) {
                val fallbackY = lastSurfaceY.coerceIn(0, HEIGHT - 1)
                val fallbackName = blocks[x][z][fallbackY]
                val fallbackColor = BlockColors.colorForDimension(fallbackName, 1)
                r = Color.red(fallbackColor)
                g = Color.green(fallbackColor)
                b = Color.blue(fallbackColor)
            } else {
                // The original decompile uses a as the red accumulator. Our explicit
                // accumulators are clearer and preserve all three channels.
                r = ((avgShading * sumR) / layers * 255f).toInt()
                g = ((avgShading * sumG) / layers * 255f).toInt()
                b = ((avgShading * sumB) / layers * 255f).toInt()
            }

            r = r.coerceIn(0, 255)
            g = g.coerceIn(0, 255)
            b = b.coerceIn(0, 255)

            // Blocktopograph-style points of interest. Highest worth wins.
            // Only the occupied part of the column is scanned, and block names are
            // classified through a cache instead of allocating a substring per block.
            val topY = heightMap[x][z]
            var lastName: String? = null
            for (y in 0..topY) {
                val name = blocks[x][z][y]
                if (name === lastName || name == AIR) continue
                lastName = name
                when (poiCode(name)) {
                    1 -> { r = 255; g = 255; b = 255 }
                    2 -> if (worth < 90) { r = 240; g = 40; b = 170; worth = 90 }
                    3 -> if (worth < 80) { r = 120; g = 170; b = 120; worth = 80 }
                    4 -> if (worth < 95) { r = 60; g = 0; b = 170; worth = 95 }
                }
            }

            grid[x][z] = ColumnInfo(Color.rgb(r, g, b), lastSurfaceY.coerceAtLeast(0), lastSurfaceName)
        }

        return grid
    }

    private val poiCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private fun poiCode(name: String): Int = poiCache.getOrPut(name) {
        when (name.substringAfter(':')) {
            "spawner" -> 1
            "chest", "trapped_chest" -> 2
            "nether_wart" -> 3
            "portal" -> 4
            else -> 0
        }
    }

    private fun isAir(name: String): Boolean = name == AIR || (name.endsWith(":air") && name.substringAfter(':') == "air")

    private fun findAirUnder(blocks: Array<Array<Array<String>>>, x: Int, z: Int, startY: Int): Int {
        var y = startY.coerceAtMost(HEIGHT - 1)
        while (y >= 0) {
            if (isAir(blocks[x][z][y])) return y
            y--
        }
        return -1
    }

    private fun findSolidUnder(blocks: Array<Array<Array<String>>>, x: Int, z: Int, startY: Int): Int {
        var y = startY.coerceAtMost(HEIGHT - 1)
        while (y >= 0) {
            if (!isAir(blocks[x][z][y])) return y
            y--
        }
        return -1
    }

    private fun getHeightShading(height: Int, heightW: Int, heightN: Int): Float {
        var samples = 0
        var heightDiff = 0f
        if (heightW > 0) {
            heightDiff += height - heightW
            samples++
        }
        if (heightN > 0) {
            heightDiff += height - heightN
            samples++
        }
        val scaled = heightDiff.toDouble() * Math.pow(1.0499999523162842, samples.toDouble())
        return ((atan(scaled) / PI).toFloat() * 0.8f) + 1f
    }
}
