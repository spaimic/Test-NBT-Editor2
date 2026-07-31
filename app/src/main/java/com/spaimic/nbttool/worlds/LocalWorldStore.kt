package com.spaimic.nbttool.worlds

import android.content.Context
import android.net.Uri
import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.ShizukuShell
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Worlds this app can actually edit live entirely inside our own sandbox —
 * `getExternalFilesDir()/worlds/<name>/` — so editing them is plain java.io.File
 * access, no Shizuku involved, and none of the write-permission wall that blocks
 * writing into Minecraft's own Android/data folder. Getting a world IN here is
 * the only part that still needs help:
 *   - Shizuku, to copy a live world out of Minecraft's sandbox — read-only,
 *     which always worked fine (only writing back was ever blocked), or
 *   - Android's document picker, to import an exported .mcworld/.mctemplate
 *     file, which is just a zip with level.dat/db/ at its root.
 * Getting an edited world back into Minecraft is symmetric: zip it back into a
 * .mcworld and hand it to Minecraft via a normal share Intent — Minecraft does
 * the importing itself, so we never need write access to its sandbox at all.
 */
object LocalWorldStore {

    fun rootDir(context: Context): File =
        File(context.getExternalFilesDir(null), "worlds").apply { mkdirs() }

    fun backupsDir(context: Context): File =
        File(context.getExternalFilesDir(null), "backups").apply { mkdirs() }

    fun exportsDir(context: Context): File =
        File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }

    fun listLocalWorlds(context: Context): List<File> =
        rootDir(context).listFiles { f -> f.isDirectory && File(f, "level.dat").exists() }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** Reads a local world's LevelName for display, falling back to the folder name. */
    fun levelName(worldDir: File): String = try {
        val dat = NbtIO.readLevelDat(File(worldDir, "level.dat").readBytes())
        (dat.root.entries["LevelName"] as? NbtTag.Str)?.value?.takeIf { it.isNotBlank() } ?: worldDir.name
    } catch (e: Exception) {
        worldDir.name
    }

    /** Picks a local folder name that doesn't collide with an existing import. */
    fun uniqueLocalName(context: Context, desired: String): String {
        val safe = desired.trim().ifBlank { "world" }.replace(Regex("[^A-Za-z0-9 _-]"), "_")
        var candidate = safe
        var n = 2
        while (File(rootDir(context), candidate).exists()) {
            candidate = "$safe ($n)"
            n++
        }
        return candidate
    }

    /** Pulls a whole world folder out of Minecraft's sandbox via Shizuku — read-only, always worked. */
    fun importFromMinecraft(context: Context, worldsPath: String, remoteWorldDir: String, localName: String): File {
        val dest = File(rootDir(context), localName)
        ShizukuShell.pullDir("$worldsPath/$remoteWorldDir", dest.absolutePath)
        return dest
    }

    /** Unzips a picked .mcworld/.mctemplate file into our sandbox. */
    fun importFromZip(context: Context, sourceUri: Uri, localName: String): File {
        val dest = File(rootDir(context), localName)
        dest.mkdirs()
        val opened = context.contentResolver.openInputStream(sourceUri)
            ?: throw IllegalStateException("Couldn't open the picked file")
        opened.use { input ->
            ZipInputStream(input).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val outFile = File(dest, entry.name)
                    val destCanonical = dest.canonicalPath + File.separator
                    if (!outFile.canonicalPath.startsWith(destCanonical)) {
                        throw SecurityException("Zip entry escapes destination folder: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out -> zip.copyTo(out) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        if (!File(dest, "level.dat").exists()) {
            dest.deleteRecursively()
            throw IllegalStateException("That file doesn't look like a Bedrock world (no level.dat found inside)")
        }
        return dest
    }

    /** Zips a local world's contents back into a .mcworld, files at the zip root (matching Bedrock's own layout). */
    fun exportToMcworld(context: Context, worldDir: File): File {
        val out = File(exportsDir(context), "${worldDir.name}.mcworld")

        fun addFile(zos: ZipOutputStream, file: File, basePath: String) {
            if (file.isDirectory) {
                file.listFiles()?.forEach { addFile(zos, it, "$basePath${file.name}/") }
            } else {
                zos.putNextEntry(ZipEntry("$basePath${file.name}"))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }

        out.outputStream().use { fos ->
            ZipOutputStream(fos).use { zos ->
                worldDir.listFiles()?.forEach { addFile(zos, it, "") }
            }
        }
        return out
    }

    fun deleteLocalWorld(worldDir: File) {
        worldDir.deleteRecursively()
    }
}
