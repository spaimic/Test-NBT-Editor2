package com.spaimic.nbttool.chunk

import android.graphics.Color

/**
 * Rough block-name -> color, in the spirit of Minecraft's own in-game map
 * palette but far less exhaustive — covers common overworld/nether/end terrain.
 * Anything not listed falls back to a neutral "solid, unknown" color rather
 * than guessing wrong or crashing. Not a pixel-perfect match to the real map
 * item's colors, which use per-block-state shading Mojang has never published.
 */
object BlockColors {

    private val table: Map<String, Int> = mapOf(
        "minecraft:grass" to Color.rgb(86, 149, 55),
        "minecraft:grass_block" to Color.rgb(86, 149, 55),
        "minecraft:tallgrass" to Color.rgb(86, 149, 55),
        "minecraft:dirt" to Color.rgb(134, 96, 67),
        "minecraft:coarse_dirt" to Color.rgb(120, 85, 60),
        "minecraft:podzol" to Color.rgb(101, 76, 43),
        "minecraft:farmland" to Color.rgb(107, 77, 51),
        "minecraft:path" to Color.rgb(151, 127, 84),
        "minecraft:grass_path" to Color.rgb(151, 127, 84),
        "minecraft:stone" to Color.rgb(125, 125, 125),
        "minecraft:deepslate" to Color.rgb(80, 80, 84),
        "minecraft:cobblestone" to Color.rgb(115, 115, 115),
        "minecraft:mossy_cobblestone" to Color.rgb(100, 118, 95),
        "minecraft:sand" to Color.rgb(219, 207, 163),
        "minecraft:red_sand" to Color.rgb(190, 101, 33),
        "minecraft:sandstone" to Color.rgb(216, 203, 155),
        "minecraft:gravel" to Color.rgb(136, 126, 126),
        "minecraft:water" to Color.rgb(64, 100, 192),
        "minecraft:flowing_water" to Color.rgb(64, 100, 192),
        "minecraft:lava" to Color.rgb(207, 92, 15),
        "minecraft:flowing_lava" to Color.rgb(207, 92, 15),
        "minecraft:snow" to Color.rgb(240, 240, 240),
        "minecraft:snow_layer" to Color.rgb(240, 240, 240),
        "minecraft:ice" to Color.rgb(160, 188, 255),
        "minecraft:packed_ice" to Color.rgb(150, 178, 245),
        "minecraft:blue_ice" to Color.rgb(120, 160, 240),
        "minecraft:clay" to Color.rgb(160, 166, 179),
        "minecraft:mycelium" to Color.rgb(111, 98, 100),
        "minecraft:netherrack" to Color.rgb(112, 54, 53),
        "minecraft:soul_sand" to Color.rgb(84, 64, 51),
        "minecraft:soul_soil" to Color.rgb(75, 60, 51),
        "minecraft:crimson_nylium" to Color.rgb(150, 40, 40),
        "minecraft:warped_nylium" to Color.rgb(40, 130, 120),
        "minecraft:end_stone" to Color.rgb(219, 219, 172),
        "minecraft:oak_log" to Color.rgb(102, 81, 51),
        "minecraft:log" to Color.rgb(102, 81, 51),
        "minecraft:oak_leaves" to Color.rgb(59, 105, 36),
        "minecraft:spruce_leaves" to Color.rgb(48, 84, 47),
        "minecraft:birch_leaves" to Color.rgb(96, 122, 60),
        "minecraft:leaves" to Color.rgb(59, 105, 36),
        "minecraft:leaves2" to Color.rgb(59, 105, 36),
        "minecraft:oak_planks" to Color.rgb(157, 128, 79),
        "minecraft:planks" to Color.rgb(157, 128, 79),
        "minecraft:bedrock" to Color.rgb(40, 40, 40),
        "minecraft:obsidian" to Color.rgb(20, 18, 28),
        "minecraft:crying_obsidian" to Color.rgb(40, 15, 60),
        "minecraft:coal_ore" to Color.rgb(90, 90, 90),
        "minecraft:iron_ore" to Color.rgb(150, 130, 110),
        "minecraft:gold_ore" to Color.rgb(190, 165, 60),
        "minecraft:diamond_ore" to Color.rgb(110, 190, 190),
        "minecraft:emerald_ore" to Color.rgb(80, 180, 100),
        "minecraft:lapis_ore" to Color.rgb(60, 90, 170),
        "minecraft:redstone_ore" to Color.rgb(160, 50, 40),
        "minecraft:magma" to Color.rgb(150, 70, 30),
        "minecraft:basalt" to Color.rgb(80, 80, 88),
        "minecraft:blackstone" to Color.rgb(50, 47, 51),
        "minecraft:calcite" to Color.rgb(224, 224, 216),
        "minecraft:tuff" to Color.rgb(108, 110, 100),
        "minecraft:dripstone_block" to Color.rgb(140, 105, 85),
        "minecraft:moss_block" to Color.rgb(89, 121, 46)
    )

    val fallbackSolid: Int = Color.rgb(120, 120, 120)   // a recognized-solid but not-in-the-table block
    val fallbackUnloaded: Int = Color.rgb(55, 55, 55)   // no chunk data at all
    val fallbackUnparsed: Int = Color.rgb(90, 90, 90)   // chunk exists but its subchunk format wasn't decodable

    fun colorFor(blockName: String): Int = table[blockName] ?: fallbackSolid
}
