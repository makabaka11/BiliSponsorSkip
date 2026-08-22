package com.retrsoft.bilisponsorskip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager

class SettingsSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            SettingsContract.ACTION_UPDATE_MODULE_SETTINGS -> receiveEmbeddedUpdate(context, intent)
            SettingsContract.ACTION_REQUEST_MODULE_SETTINGS -> sendCurrentSettings(context, intent)
        }
    }

    private fun receiveEmbeddedUpdate(context: Context, intent: Intent) {
        val values = intent.getBundleExtra(SettingsContract.EXTRA_SETTINGS) ?: return
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        SettingsContract.BOOLEAN_SETTING_KEYS.forEach { key ->
            if (values.containsKey(key)) editor.putBoolean(key, values.getBoolean(key))
        }
        SettingsContract.STRING_SETTING_KEYS.forEach { key ->
            if (values.containsKey(key)) editor.putString(key, values.getString(key))
        }
        if (!editor.commit()) {
            Log.e("failed to persist settings received from embedded Bili page")
            return
        }

        SettingsContract.TARGET_PACKAGES.forEach { targetPackage ->
            context.sendBroadcast(
                Intent(SettingsContract.ACTION_UPDATE_SETTINGS)
                    .setPackage(targetPackage)
                    .putExtra(SettingsContract.EXTRA_SETTINGS, values),
            )
        }
        Log.d("embedded Bili settings synchronized to the module and target clients")
    }

    private fun sendCurrentSettings(context: Context, intent: Intent) {
        val targetPackage = intent.getStringExtra(SettingsContract.EXTRA_TARGET_PACKAGE)
            ?.takeIf { it in SettingsContract.TARGET_PACKAGES }
            ?: return
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val defaults = SettingsSnapshot()
        val snapshot = defaults.copy(
            enabled = preferences.getBoolean(SettingsContract.KEY_ENABLED, defaults.enabled),
            notifyFound = preferences.getBoolean(SettingsContract.KEY_NOTIFY_FOUND, defaults.notifyFound),
            notifySkipped = preferences.getBoolean(SettingsContract.KEY_NOTIFY_SKIPPED, defaults.notifySkipped),
            notifyFetchFailure = preferences.getBoolean(
                SettingsContract.KEY_NOTIFY_FETCH_FAILURE,
                defaults.notifyFetchFailure,
            ),
            showTitleLabel = preferences.getBoolean(SettingsContract.KEY_SHOW_TITLE_LABEL, defaults.showTitleLabel),
            showProgressMarkers = preferences.getBoolean(
                SettingsContract.KEY_SHOW_PROGRESS_MARKERS,
                defaults.showProgressMarkers,
            ),
            skipOnSeek = preferences.getBoolean(SettingsContract.KEY_SKIP_ON_SEEK, defaults.skipOnSeek),
            minDurationSeconds = preferences.getString(SettingsContract.KEY_MIN_DURATION, "0")
                ?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            showSubmissionButton = preferences.getBoolean(
                SettingsContract.KEY_SHOW_SUBMISSION_BUTTON,
                defaults.showSubmissionButton,
            ),
            userId = preferences.getString(SettingsContract.KEY_USER_ID, defaults.userId).orEmpty().trim(),
            categoryModes = SettingsContract.CATEGORIES.associateWith { category ->
                val default = SettingsContract.defaultCategoryMode(category)
                CategoryMode.fromPersisted(
                    preferences.getString(SettingsContract.categoryModeKey(category), default.persistedValue),
                    default,
                )
            },
        )
        val values = SettingsContract.settingsBundle(snapshot).apply {
            putString(
                SettingsContract.KEY_USERNAME,
                preferences.getString(SettingsContract.KEY_USERNAME, "").orEmpty(),
            )
        }
        context.sendBroadcast(
            Intent(SettingsContract.ACTION_UPDATE_SETTINGS)
                .setPackage(targetPackage)
                .putExtra(SettingsContract.EXTRA_SETTINGS, values),
        )
        Log.d("module settings sent to $targetPackage on process startup")
    }
}
