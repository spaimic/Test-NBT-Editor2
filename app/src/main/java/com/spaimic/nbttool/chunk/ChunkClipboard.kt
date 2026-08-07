package com.spaimic.nbttool.chunk

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.BufferedInputStream
import java.io.BufferedOutputStream

/**
 * Persistent clipboard for one literal Bedrock chunk.
 *
 * The clipboard stores the raw LevelDB key/value pairs belonging to the copied
 * chunk, plus its actor records. It is app-private, so it can safely be reused
 * across different worlds without touching Android's system clipboard.
 */
object ChunkClipboard {

    data class Entry(
        val chunkX: Int,
        val chunkZ: Int,
        val records: List<Record>
    )

    data class Record(val key: ByteArray, val value: ByteArray)

    private const val MAGIC = 0x5343484B // "SCHK"
    private const val VERSION = 1
    private const val FILE_NAME = "literal_chunk.clip"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun hasChunk(context: Context): Boolean = file(context).isFile

    fun save(context: Context, entry: Entry) {
        val f = file(context)
        DataOutputStream(BufferedOutputStream(f.outputStream())).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(entry.chunkX)
            out.writeInt(entry.chunkZ)
            out.writeInt(entry.records.size)
            for (r in entry.records) {
                out.writeInt(r.key.size)
                out.write(r.key)
                out.writeInt(r.value.size)
                out.write(r.value)
            }
        }
    }

    fun load(context: Context): Entry? {
        val f = file(context)
        if (!f.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(f.inputStream())).use { input ->
                if (input.readInt() != MAGIC) return null
                if (input.readInt() != VERSION) return null
                val x = input.readInt()
                val z = input.readInt()
                val count = input.readInt()
                if (count < 0 || count > 10000) return null

                val records = ArrayList<Record>(count)
                repeat(count) {
                    val keySize = input.readInt()
                    if (keySize <= 0 || keySize > 1024) throw IllegalArgumentException("Invalid chunk key size")
                    val key = ByteArray(keySize)
                    input.readFully(key)

                    val valueSize = input.readInt()
                    if (valueSize < 0 || valueSize > 100 * 1024 * 1024) {
                        throw IllegalArgumentException("Invalid chunk value size")
                    }
                    val value = ByteArray(valueSize)
                    input.readFully(value)
                    records += Record(key, value)
                }
                Entry(x, z, records)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        file(context).delete()
    }
}
