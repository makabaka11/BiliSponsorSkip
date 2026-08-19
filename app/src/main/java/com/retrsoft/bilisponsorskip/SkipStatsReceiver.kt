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
        if (intent.hasExtra(SettingsContract.EXTRA_STATS_GENERATION)) {
            recordSnapshot(context, intent, sourcePackages)
        } else {
            recordLegacyEvent(context, intent, sourcePackages)
        }
    }

    private fun recordSnapshot(context: Context, intent: Intent, sourcePackages: Set<String>) {
        val snapshot = LocalSkipStatsSnapshot(
            packageName = intent.getStringExtra(SettingsContract.EXTRA_STATS_PACKAGE).orEmpty(),
            processName = intent.getStringExtra(SettingsContract.EXTRA_STATS_PROCESS).orEmpty(),
            generation = intent.getStringExtra(SettingsContract.EXTRA_STATS_GENERATION).orEmpty(),
            count = intent.getLongExtra(SettingsContract.EXTRA_STATS_COUNT, -1L),
            savedMs = intent.getLongExtra(SettingsContract.EXTRA_STATS_SAVED_MS, -1L),
        )
        if (
            snapshot.packageName !in SettingsContract.TARGET_PACKAGES ||
            (sourcePackages.isNotEmpty() && snapshot.packageName !in sourcePackages) ||
            snapshot.processName.length !in 1..MAX_PROCESS_NAME_LENGTH ||
            (snapshot.processName != snapshot.packageName &&
                !snapshot.processName.startsWith("${snapshot.packageName}:")) ||
            !Identity.isValid(snapshot.generation) ||
            snapshot.count < 0L ||
            snapshot.savedMs < 0L
        ) {
            android.util.Log.w(TAG, "Ignored invalid local skip statistics snapshot")
            return
        }

        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val sourceKey = LocalSkipStatsProtocol.sourceKey(snapshot)
        val sourceCountKey = SettingsContract.sourceStatsKey(sourceKey, "count")
        val sourceSavedMsKey = SettingsContract.sourceStatsKey(sourceKey, "saved_ms")
        val previousCount = preferences.getLong(sourceCountKey, 0L).coerceAtLeast(0L)
        val previousSavedMs = preferences.getLong(sourceSavedMsKey, 0L).coerceAtLeast(0L)
        val delta = LocalSkipStatsProtocol.delta(previousCount, previousSavedMs, snapshot)
        val totalCount = preferences.getLong(SettingsContract.KEY_LOCAL_SKIP_COUNT, 0L)
            .coerceAtLeast(0L)
            .saturatedAdd(delta.count)
        val totalSavedMs = preferences.getLong(SettingsContract.KEY_LOCAL_SAVED_MS, 0L)
            .coerceAtLeast(0L)
            .saturatedAdd(delta.savedMs)
        val committed = preferences.edit()
            .putLong(sourceCountKey, maxOf(previousCount, snapshot.count))
            .putLong(sourceSavedMsKey, maxOf(previousSavedMs, snapshot.savedMs))
            .putLong(SettingsContract.KEY_LOCAL_SKIP_COUNT, totalCount)
            .putLong(SettingsContract.KEY_LOCAL_SAVED_MS, totalSavedMs)
            .commit()
        android.util.Log.d(
            TAG,
            "Merged local skip snapshot: source=${snapshot.packageName}/${snapshot.processName}, " +
                "snapshotCount=${snapshot.count}, deltaCount=${delta.count}, " +
                "deltaSavedMs=${delta.savedMs}, totalCount=$totalCount, " +
                "totalSavedMs=$totalSavedMs, committed=$committed",
        )
    }

    private fun recordLegacyEvent(context: Context, intent: Intent, sourcePackages: Set<String>) {
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
        const val MAX_PROCESS_NAME_LENGTH = 200
        const val MAX_SINGLE_SKIP_MS = 24L * 60L * 60L * 1000L
    }
}
