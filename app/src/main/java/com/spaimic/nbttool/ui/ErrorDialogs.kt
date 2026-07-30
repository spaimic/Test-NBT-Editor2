package com.spaimic.nbttool.ui

import android.app.AlertDialog
import android.content.Context

/**
 * Toasts truncate long messages and don't show an exception's cause chain, which
 * has repeatedly hidden the actually-useful detail (e.g. "Could not open table 87"
 * without the underlying zlib/IO error that caused it). This shows the full chain
 * in a scrollable, copy-able dialog instead.
 */
fun showErrorDialog(context: Context, title: String, e: Throwable) {
    val sb = StringBuilder()
    var t: Throwable? = e
    while (t != null) {
        sb.append(t.javaClass.simpleName).append(": ").append(t.message ?: "(no message)").append("\n\n")
        t = t.cause
    }
    AlertDialog.Builder(context)
        .setTitle(title)
        .setMessage(sb.toString().trim())
        .setPositiveButton("OK", null)
        .show()
}
