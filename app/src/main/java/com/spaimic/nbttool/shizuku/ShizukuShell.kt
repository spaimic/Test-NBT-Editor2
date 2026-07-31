package com.spaimic.nbttool.shizuku

import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

// Runs shell commands through Shizuku's elevated process, which is what lets us
// reach into another app's sandbox (Android/data/com.mojang.minecraftpe/...)
// without root. Shizuku.newProcess is not part of the public API surface, so it's
// invoked via reflection -- this is the standard approach used by Shizuku-based apps.
object ShizukuShell {

    const val WORLDS_PATH_STANDARD =
        "/storage/emulated/0/Android/data/com.mojang.minecraftpe/files/games/com.mojang/minecraftWorlds"
    const val WORLDS_PATH_LEGACY =
        "/storage/emulated/0/games/com.mojang/minecraftWorlds"

    fun isReady(): Boolean =
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED

    // Runs `sh -c <command>`, blocks until it exits, returns (exitCode, stdout+stderr).
    fun run(command: String): Pair<Int, String> {
        val process = newProcess(arrayOf("sh", "-c", command), null, null)
        val output = StringBuilder()
        BufferedReader(InputStreamReader(process.inputStream)).forEachLine { output.appendLine(it) }
        BufferedReader(InputStreamReader(process.errorStream)).forEachLine { output.appendLine(it) }
        val exit = process.waitFor()
        return exit to output.toString()
    }

    // Lists world folder names under [worldsPath] (one per line from `ls`).
    fun listWorldDirs(worldsPath: String = WORLDS_PATH_STANDARD): List<String> {
        val (exit, out) = run("ls -1 '$worldsPath' 2>/dev/null")
        if (exit != 0) return emptyList()
        return out.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    // True if `<worldsPath>/<worldDir>/level.dat` exists.
    fun hasLevelDat(worldDir: String, worldsPath: String = WORLDS_PATH_STANDARD): Boolean {
        val (exit, _) = run("test -f '$worldsPath/$worldDir/level.dat'")
        return exit == 0
    }

    // Copies a file from the sandboxed source into our own app-private staging area.
    fun pullFile(remotePath: String, localPath: String) {
        val (exit, out) = run("cp '$remotePath' '$localPath'")
        check(exit == 0) { "Shizuku pull failed: $out" }
    }

    // Copies our edited file back into the sandboxed destination.
    fun pushFile(localPath: String, remotePath: String) {
        val (exit, out) = run("cp '$localPath' '$remotePath'")
        check(exit == 0) { "Shizuku push failed: $out" }
    }

    // Recursively copies a whole folder (e.g. a world's LevelDB db/ directory) out of the sandbox.
    fun pullDir(remoteDir: String, localDir: String) {
        val (exit, out) = run(
            "rm -rf '$localDir' && cp -r '$remoteDir' '$localDir' && chmod -R 777 '$localDir'"
        )
        check(exit == 0) { "Shizuku pull-dir failed: $out" }
    }

    // Replaces remoteDir's contents with localDir's, after first backing up the
    // current remote contents into localBackupDir (our own app storage -- proven
    // writable, unlike creating a brand-new sibling folder inside another app's
    // sandbox, which some devices refuse even though overwriting existing files
    // there works fine).
    //
    // Deliberately does NOT rm -rf the remote folder first: some devices refuse
    // to delete LevelDB's CURRENT/MANIFEST/*.log files even via Shizuku (likely
    // because the world isn't fully released -- e.g. Minecraft backgrounded but
    // not fully closed), while overwriting those same files in place works fine.
    // Any stale files LevelDB compacted away locally are harmless left behind --
    // it only ever reads what CURRENT/MANIFEST still reference.
    fun pushDirWithBackup(localDir: String, remoteDir: String, localBackupDir: String) {
        pullDir(remoteDir, localBackupDir)
        val (exit, out) = run("cp -r '$localDir/.' '$remoteDir/'")
        check(exit == 0) { "Shizuku push-dir failed: $out" }
    }

    // Diagnostic only: shows what user Shizuku's shell runs as, and the real
    // ownership/permissions on a remote path, to debug write-permission failures.
    fun diagnose(remotePath: String): String {
        val (_, whoami) = run("id")
        val (_, listing) = run("ls -la '$remotePath' 2>&1 | head -20")
        val (_, parentListing) = run("ls -la '$remotePath/..' 2>&1 | head -5")
        return "whoami: $whoami\nls -la (target):\n$listing\nls -la (parent):\n$parentListing"
    }

    private fun newProcess(cmd: Array<String>, env: Array<String>?, dir: String?): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, cmd, env, dir) as Process
    }
}
