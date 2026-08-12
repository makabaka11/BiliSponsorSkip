package com.retrsoft.bilisponsorskip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.preference.PreferenceManager

class SkipStatsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != SettingsContract.ACTION_RECORD_LOCAL_SKIP) return
        if (Build.VERSION.SDK_INT >= 34 && sentFromPackage !in SettingsContract.TARGET_PACKAGES) return
        val durationMs = intent.getLongExtra(SettingsContract.EXTRA_SAVED_DURATION_MS, 0L)
        if (durationMs !in 1L..MAX_SINGLE_SKIP_MS) return
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val count = preferences.getLong(SettingsContract.KEY_LOCAL_SKIP_COUNT, 0L)
        val savedMs = preferences.getLong(SettingsContract.KEY_LOCAL_SAVED_MS, 0L)
        preferences.edit()
            .putLong(SettingsContract.KEY_LOCAL_SKIP_COUNT, count.saturatedAdd(1L))
            .putLong(SettingsContract.KEY_LOCAL_SAVED_MS, savedMs.saturatedAdd(durationMs))
            .apply()
    }

    private fun Long.saturatedAdd(value: Long): Long =
        if (this > Long.MAX_VALUE - value) Long.MAX_VALUE else this + value

    private companion object {
        const val MAX_SINGLE_SKIP_MS = 24L * 60L * 60L * 1000L
    }
}
