package com.spaimic.nbttool.chunk

import android.content.Context
import com.spaimic.nbttool.R

/**
 * Maps a block entity's "id" field (e.g. "Chest", "ShulkerBox", "BrewingStand")
 * to an icon drawable. Unlike EntityIcons, this doesn't hardcode every mapping —
 * Bedrock's exact block-entity id strings aren't something I have high confidence
 * in for every type, so this converts PascalCase -> snake_case and looks the
 * resource up by name at runtime (works automatically for anything that
 * converts cleanly, e.g. Chest -> chest, BlastFurnace -> blast_furnace), with a
 * small alias table for the known mismatches, falling back to the question-mark
 * placeholder for anything else — exactly as asked for.
 */
object TileEntityIcons {

    // Known cases where the id doesn't convert cleanly to the icon filename.
    private val aliases: Map<String, Int> = mapOf(
        "MobSpawner" to R.drawable.monster_spawner,
        "EnchantTable" to R.drawable.enchanting_table,
        "Comparator" to R.drawable.redstone_comparator,
        "Jigsaw" to R.drawable.jigsaw,
        "JigsawBlock" to R.drawable.jigsaw,
        "Dropper" to R.drawable.dropped, // asset filename appears to be a typo for "dropper"
        "NetherReactor" to R.drawable.nether_reactor_core,
        "PistonArm" to R.drawable.piston,
        "Music" to R.drawable.note_block
    )

    fun iconFor(context: Context, id: String): Int {
        aliases[id]?.let { return it }
        val snakeCase = id
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase()
        val resId = context.resources.getIdentifier(snakeCase, "drawable", context.packageName)
        return if (resId != 0) resId else R.drawable.question_mark
    }
}
