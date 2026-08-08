package com.spaimic.nbttool.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import android.os.Handler
import android.os.Looper
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
    private var rows: List<WorldRow> = emptyList()
    private var worldsBasePath: String = ShizukuShell.WORLDS_PATH_STANDARD

    private var loadingDialog: android.app.Dialog? = null
    private val loadingHandler = Handler(Looper.getMainLooper())
    private var loadingDots = 1
    private var loadingWorldCount = 0
    private var loadingTextView: TextView? = null
    private val loadingRunnable = object : Runnable {
        override fun run() {
            updateLoadingDialog()
            loadingHandler.postDelayed(this, 1000L)
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            refreshWorldList()
        } else {
            hideLoadingDialog()
            Toast.makeText(this, "Shizuku permission denied — can't read Minecraft's worlds", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Copy from Minecraft"
        listView = ListView(this)
        setContentView(listView)

        listView.setOnItemClickListener { _, _, position, _ -> promptImport(worldDirs[position], rows[position].name) }

        Shizuku.addRequestPermissionResultListener(permissionListener)
        showLoadingDialog()
        ensureShizukuAndScan()
    }

    override fun onDestroy() {
        hideLoadingDialog()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    private fun ensureShizukuAndScan() {
        if (!Shizuku.pingBinder()) {
            hideLoadingDialog()
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
            val fetched = mutableListOf<Pair<String, Pair<WorldRow, Long>>>()
            withLevelDat.forEach { dir ->
                val row = dir to readWorldRow(basePath, dir)
                fetched += row
                runOnUiThread {
                    loadingWorldCount = fetched.size
                    updateLoadingDialog()
                }
            }
            fetched.sortByDescending { it.second.second }
            runOnUiThread {
                worldDirs = fetched.map { it.first }
                rows = fetched.map { it.second.first }
                worldsBasePath = basePath
                if (worldDirs.isEmpty()) {
                    Toast.makeText(this, "No worlds with level.dat found under $basePath", Toast.LENGTH_LONG).show()
                }
                listView.adapter = WorldRowAdapter(this, rows)
                hideLoadingDialog()
            }
        }.start()
    }

    private fun showLoadingDialog() {
        loadingWorldCount = 0
        loadingDots = 1

        loadingTextView = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(48, 40, 48, 40)
        }
        loadingDialog = android.app.Dialog(this).apply {
            setContentView(loadingTextView)
            setCancelable(false)
            show()
        }
        loadingDialog?.window?.setLayout(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        loadingHandler.removeCallbacks(loadingRunnable)
        loadingHandler.post(loadingRunnable)
        updateLoadingDialog()
    }

    private fun updateLoadingDialog() {
        if (loadingDialog == null) return
        val dots = ".".repeat(loadingDots.coerceIn(1, 3))
        loadingTextView?.text = "Loading worlds$dots\n$loadingWorldCount worlds loaded"
        loadingDots = if (loadingDots >= 3) 1 else loadingDots + 1
    }

    private fun hideLoadingDialog() {
        loadingHandler.removeCallbacks(loadingRunnable)
        loadingDialog?.dismiss()
        loadingDialog = null
        loadingTextView = null
    }

    private fun readWorldRow(basePath: String, worldDir: String): Pair<WorldRow, Long> = try {
        val localPath = File(getExternalFilesDir(null), "peek_level.dat").absolutePath
        ShizukuShell.pullFile("$basePath/$worldDir/level.dat", localPath)
        val dat = NbtIO.readLevelDat(File(localPath).readBytes())
        val name = (dat.root.entries["LevelName"] as? NbtTag.Str)?.value?.takeIf { it.isNotBlank() } ?: worldDir
        val (date, time) = LocalWorldStore.lastPlayedDateTime(dat)
        val iconDir = File(getExternalFilesDir(null), "world_icons").apply { mkdirs() }
        val safe = worldDir.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val iconPath = File(iconDir, "$safe.jpeg")
        val icon = try {
            ShizukuShell.pullFile("$basePath/$worldDir/world_icon.jpeg", iconPath.absolutePath)
            iconPath.takeIf { it.isFile && it.length() > 0L }?.absolutePath
        } catch (_: Exception) { null }
        WorldRow(name, date, time, icon) to LocalWorldStore.lastPlayedEpoch(dat)
    } catch (e: Exception) {
        WorldRow(worldDir, "", "", null) to 0L
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
                        val copied = LocalWorldStore.importFromMinecraft(this, worldsBasePath, remoteWorldDir, localName)
                        LocalWorldStore.writeDisplayName(copied, input.text.toString())
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
