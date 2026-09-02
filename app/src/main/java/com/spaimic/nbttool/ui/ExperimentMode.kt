package com.spaimic.nbttool.ui

import android.content.Context

/** Persistent, one-way opt-in switch for dangerous world-editing features. */
object ExperimentMode {
    private const val PREFS = "nbt_tool_settings"
    private const val KEY_ENABLED = "experiment_mode_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun enable(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, true)
            .apply()
    }
}
