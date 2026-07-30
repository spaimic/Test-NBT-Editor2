package com.spaimic.nbttool.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.ShizukuShell
import java.io.File

class WorldListActivity : AppCompatActivity() {

    private lateinit var listView: ListView

    /** folder name (real, on-disk id) -> display name (LevelName from level.dat, falls back to the folder name) */
    private var worldDirs: List<String> = emptyList()
    private var displayNames: List<String> = emptyList()

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            refreshWorldList()
        } else {
            Toast.makeText(this, "Shizuku permission denied — can't reach Minecraft's data folder", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        listView = ListView(this)
        setContentView(listView)
        title = "Minecraft Worlds"

        listView.setOnItemClickListener { _, _, position, _ ->
            openWorld(worldDirs[position])
        }

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

            // Pull each world's level.dat just to read its LevelName for a human-readable list.
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

    /** Pulls just level.dat (small file) and reads its "LevelName" tag; falls back to the folder id on any failure. */
    private fun readLevelName(basePath: String, worldDir: String): String {
        return try {
            val localPath = File(getExternalFilesDir(null), "peek_level.dat").absolutePath
            ShizukuShell.pullFile("$basePath/$worldDir/level.dat", localPath)
            val dat = NbtIO.readLevelDat(File(localPath).readBytes())
            (dat.root.entries["LevelName"] as? NbtTag.Str)?.value?.takeIf { it.isNotBlank() } ?: worldDir
        } catch (e: Exception) {
            worldDir
        }
    }

    private fun openWorld(worldDir: String) {
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra(EditorActivity.EXTRA_WORLDS_PATH, worldsBasePath)
        intent.putExtra(EditorActivity.EXTRA_WORLD_DIR, worldDir)
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, MENU_ITEM_CLIPBOARD, 0, "Clipboard")
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == MENU_ITEM_CLIPBOARD) {
            startActivity(Intent(this, ClipboardActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val REQUEST_CODE_SHIZUKU = 1001
        private const val MENU_ITEM_CLIPBOARD = 1
        var worldsBasePath: String = ShizukuShell.WORLDS_PATH_STANDARD
    }
}
