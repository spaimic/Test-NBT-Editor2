package com.spaimic.nbttool.chunk

import com.spaimic.nbttool.shizuku.LevelDbBridge
import com.spaimic.nbttool.selection.WorldSelection

/** Clears the selected X/Z rectangle to air while preserving blocks outside it. */
object AreaDeleter {
    fun clearSelection(bridge: LevelDbBridge, selection: WorldSelection, dimension: Int = 0) {
        val minCx=Math.floorDiv(selection.minX,16)
        val maxCx=Math.floorDiv(selection.maxX,16)
        val minCz=Math.floorDiv(selection.minZ,16)
        val maxCz=Math.floorDiv(selection.maxZ,16)
        for(cx in minCx..maxCx) for(cz in minCz..maxCz) {
            val lx0=Math.floorMod(selection.minX,16).takeIf { cx==minCx } ?: 0
            val lx1=Math.floorMod(selection.maxX,16).takeIf { cx==maxCx } ?: 15
            val lz0=Math.floorMod(selection.minZ,16).takeIf { cz==minCz } ?: 0
            val lz1=Math.floorMod(selection.maxZ,16).takeIf { cz==maxCz } ?: 15
            val full=lx0==0 && lx1==15 && lz0==0 && lz1==15
            if(full) {
                ChunkDeleter.clearChunkToAir(bridge,cx,cz,dimension)
                continue
            }
            for(subY in -4..19) {
                val key=ChunkKeys.subChunkKey(cx,cz,subY,dimension)
                val bytes=bridge.read(key) ?: continue
                bridge.write(key,SubChunkEditor.clearColumnsToAir(bytes,lx0,lx1,lz0,lz1))
            }
        }
    }
}
