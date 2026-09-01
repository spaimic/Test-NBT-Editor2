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
private const val BUILD_180_FAST_RENDER = true

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
    private val RENDER_THREADS: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

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
            onProgress(0)
            renderRows(
                bridge, bounds, dimension, pixelsPerBlock,
                consume = { row, worldRow ->
                    png.writeRow(row)
                    val percent = 75 + ((worldRow + 1L) * 24L / height.toLong())
                        .toInt().coerceIn(75, 99)
                    if (percent != lastProgress) {
                        lastProgress = percent
                        onProgress(percent)
                    }
                },
                onScanProgress = { scan ->
                    // Scan and rendering overlap, so progress is one unified 0..100
                    // scale. The scan only owns the first 15%, then workers
                    // and row assembly advance the same scale.
                    val mapped = (scan * 15 / 100).coerceIn(0, 15)
                    if (mapped > lastProgress) {
                        lastProgress = mapped
                        onProgress(mapped)
                    }
                },
                onChunkProgress = { chunk ->
                    val mapped = (15 + chunk * 60 / 100).coerceIn(15, 75)
                    if (mapped > lastProgress) {
                        lastProgress = mapped
                        onProgress(mapped)
                    }
                },
                isCancelled = isCancelled
            )
            checkCancelled(isCancelled)
            // 100% is emitted by the caller after the stream is completely closed.
        }
    }

    /**
     * Saves the already-rendered whole-map preview without reading LevelDB again.
     *
     * The whole-map preview is a block-color image: every preview pixel (or
     * preview pixel block) represents one Minecraft block color. The final PNG
     * simply repeats each block color horizontally and vertically to the
     * requested pixels-per-block scale. This avoids the expensive second
     * LevelDB scan + chunk decode that the old save path performed after the
     * user had already waited for the preview.
     *
     * The preview bitmap is already held by the preview dialog, so this adds no
     * second copy of the preview image. PNG encoding remains lossless.
     */
    fun exportFromPreview(
        preview: Bitmap,
        bounds: WholeMapBounds,
        outputPixelsPerBlock: Int,
        output: OutputStream,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ) {
        require(outputPixelsPerBlock in MIN_PIXELS_PER_BLOCK..MAX_PIXELS_PER_BLOCK)

        // Bitmap dimensions are Int while WholeMapBounds dimensions are Long.
        // Convert the derived preview scale back to Int before using it in
        // Bitmap/getPixels and array indexing APIs.
        val previewPpbX = (preview.width.toLong() / bounds.widthBlocks.coerceAtLeast(1L))
            .toInt()
        val previewPpbY = (preview.height.toLong() / bounds.heightBlocks.coerceAtLeast(1L))
            .toInt()
        require(previewPpbX >= 1 && previewPpbX == previewPpbY) {
            "Preview dimensions do not match whole-map block bounds"
        }
        require(preview.width.toLong() == bounds.widthBlocks * previewPpbX.toLong())
        require(preview.height.toLong() == bounds.heightBlocks * previewPpbX.toLong())

        val widthLong = bounds.widthBlocks.toLong() * outputPixelsPerBlock
        val heightLong = bounds.heightBlocks.toLong() * outputPixelsPerBlock
        require(widthLong <= Int.MAX_VALUE && heightLong <= Int.MAX_VALUE)
        require(widthLong * heightLong < MAX_TOTAL_PIXELS) {
            "Image exceeds ${MAX_TOTAL_PIXELS - 1} pixels"
        }

        val width = widthLong.toInt()
        val height = heightLong.toInt()
        require(bounds.widthBlocks <= Int.MAX_VALUE.toLong() && bounds.heightBlocks <= Int.MAX_VALUE.toLong())
        val blockWidth = bounds.widthBlocks.toInt()
        val blockHeight = bounds.heightBlocks.toInt()
        val previewRow = IntArray(preview.width)
        val blockColors = IntArray(blockWidth)
        val outputRow = IntArray(width)

        PngWriter(output, width, height).use { png ->
            onProgress(0)
            var lastProgress = 0
            var outputRowIndex = 0L

            for (blockY in 0 until blockHeight) {
                checkCancelled(isCancelled)

                // Read one representative preview pixel per Minecraft block.
                // The preview renderer paints each block uniformly, even when
                // previewPpb > 1, so the first pixel of each preview block is
                // sufficient.
                preview.getPixels(
                    previewRow, 0, preview.width,
                    0, blockY * previewPpbX,
                    preview.width, 1
                )
                for (blockX in 0 until blockWidth) {
                    blockColors[blockX] = previewRow[blockX * previewPpbX]
                }

                // Expand one block-color row to the requested full resolution.
                var cursor = 0
                for (blockX in 0 until blockWidth) {
                    val color = blockColors[blockX]
                    repeat(outputPixelsPerBlock) { outputRow[cursor++] = color }
                }

                repeat(outputPixelsPerBlock) {
                    checkCancelled(isCancelled)
                    png.writeRow(outputRow)
                    outputRowIndex++
                    val percent = (outputRowIndex * 99L / heightLong)
                        .toInt().coerceIn(0, 99)
                    if (percent != lastProgress) {
                        lastProgress = percent
                        onProgress(percent)
                    }
                }
            }
            checkCancelled(isCancelled)
        }
        onProgress(100)
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
        val ppb = minOf(
            requested,
            maxOf(1, (maxPixels.toLong() / maxBlock).toInt())
        ).coerceIn(1, requested)

        val width = (bounds.widthBlocks * ppb)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val height = (bounds.heightBlocks * ppb)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        require(width > 0 && height > 0)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var lastProgress = -1
        onProgress(0)

        renderRows(
            bridge,
            bounds,
            dimension,
            ppb,
            consume = { row, worldRow ->
                checkCancelled(isCancelled)
                bitmap.setPixels(row, 0, width, 0, worldRow.toInt(), width, 1)

                // Row assembly is the final 25% of the unified 0..100 progress.
                val percent = 75 + ((worldRow + 1L) * 24L / height.toLong())
                    .toInt().coerceIn(75, 99)
                if (percent > lastProgress) {
                    lastProgress = percent
                    onProgress(percent)
                }
            },
            onScanProgress = { scan ->
                val mapped = (scan * 15 / 100).coerceIn(0, 15)
                if (mapped > lastProgress) {
                    lastProgress = mapped
                    onProgress(mapped)
                }
            },
            onChunkProgress = { chunk ->
                val mapped = (15 + chunk * 60 / 100).coerceIn(15, 75)
                if (mapped > lastProgress) {
                    lastProgress = mapped
                    onProgress(mapped)
                }
            },
            isCancelled = isCancelled
        )

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
    /**
     * Build 171 whole-map renderer.
     *
     * Unlike the old Z-strip batches, this keeps a bounded sliding window of
     * chunk jobs across the ENTIRE map. Workers stay continuously fed while the
     * writer consumes chunks in deterministic Z/X order.
     *
     * The output format and PNG writer are deliberately unchanged from the
     * successful Build 163 baseline.
     */
    /**
     * Build 173 whole-map renderer.
     *
     * Uses a bounded sliding window of chunk-load jobs across the whole map.
     * Every completed future carries its own (x,z) coordinate, while the
     * coordinate map stores only ChunkResult. This keeps Kotlin types simple
     * and avoids the Completed/ChunkResult mismatch from Builds 171/172.
     */
    private fun renderRows(
        bridge: LevelDbBridge,
        b: WholeMapBounds,
        dimension: Int,
        ppb: Int,
        consume: (IntArray, Long) -> Unit,
        onScanProgress: (Int) -> Unit = {},
        onChunkProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean
    ) {
        val rowWidthLong = b.widthBlocks * ppb.toLong()
        require(rowWidthLong <= Int.MAX_VALUE.toLong())
        val rowWidth = rowWidthLong.toInt()

        val chunkWidthLong =
            (b.maxChunkX.toLong() - b.minChunkX.toLong() + 1L).coerceAtLeast(1L)
        val chunkHeightLong =
            (b.maxChunkZ.toLong() - b.minChunkZ.toLong() + 1L).coerceAtLeast(1L)
        val totalChunks = (chunkWidthLong * chunkHeightLong).coerceAtLeast(1L)

        val poolSize = minOf(
            RENDER_THREADS,
            chunkWidthLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
        )

        data class CompletedChunk(
            val x: Int,
            val z: Int,
            val result: ChunkResult
        )

        val executor = Executors.newFixedThreadPool(poolSize)
        val completion =
            java.util.concurrent.ExecutorCompletionService<CompletedChunk>(executor)

        val maxInFlight = (poolSize * 8).coerceIn(poolSize, 64)
        val completedByCoord = HashMap<Long, ChunkResult>(maxInFlight * 2)

        fun coordKey(x: Int, z: Int): Long =
            (x.toLong() shl 32) xor (z.toLong() and 0xFFFFFFFFL)

        // Bounds how many chunks' raw bytes can be queued/decoding at once —
        // one permit per submitted chunk, released the moment that chunk's
        // Callable finishes parsing (see submitChunk below), NOT when the
        // row-writing loop later consumes its (small, already-parsed) result.
        // Build 175 read the whole export region's raw subchunk bytes into
        // memory before rendering a single pixel, which OOM'd on large
        // worlds. An earlier version of this fix released permits on
        // row-major consumption instead of on parse completion, which
        // deadlocked: the scan discovers chunks in LevelDB's raw key order
        // (scrambled, not row-major), so it could fill every permit with
        // coordinates the row-writer doesn't need yet, then block forever
        // waiting for a release only the still-blocked writer could trigger.
        // Releasing on parse completion keeps this bounding what it's
        // actually meant to bound (raw bytes in flight) with no ordering
        // dependency on the consumer.
        val inFlight = java.util.concurrent.Semaphore(maxInFlight)

        fun submitChunk(x: Int, z: Int, subchunks: List<Pair<Int, ByteArray>>, biome: ByteArray?) {
            inFlight.acquire()
            completion.submit(Callable {
                try {
                    val localUnknown = HashSet<String>()
                    val localReasons = HashMap<String, Int>()
                    val localExamples = HashMap<String, String>()
                    val grid = ChunkScanner.loadChunkFromRawDataFast(
                        subchunks, biome, dimension, localUnknown, localReasons, localExamples
                    )
                    CompletedChunk(x, z, ChunkResult(grid, localUnknown, localReasons, localExamples))
                } finally {
                    // Release as soon as the raw bytes are no longer needed —
                    // NOT when the row-writer below happens to consume this
                    // coordinate. The scan discovers chunks in LevelDB's raw
                    // key order (scrambled by little-endian coordinate
                    // encoding), not row-major order, so the row-writer (which
                    // always wants the top-left-most remaining coordinate
                    // next) will very often need a chunk the scan hasn't
                    // reached yet. Releasing only on row-major consumption
                    // deadlocks: the scan fills all `maxInFlight` permits with
                    // scattered coordinates the writer doesn't need yet, then
                    // blocks forever waiting for a release that only the
                    // still-blocked writer could trigger. Releasing here
                    // keeps the semaphore bounding what it's actually meant to
                    // bound — raw bytes in flight — without an ordering
                    // dependency on consumption.
                    inFlight.release()
                }
            })
        }

        val scanError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

        // Scans the whole database exactly once (on this dedicated thread, in
        // parallel with the row-writing/consumer loop below) and submits a
        // render job the moment each chunk's records are fully read — never
        // holding more than `maxInFlight` chunks' raw bytes at a time thanks
        // to the semaphore. Chunk coordinates the scan never sees at all (no
        // data in the world) are unloaded/unexplored chunks; a synthetic empty
        // result is submitted for those afterward so every coordinate in
        // bounds still gets exactly one CompletedChunk, same as before.
        val discovered = HashSet<Long>()
        val scanThread = Thread({
            try {
                bridge.scanChunkRecordsStreaming(
                    dimension,
                    b.minChunkX, b.maxChunkX,
                    b.minChunkZ, b.maxChunkZ,
                    onProgress = onScanProgress
                ) { x, z, subchunks, biome ->
                    checkCancelled(isCancelled)
                    discovered.add(coordKey(x, z))
                    submitChunk(x, z, subchunks, biome)
                }
                for (cz in b.minChunkZ..b.maxChunkZ) {
                    for (cx in b.minChunkX..b.maxChunkX) {
                        checkCancelled(isCancelled)
                        val key = coordKey(cx, cz)
                        if (key !in discovered) {
                            inFlight.acquire()
                            completion.submit(Callable {
                                try {
                                    CompletedChunk(cx, cz, ChunkResult(null, emptySet(), emptyMap(), emptyMap()))
                                } finally {
                                    // Synthetic empty chunks consume the same in-flight permit.
                                    // Release it when the task completes, just like real chunks.
                                    inFlight.release()
                                }
                            })
                        }
                    }
                }
            } catch (t: Throwable) {
                scanError.set(t)
            }
        }, "WholeMapExporter-scan")
        scanThread.isDaemon = true
        scanThread.start()

        var completed = 0L

        val unknown = HashSet<String>()
        val reasons = HashMap<String, Int>()
        val examples = HashMap<String, String>()

        try {
            onChunkProgress(0)

            var worldRow = 0L

            for (cz in b.minChunkZ..b.maxChunkZ) {
                checkCancelled(isCancelled)
                val grids = HashMap<Int, Array<Array<ColumnInfo>>>(
                    chunkWidthLong.toInt().coerceAtMost(1024)
                )

                for (cx in b.minChunkX..b.maxChunkX) {
                    checkCancelled(isCancelled)
                    val key = coordKey(cx, cz)

                    while (!completedByCoord.containsKey(key)) {
                        checkCancelled(isCancelled)
                        scanError.get()?.let { throw it }

                        val done = completion.take().get()
                        completedByCoord[coordKey(done.x, done.z)] = done.result
                        completed++

                        unknown.addAll(done.result.unknown)
                        for ((k, v) in done.result.reasons) {
                            reasons[k] = (reasons[k] ?: 0) + v
                        }
                        for ((k, v) in done.result.examples) {
                            examples.putIfAbsent(k, v)
                        }

                        onChunkProgress(
                            ((completed * 45L) / totalChunks)
                                .toInt()
                                .coerceIn(0, 45)
                        )
                    }

                    val result = completedByCoord.remove(key)
                        ?: throw IllegalStateException(
                            "Missing chunk result for $cx,$cz"
                        )

                    result.grid?.let { grids[cx] = it }
                }

                val rgb = IntArray(rowWidth)

                for (lz in 0..15) {
                    checkCancelled(isCancelled)

                    var cursor = 0

                    for (cx in b.minChunkX..b.maxChunkX) {
                        val grid = grids[cx]

                        for (lx in 0..15) {
                            val color = grid?.get(lx)?.get(lz)?.color
                                ?: BlockColors.fallbackUnloaded

                            repeat(ppb) {
                                rgb[cursor++] = color
                            }
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
            scanThread.interrupt()
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
    /**
     * Lossless indexed-color PNG writer. NBT Tool's map renderer uses a small,
     * fixed map-color palette, so an 8-bit indexed PNG stores one byte per
     * pixel instead of three RGB bytes. This keeps the PNG lossless while
     * making large map captures much smaller.
     */
    /**
     * Streaming true-color PNG writer.
     *
     * The indexed writer used in earlier builds depended on a hardcoded palette.
     * MapShading can generate runtime colors that are not known ahead of time.
     * Standard RGB PNG output is used here so every 24-bit map color is valid.
     */
    /**
     * Streaming true-color PNG writer with an adaptive row filter.
     *
     * The same map row is emitted repeatedly for pixels-per-block > 1. When a
     * row is identical to the previous row, the PNG Up filter turns the whole
     * row into zeros, which compresses dramatically better than filtering each
     * row independently. For the first/different row we use Sub filtering.
     * No hardcoded color palette is involved, so runtime MapShading colors are
     * always supported.
     */
    private class PngWriter(
        raw: OutputStream,
        private val width: Int,
        private val height: Int
    ) : AutoCloseable {
        private val out = java.io.BufferedOutputStream(raw, 1024 * 1024)
        private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        private val buf = ByteArray(256 * 1024)
        private val idatBuffer = ByteArrayOutputStream(1024 * 1024)
        private val rawRow = ByteArray(width * 3 + 1)
        private val previousColors = IntArray(width)
        private var hasPrevious = false
        private var rows = 0
        private var closed = false

        init {
            out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            val ihdr = ByteArray(13)
            writeInt(ihdr, 0, width)
            writeInt(ihdr, 4, height)
            ihdr[8] = 8
            ihdr[9] = 2
            writeChunk("IHDR", ihdr, ihdr.size)
        }

        fun writeRow(colors: IntArray) {
            require(!closed) { "PNG writer is closed" }
            require(colors.size == width) { "Expected $width pixels, got ${colors.size}" }

            val sameAsPrevious = hasPrevious && colors.contentEquals(previousColors)
            rawRow[0] = if (sameAsPrevious) 2 else 1 // Up or Sub

            var pos = 1
            var prevR = 0
            var prevG = 0
            var prevB = 0
            for (i in colors.indices) {
                val rgb = colors[i] and 0xFFFFFF
                val r = (rgb ushr 16) and 0xFF
                val g = (rgb ushr 8) and 0xFF
                val b = rgb and 0xFF

                if (sameAsPrevious) {
                    val prev = previousColors[i] and 0xFFFFFF
                    rawRow[pos++] = (r - ((prev ushr 16) and 0xFF)).toByte()
                    rawRow[pos++] = (g - ((prev ushr 8) and 0xFF)).toByte()
                    rawRow[pos++] = (b - (prev and 0xFF)).toByte()
                } else {
                    rawRow[pos++] = (r - prevR).toByte()
                    rawRow[pos++] = (g - prevG).toByte()
                    rawRow[pos++] = (b - prevB).toByte()
                }
                prevR = r
                prevG = g
                prevB = b
            }

            System.arraycopy(colors, 0, previousColors, 0, width)
            hasPrevious = true

            deflater.setInput(rawRow)
            while (!deflater.needsInput()) {
                val n = deflater.deflate(buf)
                if (n <= 0) break
                idatBuffer.write(buf, 0, n)
                if (idatBuffer.size() >= 1024 * 1024) flushIdat()
            }
            rows++
        }

        override fun close() {
            if (closed) return
            try {
                if (rows == height) {
                    deflater.finish()
                    while (!deflater.finished()) {
                        val n = deflater.deflate(buf)
                        if (n <= 0) break
                        idatBuffer.write(buf, 0, n)
                        if (idatBuffer.size() >= 1024 * 1024) flushIdat()
                    }
                    flushIdat()
                    writeChunk("IEND", ByteArray(0), 0)
                }
                out.flush()
            } finally {
                closed = true
                deflater.end()
                out.close()
            }
        }

        private fun flushIdat() {
            if (idatBuffer.size() == 0) return
            val bytes = idatBuffer.toByteArray()
            writeChunk("IDAT", bytes, bytes.size)
            idatBuffer.reset()
        }

        private fun writeChunk(type: String, data: ByteArray, len: Int) {
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            writeInt(len)
            out.write(typeBytes)
            if (len > 0) out.write(data, 0, len)
            val crc = CRC32()
            crc.update(typeBytes)
            if (len > 0) crc.update(data, 0, len)
            writeInt(crc.value.toInt())
        }

        private fun writeInt(v: Int) {
            out.write((v ushr 24) and 255)
            out.write((v ushr 16) and 255)
            out.write((v ushr 8) and 255)
            out.write(v and 255)
        }

        private fun writeInt(a: ByteArray, offset: Int, v: Int) {
            a[offset] = (v ushr 24).toByte()
            a[offset + 1] = (v ushr 16).toByte()
            a[offset + 2] = (v ushr 8).toByte()
            a[offset + 3] = v.toByte()
        }
    }
}
