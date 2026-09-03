package com.spaimic.nbttool.export


import android.graphics.Bitmap
import com.spaimic.nbttool.chunk.BlockColors
import com.spaimic.nbttool.chunk.ChunkKeys
import com.spaimic.nbttool.chunk.ChunkScanner
import com.spaimic.nbttool.chunk.ColumnInfo
import com.spaimic.nbttool.chunk.MapViewerRenderStyle
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
                    // Final row assembly owns the last 30% of progress.
                    val percent = 70 + ((worldRow + 1L) * 29L / height.toLong())
                        .toInt().coerceIn(70, 99)
                    if (percent != lastProgress) {
                        lastProgress = percent
                        onProgress(percent)
                    }
                },
                onScanProgress = { scan ->
                    // Scan progress owns the first 20% of the unified scale.
                    val mapped = (scan * 20 / 100).coerceIn(0, 20)
                    if (mapped > lastProgress) {
                        lastProgress = mapped
                        onProgress(mapped)
                    }
                },
                onChunkProgress = { chunk ->
                    // Chunk decoding owns the next 50%. renderRows reports this
                    // callback as a true 0..100 percentage.
                    val mapped = (20 + chunk * 50 / 100).coerceIn(20, 70)
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
        require(maxPixels > 0) { "maxPixels must be positive" }
        requestedPixelsPerBlock.coerceIn(MIN_PIXELS_PER_BLOCK, MAX_PIXELS_PER_BLOCK)

        // Never allocate a full-resolution Bitmap just to display a preview.
        // A large world can be many thousands of blocks wide/tall; at 4
        // px/block the full image in the user's example would be ~1 billion
        // pixels (~4 GB for ARGB_8888), which can crash Android as soon as the
        // preview reaches 100%. Render one block per source pixel, then sample
        // that stream down to a small display bitmap.
        val maxBlock = maxOf(bounds.widthBlocks, bounds.heightBlocks)
        val sample = maxOf(1, ((maxBlock + maxPixels - 1L) / maxPixels).toInt())
        val previewWidth = ((bounds.widthBlocks + sample - 1L) / sample)
            .coerceAtMost(maxPixels.toLong()).toInt()
        val previewHeight = ((bounds.heightBlocks + sample - 1L) / sample)
            .coerceAtMost(maxPixels.toLong()).toInt()

        require(previewWidth > 0 && previewHeight > 0)

        val bitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        var lastProgress = -1
        onProgress(0)

        renderRows(
            bridge,
            bounds,
            dimension,
            1,
            consume = { row, worldRow ->
                checkCancelled(isCancelled)

                // Keep only every Nth block row. The last partial sample is
                // represented by the final source row that falls inside the
                // preview bitmap's height.
                if (worldRow % sample.toLong() == 0L) {
                    val previewY = (worldRow / sample.toLong()).toInt()
                    if (previewY < previewHeight) {
                        val sampled = IntArray(previewWidth)
                        for (x in 0 until previewWidth) {
                            val sourceX = minOf(x * sample, row.lastIndex)
                            sampled[x] = row[sourceX]
                        }
                        bitmap.setPixels(
                            sampled, 0, previewWidth,
                            0, previewY,
                            previewWidth, 1
                        )
                    }
                }

                // Progress is based on the full source render, not just the
                // sampled rows, so it remains smooth and reaches 100% only
                // after the complete map scan/render has finished.
                val percent = 70 + ((worldRow + 1L) * 29L / bounds.heightBlocks)
                    .toInt().coerceIn(70, 99)
                if (percent > lastProgress) {
                    lastProgress = percent
                    onProgress(percent)
                }
            },
            onScanProgress = { scan ->
                val mapped = (scan * 20 / 100).coerceIn(0, 20)
                if (mapped > lastProgress) {
                    lastProgress = mapped
                    onProgress(mapped)
                }
            },
            onChunkProgress = { chunk ->
                val mapped = (20 + chunk * 50 / 100).coerceIn(20, 70)
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


    /**
     * Build 194 fast path: render the small on-screen preview and the final
     * full-resolution PNG in the SAME world pass. The final PNG is streamed to
     * [preparedOutput] while a <=1024px display bitmap is sampled from the same
     * block rows. This avoids the expensive second LevelDB scan/render that was
     * introduced when the preview became memory-safe for very large maps.
     */
    fun previewAndPreparePng(
        bridge: LevelDbBridge,
        bounds: WholeMapBounds,
        dimension: Int,
        pixelsPerBlock: Int,
        preparedOutput: OutputStream,
        maxPixels: Int = 1024,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): Bitmap {
        require(pixelsPerBlock in MIN_PIXELS_PER_BLOCK..MAX_PIXELS_PER_BLOCK)
        require(maxPixels > 0)
        require(totalPixels(bounds, pixelsPerBlock) < MAX_TOTAL_PIXELS) {
            "Image exceeds ${MAX_TOTAL_PIXELS - 1} pixels: ${totalPixels(bounds, pixelsPerBlock)}"
        }
        val widthLong = bounds.widthBlocks * pixelsPerBlock.toLong()
        val heightLong = bounds.heightBlocks * pixelsPerBlock.toLong()
        require(widthLong <= Int.MAX_VALUE && heightLong <= Int.MAX_VALUE)
        val width = widthLong.toInt()
        val height = heightLong.toInt()

        val maxBlock = maxOf(bounds.widthBlocks, bounds.heightBlocks)
        val sample = maxOf(1, ((maxBlock + maxPixels - 1L) / maxPixels).toInt())
        val previewWidth = ((bounds.widthBlocks + sample - 1L) / sample)
            .coerceAtMost(maxPixels.toLong()).toInt()
        val previewHeight = ((bounds.heightBlocks + sample - 1L) / sample)
            .coerceAtMost(maxPixels.toLong()).toInt()
        val bitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        val sampledRow = IntArray(previewWidth)
        var lastProgress = -1

        PngWriter(preparedOutput, width, height).use { png ->
            onProgress(0)
            renderRows(
                bridge, bounds, dimension, 1,
                consume = { row, worldRow ->
                    checkCancelled(isCancelled)

                    if (worldRow % sample.toLong() == 0L) {
                        val previewY = (worldRow / sample.toLong()).toInt()
                        if (previewY < previewHeight) {
                            for (x in 0 until previewWidth) {
                                sampledRow[x] = row[minOf(x * sample, row.lastIndex)]
                            }
                            bitmap.setPixels(sampledRow, 0, previewWidth, 0, previewY, previewWidth, 1)
                        }
                    }

                    // Stream the requested final resolution from the SAME rendered
                    // source row. No second LevelDB pass and no full-resolution Bitmap.
                    png.writeSourceRow(row, pixelsPerBlock)

                    val percent = 70 + ((worldRow + 1L) * 29L / bounds.heightBlocks)
                        .toInt().coerceIn(70, 99)
                    if (percent > lastProgress) {
                        lastProgress = percent
                        onProgress(percent)
                    }
                },
                onScanProgress = { scan ->
                    val mapped = (scan * 20 / 100).coerceIn(0, 20)
                    if (mapped > lastProgress) {
                        lastProgress = mapped
                        onProgress(mapped)
                    }
                },
                onChunkProgress = { chunk ->
                    val mapped = (20 + chunk * 50 / 100).coerceIn(20, 70)
                    if (mapped > lastProgress) {
                        lastProgress = mapped
                        onProgress(mapped)
                    }
                },
                isCancelled = isCancelled
            )
            checkCancelled(isCancelled)
            onProgress(100)
        }
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

            // The on-screen MapView shades each column using its immediate
            // west/east/north/south neighbors. Most neighbors are inside the
            // current chunk, but the north/south neighbors at chunk-row
            // boundaries live in the preceding/following chunk row. Keep only
            // those boundary rows instead of retaining three complete rows of
            // chunk grids in memory. The next row is prefetched while workers
            // are already decoding, so fidelity costs very little wall-clock
            // time and does not require a second world render.
            fun takeChunkRow(cz: Int): HashMap<Int, Array<Array<ColumnInfo>>> {
                val row = HashMap<Int, Array<Array<ColumnInfo>>>(
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
                            ((completed * 100L) / totalChunks)
                                .toInt()
                                .coerceIn(0, 100)
                        )
                    }

                    val result = completedByCoord.remove(key)
                        ?: throw IllegalStateException(
                            "Missing chunk result for $cx,$cz"
                        )
                    result.grid?.let { row[cx] = it }
                }
                return row
            }

            var previousBottomEdges: HashMap<Int, Array<ColumnInfo>>? = null
            var currentGrids: Map<Int, Array<Array<ColumnInfo>>> = takeChunkRow(b.minChunkZ)

            for (cz in b.minChunkZ..b.maxChunkZ) {
                checkCancelled(isCancelled)
                val rgb = IntArray(rowWidth)

                // Render the first 15 local Z rows immediately. None of these
                // rows needs a following chunk row: their south neighbor is still
                // inside the current chunk. This keeps the worker pool and PNG
                // writer moving instead of waiting for the next Z strip up front.
                for (lz in 0 until 15) {
                    checkCancelled(isCancelled)
                    var cursor = 0

                    for (cx in b.minChunkX..b.maxChunkX) {
                        val grid = currentGrids[cx]
                        for (lx in 0..15) {
                            val info = grid?.get(lx)?.get(lz)
                            val color = if (info == null) {
                                BlockColors.fallbackUnloaded
                            } else {
                                val west = when {
                                    lx > 0 -> grid[lx - 1][lz].surfaceY
                                    cx > b.minChunkX -> {
                                        currentGrids[cx - 1]?.get(15)?.get(lz)?.surfaceY
                                            ?: info.surfaceY
                                    }
                                    else -> info.surfaceY
                                }
                                val east = when {
                                    lx < 15 -> grid[lx + 1][lz].surfaceY
                                    cx < b.maxChunkX -> {
                                        currentGrids[cx + 1]?.get(0)?.get(lz)?.surfaceY
                                            ?: info.surfaceY
                                    }
                                    else -> info.surfaceY
                                }
                                val north = if (lz > 0) {
                                    grid[lx][lz - 1].surfaceY
                                } else {
                                    previousBottomEdges?.get(cx)?.get(lx)?.surfaceY
                                        ?: info.surfaceY
                                }
                                val south = grid[lx][lz + 1].surfaceY

                                MapViewerRenderStyle.shadeColumn(
                                    info, west, east, north, south
                                )
                            }
                            repeat(ppb) { rgb[cursor++] = color }
                        }
                    }

                    repeat(ppb) {
                        checkCancelled(isCancelled)
                        consume(rgb, worldRow)
                        worldRow++
                    }
                }

                // Only the final local Z row crosses the chunk-row boundary.
                // Fetch the next row at this point, not before, so almost all of
                // the current row can render while the scan/decode pipeline works
                // on the rest of the world.
                val nextGrids: Map<Int, Array<Array<ColumnInfo>>> = if (cz < b.maxChunkZ) {
                    takeChunkRow(cz + 1)
                } else {
                    emptyMap()
                }

                val nextTopEdges = if (nextGrids.isNotEmpty()) {
                    HashMap<Int, Array<ColumnInfo>>(nextGrids.size).also { edges ->
                        for ((cx, grid) in nextGrids) {
                            edges[cx] = Array(16) { x -> grid[x][0] }
                        }
                    }
                } else {
                    null
                }

                // Render the final local Z row using the exact MapView south-edge
                // neighbor when a following chunk row exists.
                val lz = 15
                checkCancelled(isCancelled)
                var cursor = 0

                for (cx in b.minChunkX..b.maxChunkX) {
                    val grid = currentGrids[cx]
                    for (lx in 0..15) {
                        val info = grid?.get(lx)?.get(lz)
                        val color = if (info == null) {
                            BlockColors.fallbackUnloaded
                        } else {
                            val west = when {
                                lx > 0 -> grid[lx - 1][lz].surfaceY
                                cx > b.minChunkX -> {
                                    currentGrids[cx - 1]?.get(15)?.get(lz)?.surfaceY
                                        ?: info.surfaceY
                                }
                                else -> info.surfaceY
                            }
                            val east = when {
                                lx < 15 -> grid[lx + 1][lz].surfaceY
                                cx < b.maxChunkX -> {
                                    currentGrids[cx + 1]?.get(0)?.get(lz)?.surfaceY
                                        ?: info.surfaceY
                                }
                                else -> info.surfaceY
                            }
                            val north = grid[lx][lz - 1].surfaceY
                            val south = nextTopEdges?.get(cx)?.get(lx)?.surfaceY
                                ?: info.surfaceY

                            MapViewerRenderStyle.shadeColumn(
                                info, west, east, north, south
                            )
                        }
                        repeat(ppb) { rgb[cursor++] = color }
                    }
                }

                repeat(ppb) {
                    checkCancelled(isCancelled)
                    consume(rgb, worldRow)
                    worldRow++
                }

                previousBottomEdges = HashMap<Int, Array<ColumnInfo>>(currentGrids.size).also { edges ->
                    for ((cx, grid) in currentGrids) {
                        edges[cx] = Array(16) { x -> grid[x][15] }
                    }
                }
                currentGrids = nextGrids
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
        private val deflater = Deflater(Deflater.BEST_SPEED)
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


        /** Writes a 1-pixel/block source row expanded directly to the final ppb width. */
        fun writeSourceRow(source: IntArray, ppb: Int) {
            require(ppb >= 1)
            if (ppb == 1) {
                writeRow(source)
                return
            }
            require(source.size * ppb == width) { "Expected source size ${width / ppb}, got ${source.size}" }

            // Build one expanded row. Repeated vertical rows can then reuse a very
            // cheap all-zero Up-filter row, avoiding repeated RGB conversion work.
            rawRow[0] = 1 // Sub
            var pos = 1
            var previousOutput = 0
            for (color in source) {
                val rgb = color and 0xFFFFFF
                val r = (rgb ushr 16) and 0xFF
                val g = (rgb ushr 8) and 0xFF
                val b = rgb and 0xFF
                for (rep in 0 until ppb) {
                    val dr = if (rep == 0) r - ((previousOutput ushr 16) and 0xFF) else 0
                    val dg = if (rep == 0) g - ((previousOutput ushr 8) and 0xFF) else 0
                    val db = if (rep == 0) b - (previousOutput and 0xFF) else 0
                    rawRow[pos++] = dr.toByte()
                    rawRow[pos++] = dg.toByte()
                    rawRow[pos++] = db.toByte()
                    previousOutput = rgb
                }
            }
            deflateRow(rawRow)

            // All additional pixels-per-block rows are identical to the source row.
            // PNG Up filtering makes these rows a 1-byte filter marker + zeros.
            if (ppb > 1) {
                java.util.Arrays.fill(rawRow, 1, rawRow.size, 0.toByte())
                rawRow[0] = 2
                repeat(ppb - 1) { deflateRow(rawRow) }
            }

            // writeSourceRow emits exactly `ppb` PNG rows. Build 194's
            // one-pass writer forgot to advance the row counter here, so
            // PngWriter.close() could leave the stream without its final
            // flush/IEND and some decoders would display only part of the
            // image. Keep the counter aligned with the actual emitted rows.
            rows += ppb
            require(rows <= height) { "PNG row overflow: wrote $rows of $height rows" }
        }

        private fun deflateRow(row: ByteArray) {
            deflater.setInput(row)
            while (!deflater.needsInput()) {
                val n = deflater.deflate(buf)
                if (n <= 0) break
                idatBuffer.write(buf, 0, n)
                if (idatBuffer.size() >= 1024 * 1024) flushIdat()
            }
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
            require(rows <= height) { "PNG row overflow: wrote $rows of $height rows" }
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
