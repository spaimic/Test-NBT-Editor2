package com.spaimic.nbttool.chunk

import android.graphics.Color

/** Bedrock numeric biome ids -> display name and map color. */
object Biomes {
    private class B(val name: String, val rgb: Int)

    private val table: Map<Int, B> = mapOf(
        0 to B("Ocean", 0x000070), 1 to B("Plains", 0x8DB360), 2 to B("Desert", 0xFA9418),
        3 to B("Windswept Hills", 0x606060), 4 to B("Forest", 0x056621), 5 to B("Taiga", 0x0B6659),
        6 to B("Swamp", 0x07F9B2), 7 to B("River", 0x0000FF), 8 to B("Nether Wastes", 0xBF3B3B),
        9 to B("The End", 0x8080FF), 10 to B("Frozen Ocean", 0x7070D6), 11 to B("Frozen River", 0xA0A0FF),
        12 to B("Snowy Plains", 0xFFFFFF), 13 to B("Snowy Mountains", 0xA0A0A0),
        14 to B("Mushroom Fields", 0xFF00FF), 15 to B("Mushroom Shore", 0xA000FF),
        16 to B("Beach", 0xFADE55), 17 to B("Desert Hills", 0xD25F12), 18 to B("Wooded Hills", 0x22551C),
        19 to B("Taiga Hills", 0x163933), 20 to B("Mountain Edge", 0x72789A), 21 to B("Jungle", 0x537B09),
        22 to B("Jungle Hills", 0x2C4205), 23 to B("Sparse Jungle", 0x628B17), 24 to B("Deep Ocean", 0x000030),
        25 to B("Stony Shore", 0xA2A284), 26 to B("Snowy Beach", 0xFAF0C0), 27 to B("Birch Forest", 0x307444),
        28 to B("Birch Forest Hills", 0x1F5F32), 29 to B("Dark Forest", 0x40511A),
        30 to B("Snowy Taiga", 0x31554A), 31 to B("Snowy Taiga Hills", 0x243F36),
        32 to B("Old Growth Pine Taiga", 0x596651), 33 to B("Giant Tree Taiga Hills", 0x454F3E),
        34 to B("Windswept Forest", 0x507050), 35 to B("Savanna", 0xBDB25F),
        36 to B("Savanna Plateau", 0xA79D64), 37 to B("Badlands", 0xD94515),
        38 to B("Wooded Badlands", 0xB09765), 39 to B("Badlands Plateau", 0xCA8C65),
        40 to B("Warm Ocean", 0x0000AC), 41 to B("Deep Warm Ocean", 0x000050),
        42 to B("Lukewarm Ocean", 0x000090), 43 to B("Deep Lukewarm Ocean", 0x000040),
        44 to B("Cold Ocean", 0x202070), 45 to B("Deep Cold Ocean", 0x202038),
        46 to B("Frozen Ocean", 0x7070D6), 47 to B("Deep Frozen Ocean", 0x404090),
        48 to B("Bamboo Jungle", 0x768E14), 49 to B("Bamboo Jungle Hills", 0x3B470A),
        129 to B("Sunflower Plains", 0xB5DB88), 130 to B("Desert Lakes", 0xFFBC40),
        131 to B("Windswept Gravelly Hills", 0x789878), 132 to B("Flower Forest", 0x2D8E49),
        133 to B("Taiga Mountains", 0x338E81), 134 to B("Swamp Hills", 0x2FFFDA),
        140 to B("Ice Spikes", 0xB4DCDC), 149 to B("Modified Jungle", 0x7BA331),
        151 to B("Modified Jungle Edge", 0x8AB33F), 155 to B("Old Growth Birch Forest", 0x589C6C),
        156 to B("Tall Birch Hills", 0x47875A), 157 to B("Dark Forest Hills", 0x687942),
        158 to B("Snowy Taiga Mountains", 0x597D72), 160 to B("Old Growth Spruce Taiga", 0x818E79),
        161 to B("Giant Spruce Taiga Hills", 0x6D7766), 162 to B("Gravelly Mountains+", 0x789878),
        163 to B("Windswept Savanna", 0xE5DA87), 164 to B("Shattered Savanna Plateau", 0xCFC58C),
        165 to B("Eroded Badlands", 0xFF6D3D), 166 to B("Modified Wooded Badlands Plateau", 0xD8BF8D),
        167 to B("Modified Badlands Plateau", 0xF2B48D),
        // 1.16+ / 1.18+ biomes. Bedrock numbers the Nether biomes from 178 and the
        // cave/mountain/new overworld biomes after them (verified against Blocktopograph).
        178 to B("Soul Sand Valley", 0x5E3830), 179 to B("Crimson Forest", 0xDD0808),
        180 to B("Warped Forest", 0x49907B), 181 to B("Basalt Deltas", 0x403636),
        182 to B("Jagged Peaks", 0xDCE4EC), 183 to B("Frozen Peaks", 0xB0C8E0),
        184 to B("Snowy Slopes", 0xE8F0F8), 185 to B("Grove", 0x9DBBAA), 186 to B("Meadow", 0x60A445),
        187 to B("Lush Caves", 0x8FBF3A), 188 to B("Dripstone Caves", 0x7B5F3A),
        189 to B("Stony Peaks", 0x888888), 190 to B("Deep Dark", 0x0A2A30),
        191 to B("Mangrove Swamp", 0x67B63A), 192 to B("Cherry Grove", 0xF4B8D4),
        193 to B("Pale Garden", 0x9AA39A)
    )

    fun name(id: Int): String = table[id]?.name ?: "Biome #$id"

    /** Opaque ARGB color for [id]; unknown ids get a stable color derived from the id. */
    fun color(id: Int): Int {
        val b = table[id]
        if (b != null) return Color.rgb((b.rgb shr 16) and 0xFF, (b.rgb shr 8) and 0xFF, b.rgb and 0xFF)
        return Color.HSVToColor(floatArrayOf((id * 47 % 360).toFloat(), 0.55f, 0.75f))
    }
}
