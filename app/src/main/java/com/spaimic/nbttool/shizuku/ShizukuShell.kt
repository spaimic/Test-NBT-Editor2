package com.spaimic.nbttool.shizuku

import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

// Runs shell commands through Shizuku's elevated process, which is what lets us
// READ another app's sandbox (Android/data/com.mojang.minecraftpe/...) without
// root. Only used for importing a copy of a world now — once a world lives in
// this app's own sandbox, editing it is plain java.io.File access, no Shizuku
// or shell commands involved at all (see LocalWorldStore / LevelDbBridge).
// Shizuku.newProcess is not part of the public API surface, so it's invoked via
// reflection -- this is the standard approach used by Shizuku-based apps.
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

    // Copies a file out of the sandbox into our own app-private staging area (read-only, always worked).
    fun pullFile(remotePath: String, localPath: String) {
        val (exit, out) = run("cp '$remotePath' '$localPath'")
        check(exit == 0) { "Shizuku pull failed: $out" }
    }

    // Recursively copies a whole folder (e.g. a world) out of the sandbox — read-only, always worked.
    fun pullDir(remoteDir: String, localDir: String) {
        val (exit, out) = run(
            "rm -rf '$localDir' && cp -r '$remoteDir' '$localDir' && chmod -R 777 '$localDir'"
        )
        check(exit == 0) { "Shizuku pull-dir failed: $out" }
    }

    private fun newProcess(cmd: Array<String>, env: Array<String>?, dir: String?): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, cmd, env, dir) as Process
    }
}
