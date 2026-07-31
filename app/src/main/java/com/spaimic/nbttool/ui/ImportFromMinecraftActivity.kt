package com.spaimic.nbttool.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.ShizukuShell
import com.spaimic.nbttool.worlds.LocalWorldStore
import java.io.File

/**
 * Read-only browse of Minecraft's actual worlds via Shizuku (reads always worked
 * fine — only writing back was ever blocked). Picking one copies it into this
 * app's own sandbox, where it can then be edited with no Shizuku involved at all.
 */
class ImportFromMinecraftActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private var worldDirs: List<String> = emptyList()
    private var displayNames: List<String> = emptyList()
    private var worldsBasePath: String = ShizukuShell.WORLDS_PATH_STANDARD

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            refreshWorldList()
        } else {
            Toast.makeText(this, "Shizuku permission denied — can't read Minecraft's worlds", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Copy from Minecraft"
        listView = ListView(this)
        setContentView(listView)

        listView.setOnItemClickListener { _, _, position, _ -> promptImport(worldDirs[position], displayNames[position]) }

        Shizuku.addRequestPermissionResultListener(permissionListener)
        ensureShizukuAndScan()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    private fun ensureShizukuAndScan() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku isn't running. Install/start Shizuku first.", Toast.LENGTH_LONG).show()
            return
        }
        if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            refreshWorldList()
        } else {
            Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
        }
    }

    private fun refreshWorldList() {
        Thread {
            var dirs = ShizukuShell.listWorldDirs(ShizukuShell.WORLDS_PATH_STANDARD)
            var basePath = ShizukuShell.WORLDS_PATH_STANDARD
            if (dirs.isEmpty()) {
                dirs = ShizukuShell.listWorldDirs(ShizukuShell.WORLDS_PATH_LEGACY)
                basePath = ShizukuShell.WORLDS_PATH_LEGACY
            }
            val withLevelDat = dirs.filter { ShizukuShell.hasLevelDat(it, basePath) }
            val names = withLevelDat.map { dir -> dir to readLevelName(basePath, dir) }
            runOnUiThread {
                worldDirs = names.map { it.first }
                displayNames = names.map { it.second }
                worldsBasePath = basePath
                if (worldDirs.isEmpty()) {
                    Toast.makeText(this, "No worlds with level.dat found under $basePath", Toast.LENGTH_LONG).show()
                }
                listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayNames)
            }
        }.start()
    }

    private fun readLevelName(basePath: String, worldDir: String): String = try {
        val localPath = File(getExternalFilesDir(null), "peek_level.dat").absolutePath
        ShizukuShell.pullFile("$basePath/$worldDir/level.dat", localPath)
        val dat = NbtIO.readLevelDat(File(localPath).readBytes())
        (dat.root.entries["LevelName"] as? NbtTag.Str)?.value?.takeIf { it.isNotBlank() } ?: worldDir
    } catch (e: Exception) {
        worldDir
    }

    private fun promptImport(remoteWorldDir: String, suggestedName: String) {
        val input = EditText(this).apply { setText(suggestedName) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Name this imported world")
            .setView(input)
            .setPositiveButton("Copy") { _, _ ->
                val localName = LocalWorldStore.uniqueLocalName(this, input.text.toString())
                Thread {
                    try {
                        LocalWorldStore.importFromMinecraft(this, worldsBasePath, remoteWorldDir, localName)
                        runOnUiThread {
                            Toast.makeText(this, "Imported \"$localName\"", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    } catch (e: Exception) {
                        runOnUiThread { showErrorDialog(this, "Import failed", e) }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val REQUEST_CODE_SHIZUKU = 1001
    }
}
