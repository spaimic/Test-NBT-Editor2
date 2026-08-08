package com.spaimic.nbttool.chunk

import android.content.Context
import java.io.*
import java.util.UUID

/** Persistent multi-entry chunk clipboard. Each entry keeps literal LevelDB records
 * plus an optional 128x128 PNG preview captured from the map viewer. */
object ChunkClipboard {
    data class Entry(
        val id: String,
        val chunkX: Int,
        val chunkZ: Int,
        val records: List<Record>,
        val previewPng: ByteArray? = null
    )
    data class Record(val key: ByteArray, val value: ByteArray)

    private const val MAGIC = 0x5343484B
    private const val VERSION = 2
    private const val DIR = "chunk_clipboard"

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }
    private fun file(context: Context, id: String) = File(dir(context), "${id}.chk")

    var selectedId: String? = null

    fun save(context: Context, entry: Entry): String {
        val id = entry.id.ifBlank { UUID.randomUUID().toString() }
        val f = file(context, id)
        DataOutputStream(BufferedOutputStream(f.outputStream())).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION)
            out.writeUTF(id); out.writeInt(entry.chunkX); out.writeInt(entry.chunkZ)
            val preview = entry.previewPng ?: ByteArray(0)
            out.writeInt(preview.size); out.write(preview)
            out.writeInt(entry.records.size)
            entry.records.forEach {
                out.writeInt(it.key.size); out.write(it.key)
                out.writeInt(it.value.size); out.write(it.value)
            }
        }
        selectedId = id
        return id
    }

    fun newEntry(chunkX: Int, chunkZ: Int, records: List<Record>, previewPng: ByteArray? = null) =
        Entry(UUID.randomUUID().toString(), chunkX, chunkZ, records, previewPng)

    fun list(context: Context): List<Entry> =
        dir(context).listFiles { f -> f.extension == "chk" }?.mapNotNull { readFile(it) }
            ?.sortedByDescending { it.id } ?: emptyList()

    fun load(context: Context, id: String): Entry? = readFile(file(context, id))

    /** Backward-compatible convenience: selected entry, or the newest one. */
    fun load(context: Context): Entry? {
        val entries = list(context)
        return (selectedId?.let { id -> entries.firstOrNull { it.id == id } } ?: entries.firstOrNull())
    }

    fun hasChunk(context: Context) = list(context).isNotEmpty()
    fun delete(context: Context, id: String) {
        file(context, id).delete()
        if (selectedId == id) selectedId = null
    }
    fun clear(context: Context) {
        dir(context).deleteRecursively()
        selectedId = null
    }

    private fun readFile(f: File): Entry? {
        if (!f.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(f.inputStream())).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                val id = input.readUTF()
                val x = input.readInt(); val z = input.readInt()
                val previewSize = input.readInt()
                if (previewSize < 0 || previewSize > 2 * 1024 * 1024) return null
                val preview = ByteArray(previewSize); input.readFully(preview)
                val count = input.readInt()
                if (count < 0 || count > 10000) return null
                val records = ArrayList<Record>(count)
                repeat(count) {
                    val ks = input.readInt()
                    if (ks <= 0 || ks > 1024) throw IllegalArgumentException("Invalid key size")
                    val key = ByteArray(ks); input.readFully(key)
                    val vs = input.readInt()
                    if (vs < 0 || vs > 100 * 1024 * 1024) throw IllegalArgumentException("Invalid value size")
                    val value = ByteArray(vs); input.readFully(value)
                    records += Record(key, value)
                }
                Entry(id, x, z, records, preview.takeIf { it.isNotEmpty() })
            }
        } catch (_: Exception) { null }
    }
}
