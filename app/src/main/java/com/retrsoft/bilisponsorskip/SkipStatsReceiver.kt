package com.retrsoft.bilisponsorskip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.preference.PreferenceManager

class SkipStatsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != SettingsContract.ACTION_RECORD_LOCAL_SKIP) return
        val sourcePackages = if (Build.VERSION.SDK_INT >= 34) {
            buildSet {
                sentFromPackage?.let(::add)
                context.packageManager.getPackagesForUid(sentFromUid)?.let(::addAll)
            }
        } else {
            emptySet()
        }
        if (
            sourcePackages.isNotEmpty() &&
            sourcePackages.none(SettingsContract.TARGET_PACKAGES::contains)
        ) {
            android.util.Log.w(TAG, "Ignored local skip statistics from $sourcePackages")
            return
        }
        val durationMs = intent.getLongExtra(SettingsContract.EXTRA_SAVED_DURATION_MS, 0L)
        if (durationMs !in 1L..MAX_SINGLE_SKIP_MS) {
            android.util.Log.w(TAG, "Ignored invalid local skip duration: $durationMs ms")
            return
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val count = preferences.getLong(SettingsContract.KEY_LOCAL_SKIP_COUNT, 0L)
        val savedMs = preferences.getLong(SettingsContract.KEY_LOCAL_SAVED_MS, 0L)
        val updatedCount = count.saturatedAdd(1L)
        val updatedSavedMs = savedMs.saturatedAdd(durationMs)
        val committed = preferences.edit()
            .putLong(SettingsContract.KEY_LOCAL_SKIP_COUNT, updatedCount)
            .putLong(SettingsContract.KEY_LOCAL_SAVED_MS, updatedSavedMs)
            .commit()
        android.util.Log.d(
            TAG,
            "Recorded local skip: source=$sourcePackages, durationMs=$durationMs, " +
                "count=$updatedCount, savedMs=$updatedSavedMs, committed=$committed",
        )
    }

    private fun Long.saturatedAdd(value: Long): Long =
        if (this > Long.MAX_VALUE - value) Long.MAX_VALUE else this + value

    private companion object {
        const val TAG = "BiliSponsorSkipStats"
        const val MAX_SINGLE_SKIP_MS = 24L * 60L * 60L * 1000L
    }
}
