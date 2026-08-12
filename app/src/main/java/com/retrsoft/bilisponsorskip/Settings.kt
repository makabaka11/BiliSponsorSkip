package com.retrsoft.bilisponsorskip

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.robv.android.xposed.XSharedPreferences

internal data class SettingsSnapshot(
    val enabled: Boolean = true,
    val autoSkip: Boolean = true,
    val notifyFound: Boolean = true,
    val notifySkipped: Boolean = true,
    val notifyFetchFailure: Boolean = false,
    val showTitleLabel: Boolean = true,
    val showProgressMarkers: Boolean = true,
    val skipOnSeek: Boolean = true,
    val minDurationSeconds: Int = 0,
    val showSubmissionButton: Boolean = false,
    val userId: String = "",
    val enabledCategories: Set<String> = setOf("sponsor"),
)

internal class SettingsRepository(private val application: Application) {
    private val preferences = XSharedPreferences(MODULE_PACKAGE)
    private val mirrorPreferences = application.getSharedPreferences(MIRROR_PREFERENCES, Context.MODE_PRIVATE)

    @Volatile
    var current = SettingsSnapshot()
        private set

    fun refresh(): SettingsSnapshot {
        current = runCatching {
            readFromMirror() ?:
                readFromLegacyPreferences()
        }.onFailure { Log.e("failed to read module settings; using defaults", it) }
            .getOrDefault(SettingsSnapshot())
        return current
    }

    init {
        registerMirrorReceiver()
    }

    private fun registerMirrorReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != SettingsContract.ACTION_UPDATE_SETTINGS) return
                val values = intent.getBundleExtra(SettingsContract.EXTRA_SETTINGS) ?: return
                val editor = mirrorPreferences.edit().clear().putBoolean(MIRROR_READY, true)
                values.keySet().forEach { key ->
                    when (val value = values.get(key)) {
                        is Boolean -> editor.putBoolean(key, value)
                        is String -> editor.putString(key, value)
                    }
                }
                editor.apply()
                current = readFromMirror() ?: SettingsSnapshot()
                Log.d(
                    "settings mirror updated: submission=${current.showSubmissionButton}; " +
                        "userIdConfigured=${Identity.isValid(current.userId)}",
                )
            }
        }
        val filter = android.content.IntentFilter(SettingsContract.ACTION_UPDATE_SETTINGS)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            application.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            application.registerReceiver(receiver, filter)
        }
    }

    private fun readFromMirror(): SettingsSnapshot? {
        if (!mirrorPreferences.getBoolean(MIRROR_READY, false)) return null
        return snapshot(mirrorPreferences::getBoolean) { key, default ->
            mirrorPreferences.getString(key, default) ?: default
        }
    }

    private fun readFromLegacyPreferences(): SettingsSnapshot {
        preferences.reload()
        return snapshot(preferences::getBoolean) { key, default -> preferences.getString(key, default) ?: default }
    }

    private fun snapshot(
        getBoolean: (String, Boolean) -> Boolean,
        getString: (String, String) -> String,
    ) = SettingsSnapshot(
        enabled = getBoolean(SettingsContract.KEY_ENABLED, true),
        autoSkip = getBoolean(SettingsContract.KEY_AUTO_SKIP, true),
        notifyFound = getBoolean(SettingsContract.KEY_NOTIFY_FOUND, true),
        notifySkipped = getBoolean(SettingsContract.KEY_NOTIFY_SKIPPED, true),
        notifyFetchFailure = getBoolean(SettingsContract.KEY_NOTIFY_FETCH_FAILURE, false),
        showTitleLabel = getBoolean(SettingsContract.KEY_SHOW_TITLE_LABEL, true),
        showProgressMarkers = getBoolean(SettingsContract.KEY_SHOW_PROGRESS_MARKERS, true),
        skipOnSeek = getBoolean(SettingsContract.KEY_SKIP_ON_SEEK, true),
        minDurationSeconds = getString(SettingsContract.KEY_MIN_DURATION, "0")
            .toIntOrNull()?.coerceAtLeast(0) ?: 0,
        showSubmissionButton = getBoolean(SettingsContract.KEY_SHOW_SUBMISSION_BUTTON, false),
        userId = getString(SettingsContract.KEY_USER_ID, "").trim(),
        enabledCategories = SettingsContract.CATEGORIES.filterTo(linkedSetOf()) {
            getBoolean(SettingsContract.categoryKey(it), it == "sponsor")
        },
    )

    private companion object {
        const val MODULE_PACKAGE = "com.retrsoft.bilisponsorskip"
        const val MIRROR_PREFERENCES = "bili_sponsor_skip_settings"
        const val MIRROR_READY = "mirror_ready"
    }
}

internal object SettingsContract {
    const val MODULE_PACKAGE = "com.retrsoft.bilisponsorskip"
    const val KEY_ENABLED = "enabled"
    const val KEY_AUTO_SKIP = "auto_skip"
    const val KEY_NOTIFY_FOUND = "notify_found"
    const val KEY_NOTIFY_SKIPPED = "notify_skipped"
    const val KEY_NOTIFY_FETCH_FAILURE = "notify_fetch_failure"
    const val KEY_SHOW_TITLE_LABEL = "show_title_label"
    const val KEY_SHOW_PROGRESS_MARKERS = "show_progress_markers"
    const val KEY_SKIP_ON_SEEK = "skip_on_seek"
    const val KEY_MIN_DURATION = "min_duration"
    const val KEY_SHOW_SUBMISSION_BUTTON = "show_submission_button"
    const val KEY_USER_ID = "user_id"
    const val KEY_USERNAME = "username"
    const val ACTION_UPDATE_SETTINGS = "com.retrsoft.bilisponsorskip.UPDATE_SETTINGS"
    const val ACTION_RECORD_LOCAL_SKIP = "com.retrsoft.bilisponsorskip.RECORD_LOCAL_SKIP"
    const val EXTRA_SETTINGS = "settings"
    const val EXTRA_SAVED_DURATION_MS = "saved_duration_ms"
    const val KEY_LOCAL_SKIP_COUNT = "local_skip_count"
    const val KEY_LOCAL_SAVED_MS = "local_saved_ms"

    val TARGET_PACKAGES = setOf(
        "tv.danmaku.bili",
        "com.bilibili.app.blue",
        "com.bilibili.app.in",
        "tv.danmaku.bilibilihd",
    )

    val CATEGORIES = listOf(
        "sponsor",
        "selfpromo",
        "interaction",
        "intro",
        "outro",
        "preview",
        "filler",
        "padding",
        "music_offtopic",
    )

    fun categoryKey(category: String) = "category_$category"
}

internal fun String.categoryLabel(): String = when (this) {
    "sponsor" -> "赞助/恰饭"
    "selfpromo" -> "无偿/自我推广"
    "interaction" -> "三连/互动提醒"
    "intro" -> "过场/开场动画"
    "outro" -> "鸣谢/结束画面"
    "preview" -> "回顾/概要"
    "filler" -> "离题闲聊/玩笑"
    "padding" -> "填充内容/前黑/后黑"
    "music_offtopic" -> "音乐中的非音乐部分"
    else -> this
}
