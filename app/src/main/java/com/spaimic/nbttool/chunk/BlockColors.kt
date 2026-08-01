package com.spaimic.nbttool.chunk

import android.graphics.Color

/**
 * Block-name -> map color. Minecraft has 1000+ block identifiers, but most of
 * that count is repetition — 16 dye colors × (wool/concrete/terracotta/glass/
 * carpet/etc), a dozen wood types × (planks/log/fence/door/etc), redstone
 * on/off variants, stairs/slabs/walls of the same material, and so on. Rather
 * than hand-type every single one, the big repeating families are generated
 * from a small color table below; everything else is curated by hand.
 * Anything still missing falls back to a neutral "solid, unknown" color.
 */
object BlockColors {

    // ---- dye-color families: wool, concrete, terracotta, glass, carpet, etc. ----
    // all come in the same 16 colors, so generate them instead of typing ~190 entries.

    private data class Dye(val id: String, val rgb: Int)

    private val DYES = listOf(
        Dye("white", Color.rgb(234, 236, 236)),
        Dye("orange", Color.rgb(241, 118, 20)),
        Dye("magenta", Color.rgb(189, 68, 179)),
        Dye("light_blue", Color.rgb(58, 175, 217)),
        Dye("yellow", Color.rgb(249, 198, 40)),
        Dye("lime", Color.rgb(112, 185, 26)),
        Dye("pink", Color.rgb(237, 141, 173)),
        Dye("gray", Color.rgb(62, 68, 71)),
        Dye("light_gray", Color.rgb(142, 142, 135)),
        Dye("cyan", Color.rgb(21, 138, 145)),
        Dye("purple", Color.rgb(121, 43, 173)),
        Dye("blue", Color.rgb(53, 58, 159)),
        Dye("brown", Color.rgb(114, 72, 41)),
        Dye("green", Color.rgb(85, 110, 28)),
        Dye("red", Color.rgb(161, 40, 35)),
        Dye("black", Color.rgb(21, 21, 26))
    )

    /** Muted toward gray/brown — terracotta and glazed terracotta read as more earthy than pure wool/dye colors. */
    private fun muted(rgb: Int): Int {
        val r = (Color.red(rgb) * 0.75 + 90 * 0.25).toInt()
        val g = (Color.green(rgb) * 0.75 + 75 * 0.25).toInt()
        val b = (Color.blue(rgb) * 0.75 + 65 * 0.25).toInt()
        return Color.rgb(r, g, b)
    }

    /** Lightened/washed out — stained glass is translucent, so it reads lighter than a solid dye color. */
    private fun lightened(rgb: Int): Int {
        val r = (Color.red(rgb) + 255) / 2
        val g = (Color.green(rgb) + 255) / 2
        val b = (Color.blue(rgb) + 255) / 2
        return Color.rgb(r, g, b)
    }

    private fun dyeFamily(): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (d in DYES) {
            out["minecraft:${d.id}_wool"] = d.rgb
            out["minecraft:${d.id}_carpet"] = d.rgb
            out["minecraft:${d.id}_concrete"] = d.rgb
            out["minecraft:${d.id}_concrete_powder"] = lightened(d.rgb)
            out["minecraft:${d.id}_terracotta"] = muted(d.rgb)
            out["minecraft:${d.id}_glazed_terracotta"] = muted(d.rgb)
            out["minecraft:${d.id}_stained_glass"] = lightened(d.rgb)
            out["minecraft:${d.id}_stained_glass_pane"] = lightened(d.rgb)
            out["minecraft:${d.id}_shulker_box"] = d.rgb
            out["minecraft:${d.id}_candle"] = d.rgb
            out["minecraft:${d.id}_bed"] = d.rgb
            out["minecraft:${d.id}_banner"] = d.rgb
        }
        return out
    }

    // ---- wood-type families: planks/log/fence/door/etc share one color per wood type ----

    private data class Wood(val id: String, val planksRgb: Int, val logRgb: Int, val leavesRgb: Int? = null)

    private val WOODS = listOf(
        Wood("oak", Color.rgb(157, 128, 79), Color.rgb(102, 81, 51), Color.rgb(59, 105, 36)),
        Wood("spruce", Color.rgb(114, 84, 48), Color.rgb(66, 49, 30), Color.rgb(48, 84, 47)),
        Wood("birch", Color.rgb(192, 175, 121), Color.rgb(197, 191, 172), Color.rgb(96, 122, 60)),
        Wood("jungle", Color.rgb(160, 115, 80), Color.rgb(85, 67, 40), Color.rgb(65, 115, 35)),
        Wood("acacia", Color.rgb(168, 90, 50), Color.rgb(103, 90, 79), Color.rgb(97, 128, 51)),
        Wood("dark_oak", Color.rgb(66, 43, 20), Color.rgb(60, 46, 27), Color.rgb(52, 88, 33)),
        Wood("mangrove", Color.rgb(117, 54, 48), Color.rgb(84, 60, 60), Color.rgb(65, 122, 68)),
        Wood("cherry", Color.rgb(227, 168, 193), Color.rgb(147, 94, 90), Color.rgb(233, 192, 210)),
        Wood("bamboo", Color.rgb(193, 168, 80), Color.rgb(163, 150, 65), Color.rgb(103, 138, 46)),
        Wood("crimson", Color.rgb(110, 49, 58), Color.rgb(92, 46, 62), null),
        Wood("warped", Color.rgb(46, 110, 105), Color.rgb(58, 94, 91), null)
    )

    private fun woodFamily(): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (w in WOODS) {
            val stemOrLog = if (w.id == "crimson" || w.id == "warped") "stem" else "log"
            out["minecraft:${w.id}_planks"] = w.planksRgb
            out["minecraft:${w.id}_$stemOrLog"] = w.logRgb
            out["minecraft:stripped_${w.id}_$stemOrLog"] = w.planksRgb
            out["minecraft:${w.id}_fence"] = w.planksRgb
            out["minecraft:${w.id}_fence_gate"] = w.planksRgb
            out["minecraft:${w.id}_door"] = w.planksRgb
            out["minecraft:${w.id}_trapdoor"] = w.planksRgb
            out["minecraft:${w.id}_slab"] = w.planksRgb
            out["minecraft:${w.id}_stairs"] = w.planksRgb
            out["minecraft:${w.id}_button"] = w.planksRgb
            out["minecraft:${w.id}_pressure_plate"] = w.planksRgb
            out["minecraft:${w.id}_sign"] = w.planksRgb
            out["minecraft:${w.id}_hanging_sign"] = w.planksRgb
            if (w.leavesRgb != null) out["minecraft:${w.id}_leaves"] = w.leavesRgb
        }
        return out
    }

    // ---- copper: several oxidation stages, each with waxed/unwaxed and cut/block/etc variants ----

    private fun copperFamily(): Map<String, Int> {
        // (block-name stage id, cut-variant stage id, color) — the "cut_" prefix comes
        // BEFORE the oxidation stage name for anything past plain copper, e.g.
        // "exposed_cut_copper", not "cut_exposed_copper".
        val stages = listOf(
            Triple("copper", "cut_copper", Color.rgb(201, 111, 76)),
            Triple("exposed_copper", "exposed_cut_copper", Color.rgb(153, 135, 113)),
            Triple("weathered_copper", "weathered_cut_copper", Color.rgb(109, 152, 118)),
            Triple("oxidized_copper", "oxidized_cut_copper", Color.rgb(82, 162, 132))
        )
        val out = HashMap<String, Int>()
        for ((blockId, cutId, rgb) in stages) {
            for (prefix in listOf("", "waxed_")) {
                out["minecraft:$prefix${blockId}_block"] = rgb
                out["minecraft:$prefix$blockId"] = rgb
                out["minecraft:$prefix$cutId"] = rgb
                out["minecraft:${prefix}${cutId}_stairs"] = rgb
                out["minecraft:${prefix}${cutId}_slab"] = rgb
                out["minecraft:${prefix}chiseled_$cutId"] = rgb
            }
        }
        return out
    }

    // ---- everything else: curated by hand ----

    private val curated: Map<String, Int> = mapOf(
        // terrain
        "minecraft:grass" to Color.rgb(86, 149, 55),
        "minecraft:grass_block" to Color.rgb(86, 149, 55),
        "minecraft:tallgrass" to Color.rgb(86, 149, 55),
        "minecraft:short_grass" to Color.rgb(86, 149, 55),
        "minecraft:tall_grass" to Color.rgb(86, 149, 55),
        "minecraft:fern" to Color.rgb(89, 138, 62),
        "minecraft:large_fern" to Color.rgb(89, 138, 62),
        "minecraft:dirt" to Color.rgb(134, 96, 67),
        "minecraft:coarse_dirt" to Color.rgb(120, 85, 60),
        "minecraft:rooted_dirt" to Color.rgb(128, 92, 64),
        "minecraft:mud" to Color.rgb(60, 56, 60),
        "minecraft:muddy_mangrove_roots" to Color.rgb(70, 60, 55),
        "minecraft:mangrove_roots" to Color.rgb(90, 65, 55),
        "minecraft:podzol" to Color.rgb(101, 76, 43),
        "minecraft:farmland" to Color.rgb(107, 77, 51),
        "minecraft:path" to Color.rgb(151, 127, 84),
        "minecraft:grass_path" to Color.rgb(151, 127, 84),
        "minecraft:dirt_path" to Color.rgb(151, 127, 84),

        // stone family
        "minecraft:stone" to Color.rgb(125, 125, 125),
        "minecraft:granite" to Color.rgb(149, 103, 85),
        "minecraft:polished_granite" to Color.rgb(154, 108, 90),
        "minecraft:diorite" to Color.rgb(188, 188, 188),
        "minecraft:polished_diorite" to Color.rgb(193, 193, 193),
        "minecraft:andesite" to Color.rgb(132, 133, 132),
        "minecraft:polished_andesite" to Color.rgb(136, 138, 136),
        "minecraft:deepslate" to Color.rgb(80, 80, 84),
        "minecraft:cobbled_deepslate" to Color.rgb(76, 76, 80),
        "minecraft:polished_deepslate" to Color.rgb(70, 70, 74),
        "minecraft:deepslate_bricks" to Color.rgb(68, 68, 72),
        "minecraft:deepslate_tiles" to Color.rgb(63, 63, 67),
        "minecraft:cobblestone" to Color.rgb(115, 115, 115),
        "minecraft:mossy_cobblestone" to Color.rgb(100, 118, 95),
        "minecraft:mossy_stone_bricks" to Color.rgb(115, 128, 108),
        "minecraft:stone_bricks" to Color.rgb(122, 122, 122),
        "minecraft:cracked_stone_bricks" to Color.rgb(118, 118, 118),
        "minecraft:chiseled_stone_bricks" to Color.rgb(124, 124, 124),
        "minecraft:smooth_stone" to Color.rgb(160, 160, 160),
        "minecraft:calcite" to Color.rgb(224, 224, 216),
        "minecraft:tuff" to Color.rgb(108, 110, 100),
        "minecraft:dripstone_block" to Color.rgb(140, 105, 85),
        "minecraft:pointed_dripstone" to Color.rgb(133, 100, 82),

        // sand / gravel / clay
        "minecraft:sand" to Color.rgb(219, 207, 163),
        "minecraft:red_sand" to Color.rgb(190, 101, 33),
        "minecraft:sandstone" to Color.rgb(216, 203, 155),
        "minecraft:chiseled_sandstone" to Color.rgb(214, 201, 153),
        "minecraft:smooth_sandstone" to Color.rgb(218, 205, 157),
        "minecraft:cut_sandstone" to Color.rgb(216, 203, 155),
        "minecraft:red_sandstone" to Color.rgb(180, 96, 34),
        "minecraft:chiseled_red_sandstone" to Color.rgb(178, 94, 32),
        "minecraft:smooth_red_sandstone" to Color.rgb(182, 98, 36),
        "minecraft:cut_red_sandstone" to Color.rgb(180, 96, 34),
        "minecraft:gravel" to Color.rgb(136, 126, 126),
        "minecraft:clay" to Color.rgb(160, 166, 179),

        // liquids
        "minecraft:water" to Color.rgb(64, 100, 192),
        "minecraft:flowing_water" to Color.rgb(64, 100, 192),
        "minecraft:lava" to Color.rgb(207, 92, 15),
        "minecraft:flowing_lava" to Color.rgb(207, 92, 15),

        // ice / snow
        "minecraft:snow" to Color.rgb(240, 240, 240),
        "minecraft:snow_layer" to Color.rgb(240, 240, 240),
        "minecraft:powder_snow" to Color.rgb(233, 241, 246),
        "minecraft:ice" to Color.rgb(160, 188, 255),
        "minecraft:packed_ice" to Color.rgb(150, 178, 245),
        "minecraft:blue_ice" to Color.rgb(120, 160, 240),
        "minecraft:frosted_ice" to Color.rgb(155, 185, 250),

        // nether
        "minecraft:netherrack" to Color.rgb(112, 54, 53),
        "minecraft:soul_sand" to Color.rgb(84, 64, 51),
        "minecraft:soul_soil" to Color.rgb(75, 60, 51),
        "minecraft:crimson_nylium" to Color.rgb(150, 40, 40),
        "minecraft:warped_nylium" to Color.rgb(40, 130, 120),
        "minecraft:nether_bricks" to Color.rgb(45, 20, 24),
        "minecraft:cracked_nether_bricks" to Color.rgb(42, 18, 22),
        "minecraft:chiseled_nether_bricks" to Color.rgb(47, 21, 25),
        "minecraft:red_nether_bricks" to Color.rgb(70, 8, 10),
        "minecraft:nether_wart_block" to Color.rgb(114, 20, 20),
        "minecraft:warped_wart_block" to Color.rgb(20, 138, 130),
        "minecraft:nether_gold_ore" to Color.rgb(130, 68, 50),
        "minecraft:nether_quartz_ore" to Color.rgb(120, 75, 68),
        "minecraft:ancient_debris" to Color.rgb(98, 77, 68),
        "minecraft:glowstone" to Color.rgb(171, 131, 84),
        "minecraft:shroomlight" to Color.rgb(240, 149, 68),
        "minecraft:magma" to Color.rgb(150, 70, 30),
        "minecraft:magma_block" to Color.rgb(150, 70, 30),
        "minecraft:basalt" to Color.rgb(80, 80, 88),
        "minecraft:polished_basalt" to Color.rgb(94, 94, 100),
        "minecraft:smooth_basalt" to Color.rgb(84, 84, 90),
        "minecraft:blackstone" to Color.rgb(50, 47, 51),
        "minecraft:polished_blackstone" to Color.rgb(58, 54, 59),
        "minecraft:polished_blackstone_bricks" to Color.rgb(53, 50, 54),
        "minecraft:chiseled_polished_blackstone" to Color.rgb(56, 52, 57),
        "minecraft:gilded_blackstone" to Color.rgb(96, 78, 47),
        "minecraft:crimson_fungus" to Color.rgb(150, 30, 30),
        "minecraft:warped_fungus" to Color.rgb(20, 120, 110),
        "minecraft:weeping_vines" to Color.rgb(130, 20, 25),
        "minecraft:twisting_vines" to Color.rgb(20, 130, 110),

        // end
        "minecraft:end_stone" to Color.rgb(219, 219, 172),
        "minecraft:end_stone_bricks" to Color.rgb(216, 216, 169),
        "minecraft:purpur_block" to Color.rgb(169, 125, 169),
        "minecraft:purpur_pillar" to Color.rgb(172, 128, 172),
        "minecraft:chorus_plant" to Color.rgb(97, 68, 97),
        "minecraft:chorus_flower" to Color.rgb(151, 128, 151),
        "minecraft:end_rod" to Color.rgb(210, 200, 190),
        "minecraft:obsidian" to Color.rgb(20, 18, 28),
        "minecraft:crying_obsidian" to Color.rgb(40, 15, 60),

        // ores
        "minecraft:coal_ore" to Color.rgb(90, 90, 90),
        "minecraft:deepslate_coal_ore" to Color.rgb(70, 70, 74),
        "minecraft:iron_ore" to Color.rgb(150, 130, 110),
        "minecraft:deepslate_iron_ore" to Color.rgb(120, 108, 100),
        "minecraft:copper_ore" to Color.rgb(170, 120, 90),
        "minecraft:deepslate_copper_ore" to Color.rgb(130, 100, 90),
        "minecraft:gold_ore" to Color.rgb(190, 165, 60),
        "minecraft:deepslate_gold_ore" to Color.rgb(150, 135, 90),
        "minecraft:diamond_ore" to Color.rgb(110, 190, 190),
        "minecraft:deepslate_diamond_ore" to Color.rgb(95, 155, 155),
        "minecraft:emerald_ore" to Color.rgb(80, 180, 100),
        "minecraft:deepslate_emerald_ore" to Color.rgb(70, 145, 95),
        "minecraft:lapis_ore" to Color.rgb(60, 90, 170),
        "minecraft:deepslate_lapis_ore" to Color.rgb(65, 85, 140),
        "minecraft:redstone_ore" to Color.rgb(160, 50, 40),
        "minecraft:deepslate_redstone_ore" to Color.rgb(125, 55, 48),

        // metal / mineral blocks
        "minecraft:iron_block" to Color.rgb(216, 216, 216),
        "minecraft:gold_block" to Color.rgb(247, 219, 76),
        "minecraft:diamond_block" to Color.rgb(98, 219, 213),
        "minecraft:emerald_block" to Color.rgb(60, 174, 91),
        "minecraft:lapis_block" to Color.rgb(38, 66, 145),
        "minecraft:redstone_block" to Color.rgb(172, 32, 25),
        "minecraft:coal_block" to Color.rgb(24, 24, 24),
        "minecraft:netherite_block" to Color.rgb(66, 61, 62),
        "minecraft:raw_iron_block" to Color.rgb(180, 133, 105),
        "minecraft:raw_gold_block" to Color.rgb(222, 175, 61),
        "minecraft:raw_copper_block" to Color.rgb(151, 96, 70),
        "minecraft:amethyst_block" to Color.rgb(122, 92, 178),
        "minecraft:budding_amethyst" to Color.rgb(122, 92, 178),
        "minecraft:quartz_block" to Color.rgb(235, 229, 219),
        "minecraft:chiseled_quartz_block" to Color.rgb(233, 227, 217),
        "minecraft:quartz_pillar" to Color.rgb(236, 230, 220),
        "minecraft:smooth_quartz" to Color.rgb(237, 231, 221),
        "minecraft:quartz_bricks" to Color.rgb(234, 228, 218),

        // wood extras
        "minecraft:bedrock" to Color.rgb(40, 40, 40),
        "minecraft:mycelium" to Color.rgb(111, 98, 100),
        "minecraft:moss_block" to Color.rgb(89, 121, 46),
        "minecraft:moss_carpet" to Color.rgb(89, 121, 46),
        "minecraft:bookshelf" to Color.rgb(134, 108, 66),
        "minecraft:hay_block" to Color.rgb(196, 165, 30),
        "minecraft:melon_block" to Color.rgb(108, 148, 38),
        "minecraft:pumpkin" to Color.rgb(198, 118, 30),
        "minecraft:carved_pumpkin" to Color.rgb(198, 118, 30),
        "minecraft:jack_o_lantern" to Color.rgb(206, 128, 30),
        "minecraft:sponge" to Color.rgb(197, 194, 78),
        "minecraft:wet_sponge" to Color.rgb(172, 172, 60),
        "minecraft:glass" to Color.rgb(220, 235, 235),
        "minecraft:glass_pane" to Color.rgb(220, 235, 235),
        "minecraft:tinted_glass" to Color.rgb(60, 55, 66),
        "minecraft:sea_lantern" to Color.rgb(172, 199, 190),
        "minecraft:prismarine" to Color.rgb(99, 156, 151),
        "minecraft:prismarine_bricks" to Color.rgb(88, 168, 154),
        "minecraft:dark_prismarine" to Color.rgb(62, 98, 79),
        "minecraft:sea_pickle" to Color.rgb(160, 175, 60),
        "minecraft:kelp" to Color.rgb(63, 111, 46),
        "minecraft:seagrass" to Color.rgb(60, 130, 60),
        "minecraft:sculk" to Color.rgb(15, 30, 38),
        "minecraft:sculk_catalyst" to Color.rgb(30, 45, 50),
        "minecraft:sculk_sensor" to Color.rgb(24, 60, 61),
        "minecraft:sculk_vein" to Color.rgb(20, 38, 45),

        // redstone / mechanisms
        "minecraft:redstone_wire" to Color.rgb(170, 30, 20),
        "minecraft:redstone_lamp" to Color.rgb(102, 68, 36),
        "minecraft:lit_redstone_lamp" to Color.rgb(250, 220, 160),
        "minecraft:lever" to Color.rgb(140, 140, 140),
        "minecraft:tripwire_hook" to Color.rgb(140, 140, 140),
        "minecraft:tripwire" to Color.rgb(180, 180, 180),
        "minecraft:observer" to Color.rgb(100, 100, 100),
        "minecraft:piston" to Color.rgb(140, 120, 90),
        "minecraft:sticky_piston" to Color.rgb(110, 140, 70),
        "minecraft:dispenser" to Color.rgb(105, 105, 105),
        "minecraft:dropper" to Color.rgb(105, 105, 105),
        "minecraft:hopper" to Color.rgb(70, 70, 70),
        "minecraft:rail" to Color.rgb(150, 150, 150),
        "minecraft:golden_rail" to Color.rgb(200, 175, 90),
        "minecraft:detector_rail" to Color.rgb(160, 130, 90),
        "minecraft:activator_rail" to Color.rgb(140, 90, 60),
        "minecraft:tnt" to Color.rgb(200, 60, 40),
        "minecraft:target" to Color.rgb(230, 230, 220),
        "minecraft:lodestone" to Color.rgb(120, 122, 128),
        "minecraft:crafting_table" to Color.rgb(134, 94, 56),
        "minecraft:furnace" to Color.rgb(112, 112, 112),
        "minecraft:lit_furnace" to Color.rgb(120, 100, 80),
        "minecraft:smoker" to Color.rgb(105, 105, 105),
        "minecraft:blast_furnace" to Color.rgb(100, 100, 105),
        "minecraft:anvil" to Color.rgb(60, 60, 60),
        "minecraft:grindstone" to Color.rgb(130, 128, 122),
        "minecraft:cauldron" to Color.rgb(70, 70, 74),
        "minecraft:beacon" to Color.rgb(110, 210, 200),
        "minecraft:conduit" to Color.rgb(90, 170, 165),
        "minecraft:barrier" to Color.rgb(255, 0, 0),
        "minecraft:structure_void" to Color.rgb(255, 0, 255),
        "minecraft:command_block" to Color.rgb(196, 130, 90),
        "minecraft:chain_command_block" to Color.rgb(90, 145, 100),
        "minecraft:repeating_command_block" to Color.rgb(130, 90, 145),
        "minecraft:jukebox" to Color.rgb(107, 63, 46),
        "minecraft:note_block" to Color.rgb(106, 74, 51),
        "minecraft:composter" to Color.rgb(120, 90, 55),
        "minecraft:cake" to Color.rgb(245, 240, 230),
        "minecraft:scaffolding" to Color.rgb(195, 158, 96),
        "minecraft:ladder" to Color.rgb(133, 100, 62),
        "minecraft:chest" to Color.rgb(134, 94, 56),
        "minecraft:trapped_chest" to Color.rgb(134, 94, 56),
        "minecraft:ender_chest" to Color.rgb(40, 55, 55),
        "minecraft:barrel" to Color.rgb(118, 87, 51),
        "minecraft:loom" to Color.rgb(150, 120, 80),
        "minecraft:cartography_table" to Color.rgb(120, 95, 65),
        "minecraft:fletching_table" to Color.rgb(210, 205, 190),
        "minecraft:smithing_table" to Color.rgb(70, 65, 75),
        "minecraft:stonecutter" to Color.rgb(120, 120, 120),
        "minecraft:brewing_stand" to Color.rgb(100, 100, 105),
        "minecraft:respawn_anchor" to Color.rgb(60, 40, 90),

        // crops / plants
        "minecraft:wheat" to Color.rgb(200, 180, 80),
        "minecraft:carrots" to Color.rgb(90, 160, 50),
        "minecraft:potatoes" to Color.rgb(90, 150, 55),
        "minecraft:beetroot" to Color.rgb(110, 150, 60),
        "minecraft:pumpkin_stem" to Color.rgb(120, 150, 50),
        "minecraft:melon_stem" to Color.rgb(120, 150, 50),
        "minecraft:sugar_cane" to Color.rgb(130, 175, 90),
        "minecraft:bamboo" to Color.rgb(103, 158, 46),
        "minecraft:cactus" to Color.rgb(75, 115, 45),
        "minecraft:vine" to Color.rgb(60, 100, 40),
        "minecraft:lily_pad" to Color.rgb(40, 105, 50),
        "minecraft:dandelion" to Color.rgb(200, 180, 40),
        "minecraft:poppy" to Color.rgb(180, 40, 35),
        "minecraft:blue_orchid" to Color.rgb(60, 150, 200),
        "minecraft:allium" to Color.rgb(160, 110, 200),
        "minecraft:azure_bluet" to Color.rgb(220, 220, 210),
        "minecraft:red_tulip" to Color.rgb(180, 50, 40),
        "minecraft:orange_tulip" to Color.rgb(210, 120, 40),
        "minecraft:white_tulip" to Color.rgb(220, 220, 210),
        "minecraft:pink_tulip" to Color.rgb(220, 170, 190),
        "minecraft:oxeye_daisy" to Color.rgb(210, 210, 190),
        "minecraft:cornflower" to Color.rgb(70, 90, 180),
        "minecraft:lily_of_the_valley" to Color.rgb(230, 230, 220),
        "minecraft:sunflower" to Color.rgb(210, 180, 40),
        "minecraft:lilac" to Color.rgb(170, 130, 180),
        "minecraft:rose_bush" to Color.rgb(150, 30, 30),
        "minecraft:peony" to Color.rgb(220, 170, 190),
        "minecraft:brown_mushroom" to Color.rgb(150, 110, 80),
        "minecraft:red_mushroom" to Color.rgb(180, 40, 35),
        "minecraft:brown_mushroom_block" to Color.rgb(110, 85, 60),
        "minecraft:red_mushroom_block" to Color.rgb(170, 50, 45),
        "minecraft:mushroom_stem" to Color.rgb(220, 215, 205),
        "minecraft:azalea" to Color.rgb(80, 120, 55),
        "minecraft:flowering_azalea" to Color.rgb(150, 130, 100),
        "minecraft:spore_blossom" to Color.rgb(190, 90, 130),
        "minecraft:big_dripleaf" to Color.rgb(70, 120, 55),
        "minecraft:small_dripleaf" to Color.rgb(70, 120, 55),
        "minecraft:glow_lichen" to Color.rgb(150, 180, 150)
    )

    private val table: Map<String, Int> = HashMap<String, Int>().apply {
        putAll(dyeFamily())
        putAll(woodFamily())
        putAll(copperFamily())
        putAll(curated)
    }

    val fallbackSolid: Int = Color.rgb(120, 120, 120)   // a recognized-solid but not-in-the-table block
    val fallbackUnloaded: Int = Color.rgb(55, 55, 55)   // no chunk data at all
    val fallbackUnparsed: Int = Color.rgb(90, 90, 90)   // chunk exists but its subchunk format wasn't decodable

    fun colorFor(blockName: String): Int = table[blockName] ?: fallbackSolid

    fun isKnown(blockName: String): Boolean = blockName in table
}
