package com.spaimic.nbttool.chunk

/**
 * Bedrock Edition slime chunks. Unlike Java Edition they do NOT depend on the world
 * seed: a chunk is a slime chunk when the first output of an MT19937 generator seeded
 * with (chunkX * 0x1f1f1f1f) xor chunkZ is divisible by 10. Overworld only.
 */
object SlimeChunks {
    fun isSlime(chunkX: Int, chunkZ: Int): Boolean {
        val m = 397
        val mt = IntArray(m + 1)
        mt[0] = (chunkX * 0x1f1f1f1f) xor chunkZ
        for (i in 1..m) mt[i] = 1812433253 * (mt[i - 1] xor (mt[i - 1] ushr 30)) + i
        // Only the very first output is needed, so a single twist step is enough.
        val y = (mt[0] and 0x80000000.toInt()) or (mt[1] and 0x7fffffff)
        var v = mt[m] xor (y ushr 1)
        if ((y and 1) != 0) v = v xor 0x9908b0df.toInt()
        v = v xor (v ushr 11)
        v = v xor ((v shl 7) and 0x9d2c5680.toInt())
        v = v xor ((v shl 15) and 0xefc60000.toInt())
        v = v xor (v ushr 18)
        return java.lang.Integer.remainderUnsigned(v, 10) == 0
    }
}
