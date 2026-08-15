package com.spaimic.nbttool.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.spaimic.nbttool.chunk.BlockColors
import com.spaimic.nbttool.chunk.ChunkKeys
import com.spaimic.nbttool.chunk.ChunkScanner
import com.spaimic.nbttool.chunk.ColumnInfo
import com.spaimic.nbttool.shizuku.LevelDbBridge
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.sqrt

/**
 * Captures the complete rectangular chunk extent present in a dimension.
 *
 * Unlike WorldSelection, this intentionally has no 2048-block limit: whole-world
 * capture is a map-export operation, not an interactive selection.
 */
data class WholeMapBounds(
    val minChunkX: Int,
    val maxChunkX: Int,
    val minChunkZ: Int,
    val maxChunkZ: Int
) {
    val minX: Int get() = minChunkX * 16
    val maxX: Int get() = maxChunkX * 16 + 15
    val minZ: Int get() = minChunkZ * 16
    val maxZ: Int get() = maxChunkZ * 16 + 15
    val widthBlocks: Long get() = maxX.toLong() - minX.toLong() + 1L
    val heightBlocks: Long get() = maxZ.toLong() - minZ.toLong() + 1L
}

object WholeMapExporter {
    const val MIN_PIXELS_PER_BLOCK = 2
    const val MAX_PIXELS_PER_BLOCK = 24
    const val MAX_TOTAL_PIXELS = 5_000_000_000L

    /** Highest integer pixels/block allowed by the image-size safety limit. */
    fun maximumPixelsPerBlock(bounds: WholeMapBounds): Int {
        val area = bounds.widthBlocks * bounds.heightBlocks
        if (area <= 0L) return 0
        val maxByArea = sqrt((MAX_TOTAL_PIXELS - 1L).toDouble() / area.toDouble()).toInt()
        val maxByDimension = minOf(
            Int.MAX_VALUE.toLong() / bounds.widthBlocks,
            Int.MAX_VALUE.toLong() / bounds.heightBlocks
        ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return minOf(MAX_PIXELS_PER_BLOCK, maxByArea, maxByDimension)
    }

    fun totalPixels(bounds: WholeMapBounds, pixelsPerBlock: Int): Long =
        bounds.widthBlocks * bounds.heightBlocks * pixelsPerBlock.toLong() * pixelsPerBlock.toLong()

    fun findBounds(bridge: LevelDbBridge, dimension: Int): WholeMapBounds? {
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minZ = Int.MAX_VALUE
        var maxZ = Int.MIN_VALUE
        bridge.forEachRawKey { raw ->
            val info = ChunkKeys.parse(raw) ?: return@forEachRawKey
            if (info.dimension != dimension) return@forEachRawKey
            if (info.tag !in setOf(
                    ChunkKeys.TAG_DATA_2D, ChunkKeys.TAG_DATA_3D,
                    ChunkKeys.TAG_SUBCHUNK_PREFIX, ChunkKeys.TAG_LEGACY_TERRAIN
                )) return@forEachRawKey
            minX = minOf(minX, info.chunkX)
            maxX = maxOf(maxX, info.chunkX)
            minZ = minOf(minZ, info.chunkZ)
            maxZ = maxOf(maxZ, info.chunkZ)
        }
        return if (minX == Int.MAX_VALUE) null
        else WholeMapBounds(minX, maxX, minZ, maxZ)
    }

    fun export(
        bridge: LevelDbBridge,
        bounds: WholeMapBounds,
        dimension: Int,
        pixelsPerBlock: Int,
        output: OutputStream,
        onProgress: (Int) -> Unit = {}
    ) {
        require(pixelsPerBlock in MIN_PIXELS_PER_BLOCK..MAX_PIXELS_PER_BLOCK)
        require(totalPixels(bounds, pixelsPerBlock) < MAX_TOTAL_PIXELS) {
            "Image exceeds ${MAX_TOTAL_PIXELS - 1} pixels: ${totalPixels(bounds, pixelsPerBlock)}"
        }
        val widthLong = bounds.widthBlocks * pixelsPerBlock
        val heightLong = bounds.heightBlocks * pixelsPerBlock
        require(widthLong <= Int.MAX_VALUE && heightLong <= Int.MAX_VALUE) {
            "Image dimensions are too large for PNG: ${widthLong}×${heightLong}"
        }
        val width = widthLong.toInt()
        val height = heightLong.toInt()
        PngWriter(output, width, height).use { png ->
            val totalRows = bounds.heightBlocks.coerceAtLeast(1L)
            renderRows(bridge, bounds, dimension, pixelsPerBlock) { row, worldRow ->
                png.writeRow(row)
                val percent = ((worldRow.toLong() * 95L) / totalRows).toInt().coerceIn(0, 95)
                onProgress(percent)
            }
            // 100% is emitted by the caller only after PNG encoding and closing complete.
        }
    }

    fun preview(
        bridge: LevelDbBridge,
        bounds: WholeMapBounds,
        dimension: Int,
        requestedPixelsPerBlock: Int,
        maxPixels: Int = 1024
    ): Bitmap {
        val requested = requestedPixelsPerBlock.coerceIn(MIN_PIXELS_PER_BLOCK, MAX_PIXELS_PER_BLOCK)
        val maxBlock = maxOf(bounds.widthBlocks, bounds.heightBlocks)
        val ppb = minOf(requested, maxOf(1, (maxPixels.toLong() / maxBlock).toInt()))
            .coerceIn(1, requested)
        val width = (bounds.widthBlocks * ppb).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val height = (bounds.heightBlocks * ppb).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        require(width > 0 && height > 0)
        val bytes = ByteArrayOutputStream((width * height).coerceAtMost(4 * 1024 * 1024))
        PngWriter(bytes, width, height).use { png ->
            renderRows(bridge, bounds, dimension, ppb) { row, _ -> png.writeRow(row) }
        }
        val data = bytes.toByteArray()
        return BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: throw IllegalStateException("Unable to decode whole-map preview")
    }

    private fun renderRows(
        bridge: LevelDbBridge,
        b: WholeMapBounds,
        dimension: Int,
        ppb: Int,
        consume: (IntArray, Long) -> Unit
    ) {
        val unknown = HashSet<String>()
        val reasons = HashMap<String, Int>()
        val examples = HashMap<String, String>()
        val rowWidthLong = b.widthBlocks * ppb
        require(rowWidthLong <= Int.MAX_VALUE)
        val rowWidth = rowWidthLong.toInt()
        val rgb = IntArray(rowWidth)
        var worldRow = 0L

        for (cz in b.minChunkZ..b.maxChunkZ) {
            val grids = HashMap<Int, Array<Array<ColumnInfo>>>()
            for (cx in b.minChunkX..b.maxChunkX) {
                val loaded = ChunkScanner.loadChunkDirect(
                    bridge, cx, cz, unknown, reasons, examples, dimension, includeMarkers = false
                )
                if (loaded != null) grids[cx] = loaded.grid
            }
            for (lz in 0..15) {
                var cursor = 0
                for (cx in b.minChunkX..b.maxChunkX) {
                    val grid = grids[cx]
                    for (lx in 0..15) {
                        val color = grid?.get(lx)?.get(lz)?.color ?: BlockColors.fallbackUnloaded
                        repeat(ppb) { rgb[cursor++] = color }
                    }
                }
                repeat(ppb) {
                    consume(rgb.copyOf(), worldRow)
                    worldRow++
                }
            }
        }
    }

    private class PngWriter(raw: OutputStream, private val width: Int, private val height: Int) : AutoCloseable {
        private val out = java.io.BufferedOutputStream(raw, 64 * 1024)
        private val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED)
        private val buf = ByteArray(1024 * 1024)
        private var rows = 0
        init {
            out.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
            val ihdr=ByteArray(13)
            writeInt(ihdr,0,width); writeInt(ihdr,4,height); ihdr[8]=8; ihdr[9]=2
            writeChunk("IHDR",ihdr)
        }
        fun writeRow(colors: IntArray) {
            val raw=ByteArray(colors.size*3+1); raw[0]=0
            var j=1
            for(c in colors){raw[j++]=android.graphics.Color.red(c).toByte();raw[j++]=android.graphics.Color.green(c).toByte();raw[j++]=android.graphics.Color.blue(c).toByte()}
            if(rows==0) deflater.reset()
            deflater.setInput(raw)
            while(!deflater.needsInput()){
                val n=deflater.deflate(buf)
                if(n>0) writeChunk("IDAT",buf,n) else break
            }
            rows++
        }
        override fun close(){
            deflater.finish()
            while(!deflater.finished()){
                val n=deflater.deflate(buf)
                if(n>0) writeChunk("IDAT",buf,n) else break
            }
            writeChunk("IEND",ByteArray(0))
            out.flush(); out.close(); deflater.end()
        }
        private fun writeChunk(type:String,data:ByteArray,len:Int=data.size){
            val tb=type.toByteArray(Charsets.US_ASCII)
            val actual=if(len==data.size)data else data.copyOf(len)
            writeInt(actual.size); out.write(tb); out.write(actual)
            val crc=java.util.zip.CRC32(); crc.update(tb); crc.update(actual); writeInt(crc.value.toInt())
        }
        private fun writeInt(v:Int){out.write((v ushr 24) and 255);out.write((v ushr 16) and 255);out.write((v ushr 8) and 255);out.write(v and 255)}
        private fun writeInt(a:ByteArray,o:Int,v:Int){a[o]=(v ushr 24).toByte();a[o+1]=(v ushr 16).toByte();a[o+2]=(v ushr 8).toByte();a[o+3]=v.toByte()}
    }
}
