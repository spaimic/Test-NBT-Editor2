package com.spaimic.nbttool.chunk

import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.LevelDbBridge
import java.io.ByteArrayOutputStream

/**
 * "Deletes" a chunk by replacing every block in it with air, from Y 320 down
 * to Y -64 (the full modern Bedrock height range), and removing every entity
 * and block entity (chest, furnace, etc.) in it. The chunk itself stays
 * marked as generated — Minecraft won't regenerate fresh terrain there, it'll
 * just be an empty void, which is what was asked for (as opposed to deleting
 * the chunk's keys outright, which would make Minecraft regenerate it).
 *
 * The air blocks are written as a normal single-block-type subchunk (the same
 * "bitsPerBlock == 0" shape SubChunkParser already reads), with a palette
 * entry shaped like what Bedrock itself writes for a plain block — including
 * the block-state "version" integer, which packs a game version into 4 bytes
 * (confirmed via Bedrock's documented mcstructure format).
 */
object ChunkDeleter {

    // subchunk Y indices spanning the full -64..319 height range (16 blocks per subchunk)
    private const val MIN_SUBCHUNK_Y = -4
    private const val MAX_SUBCHUNK_Y = 19

    // 1.21.100 packed as (major<<24 | minor<<16 | patch<<8 | revision), matching the
    // format documented for block-state "version" fields in Bedrock's NBT.
    private const val BLOCKSTATE_VERSION = (1 shl 24) or (21 shl 16) or (100 shl 8) or 0

    fun clearChunkToAir(bridge: LevelDbBridge, chunkX: Int, chunkZ: Int, dimension: Int = 0) {
        // Commit all 24 air subchunks together instead of issuing 24 independent writes.
        val airWrites = (MIN_SUBCHUNK_Y..MAX_SUBCHUNK_Y).map { subY ->
            ChunkKeys.subChunkKey(chunkX, chunkZ, subY, dimension) to buildAirSubChunkBytes(subY)
        }
        bridge.writeBatch(airWrites)

        // Remove chunk-local block entities and actor records referenced by this chunk's digest.
        bridge.delete(ChunkKeys.blockEntityKey(chunkX, chunkZ, dimension))
        val digpKey = EntityKeys.digpKey(chunkX, chunkZ, dimension)
        val digpValue = bridge.read(digpKey)
        if (digpValue != null) {
            for (actorId in EntityKeys.splitActorIds(digpValue)) {
                bridge.delete(EntityKeys.actorPrefixKey(actorId))
            }
            bridge.delete(digpKey)
        }
    }

    private fun buildAirSubChunkBytes(subChunkY: Int): ByteArray {
        val airEntry = NbtTag.Compound(linkedMapOf(
            "name" to NbtTag.Str("minecraft:air"),
            "states" to NbtTag.Compound(LinkedHashMap()),
            "version" to NbtTag.Int(BLOCKSTATE_VERSION)
        ))
        val paletteEntryBytes = NbtIO.writeRootCompoundNoHeader(airEntry)

        val out = ByteArrayOutputStream()
        out.write(9)          // subchunk format version
        out.write(1)          // one storage layer
        out.write(subChunkY)  // embedded Y index — version 9 stores this here, right after the layer count
        out.write(0)          // bitsPerBlock = 0: single-value persistent layer
        // No palette-size field is present for bitsPerBlock == 0.
        out.write(paletteEntryBytes)
        return out.toByteArray()
    }
}
