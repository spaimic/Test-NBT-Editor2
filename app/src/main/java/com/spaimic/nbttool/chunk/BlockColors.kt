package com.spaimic.nbttool.chunk

import android.graphics.Color

/**
 * Block name -> map color. Rather than inventing a unique RGB per block (or
 * hand-typing thousands of lines), this uses the same system Minecraft's own
 * in-game maps use: every block is assigned one of a small set of ~62
 * official "base map colors" (net.minecraft.world.level.material.MapColor —
 * Bedrock's own map rendering matches this palette closely). Coverage of the
 * huge number of actual block IDs comes from:
 *   1. An explicit table for common/structurally-unique blocks.
 *   2. Pattern rules for the big regular "product families" — 16 dye colors
 *      across wool/concrete/terracotta/glass/carpet/etc., wood species across
 *      planks/stairs/slabs/doors/etc., ore variants, stone-family shapes.
 * A block this app has never heard of (e.g. one added after this was written)
 * still gets colored correctly as long as it follows one of those naming
 * patterns, which almost all Minecraft blocks do.
 */
object BlockColors {

    // ---- the official ~62 base map colors (Java's MapColor table; Bedrock's own
    // map palette matches this closely) — every block below reuses one of these,
    // never an invented RGB. ----
    private fun rgb(r: Int, g: Int, b: Int) = Color.rgb(r, g, b)

    private val NONE = rgb(0, 0, 0)
    private val GRASS = rgb(127, 178, 56)
    private val SAND = rgb(247, 233, 163)
    private val WOOL = rgb(199, 199, 199)
    private val FIRE = rgb(255, 0, 0)
    private val ICE = rgb(160, 160, 255)
    private val METAL = rgb(167, 167, 167)
    private val PLANT = rgb(0, 124, 0)
    private val SNOW = rgb(255, 255, 255)
    private val CLAY = rgb(164, 168, 184)
    private val DIRT = rgb(151, 109, 77)
    private val STONE = rgb(112, 112, 112)
    private val WATER = rgb(64, 64, 255)
    private val WOOD = rgb(143, 119, 72)
    private val QUARTZ = rgb(255, 252, 245)
    private val COLOR_ORANGE = rgb(216, 127, 51)
    private val COLOR_MAGENTA = rgb(178, 76, 216)
    private val COLOR_LIGHT_BLUE = rgb(102, 153, 216)
    private val COLOR_YELLOW = rgb(229, 229, 51)
    private val COLOR_LIGHT_GREEN = rgb(127, 204, 25)
    private val COLOR_PINK = rgb(242, 127, 165)
    private val COLOR_GRAY = rgb(76, 76, 76)
    private val COLOR_LIGHT_GRAY = rgb(153, 153, 153)
    private val COLOR_CYAN = rgb(76, 127, 153)
    private val COLOR_PURPLE = rgb(127, 63, 178)
    private val COLOR_BLUE = rgb(51, 76, 178)
    private val COLOR_BROWN = rgb(102, 76, 51)
    private val COLOR_GREEN = rgb(102, 127, 51)
    private val COLOR_RED = rgb(153, 51, 51)
    private val COLOR_BLACK = rgb(25, 25, 25)
    private val GOLD = rgb(250, 238, 77)
    private val DIAMOND = rgb(92, 219, 213)
    private val LAPIS = rgb(74, 128, 255)
    private val EMERALD = rgb(0, 217, 58)
    private val PODZOL = rgb(129, 86, 49)
    private val NETHER = rgb(112, 2, 0)
    private val TERRACOTTA_WHITE = rgb(209, 177, 161)
    private val TERRACOTTA_ORANGE = rgb(159, 82, 36)
    private val TERRACOTTA_MAGENTA = rgb(149, 87, 108)
    private val TERRACOTTA_LIGHT_BLUE = rgb(112, 108, 138)
    private val TERRACOTTA_YELLOW = rgb(186, 133, 36)
    private val TERRACOTTA_LIGHT_GREEN = rgb(103, 117, 53)
    private val TERRACOTTA_PINK = rgb(160, 77, 78)
    private val TERRACOTTA_GRAY = rgb(57, 41, 35)
    private val TERRACOTTA_LIGHT_GRAY = rgb(135, 107, 98)
    private val TERRACOTTA_CYAN = rgb(87, 92, 92)
    private val TERRACOTTA_PURPLE = rgb(122, 73, 88)
    private val TERRACOTTA_BLUE = rgb(76, 62, 92)
    private val TERRACOTTA_BROWN = rgb(76, 50, 35)
    private val TERRACOTTA_GREEN = rgb(76, 82, 42)
    private val TERRACOTTA_RED = rgb(142, 60, 46)
    private val TERRACOTTA_BLACK = rgb(37, 22, 16)
    private val CRIMSON_NYLIUM = rgb(189, 48, 49)
    private val CRIMSON_STEM = rgb(148, 63, 97)
    private val CRIMSON_HYPHAE = rgb(92, 25, 29)
    private val WARPED_NYLIUM = rgb(22, 126, 134)
    private val WARPED_STEM = rgb(58, 142, 140)
    private val WARPED_HYPHAE = rgb(86, 44, 62)
    private val WARPED_WART_BLOCK = rgb(20, 180, 133)
    private val DEEPSLATE = rgb(100, 100, 100)
    private val RAW_IRON = rgb(216, 175, 147)
    private val GLOW_LICHEN = rgb(127, 167, 150)
    // Bedrock Edition base map colors (MCBE 1.21.3 reference palette).
    // These are intentionally kept separate from the older Java-style fallback
    // palette above.  The renderer should prefer these values whenever a block
    // has a known Bedrock map color.
    private val BEDROCK_GRASS = rgb(146, 188, 88)
    private val BEDROCK_OAK_LEAVES = rgb(63, 92, 24)
    private val BEDROCK_BIRCH_LEAVES = rgb(69, 90, 46)
    private val BEDROCK_SPRUCE_LEAVES = rgb(52, 82, 52)
    private val BEDROCK_CHERRY_LEAVES = rgb(242, 127, 165)
    private val BEDROCK_MANGROVE_LEAVES = rgb(63, 92, 24)
    private val BEDROCK_WATER = rgb(30, 90, 245)
    private val BEDROCK_SHORT_GRASS = rgb(146, 188, 88)
    private val BEDROCK_FERN = rgb(146, 188, 88)
    private val BEDROCK_VINE = rgb(78, 113, 30)
    private val BEDROCK_STONE = rgb(112, 112, 112)
    private val BEDROCK_DEEPSLATE = rgb(100, 100, 100)
    private val BEDROCK_CHERRY_WOOD = rgb(224, 173, 177)
    private val BEDROCK_CHERRY_LOG = rgb(217, 168, 172)
    private val BEDROCK_CHERRY_STRIPPED = rgb(178, 92, 101)
    private val BEDROCK_BIRCH_WOOD = rgb(247, 233, 163)
    private val BEDROCK_SPRUCE_WOOD = rgb(129, 86, 49)
    private val BEDROCK_JUNGLE_WOOD = rgb(151, 109, 77)
    private val BEDROCK_ACACIA_WOOD = rgb(216, 127, 51)
    private val BEDROCK_DARK_OAK_WOOD = rgb(102, 76, 51)
    private val BEDROCK_MANGROVE_WOOD = rgb(146, 71, 63)
    private val BEDROCK_BAMBOO = rgb(224, 206, 94)
    private val BEDROCK_PALE_OAK_WOOD = rgb(223, 218, 209)
    private val BEDROCK_PALE_OAK_STRIPPED = rgb(190, 185, 176)
    private val BEDROCK_SCULK = rgb(13, 18, 23)

    // dye-color name -> its wool/concrete-family map color and terracotta-family map color
    private val DYE_WOOL = mapOf(
        "white" to WOOL, "orange" to COLOR_ORANGE, "magenta" to COLOR_MAGENTA, "light_blue" to COLOR_LIGHT_BLUE,
        "yellow" to COLOR_YELLOW, "lime" to COLOR_LIGHT_GREEN, "pink" to COLOR_PINK, "gray" to COLOR_GRAY,
        "light_gray" to COLOR_LIGHT_GRAY, "cyan" to COLOR_CYAN, "purple" to COLOR_PURPLE, "blue" to COLOR_BLUE,
        "brown" to COLOR_BROWN, "green" to COLOR_GREEN, "red" to COLOR_RED, "black" to COLOR_BLACK
    )
    // Clean, texture-average colors for concrete blocks. Concrete is much
    // brighter/saturated than terracotta and must not reuse the terracotta
    // palette (that was one of the causes of pale/gray-looking builds).
    private val DYE_CONCRETE = mapOf(
        "white" to rgb(207, 213, 214), "orange" to rgb(224, 97, 0),
        "magenta" to rgb(169, 48, 159), "light_blue" to rgb(95, 172, 222),
        "yellow" to rgb(240, 175, 21), "lime" to rgb(126, 189, 41),
        "pink" to rgb(224, 102, 126), "gray" to rgb(54, 57, 61),
        "light_gray" to rgb(125, 125, 115), "cyan" to rgb(21, 137, 145),
        "purple" to rgb(100, 32, 156), "blue" to rgb(44, 46, 143),
        "brown" to rgb(96, 59, 36), "green" to rgb(73, 91, 36),
        "red" to rgb(142, 32, 32), "black" to rgb(8, 10, 15)
    )

    private val DYE_TERRACOTTA = mapOf(
        "white" to TERRACOTTA_WHITE, "orange" to TERRACOTTA_ORANGE, "magenta" to TERRACOTTA_MAGENTA,
        "light_blue" to TERRACOTTA_LIGHT_BLUE, "yellow" to TERRACOTTA_YELLOW, "lime" to TERRACOTTA_LIGHT_GREEN,
        "pink" to TERRACOTTA_PINK, "gray" to TERRACOTTA_GRAY, "light_gray" to TERRACOTTA_LIGHT_GRAY,
        "cyan" to TERRACOTTA_CYAN, "purple" to TERRACOTTA_PURPLE, "blue" to TERRACOTTA_BLUE,
        "brown" to TERRACOTTA_BROWN, "green" to TERRACOTTA_GREEN, "red" to TERRACOTTA_RED, "black" to TERRACOTTA_BLACK
    )

    // wood species -> its map color (all standard species share WOOD; nether "wood" is its own family)
    private val WOOD_SPECIES = listOf(
        "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo", "pale_oak"
    )
    private val CRIMSON_SPECIES = mapOf("crimson" to (CRIMSON_STEM to CRIMSON_HYPHAE))
    private val WARPED_SPECIES = mapOf("warped" to (WARPED_STEM to WARPED_HYPHAE))

    // explicit table: common/structurally-unique blocks that don't fit a generic pattern
    val fallbackSolid: Int = rgb(120, 120, 120)   // a recognized-solid but not-in-the-table block
    val fallbackUnloaded: Int = rgb(55, 55, 55)   // no chunk data at all
    val fallbackUnparsed: Int = rgb(90, 90, 90)   // chunk exists but its subchunk format wasn't decodable

    private val bedrockExact: Map<String, Int> = mapOf(
        "grass_block" to BEDROCK_GRASS,
        "short_grass" to BEDROCK_SHORT_GRASS,
        "fern" to BEDROCK_FERN,
        "large_fern" to PLANT,
        "oak_leaves" to BEDROCK_OAK_LEAVES,
        "birch_leaves" to BEDROCK_BIRCH_LEAVES,
        "spruce_leaves" to BEDROCK_SPRUCE_LEAVES,
        "jungle_leaves" to BEDROCK_OAK_LEAVES,
        "acacia_leaves" to BEDROCK_OAK_LEAVES,
        "dark_oak_leaves" to BEDROCK_OAK_LEAVES,
        "mangrove_leaves" to BEDROCK_MANGROVE_LEAVES,
        "cherry_leaves" to BEDROCK_CHERRY_LEAVES,
        "azalea_leaves" to PLANT,
        "azalea_leaves_flowered" to PLANT,
        "water" to BEDROCK_WATER,
        "flowing_water" to BEDROCK_WATER,
        "vine" to BEDROCK_VINE,
        "stone" to BEDROCK_STONE,
        "cobblestone" to BEDROCK_STONE,
        "andesite" to BEDROCK_STONE,
        "polished_andesite" to BEDROCK_STONE,
        "deepslate" to BEDROCK_DEEPSLATE,
        "cobbled_deepslate" to BEDROCK_DEEPSLATE,
        "polished_deepslate" to BEDROCK_DEEPSLATE,
        "deepslate_bricks" to BEDROCK_DEEPSLATE,
        "deepslate_tiles" to BEDROCK_DEEPSLATE,
        "cherry_wood" to BEDROCK_CHERRY_WOOD,
        "cherry_log" to BEDROCK_CHERRY_LOG,
        "stripped_cherry_wood" to BEDROCK_CHERRY_STRIPPED,
        "stripped_cherry_log" to BEDROCK_CHERRY_LOG,
        "birch_wood" to BEDROCK_BIRCH_WOOD,
        "birch_log" to BEDROCK_BIRCH_WOOD,
        "stripped_birch_wood" to BEDROCK_BIRCH_WOOD,
        "stripped_birch_log" to BEDROCK_BIRCH_WOOD,
        "spruce_wood" to BEDROCK_SPRUCE_WOOD,
        "spruce_log" to BEDROCK_SPRUCE_WOOD,
        "stripped_spruce_wood" to BEDROCK_SPRUCE_WOOD,
        "stripped_spruce_log" to BEDROCK_SPRUCE_WOOD,
        "jungle_wood" to BEDROCK_JUNGLE_WOOD,
        "jungle_log" to BEDROCK_JUNGLE_WOOD,
        "stripped_jungle_wood" to BEDROCK_JUNGLE_WOOD,
        "stripped_jungle_log" to BEDROCK_JUNGLE_WOOD,
        "acacia_wood" to BEDROCK_ACACIA_WOOD,
        "acacia_log" to BEDROCK_ACACIA_WOOD,
        "stripped_acacia_wood" to BEDROCK_ACACIA_WOOD,
        "stripped_acacia_log" to BEDROCK_ACACIA_WOOD,
        "dark_oak_wood" to BEDROCK_DARK_OAK_WOOD,
        "dark_oak_log" to BEDROCK_DARK_OAK_WOOD,
        "stripped_dark_oak_wood" to BEDROCK_DARK_OAK_WOOD,
        "stripped_dark_oak_log" to BEDROCK_DARK_OAK_WOOD,
        "mangrove_wood" to BEDROCK_MANGROVE_WOOD,
        "mangrove_log" to BEDROCK_MANGROVE_WOOD,
        "stripped_mangrove_log" to BEDROCK_MANGROVE_WOOD,
        "bamboo" to PLANT,
        "bamboo_block" to BEDROCK_BAMBOO,
        "stripped_bamboo_block" to BEDROCK_BAMBOO,
        "bamboo_planks" to BEDROCK_BAMBOO,
        "moss_block" to rgb(102, 127, 51),
        "moss_carpet" to rgb(102, 127, 51),
        "sculk" to BEDROCK_SCULK,
        "sculk_catalyst" to BEDROCK_SCULK,
        "sculk_sensor" to BEDROCK_SCULK,
        "sculk_shrieker" to BEDROCK_SCULK,
        "glow_lichen" to rgb(127, 167, 150),
        "sand" to rgb(247, 233, 163),
        "snow" to Color.WHITE,
        "snow_layer" to Color.WHITE,
        "powder_snow" to Color.WHITE,
        "ice" to rgb(160, 160, 255),
        "packed_ice" to rgb(160, 160, 255),
        "blue_ice" to rgb(160, 160, 255),
        "lava" to Color.RED,
        "flowing_lava" to Color.RED,
        "netherrack" to rgb(112, 2, 0),
        "crimson_nylium" to rgb(189, 48, 49),
        "warped_nylium" to rgb(22, 126, 134),
        "warped_wart_block" to rgb(20, 180, 133),
        "prismarine" to rgb(74, 161, 163),
        "diamond_block" to rgb(92, 219, 213),
        "emerald_block" to rgb(0, 217, 58),
        "gold_block" to rgb(250, 238, 77),
        "iron_block" to rgb(167, 167, 167),
        "raw_iron_block" to rgb(216, 175, 147),
        "quartz_block" to rgb(255, 252, 245),
"calcite" to QUARTZ,
"tuff" to rgb(105, 102, 98),
"tuff_bricks" to rgb(105, 102, 98),
"mud" to rgb(97, 88, 76),
"mud_bricks" to rgb(136, 106, 92),
"cherry_planks" to BEDROCK_CHERRY_WOOD,
"cherry_stairs" to BEDROCK_CHERRY_WOOD,
"cherry_slab" to BEDROCK_CHERRY_WOOD,
"cherry_fence" to BEDROCK_CHERRY_WOOD,
"cherry_fence_gate" to BEDROCK_CHERRY_WOOD,
"cherry_door" to BEDROCK_CHERRY_WOOD,
"cherry_trapdoor" to BEDROCK_CHERRY_WOOD,
"cherry_button" to BEDROCK_CHERRY_WOOD,
"cherry_pressure_plate" to BEDROCK_CHERRY_WOOD,
"cherry_sign" to BEDROCK_CHERRY_WOOD,
"cherry_standing_sign" to BEDROCK_CHERRY_WOOD,
"cherry_wall_sign" to BEDROCK_CHERRY_WOOD,
"cherry_hanging_sign" to BEDROCK_CHERRY_STRIPPED,
"cherry_wall_hanging_sign" to BEDROCK_CHERRY_STRIPPED,

"mangrove_planks" to BEDROCK_MANGROVE_WOOD,
"mangrove_stairs" to BEDROCK_MANGROVE_WOOD,
"mangrove_slab" to BEDROCK_MANGROVE_WOOD,
"mangrove_fence" to BEDROCK_MANGROVE_WOOD,
"mangrove_fence_gate" to BEDROCK_MANGROVE_WOOD,
"mangrove_door" to BEDROCK_MANGROVE_WOOD,
"mangrove_trapdoor" to BEDROCK_MANGROVE_WOOD,
"mangrove_button" to BEDROCK_MANGROVE_WOOD,
"mangrove_pressure_plate" to BEDROCK_MANGROVE_WOOD,
"mangrove_sign" to BEDROCK_MANGROVE_WOOD,
"mangrove_standing_sign" to BEDROCK_MANGROVE_WOOD,
"mangrove_wall_sign" to BEDROCK_MANGROVE_WOOD,
"mangrove_hanging_sign" to BEDROCK_MANGROVE_WOOD,
"mangrove_wall_hanging_sign" to BEDROCK_MANGROVE_WOOD,
"mangrove_boat" to BEDROCK_MANGROVE_WOOD,
"mangrove_chest_boat" to BEDROCK_MANGROVE_WOOD,

"bamboo_planks" to BEDROCK_BAMBOO,
"bamboo_mosaic" to BEDROCK_BAMBOO,
"bamboo_mosaic_stairs" to BEDROCK_BAMBOO,
"bamboo_mosaic_slab" to BEDROCK_BAMBOO,
"bamboo_fence" to BEDROCK_BAMBOO,
"bamboo_fence_gate" to BEDROCK_BAMBOO,
"bamboo_door" to BEDROCK_BAMBOO,
"bamboo_trapdoor" to BEDROCK_BAMBOO,
"bamboo_button" to BEDROCK_BAMBOO,
"bamboo_pressure_plate" to BEDROCK_BAMBOO,
"bamboo_sign" to BEDROCK_BAMBOO,
"bamboo_standing_sign" to BEDROCK_BAMBOO,
"bamboo_wall_sign" to BEDROCK_BAMBOO,
"bamboo_hanging_sign" to BEDROCK_BAMBOO,
"bamboo_wall_hanging_sign" to BEDROCK_BAMBOO,
"bamboo_boat" to BEDROCK_BAMBOO,
"bamboo_chest_boat" to BEDROCK_BAMBOO,

"pale_oak_planks" to BEDROCK_PALE_OAK_WOOD,
"pale_oak_stairs" to BEDROCK_PALE_OAK_WOOD,
"pale_oak_slab" to BEDROCK_PALE_OAK_WOOD,
"pale_oak_fence" to BEDROCK_PALE_OAK_WOOD,
"pale_oak_fence_gate" to BEDROCK_PALE_OAK_WOOD,
"pale_oak_door" to BEDROCK_PALE_OAK_WOOD,
"pale_oak_trapdoor" to BEDROCK_PALE_OAK_WOOD,
"pale_oak_button" to BEDROCK_PALE_OAK_WOOD,
"pale_oak_pressure_plate" to BEDROCK_PALE_OAK_WOOD,
"pale_oak_sign" to BEDROCK_PALE_OAK_WOOD,
"pale_oak_standing_sign" to BEDROCK_PALE_OAK_WOOD,
"pale_oak_wall_sign" to BEDROCK_PALE_OAK_WOOD,
"pale_oak_hanging_sign" to BEDROCK_PALE_OAK_WOOD,
"pale_oak_wall_hanging_sign" to BEDROCK_PALE_OAK_WOOD,
"pale_oak_boat" to BEDROCK_PALE_OAK_WOOD,
"pale_oak_chest_boat" to BEDROCK_PALE_OAK_WOOD,

"warped_planks" to WARPED_STEM,
"warped_stairs" to WARPED_STEM,
"warped_slab" to WARPED_STEM,
"warped_fence" to WARPED_STEM,
"warped_fence_gate" to WARPED_STEM,
"warped_door" to WARPED_STEM,
"warped_trapdoor" to WARPED_STEM,
"warped_button" to WARPED_STEM,
"warped_pressure_plate" to WARPED_STEM,
"warped_sign" to WARPED_STEM,
"warped_standing_sign" to WARPED_STEM,
"warped_wall_sign" to WARPED_STEM,
"warped_hanging_sign" to WARPED_STEM,
"warped_wall_hanging_sign" to WARPED_STEM,
"warped_boat" to WARPED_STEM,
"warped_chest_boat" to WARPED_STEM,

"crimson_planks" to CRIMSON_STEM,
"crimson_stairs" to CRIMSON_STEM,
"crimson_slab" to CRIMSON_STEM,
"crimson_fence" to CRIMSON_STEM,
"crimson_fence_gate" to CRIMSON_STEM,
"crimson_door" to CRIMSON_STEM,
"crimson_trapdoor" to CRIMSON_STEM,
"crimson_button" to CRIMSON_STEM,
"crimson_pressure_plate" to CRIMSON_STEM,
"crimson_sign" to CRIMSON_STEM,
"crimson_standing_sign" to CRIMSON_STEM,
"crimson_wall_sign" to CRIMSON_STEM,
"crimson_hanging_sign" to CRIMSON_STEM,
"crimson_wall_hanging_sign" to CRIMSON_STEM,
"crimson_boat" to CRIMSON_STEM,
"crimson_chest_boat" to CRIMSON_STEM
    )

    private val explicit: Map<String, Int> = mapOf(
        // terrain / ground
        "grass_block" to GRASS, "grass" to PLANT, "tallgrass" to PLANT, "short_grass" to PLANT, "tall_grass" to PLANT,
        "fern" to PLANT, "large_fern" to PLANT, "seagrass" to PLANT, "tall_seagrass" to PLANT, "kelp" to PLANT, "kelp_plant" to PLANT,
        "vine" to PLANT, "lily_pad" to PLANT, "sugar_cane" to PLANT, "reeds" to PLANT, "bamboo" to PLANT, "bamboo_sapling" to PLANT,
        "sapling" to PLANT, "azalea" to PLANT, "flowering_azalea" to PLANT, "moss_block" to PLANT, "moss_carpet" to PLANT,
        "hanging_roots" to DIRT, "sculk_vein" to COLOR_BLACK, "sculk" to COLOR_BLACK, "sculk_catalyst" to COLOR_BLACK, "sculk_shrieker" to TERRACOTTA_WHITE,
        "dirt" to DIRT, "coarse_dirt" to DIRT, "farmland" to DIRT, "dirt_path" to DIRT, "grass_path" to DIRT,
        "rooted_dirt" to DIRT, "mud" to COLOR_GRAY, "muddy_mangrove_roots" to DIRT, "mangrove_roots" to WOOD,
        "podzol" to PODZOL, "mycelium" to TERRACOTTA_PURPLE,
        "sand" to SAND, "red_sand" to COLOR_ORANGE, "sandstone" to SAND, "red_sandstone" to COLOR_ORANGE,
        "gravel" to STONE, "clay" to CLAY, "ice" to ICE, "packed_ice" to ICE, "blue_ice" to DIAMOND, "frosted_ice" to ICE,
        "snow" to SNOW, "snow_layer" to SNOW, "powder_snow" to SNOW,
        // stone family
        "stone" to STONE, "cobblestone" to STONE, "mossy_cobblestone" to STONE, "smooth_stone" to STONE,
        "andesite" to STONE, "polished_andesite" to STONE, "diorite" to QUARTZ, "polished_diorite" to QUARTZ,
        "granite" to rgb(216, 127, 82), "polished_granite" to rgb(216, 127, 82),
        "deepslate" to DEEPSLATE, "cobbled_deepslate" to DEEPSLATE, "polished_deepslate" to DEEPSLATE,
        "deepslate_bricks" to DEEPSLATE, "deepslate_tiles" to DEEPSLATE, "chiseled_deepslate" to DEEPSLATE,
        "tuff" to rgb(105, 102, 98), "tuff_bricks" to rgb(105, 102, 98), "chiseled_tuff" to rgb(105, 102, 98), "tuff_brick_slab" to rgb(105, 102, 98),
        "calcite" to QUARTZ, "dripstone_block" to DIRT, "pointed_dripstone" to DIRT,
        "bedrock" to STONE, "obsidian" to COLOR_BLACK, "crying_obsidian" to COLOR_PURPLE,
        "basalt" to STONE, "polished_basalt" to STONE, "smooth_basalt" to STONE, "blackstone" to TERRACOTTA_BLACK,
        "polished_blackstone" to TERRACOTTA_BLACK, "chiseled_polished_blackstone" to TERRACOTTA_BLACK,
        "gilded_blackstone" to TERRACOTTA_BLACK, "cracked_polished_blackstone_bricks" to TERRACOTTA_BLACK,
        "polished_blackstone_bricks" to TERRACOTTA_BLACK,
        "quartz_block" to QUARTZ, "quartz" to QUARTZ, "smooth_quartz" to QUARTZ, "quartz_bricks" to QUARTZ, "chiseled_quartz_block" to QUARTZ,
        "quartz_pillar" to QUARTZ,
        "prismarine" to DIAMOND, "prismarine_bricks" to rgb(74, 161, 163), "dark_prismarine" to COLOR_CYAN, "sea_lantern" to QUARTZ,
        "purpur_block" to COLOR_MAGENTA, "purpur_pillar" to COLOR_MAGENTA,
        "end_stone" to SAND, "end_stone_bricks" to SAND, "end_rod" to QUARTZ,
        "netherrack" to NETHER, "nether_bricks" to NETHER, "cracked_nether_bricks" to NETHER, "chiseled_nether_bricks" to NETHER,
        "red_nether_bricks" to NETHER, "soul_sand" to COLOR_BROWN, "soul_soil" to COLOR_BROWN,
        "magma" to COLOR_RED, "magma_block" to COLOR_RED, "glowstone" to GOLD, "shroomlight" to COLOR_ORANGE,
        "crimson_nylium" to CRIMSON_NYLIUM, "warped_nylium" to WARPED_NYLIUM, "warped_wart_block" to WARPED_WART_BLOCK,
        "nether_wart_block" to COLOR_RED, "nether_gold_ore" to NETHER, "ancient_debris" to NETHER,
        // Strongly colored building blocks. Keep these explicit so they do not
        // fall through to generic material/fallback colors.
        "pink_concrete" to rgb(224, 102, 126),
        "pink_concrete_powder" to TERRACOTTA_PINK,
        "pink_wool" to rgb(238, 141, 172),
        "pink_terracotta" to TERRACOTTA_PINK,
        "black_concrete" to rgb(8, 10, 15),
        "black_concrete_powder" to TERRACOTTA_BLACK,
        "black_wool" to rgb(25, 25, 25),
        "black_terracotta" to TERRACOTTA_BLACK,
        "white_concrete" to rgb(207, 213, 214),
        "gray_concrete" to rgb(54, 57, 61),
        "light_gray_concrete" to rgb(125, 125, 115),
        "red_concrete" to rgb(142, 32, 32),
        "blue_concrete" to rgb(44, 46, 143),
        "green_concrete" to rgb(73, 91, 36),
        "yellow_concrete" to rgb(240, 175, 21),
        "orange_concrete" to rgb(224, 97, 0),
        "purple_concrete" to rgb(100, 32, 156),
        "cyan_concrete" to rgb(21, 137, 145),
        "lime_concrete" to rgb(126, 189, 41),
        "brown_concrete" to rgb(96, 59, 36),
        "magenta_concrete" to rgb(169, 48, 159),
        "light_blue_concrete" to rgb(95, 172, 222),
        "netherite_block" to COLOR_BLACK,
        "netherite" to COLOR_BLACK,
        // liquids
        "water" to WATER, "flowing_water" to WATER, "lava" to FIRE, "flowing_lava" to FIRE,
        // ores (metal/gem-colored)
        "iron_ore" to STONE, "deepslate_iron_ore" to DEEPSLATE, "iron_block" to METAL, "raw_iron_block" to RAW_IRON, "raw_iron" to RAW_IRON,
        "gold_ore" to STONE, "deepslate_gold_ore" to DEEPSLATE, "gold_block" to GOLD, "raw_gold_block" to GOLD,
        "diamond_ore" to STONE, "deepslate_diamond_ore" to DEEPSLATE, "diamond_block" to DIAMOND,
        "emerald_ore" to STONE, "deepslate_emerald_ore" to DEEPSLATE, "emerald_block" to EMERALD,
        "lapis_ore" to STONE, "deepslate_lapis_ore" to DEEPSLATE, "lapis_block" to LAPIS,
        "redstone_ore" to STONE, "deepslate_redstone_ore" to DEEPSLATE, "redstone_block" to FIRE,
        "coal_ore" to STONE, "deepslate_coal_ore" to DEEPSLATE, "coal_block" to COLOR_BLACK,
        "copper_ore" to STONE, "deepslate_copper_ore" to DEEPSLATE, "copper_block" to COLOR_ORANGE, "raw_copper_block" to COLOR_ORANGE, "raw_copper" to COLOR_ORANGE,
        "cut_copper" to COLOR_ORANGE, "chiseled_copper" to COLOR_ORANGE, "copper_grate" to COLOR_ORANGE,
        "copper_bulb" to COLOR_ORANGE, "copper_door" to COLOR_ORANGE, "copper_trapdoor" to COLOR_ORANGE,
        "oxidized_copper" to COLOR_CYAN, "weathered_copper" to COLOR_GREEN, "exposed_copper" to COLOR_ORANGE,
        // wood-derived but not planks/log family
        "bookshelf" to WOOD, "chiseled_bookshelf" to WOOD, "ladder" to WOOD, "scaffolding" to SAND,
        "crafting_table" to WOOD, "cartography_table" to WOOD, "fletching_table" to WOOD, "smithing_table" to WOOD,
        "loom" to WOOL, "composter" to WOOD, "barrel" to WOOD, "beehive" to WOOD, "bee_nest" to WOOD,
        "lectern" to WOOD, "jukebox" to WOOD, "note_block" to WOOD, "chest" to WOOD, "trapped_chest" to WOOD,
        "ender_chest" to COLOR_BLACK,
        // functional / utility
        "furnace" to STONE, "blast_furnace" to STONE, "smoker" to WOOD, "brewing_stand" to STONE,
        "cauldron" to STONE, "anvil" to METAL, "chipped_anvil" to METAL, "damaged_anvil" to METAL,
        "grindstone" to METAL, "stonecutter" to STONE, "enchanting_table" to COLOR_GREEN,
        "beacon" to DIAMOND, "conduit" to DIAMOND, "dragon_egg" to COLOR_BLACK,
        "hopper" to METAL, "dispenser" to STONE, "dropper" to STONE, "observer" to STONE, "piston" to STONE,
        "sticky_piston" to STONE, "piston_head" to WOOD, "piston_arm_collision" to WOOD, "sticky_piston_arm_collision" to WOOD,
        "target" to TERRACOTTA_WHITE,
        "tnt" to COLOR_RED, "spawner" to STONE, "trial_spawner" to DEEPSLATE, "vault" to DEEPSLATE,
        "iron_bars" to METAL, "chain" to METAL, "lightning_rod" to COLOR_ORANGE, "bell" to GOLD,
        "cake" to SNOW, "flower_pot" to TERRACOTTA_ORANGE, "armor_stand" to WOOD,
        "campfire" to WOOD, "soul_campfire" to WOOD, "torch" to WOOD, "soul_torch" to COLOR_CYAN, "lantern" to METAL, "soul_lantern" to COLOR_CYAN,
        "redstone_lamp" to COLOR_YELLOW, "sea_pickle" to COLOR_LIGHT_GREEN, "glass" to ICE, "glass_pane" to ICE, "tinted_glass" to COLOR_GRAY,
        "amethyst_block" to COLOR_PURPLE, "budding_amethyst" to COLOR_PURPLE, "amethyst_cluster" to COLOR_PURPLE,
        "small_amethyst_bud" to COLOR_PURPLE, "medium_amethyst_bud" to COLOR_PURPLE, "large_amethyst_bud" to COLOR_PURPLE,
        "honeycomb_block" to COLOR_ORANGE, "honey_block" to COLOR_ORANGE, "slime" to COLOR_LIGHT_GREEN, "slime_block" to COLOR_LIGHT_GREEN,
        "hay_block" to COLOR_YELLOW, "sponge" to COLOR_YELLOW, "wet_sponge" to COLOR_YELLOW,
        "bone_block" to QUARTZ, "structure_block" to COLOR_MAGENTA, "structure_void" to COLOR_MAGENTA,
        "jigsaw" to COLOR_PURPLE, "command_block" to COLOR_BROWN, "repeating_command_block" to COLOR_PURPLE, "chain_command_block" to COLOR_LIGHT_GREEN,
        "barrier" to fallbackSolid, "light_block" to fallbackSolid,
        "resin_block" to COLOR_ORANGE, "resin_bricks" to COLOR_ORANGE, "resin_clump" to COLOR_ORANGE,
        "chalkboard" to COLOR_BLACK, // Education Edition block, togglable in Bedrock
        // sulfur bricks: added in a Bedrock update after this app's block-name knowledge cutoff
        "sulfur_bricks" to TERRACOTTA_YELLOW, "sulfur_brick_stairs" to TERRACOTTA_YELLOW, "sulfur_brick_slab" to TERRACOTTA_YELLOW, "sulfur_brick_wall" to TERRACOTTA_YELLOW,
        // Newer Minecraft block families: keep both explicit IDs and generic fallback coverage.
        "sulfur" to rgb(214, 190, 55), "sulfur_block" to rgb(214, 190, 55),
        "cinnabar" to rgb(168, 54, 54), "cinnabar_block" to rgb(168, 54, 54),
        // redstone components
        "redstone_wire" to FIRE, "repeater" to STONE, "unpowered_repeater" to STONE, "powered_repeater" to FIRE,
        "comparator" to STONE, "unpowered_comparator" to STONE, "powered_comparator" to FIRE,
        "lever" to STONE, "tripwire" to WOOD, "tripwire_hook" to STONE,
        "daylight_detector" to WOOD, "rail" to METAL, "golden_rail" to GOLD, "detector_rail" to METAL, "activator_rail" to METAL
    )

    // Bedrock map-color entries with #000000/0 are not painted as a visible map
    // surface. Treat them as transparent so the renderer continues downward to the
    // next block. This is critical for glass panes, buttons, rails, torches, etc.
    private val transparentNames = setOf(
        "air", "cave_air", "void_air", "glass", "glass_pane", "tinted_glass",
        "white_stained_glass_pane", "orange_stained_glass_pane", "magenta_stained_glass_pane",
        "light_blue_stained_glass_pane", "yellow_stained_glass_pane", "lime_stained_glass_pane",
        "pink_stained_glass_pane", "gray_stained_glass_pane", "light_gray_stained_glass_pane",
        "cyan_stained_glass_pane", "purple_stained_glass_pane", "blue_stained_glass_pane",
        "brown_stained_glass_pane", "green_stained_glass_pane", "red_stained_glass_pane",
        "black_stained_glass_pane", "torch", "soul_torch", "redstone_torch", "lever",
        "stone_button", "wooden_button", "tripwire_hook", "rail", "golden_rail",
        "detector_rail", "activator_rail", "end_rod", "frame", "glow_frame", "barrier",
        "structure_void", "light_block", "skull", "portal"
    )

    fun isMapTransparent(blockNameWithNamespace: String): Boolean =
        blockNameWithNamespace.substringAfter(':') in transparentNames

    private val cache = HashMap<String, Int>()

    fun colorFor(blockNameWithNamespace: String): Int =
        cache.getOrPut(blockNameWithNamespace) { resolve(blockNameWithNamespace.substringAfter(':')) }

    /** Dimension-aware fallback for Nether and End terrain. These dimensions do not
     * use the Overworld biome grass/water tinting, and newer Bedrock block IDs can
     * appear before the generic table above is updated. */
    fun colorForDimension(blockNameWithNamespace: String, dimension: Int): Int {
        val name = blockNameWithNamespace.substringAfter(':').lowercase()
        if (dimension == 0) return colorFor(blockNameWithNamespace)
        resolveOrNull(name)?.let { return it }
        if (dimension == 1) {
            when {
                name.contains("netherrack") -> return NETHER
                name.contains("nether_brick") -> return NETHER
                name.contains("soul_sand") || name.contains("soul_soil") -> return COLOR_BROWN
                name.contains("crimson") -> return CRIMSON_STEM
                name.contains("warped") -> return WARPED_STEM
                name.contains("basalt") -> return STONE
                name.contains("blackstone") -> return TERRACOTTA_BLACK
                name.contains("ancient_debris") -> return NETHER
                name.contains("nether_gold") -> return GOLD
                name.contains("nether_quartz") -> return QUARTZ
                name.contains("glowstone") || name.contains("shroomlight") -> return COLOR_ORANGE
                name.contains("magma") -> return COLOR_RED
                name == "lava" || name == "flowing_lava" -> return FIRE
                name.contains("nether_wart") -> return COLOR_RED
                name.contains("fungus") -> return COLOR_GREEN
                name.contains("roots") -> return CRIMSON_STEM
                name.contains("nylium") -> return if (name.startsWith("warped")) WARPED_NYLIUM else CRIMSON_NYLIUM
            }
            return fallbackSolid
        }
        // The End
        when {
            name.contains("end_stone") -> return SAND
            name.contains("purpur") -> return COLOR_MAGENTA
            name == "obsidian" || name == "crying_obsidian" -> return COLOR_BLACK
            name.contains("chorus") -> return COLOR_PURPLE
            name == "end_rod" -> return QUARTZ
            name.contains("end_portal") -> return COLOR_BLACK
            name.contains("bedrock") -> return STONE
            name.contains("end_gateway") -> return COLOR_PURPLE
        }
        return fallbackSolid
    }

    /**
     * Adds a tiny deterministic palette variation to the flat base map color.
     *
     * Blocktopograph's map is visually richer because nearby blocks of the same
     * material are not rendered as one perfectly uniform RGB value. We do not
     * have the game's texture atlas inside the editor, so Build 104 approximates
     * that look with a five-step material palette. The variation is deterministic
     * from world coordinates, so the same block always gets the same shade at the
     * same position and the pattern does not reset at chunk boundaries.
     */
    /**
     * Build 114: map tiles use the resolved block color directly. Earlier builds
     * applied coordinate-based variants here, which made even perfectly flat
     * worlds look mottled. Terrain relief now comes exclusively from surface
     * height in MapView, so block identity remains visually accurate.
     */
    fun detailedColor(baseColor: Int, blockNameWithNamespace: String, worldX: Int, worldZ: Int): Int = baseColor

    /** Fast variant path for the map renderer: caller precomputes the normalized
     * material hash and coarse material multiplier once per block. */
    fun textureVariantFast(materialHash: Int, coarse: Float, worldX: Int, worldZ: Int, px: Int, pz: Int): Float {
        var h = worldX * 374761393 + worldZ * 668265263 + px * 1274126177 + pz * 1442695041 + materialHash
        h = h xor (h ushr 13)
        h *= -1640531527
        h = h xor (h ushr 16)
        val n = (h and 0x7fffffff) % 11
        val delta = (n - 5) * 0.012f
        val directional = if (((px shr 3) + (pz shr 3)) and 1 == 0) 0.008f else -0.004f
        return (1f + delta + directional) * (0.96f + (coarse - 0.94f) * 0.18f)
    }

    fun textureVariant(material: String, worldX: Int, worldZ: Int, px: Int, pz: Int): Float {
        val name = material.substringAfter(':').lowercase()
        val coarse = when {
            name.contains("leaves") || name.contains("leaf") -> 0.88f
            name.contains("grass") || name.contains("fern") || name.contains("vine") -> 0.93f
            name.contains("wood") || name.contains("log") || name.contains("stem") || name.contains("hyphae") -> 0.95f
            name.contains("stone") || name.contains("deepslate") || name.contains("cobble") -> 0.97f
            name.contains("sand") || name.contains("gravel") || name.contains("dirt") || name.contains("clay") -> 0.96f
            else -> 0.94f
        }
        var h = worldX * 374761393 + worldZ * 668265263 + px * 1274126177 + pz * 1442695041 + name.hashCode()
        h = h xor (h ushr 13)
        h *= -1640531527
        h = h xor (h ushr 16)
        val n = (h and 0x7fffffff) % 11
        val delta = (n - 5) * 0.012f
        val directional = if (((px shr 3) + (pz shr 3)) and 1 == 0) 0.008f else -0.004f
        return (1f + delta + directional) * (0.96f + (coarse - 0.94f) * 0.18f)
    }

    fun isKnown(blockNameWithNamespace: String): Boolean =
        resolveOrNull(blockNameWithNamespace.substringAfter(':')) != null

    private fun resolve(name: String): Int = resolveOrNull(name) ?: fallbackSolid

    private fun resolveOrNull(name: String): Int? {
        bedrockExact[name]?.let { return it }
        explicit[name]?.let { return it }

        // colored product families: <dye_color>_<family>
        for ((dye, wool) in DYE_WOOL) {
            if (name == "${dye}_wool" || name == "${dye}_carpet" || name == "${dye}_bed" ||
                name == "${dye}_banner" || name == "${dye}_wall_banner" || name == "${dye}_candle" ||
                name == "${dye}_candle_cake" || name == "${dye}_shulker_box" || name == "${dye}_stained_glass" ||
                name == "${dye}_stained_glass_pane"
            ) return wool
        }
        for ((dye, terracotta) in DYE_TERRACOTTA) {
            if (name == "${dye}_terracotta" || name == "${dye}_glazed_terracotta" || name == "${dye}_concrete_powder") return terracotta
        }
        for ((dye, concrete) in DYE_CONCRETE) {
            if (name == "${dye}_concrete") return concrete
        }
        if (name == "terracotta") return TERRACOTTA_ORANGE
        // Future/new block IDs from recent Minecraft versions.
        if (name.contains("cinnabar")) return rgb(168, 54, 54)
        if (name.contains("sulfur")) return rgb(214, 190, 55)

        // wood species: <species>_<part>
        val woodSuffixes = listOf(
            "_planks", "_log", "_wood", "_stairs", "_slab", "_fence", "_fence_gate", "_door", "_trapdoor",
            "_button", "_pressure_plate", "_sign", "_standing_sign", "_hanging_sign", "_wall_sign", "_wall_hanging_sign",
            "_boat", "_chest_boat", "_leaves"
        )
        for (species in WOOD_SPECIES) {
            if (name.startsWith("stripped_${species}_")) return WOOD
            for (suf in woodSuffixes) {
                if (name == "$species$suf") return if (suf == "_leaves") PLANT else WOOD
            }
        }
        for ((species, colors) in CRIMSON_SPECIES) {
            val (stem, hyphae) = colors
            if (name.startsWith("stripped_${species}_")) return stem
            if (name == "${species}_stem" || name.startsWith("${species}_stem_")) return stem
            if (name == "${species}_hyphae" || name.startsWith("${species}_hyphae_")) return hyphae
            for (suf in listOf("_planks", "_stairs", "_slab", "_fence", "_fence_gate", "_door", "_trapdoor", "_button", "_pressure_plate", "_sign", "_hanging_sign", "_boat")) {
                if (name == "$species$suf") return stem
            }
            if (name == "${species}_roots") return stem
        }
        for ((species, colors) in WARPED_SPECIES) {
            val (stem, hyphae) = colors
            if (name.startsWith("stripped_${species}_")) return stem
            if (name == "${species}_stem" || name.startsWith("${species}_stem_")) return stem
            if (name == "${species}_hyphae" || name.startsWith("${species}_hyphae_")) return hyphae
            for (suf in listOf("_planks", "_stairs", "_slab", "_fence", "_fence_gate", "_door", "_trapdoor", "_button", "_pressure_plate", "_sign", "_hanging_sign", "_boat")) {
                if (name == "$species$suf") return stem
            }
            if (name == "${species}_roots") return stem
        }

        // stone-family shapes (X_stairs / X_slab / X_wall / polished_X / cut_X / chiseled_X)
        // inherit the base material's color if the base material itself is a recognized entry
        val plainShapeSuffixes = listOf("_stairs", "_slab", "_double_slab", "_wall")
        for (suf in plainShapeSuffixes) {
            if (name.endsWith(suf)) {
                val base = name.removeSuffix(suf)
                explicit[base]?.let { return it }
            }
        }
        // "X_bricks" -> stairs/slab/wall forms use singular "brick" (X_brick_stairs, not X_bricks_stairs)
        val brickShapeSuffixes = listOf("_brick_stairs", "_brick_slab", "_brick_double_slab", "_brick_wall")
        for (suf in brickShapeSuffixes) {
            if (name.endsWith(suf)) {
                val base = name.removeSuffix(suf) + "_bricks"
                explicit[base]?.let { return it }
            }
        }
        val stonePrefixes = listOf("polished_", "cut_", "chiseled_", "smooth_", "cracked_", "mossy_")
        for (prefix in stonePrefixes) {
            if (name.startsWith(prefix)) {
                explicit[name.removePrefix(prefix)]?.let { return it }
            }
        }

        // ore family fallback: <metal>_ore / deepslate_<metal>_ore not already listed explicitly
        if (name.endsWith("_ore")) return STONE
        if (name.startsWith("deepslate_") && name.endsWith("_ore")) return DEEPSLATE

        // generic leaves / saplings / flowers / crops
        if (name.endsWith("_leaves")) return PLANT
        if (name.endsWith("_sapling")) return PLANT
        if (name.contains("mushroom")) return name.let { if (it.contains("stem") || it.contains("block")) TERRACOTTA_WHITE else PLANT }
        if (name.endsWith("_stem") && !name.endsWith("nether_wart_block")) return PLANT // pumpkin/melon stems etc.
        if (name in setOf(
                "poppy", "dandelion", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip",
                "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley", "wither_rose",
                "sunflower", "lilac", "rose_bush", "peony", "torchflower", "pitcher_plant", "wheat", "carrots",
                "potatoes", "beetroots", "nether_wart", "cocoa", "sweet_berry_bush", "cave_vines", "glow_berries",
                "spore_blossom", "pink_petals", "wildflowers", "leaf_litter", "bush", "firefly_bush"
            )
        ) return PLANT

        // glow lichen / sculk family
        if (name == "glow_lichen") return GLOW_LICHEN

        // concrete without dye prefix handled above; anything ending in _powder defaults to sand-ish
        if (name.endsWith("_powder")) return SAND

        return null
    }
}
