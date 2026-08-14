package com.spaimic.nbttool.selection

data class WorldSelection(val startX:Int,val startZ:Int,val endX:Int,val endZ:Int) {
    val minX:Int get()=minOf(startX,endX)
    val maxX:Int get()=maxOf(startX,endX)
    val minZ:Int get()=minOf(startZ,endZ)
    val maxZ:Int get()=maxOf(startZ,endZ)
    val width:Int get()=maxX-minX+1
    val height:Int get()=maxZ-minZ+1
    val isValid:Boolean get()=width in 1..MAX_SIZE && height in 1..MAX_SIZE
    companion object {
        const val MAX_SIZE=2048
        const val DEFAULT_SIZE=64
        fun centeredAt(x:Int,z:Int):WorldSelection {
            val h=DEFAULT_SIZE/2
            return WorldSelection(x-h,z-h,x+h-1,z+h-1)
        }
    }
}
