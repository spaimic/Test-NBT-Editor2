package com.spaimic.nbttool.shizuku

import com.spaimic.nbttool.nbt.NbtTag

/**
 * Block entities (chests, shulker boxes, signs, furnaces...) are stored as
 * multiple NBT compounds concatenated under ONE LevelDB key per chunk — unlike
 * regular entities, which each get their own key. That means editing one
 * block entity and saving has to rewrite the whole chunk's list together, not
 * entry-by-entry like everything else in LevelDbSession — hence this separate
 * small holder instead of reusing that one.
 */
object BlockEntitySession {
    var rawKey: ByteArray? = null
    val compounds = ArrayList<NbtTag.Compound>()

    fun reset() {
        rawKey = null
        compounds.clear()
    }
}
