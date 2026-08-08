package com.spaimic.nbttool.chunk

import android.graphics.Color

/** Bedrock map tint approximation based on the biome stored in Data3D. */
object BiomeTint {
    private const val PLAINS = 1
    private const val DESERT = 2
    private const val FOREST = 4
    private const val TAIGA = 5
    private const val SWAMP = 6
    private const val RIVER = 7
    private const val FROZEN_OCEAN = 10
    private const val FROZEN_RIVER = 11
    private const val ICE_PLAINS = 12
    private const val JUNGLE = 21
    private const val JUNGLE_EDGE = 23
    private const val BIRCH = 27
    private const val ROOFED_FOREST = 29
    private const val COLD_TAIGA = 30
    private const val MEGA_TAIGA = 32
    private const val SAVANNA = 35
    private const val MESA = 37
    private const val MESA_STONE = 38
    private const val MESA_DESERT = 39
    private const val DEEP_OCEAN = 24
    private const val OCEAN = 0
    // Bedrock's current Cherry Grove numeric biome ID.
    private const val CHERRY_GROVE = 192

    enum class Kind { DEFAULT, BIRCH, EVERGREEN, DRY, GRASS, WATER, COLD, CHERRY }

    fun kindFor(id: Int): Kind = when (id) {
        CHERRY_GROVE -> Kind.CHERRY
        BIRCH -> Kind.BIRCH
        TAIGA, COLD_TAIGA, MEGA_TAIGA -> Kind.EVERGREEN
        DESERT, SAVANNA, MESA, MESA_STONE, MESA_DESERT -> Kind.DRY
        OCEAN, DEEP_OCEAN, RIVER, FROZEN_OCEAN, FROZEN_RIVER -> Kind.WATER
        ICE_PLAINS -> Kind.COLD
        FOREST, JUNGLE, JUNGLE_EDGE, ROOFED_FOREST -> Kind.DEFAULT
        else -> Kind.DEFAULT
    }

    /** Applies the visual tint Minecraft uses for common Bedrock map-tinted blocks. */
    fun apply(blockName: String, base: Int, biomeId: Int?): Int {
        val name = blockName.substringAfter(':')
        if (name == "cherry_leaves" || name == "flowering_azalea_leaves") return base
        val kind = kindFor(biomeId ?: PLAINS)
        val tint = when {
            name == "water" || name == "flowing_water" -> when (kind) {
                Kind.WATER -> Color.rgb(45, 105, 235)
                Kind.COLD -> Color.rgb(55, 105, 225)
                Kind.DRY -> Color.rgb(35, 120, 230)
                else -> Color.rgb(45, 100, 230)
            }
            name.endsWith("_leaves") || name == "vine" || name == "azalea_leaves" -> when (kind) {
                Kind.BIRCH -> Color.rgb(128, 167, 85)
                Kind.EVERGREEN -> Color.rgb(57, 88, 57)
                Kind.DRY -> Color.rgb(112, 145, 52)
                Kind.CHERRY -> Color.rgb(242, 127, 165)
                else -> Color.rgb(82, 125, 48)
            }
            name == "grass_block" || name == "grass" || name == "short_grass" || name == "tall_grass" ||
                name == "fern" || name == "large_fern" || name == "moss_block" -> when (kind) {
                Kind.DRY -> Color.rgb(177, 171, 74)
                Kind.COLD -> Color.rgb(130, 170, 105)
                else -> Color.rgb(146, 188, 88)
            }
            else -> return base
        }
        // Multiply base map color by the biome tint, preserving the block's own hue.
        return Color.rgb(
            Color.red(base) * Color.red(tint) / 255,
            Color.green(base) * Color.green(tint) / 255,
            Color.blue(base) * Color.blue(tint) / 255
        )
    }
}
