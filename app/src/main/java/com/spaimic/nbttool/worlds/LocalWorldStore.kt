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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private const val DISPLAY_NAME_FILE = ".nbttool_display_name"

    /** Exact display name chosen by the user, including Bedrock formatting codes such as §.
     * The filesystem folder remains sanitized and is never used as the display name. */
    /** User-facing local display name. This is deliberately independent from level.dat. */
    fun levelName(worldDir: File): String {
        val metadata = File(worldDir, DISPLAY_NAME_FILE)
        if (metadata.isFile) {
            try {
                val value = metadata.readText(Charsets.UTF_8)
                if (value.isNotBlank()) return value
            } catch (_: Exception) {}
        }
        return actualLevelName(worldDir)
    }

    /** The real Bedrock LevelName stored in level.dat, never changed by local renaming. */
    fun actualLevelName(worldDir: File): String {
        return try {
            val dat = NbtIO.readLevelDat(File(worldDir, "level.dat").readBytes())
            (dat.root.entries["LevelName"] as? NbtTag.Str)?.value?.takeIf { it.isNotBlank() } ?: worldDir.name
        } catch (_: Exception) { worldDir.name }
    }

    fun worldIconFile(worldDir: File): File? {
        val icon = File(worldDir, "world_icon.jpeg")
        return icon.takeIf { it.isFile && it.length() > 0L }
    }

    fun writeDisplayName(worldDir: File, displayName: String) {
        val value = displayName.ifBlank { levelName(worldDir) }
        File(worldDir, DISPLAY_NAME_FILE).writeText(value, Charsets.UTF_8)
    }

    data class WorldInfo(
        val name: String,
        val realName: String,
        val dateLine: String,
        val timeLine: String,
        val lastPlayedEpoch: Long
    )

    /** Local display name + the real level.dat name + LastPlayed date/time. */
    fun worldInfo(worldDir: File): WorldInfo = try {
        val dat = NbtIO.readLevelDat(File(worldDir, "level.dat").readBytes())
        val (date, time) = lastPlayedDateTime(dat)
        val realName = (dat.root.entries["LevelName"] as? NbtTag.Str)?.value?.takeIf { it.isNotBlank() } ?: worldDir.name
        WorldInfo(levelName(worldDir), realName, date, time, lastPlayedEpoch(dat))
    } catch (e: Exception) {
        val fallback = actualLevelName(worldDir)
        WorldInfo(levelName(worldDir), fallback, "", "", 0L)
    }

    /** Formats the level.dat "LastPlayed" tag (epoch seconds) as separate date/time strings. */
    fun lastPlayedDateTime(dat: NbtIO.LevelDat): Pair<String, String> {
        val seconds = lastPlayedEpoch(dat)
        if (seconds == 0L) return "" to ""
        val date = java.util.Date(seconds * 1000)
        val dateFmt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return dateFmt.format(date) to timeFmt.format(date)
    }

    /** The level.dat "LastPlayed" tag as raw epoch seconds — 0 if missing/unreadable, for sorting. */
    fun lastPlayedEpoch(dat: NbtIO.LevelDat): Long = when (val tag = dat.root.entries["LastPlayed"]) {
        is NbtTag.Long -> tag.value
        is NbtTag.Int -> tag.value.toLong()
        else -> 0L
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
        writeDisplayName(dest, localName)
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
        writeDisplayName(dest, localName)
        return dest
    }

    /** Zips a local world's contents back into a .mcworld, files at the zip root (matching Bedrock's own layout). */
    fun exportToMcworld(context: Context, worldDir: File): File {
        val out = File(exportsDir(context), "${worldDir.name}.mcworld")

        fun addFile(zos: ZipOutputStream, file: File, basePath: String) {
            if (file.name == DISPLAY_NAME_FILE) return
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

    /** Creates a complete private backup of a local world as a zip. Unlike .mcworld export,
     * this includes the app's local display-name metadata too. */
    fun createBackup(context: Context, worldDir: File): File {
        if (!File(worldDir, "level.dat").isFile) {
            throw IllegalStateException("This is not a valid local world")
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        var out = File(backupsDir(context), "${worldDir.name}_$stamp.nbtbackup")
        var n = 2
        while (out.exists()) {
            out = File(backupsDir(context), "${worldDir.name}_${stamp}_$n.nbtbackup")
            n++
        }

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

    fun listBackups(context: Context, worldDir: File): List<File> =
        backupsDir(context).listFiles { f ->
            f.isFile && f.name.startsWith("${worldDir.name}_") && f.name.endsWith(".nbtbackup")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Restores a backup atomically-ish: unpack into a sibling temp directory, validate it,
     * then replace the current local world. */
    fun restoreBackup(worldDir: File, backupFile: File) {
        if (!backupFile.isFile) throw IllegalStateException("Backup file doesn't exist")
        val parent = worldDir.parentFile ?: throw IllegalStateException("World has no parent directory")
        val temp = File(parent, ".restore_${worldDir.name}_${System.nanoTime()}")
        temp.mkdirs()
        try {
            backupFile.inputStream().use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val outFile = File(temp, entry.name)
                        val canonicalRoot = temp.canonicalPath + File.separator
                        if (!outFile.canonicalPath.startsWith(canonicalRoot)) {
                            throw SecurityException("Backup entry escapes destination folder: ${entry.name}")
                        }
                        if (entry.isDirectory) outFile.mkdirs()
                        else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out -> zip.copyTo(out) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            if (!File(temp, "level.dat").isFile) {
                throw IllegalStateException("Backup does not contain a valid level.dat")
            }
            val old = File(parent, ".old_${worldDir.name}_${System.nanoTime()}")
            if (!worldDir.renameTo(old)) throw IllegalStateException("Couldn't move the current world out of the way")
            try {
                if (!temp.renameTo(worldDir)) {
                    old.renameTo(worldDir)
                    throw IllegalStateException("Couldn't install the restored backup")
                }
                old.deleteRecursively()
            } catch (e: Exception) {
                if (!worldDir.exists()) old.renameTo(worldDir)
                throw e
            }
        } catch (e: Exception) {
            temp.deleteRecursively()
            throw e
        }
    }

    fun deleteBackup(backupFile: File) {
        if (!backupFile.delete() && backupFile.exists()) {
            throw IllegalStateException("Couldn't delete backup")
        }
    }

    fun deleteLocalWorld(worldDir: File) {
        worldDir.deleteRecursively()
    }
}
