package com.example.airgesture

import android.app.Application
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class AirGestureApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stackTrace = Log.getStackTraceString(throwable)
                val targetDir = getExternalFilesDir(null) ?: filesDir
                val logFile = File(targetDir, "airgesture_crash.txt")
                FileOutputStream(logFile).use { out ->
                    out.write("Thread: ${thread.name}\n\n$stackTrace".toByteArray())
                }
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
