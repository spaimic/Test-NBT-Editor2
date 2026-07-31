package com.spaimic.nbttool

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * By default, an uncaught exception/error on ANY thread — not just the main
 * one — terminates the whole Android process. This app runs a lot of work
 * (LevelDB scans, chunk map decoding, imports) on background `Thread { }`
 * workers, each already try/catching what it reasonably can, but a global
 * net here means a bug that slips past one of those still shows a toast
 * instead of a full app crash for anything happening off the main thread.
 * Main-thread crashes still terminate the app — Android doesn't allow a
 * process to keep running after those regardless.
 */
class NbtToolApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val mainHandler = Handler(Looper.getMainLooper())
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (thread == Looper.getMainLooper().thread) {
                previous?.uncaughtException(thread, throwable)
            } else {
                mainHandler.post {
                    Toast.makeText(
                        applicationContext,
                        "Background error: ${throwable.javaClass.simpleName}: ${throwable.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
