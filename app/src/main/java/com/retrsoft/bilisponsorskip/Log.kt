package com.retrsoft.bilisponsorskip

import android.app.AndroidAppHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import de.robv.android.xposed.XposedBridge

internal object Log {
    private const val TAG = "BiliSponsorSkip"

    fun d(message: String) {
        android.util.Log.d(TAG, message)
        XposedBridge.log("$TAG: $message")
        appendToFile("D", message, null)
    }

    fun e(message: String, throwable: Throwable? = null) {
        android.util.Log.e(TAG, message, throwable)
        XposedBridge.log("$TAG: $message")
        throwable?.let(XposedBridge::log)
        appendToFile("E", message, throwable)
    }

    private fun appendToFile(level: String, message: String, throwable: Throwable?) {
        runCatching {
            val application = AndroidAppHelper.currentApplication() ?: return
            val directory = application.getExternalFilesDir(null) ?: application.filesDir
            val logFile = File(directory, LOG_FILE_NAME)
            val timestamp = synchronized(dateFormat) { dateFormat.format(Date()) }
            val entry = buildString {
                append(timestamp).append(' ').append(level).append('/').append(TAG).append(": ").append(message).append('\n')
                if (throwable != null) append(android.util.Log.getStackTraceString(throwable)).append('\n')
            }
            synchronized(fileLock) { logFile.appendText(entry, Charsets.UTF_8) }
        }
    }

    private val fileLock = Any()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    const val LOG_FILE_NAME = "BiliSponsorSkip.log"
}
