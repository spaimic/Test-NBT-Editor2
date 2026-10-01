package com.spaimic.nbttool.nbt

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads/writes Bedrock Edition `level.dat` files.
 *
 * level.dat layout:
 *   [4 bytes] storage version, little-endian int32
 *   [4 bytes] payload length,  little-endian int32
 *   [payload] a single root TAG_Compound in Bedrock's little-endian NBT format
 *
 * (This does NOT handle the LevelDB `db/` folder — that's a separate key-value
 * store, not flat NBT. Use a LevelDB library, e.g. org.iq80.leveldb, for that.)
 */
object NbtIO {

    data class LevelDat(val storageVersion: Int, val root: NbtTag.Compound)

    fun readLevelDat(bytes: ByteArray): LevelDat {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = buf.int
        val length = buf.int
        val payload = ByteArray(length)
        buf.get(payload)
        val din = DataInputStream(ByteArrayInputStream(payload)).let(::LEDataInput)
        val tagId = din.readUByte()
        require(tagId == 10) { "level.dat root tag is not a Compound (got ${NbtTag.typeName(tagId)})" }
        din.readString() // root name, normally empty — discarded
        val root = readCompoundBody(din)
        return LevelDat(version, root)
    }

    fun writeLevelDat(dat: LevelDat): ByteArray {
        val payloadOut = ByteArrayOutputStream()
        val dout = LEDataOutput(DataOutputStream(payloadOut))
        dout.writeUByte(10)      // root tag id: Compound
        dout.writeString("")     // root name
        writeCompoundBody(dout, dat.root)
        val payload = payloadOut.toByteArray()

        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(dat.storageVersion)
        header.putInt(payload.size)
        out.write(header.array())
        out.write(payload)
        return out.toByteArray()
    }

    /**
     * LevelDB values for player/map/village keys are a raw NBT root compound —
     * tag id (10) + name (usually empty) + body — with NO 8-byte level.dat header.
     * Bedrock sometimes stores back-to-back compounds in one value (e.g. some
     * village keys); this reads only the first one, which covers player/map data.
     */
    fun readRootCompoundNoHeader(bytes: ByteArray): NbtTag.Compound {
        val din = LEDataInput(DataInputStream(ByteArrayInputStream(bytes)))
        val tagId = din.readUByte()
        require(tagId == 10) { "Expected a root Compound (got ${NbtTag.typeName(tagId)})" }
        din.readString()
        return readCompoundBody(din)
    }

    fun writeRootCompoundNoHeader(root: NbtTag.Compound): ByteArray {
        val out = ByteArrayOutputStream()
        val dout = LEDataOutput(DataOutputStream(out))
        dout.writeUByte(10)
        dout.writeString("")
        writeCompoundBody(dout, root)
        return out.toByteArray()
    }

    /**
     * Serializes a modern Bedrock actorprefix record. The actor record is a
     * root entity compound (not an extra Entity wrapper). Bedrock stores
     * internalComponents.EntityStorageKeyComponent.StorageKey as an 8-byte
     * binary string, so that single string must bypass UTF-8 encoding. The caller
     * supplies the exact Bedrock storage-key bytes shared by actorprefix and digp.
     * [storageKeyComponent] is the exact 8-byte payload for that NBT property;
     * the caller uses the actor UID separately for the actorprefix/digp LevelDB keys.
     */
    fun writeActorPrefixCompound(root: NbtTag.Compound, storageKeyComponent: ByteArray): ByteArray {
        require(storageKeyComponent.size == 8) { "Actor storage key must be exactly 8 bytes." }
        val out = ByteArrayOutputStream()
        val dout = LEDataOutput(DataOutputStream(out))
        dout.writeUByte(10)
        dout.writeString("")
        writeActorCompoundBody(dout, root, emptyList(), storageKeyComponent)
        return out.toByteArray()
    }

    /** Standalone (de)serialization of a single tag's payload — used by the in-app NBT clipboard. */
    fun serializeTag(tag: NbtTag): ByteArray {
        val out = ByteArrayOutputStream()
        val dout = LEDataOutput(DataOutputStream(out))
        dout.writeUByte(tag.id)
        writeTagPayload(dout, tag)
        return out.toByteArray()
    }

    fun deserializeTag(bytes: ByteArray): NbtTag {
        val din = LEDataInput(DataInputStream(ByteArrayInputStream(bytes)))
        val typeId = din.readUByte()
        return readTagPayload(din, typeId)
    }

    /** Reads every concatenated root compound in a byte array until the bytes run out —
     * the shape Bedrock uses for "BlockEntity" chunk records (chests, furnaces, signs, etc). */
    fun readConcatenatedCompounds(bytes: ByteArray): List<NbtTag.Compound> {
        val stream = DataInputStream(ByteArrayInputStream(bytes))
        val out = ArrayList<NbtTag.Compound>()
        while (stream.available() > 0) {
            out.add(readCompoundFromStream(stream))
        }
        return out
    }

    fun writeConcatenatedCompounds(compounds: List<NbtTag.Compound>): ByteArray {
        val out = ByteArrayOutputStream()
        for (c in compounds) out.write(writeRootCompoundNoHeader(c))
        return out.toByteArray()
    }

    /** Reads one (unnamed) NBT compound directly from a shared stream cursor — used
     * when compounds are packed back-to-back inside a larger structure (e.g. a
     * subchunk's block palette), not as a standalone byte array. */
    fun readCompoundFromStream(input: DataInputStream): NbtTag.Compound {
        val din = LEDataInput(input)
        val tagId = din.readUByte()
        require(tagId == 10) { "Expected a Compound tag while reading a palette entry (got ${NbtTag.typeName(tagId)})" }
        din.readString()
        return readCompoundBody(din)
    }

    // ---- tag payload (de)serialization, shared by lists/compounds/root ----

    private fun readTagPayload(din: LEDataInput, typeId: Int): NbtTag = when (typeId) {
        0 -> NbtTag.End
        1 -> NbtTag.Byte(din.readByte())
        2 -> NbtTag.Short(din.readShortLE())
        3 -> NbtTag.Int(din.readIntLE())
        4 -> NbtTag.Long(din.readLongLE())
        5 -> NbtTag.Float(din.readFloatLE())
        6 -> NbtTag.Double(din.readDoubleLE())
        7 -> {
            val len = din.readIntLE()
            NbtTag.ByteArray(din.readBytes(len))
        }
        8 -> NbtTag.Str(din.readString())
        9 -> {
            val elemType = din.readUByte()
            val count = din.readIntLE()
            val items = ArrayList<NbtTag>(count)
            repeat(count) { items.add(readTagPayload(din, elemType)) }
            NbtTag.ListTag(elemType, items)
        }
        10 -> readCompoundBody(din)
        11 -> {
            val len = din.readIntLE()
            NbtTag.IntArray(IntArray(len) { din.readIntLE() })
        }
        12 -> {
            val len = din.readIntLE()
            NbtTag.LongArray(LongArray(len) { din.readLongLE() })
        }
        else -> throw IOException("Unknown NBT tag id $typeId")
    }

    private fun readCompoundBody(din: LEDataInput): NbtTag.Compound {
        val entries = LinkedHashMap<String, NbtTag>()
        while (true) {
            val typeId = din.readUByte()
            if (typeId == 0) break // TAG_End terminates the compound
            val name = din.readString()
            entries[name] = readTagPayload(din, typeId)
        }
        return NbtTag.Compound(entries)
    }

    private fun writeTagPayload(dout: LEDataOutput, tag: NbtTag) {
        when (tag) {
            is NbtTag.End -> {}
            is NbtTag.Byte -> dout.writeByte(tag.value.toInt())
            is NbtTag.Short -> dout.writeShortLE(tag.value)
            is NbtTag.Int -> dout.writeIntLE(tag.value)
            is NbtTag.Long -> dout.writeLongLE(tag.value)
            is NbtTag.Float -> dout.writeFloatLE(tag.value)
            is NbtTag.Double -> dout.writeDoubleLE(tag.value)
            is NbtTag.ByteArray -> {
                dout.writeIntLE(tag.value.size)
                dout.writeBytes(tag.value)
            }
            is NbtTag.Str -> dout.writeString(tag.value)
            is NbtTag.ListTag -> {
                // Bedrock NBT lists declare one element type and then store every
                // element using that type's payload format. Do not trust a malformed
                // in-memory item subtype when serializing; validate it against the
                // declared element type first so a later read can never desynchronize.
                dout.writeUByte(tag.elementTypeId)
                dout.writeIntLE(tag.items.size)
                for ((index, item) in tag.items.withIndex()) {
                    if (item.id != tag.elementTypeId) {
                        throw IllegalArgumentException(
                            "NBT list element $index has type ${item.id}, expected ${tag.elementTypeId}."
                        )
                    }
                    writeTagPayload(dout, item)
                }
            }
            is NbtTag.Compound -> writeCompoundBody(dout, tag)
            is NbtTag.IntArray -> {
                dout.writeIntLE(tag.value.size)
                for (v in tag.value) dout.writeIntLE(v)
            }
            is NbtTag.LongArray -> {
                dout.writeIntLE(tag.value.size)
                for (v in tag.value) dout.writeLongLE(v)
            }
        }
    }

    private fun writeCompoundBody(dout: LEDataOutput, compound: NbtTag.Compound) {
        for ((name, tag) in compound.entries) {
            dout.writeUByte(tag.id)
            dout.writeString(name)
            writeTagPayload(dout, tag)
        }
        dout.writeUByte(0) // TAG_End
    }

    private fun writeActorCompoundBody(
        dout: LEDataOutput,
        compound: NbtTag.Compound,
        path: List<String>,
        storageKey: ByteArray
    ) {
        for ((name, tag) in compound.entries) {
            dout.writeUByte(tag.id)
            dout.writeString(name)
            val childPath = path + name
            if (tag is NbtTag.Str && childPath == listOf("internalComponents", "EntityStorageKeyComponent", "StorageKey")) {
                dout.writeShortLE(storageKey.size.toShort())
                dout.writeBytes(storageKey)
            } else {
                writeActorTagPayload(dout, tag, childPath, storageKey)
            }
        }
        dout.writeUByte(0)
    }

    private fun writeActorTagPayload(
        dout: LEDataOutput,
        tag: NbtTag,
        path: List<String>,
        storageKey: ByteArray
    ) {
        when (tag) {
            is NbtTag.Compound -> writeActorCompoundBody(dout, tag, path, storageKey)
            is NbtTag.ListTag -> {
                dout.writeUByte(tag.elementTypeId)
                dout.writeIntLE(tag.items.size)
                for ((index, item) in tag.items.withIndex()) {
                    require(item.id == tag.elementTypeId) {
                        "NBT list element $index has type ${item.id}, expected ${tag.elementTypeId}."
                    }
                    writeActorTagPayload(dout, item, path + index.toString(), storageKey)
                }
            }
            is NbtTag.Str -> dout.writeString(tag.value)
            else -> writeTagPayload(dout, tag)
        }
    }
}

// ---- tiny little-endian I/O helpers (java.io.DataInput/Output default to big-endian) ----

private class LEDataInput(private val din: DataInputStream) {
    fun readUByte(): Int = din.readUnsignedByte()
    fun readByte(): kotlin.Byte = din.readByte()
    fun readBytes(len: Int): ByteArray {
        val b = ByteArray(len)
        din.readFully(b)
        return b
    }
    fun readShortLE(): Short {
        val a = din.readUnsignedByte(); val b = din.readUnsignedByte()
        return ((b shl 8) or a).toShort()
    }
    fun readIntLE(): Int {
        val a = din.readUnsignedByte(); val b = din.readUnsignedByte()
        val c = din.readUnsignedByte(); val d = din.readUnsignedByte()
        return (d shl 24) or (c shl 16) or (b shl 8) or a
    }
    fun readLongLE(): Long {
        var result = 0L
        for (i in 0 until 8) result = result or (din.readUnsignedByte().toLong() shl (8 * i))
        return result
    }
    fun readFloatLE(): Float = java.lang.Float.intBitsToFloat(readIntLE())
    fun readDoubleLE(): Double = java.lang.Double.longBitsToDouble(readLongLE())
    fun readString(): String {
        val len = readShortLE().toInt() and 0xFFFF
        return String(readBytes(len), Charsets.UTF_8)
    }
}

private class LEDataOutput(private val dout: DataOutputStream) {
    fun writeUByte(v: Int) = dout.writeByte(v and 0xFF)
    fun writeByte(v: Int) = dout.writeByte(v)
    fun writeBytes(b: ByteArray) = dout.write(b)
    fun writeShortLE(v: Short) {
        dout.writeByte(v.toInt() and 0xFF)
        dout.writeByte((v.toInt() shr 8) and 0xFF)
    }
    fun writeIntLE(v: Int) {
        for (i in 0 until 4) dout.writeByte((v shr (8 * i)) and 0xFF)
    }
    fun writeLongLE(v: Long) {
        for (i in 0 until 8) dout.writeByte(((v shr (8 * i)) and 0xFF).toInt())
    }
    fun writeFloatLE(v: Float) = writeIntLE(java.lang.Float.floatToIntBits(v))
    fun writeDoubleLE(v: Double) = writeLongLE(java.lang.Double.doubleToLongBits(v))
    fun writeString(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeShortLE(bytes.size.toShort())
        dout.write(bytes)
    }
}
