package com.spaimic.nbttool.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader

/** Shared Save-As / Open-Document plumbing for NBT-as-JSON export/import —
 * only what happens with the resulting text differs per screen. */
object NbtJsonFileOps {
    const val REQUEST_EXPORT_JSON = 9001
    const val REQUEST_IMPORT_JSON = 9002

    fun launchExport(activity: Activity, suggestedFileName: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "$suggestedFileName.json")
        }
        activity.startActivityForResult(intent, REQUEST_EXPORT_JSON)
    }

    fun launchImportPicker(activity: Activity) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // JSON files aren't always registered with a consistent MIME type
        }
        activity.startActivityForResult(intent, REQUEST_IMPORT_JSON)
    }

    fun writeText(activity: Activity, uri: Uri, text: String) {
        val stream = activity.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("Couldn't open the destination file")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    fun readText(activity: Activity, uri: Uri): String {
        val stream = activity.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Couldn't open the picked file")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /**
     * Opens a buffered character stream for large JSON imports. Callers must
     * consume and close the returned Reader. Unlike readText(), this never
     * loads the complete document into a single String.
     */
    fun openReader(activity: Activity, uri: Uri): BufferedReader {
        val stream = activity.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Couldn't open the picked file")
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 64 * 1024)
    }

    /** Turns a screen title into a filesystem-safe suggested filename. */
    fun sanitizeFileName(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").ifBlank { "nbt_export" }
}
