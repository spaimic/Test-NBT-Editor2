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
import java.util.concurrent.CancellationException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.sqrt

/**
 * Captures the complete rectangular chunk extent present in a dimension.
 *
 * The exporter follows the same basic idea as Blocktopograph's whole-map
 * picture renderer: determine the chunk bounds once, load/render chunks in
 * parallel, then place every chunk directly into the final output stream in
 * map order. It does not render the visible MapView and does not regenerate
 * the map for preview and saving through separate pipelines.
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
    val chunkWidth: Long get() = maxChunkX.toLong() - minChunkX.toLong() + 1L
    val chunkHeight: Long get() = maxChunkZ.toLong() - minChunkZ.toLong() + 1L
    val chunkCount: Long get() = chunkWidth * chunkHeight
}

object WholeMapExporter {
    const val MIN_PIXELS_PER_BLOCK = 2
    /** Same upper UI scale as the original Blocktopograph picture picker. */
    const val MAX_PIXELS_PER_BLOCK = 24
    /** Hard safety limit: exports must stay strictly below one billion pixels. */
    const val MAX_TOTAL_PIXELS = 1_000_000_000L
    private const val RENDER_THREADS = 5

    /** Highest integer pixels/block allowed by the image-size safety limits. */
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
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false }
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
            var lastProgress = -1
            renderRows(
                bridge, bounds, dimension, pixelsPerBlock,
                consume = { row, worldRow ->
                    png.writeRow(row)
                    val percent = 70 + ((worldRow.toLong() + 1L) * 29L / height.toLong())
                        .toInt().coerceIn(70, 99)
                    if (percent != lastProgress) {
                        lastProgress = percent
                        onProgress(percent)
                    }
                },
                onChunkProgress = { percent ->
                    if (percent != lastProgress) {
                        lastProgress = percent
                        onProgress(percent)
                    }
                },
                isCancelled = isCancelled
            )
            checkCancelled(isCancelled)
            // 100% is emitted by the caller after the stream is completely closed.
        }
    }

    /**
     * Generates the same map image as export(), but downscaled for the preview.
     * Progress is reported from 0..100 while the preview rows are rendered.
     */
    fun preview(
        bridge: LevelDbBridge,
        bounds: WholeMapBounds,
        dimension: Int,
        requestedPixelsPerBlock: Int,
        maxPixels: Int = 1024,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false }
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
            var lastProgress = -1
            renderRows(
                bridge, bounds, dimension, ppb,
                consume = { row, worldRow ->
                    png.writeRow(row)
                    val percent = 70 + ((worldRow.toLong() + 1L) * 29L / height.toLong()).toInt().coerceIn(70, 99)
                    if (percent != lastProgress) {
                        lastProgress = percent
                        onProgress(percent)
                    }
                },
                onChunkProgress = { percent ->
                    if (percent != lastProgress) {
                        lastProgress = percent
                        onProgress(percent)
                    }
                },
                isCancelled = isCancelled
            )
            checkCancelled(isCancelled)
        }
        checkCancelled(isCancelled)
        val data = bytes.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: throw IllegalStateException("Unable to decode whole-map preview")
        checkCancelled(isCancelled)
        onProgress(100)
        return bitmap
    }

    private data class ChunkResult(
        val grid: Array<Array<ColumnInfo>>?,
        val unknown: Set<String>,
        val reasons: Map<String, Int>,
        val examples: Map<String, String>
    )

    /**
     * Renders one Z-chunk strip at a time. Chunk decoding is parallelized with a
     * small fixed pool, while PNG rows are still consumed strictly in top-to-bottom
     * order. This keeps memory bounded instead of allocating the entire world as a
     * giant Bitmap, while removing the old one-chunk-at-a-time bottleneck.
     */
    private fun renderRows(
        bridge: LevelDbBridge,
        b: WholeMapBounds,
        dimension: Int,
        ppb: Int,
        consume: (IntArray, Long) -> Unit,
        onChunkProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean
    ) {
        val rowWidthLong = b.widthBlocks * ppb
        require(rowWidthLong <= Int.MAX_VALUE)
        val rowWidth = rowWidthLong.toInt()
        val rgb = IntArray(rowWidth)
        val poolSize = minOf(RENDER_THREADS, b.chunkWidth.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1))
        val executor = Executors.newFixedThreadPool(poolSize)
        val unknown = HashSet<String>()
        val reasons = HashMap<String, Int>()
        val examples = HashMap<String, String>()
        var worldRow = 0L
        val totalChunks = b.chunkCount.coerceAtLeast(1L)
        var completedChunks = 0L

        try {
            for (cz in b.minChunkZ..b.maxChunkZ) {
                checkCancelled(isCancelled)
                val futures = ArrayList<Pair<Int, Future<ChunkResult>>>()
                for (cx in b.minChunkX..b.maxChunkX) {
                    checkCancelled(isCancelled)
                    val future = executor.submit(Callable {
                        val localUnknown = HashSet<String>()
                        val localReasons = HashMap<String, Int>()
                        val localExamples = HashMap<String, String>()
                        val loaded = ChunkScanner.loadChunkDirect(
                            bridge, cx, cz,
                            localUnknown, localReasons, localExamples,
                            dimension, includeMarkers = false
                        )
                        ChunkResult(
                            loaded?.grid,
                            localUnknown,
                            localReasons,
                            localExamples
                        )
                    })
                    futures.add(cx to future)
                }

                val grids = HashMap<Int, Array<Array<ColumnInfo>>>()
                for ((cx, future) in futures) {
                    checkCancelled(isCancelled)
                    val result = future.get()
                    completedChunks++
                    // Loading/decoding chunks is the expensive part. Report this
                    // phase early, then row encoding continues toward the end.
                    val chunkPercent = (completedChunks * 70L / totalChunks).toInt().coerceIn(0, 70)
                    onChunkProgress(chunkPercent)
                    unknown.addAll(result.unknown)
                    result.reasons.forEach { (key, value) -> reasons[key] = (reasons[key] ?: 0) + value }
                    result.examples.forEach { (key, value) -> examples.putIfAbsent(key, value) }
                    result.grid?.let { grids[cx] = it }
                }

                for (lz in 0..15) {
                    checkCancelled(isCancelled)
                    var cursor = 0
                    for (cx in b.minChunkX..b.maxChunkX) {
                        val grid = grids[cx]
                        for (lx in 0..15) {
                            val color = grid?.get(lx)?.get(lz)?.color ?: BlockColors.fallbackUnloaded
                            repeat(ppb) { rgb[cursor++] = color }
                        }
                    }
                    repeat(ppb) {
                        checkCancelled(isCancelled)
                        consume(rgb, worldRow)
                        worldRow++
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || isCancelled()) {
            throw CancellationException("Whole-map export cancelled")
        }
    }

    /**
     * Streaming PNG writer. Rows are reused instead of allocating a new raw
     * byte array for every row, and normal zlib compression is used so the final
     * PNG is substantially closer to Android/Blocktopograph's PNG output size.
     */
    private class PngWriter(raw: OutputStream, private val width: Int, private val height: Int) : AutoCloseable {
        private val out = java.io.BufferedOutputStream(raw, 128 * 1024)
        private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        private val buf = ByteArray(1024 * 1024)
        private val rawRow = ByteArray(width * 3 + 1)
        private var rows = 0
        private var closed = false

        init {
            out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            val ihdr = ByteArray(13)
            writeInt(ihdr, 0, width)
            writeInt(ihdr, 4, height)
            ihdr[8] = 8
            ihdr[9] = 2 // RGB
            writeChunk("IHDR", ihdr, ihdr.size)
        }

        fun writeRow(colors: IntArray) {
            require(!closed) { "PNG writer is closed" }
            require(colors.size == width) { "Expected $width pixels, got ${colors.size}" }
            // PNG Sub filtering is especially effective for this map because each
            // Minecraft block is repeated horizontally pixelsPerBlock times. The
            // repeated RGB triples therefore become mostly zero bytes before zlib
            // compression, substantially reducing PNG size without changing a
            // single visible pixel.
            rawRow[0] = 1 // PNG filter: Sub
            var j = 1
            var prevR = 0
            var prevG = 0
            var prevB = 0
            for (c in colors) {
                val r = android.graphics.Color.red(c)
                val g = android.graphics.Color.green(c)
                val b = android.graphics.Color.blue(c)
                rawRow[j++] = (r - prevR).toByte()
                rawRow[j++] = (g - prevG).toByte()
                rawRow[j++] = (b - prevB).toByte()
                prevR = r
                prevG = g
                prevB = b
            }
            deflater.setInput(rawRow)
            while (!deflater.needsInput()) {
                val n = deflater.deflate(buf)
                if (n > 0) writeChunk("IDAT", buf, n) else break
            }
            rows++
        }

        override fun close() {
            if (closed) return
            try {
                // If rendering was cancelled or failed before every row was
                // produced, do not spend extra time finishing a useless PNG.
                // The MediaStore/file layer will remove the incomplete output.
                if (rows >= height) {
                    deflater.finish()
                    while (!deflater.finished()) {
                        val n = deflater.deflate(buf)
                        if (n > 0) writeChunk("IDAT", buf, n) else break
                    }
                    writeChunk("IEND", ByteArray(0), 0)
                }
                out.flush()
            } finally {
                closed = true
                deflater.end()
                out.close()
            }
        }

        private fun writeChunk(type: String, data: ByteArray, len: Int) {
            val tb = type.toByteArray(Charsets.US_ASCII)
            writeInt(dataLength = len)
            out.write(tb)
            if (len > 0) out.write(data, 0, len)
            val crc = CRC32()
            crc.update(tb)
            if (len > 0) crc.update(data, 0, len)
            writeInt(crc.value.toInt())
        }

        private fun writeInt(dataLength: Int) {
            out.write((dataLength ushr 24) and 255)
            out.write((dataLength ushr 16) and 255)
            out.write((dataLength ushr 8) and 255)
            out.write(dataLength and 255)
        }

        private fun writeInt(a: ByteArray, o: Int, v: Int) {
            a[o] = (v ushr 24).toByte()
            a[o + 1] = (v ushr 16).toByte()
            a[o + 2] = (v ushr 8).toByte()
            a[o + 3] = v.toByte()
        }
    }
}
