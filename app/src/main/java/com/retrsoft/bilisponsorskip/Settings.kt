package com.retrsoft.bilisponsorskip

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import de.robv.android.xposed.XSharedPreferences

internal data class SettingsSnapshot(
    val enabled: Boolean = true,
    val notifyFound: Boolean = true,
    val notifySkipped: Boolean = true,
    val notifyFetchFailure: Boolean = false,
    val showTitleLabel: Boolean = true,
    val showProgressMarkers: Boolean = true,
    val skipOnSeek: Boolean = true,
    val minDurationSeconds: Int = 0,
    val showSubmissionButton: Boolean = false,
    val userId: String = "",
    val categoryModes: Map<String, CategoryMode> = SettingsContract.DEFAULT_CATEGORY_MODES,
)

internal enum class CategoryMode(val persistedValue: String) {
    DISABLED("disabled"),
    SHOW_OVERLAY("show_overlay"),
    MANUAL_SKIP("manual_skip"),
    AUTO_SKIP("auto_skip");

    companion object {
        fun fromPersisted(value: String?, default: CategoryMode): CategoryMode =
            entries.firstOrNull { it.persistedValue == value } ?: default
    }
}

internal fun SettingsSnapshot.categoryMode(category: String): CategoryMode =
    categoryModes[category] ?: CategoryMode.DISABLED

internal class SettingsRepository(private val application: Application) {
    private val preferences = XSharedPreferences(MODULE_PACKAGE)
    private val mirrorPreferences = application.getSharedPreferences(MIRROR_PREFERENCES, Context.MODE_PRIVATE)

    @Volatile
    var current = SettingsSnapshot()
        private set

    @Volatile
    var onLocalStatsSyncRequested: (() -> Unit)? = null

    fun refresh(): SettingsSnapshot {
        current = runCatching {
            readFromMirror() ?:
                readFromLegacyPreferences()
        }.onFailure { Log.e("failed to read module settings; using defaults", it) }
            .getOrDefault(SettingsSnapshot())
        return current
    }

    fun updateFromEmbeddedSettings(snapshot: SettingsSnapshot) {
        mirrorPreferences.edit()
            .clear()
            .putBoolean(MIRROR_READY, true)
            .putBoolean(SettingsContract.KEY_ENABLED, snapshot.enabled)
            .putBoolean(SettingsContract.KEY_NOTIFY_FOUND, snapshot.notifyFound)
            .putBoolean(SettingsContract.KEY_NOTIFY_SKIPPED, snapshot.notifySkipped)
            .putBoolean(SettingsContract.KEY_NOTIFY_FETCH_FAILURE, snapshot.notifyFetchFailure)
            .putBoolean(SettingsContract.KEY_SHOW_TITLE_LABEL, snapshot.showTitleLabel)
            .putBoolean(SettingsContract.KEY_SHOW_PROGRESS_MARKERS, snapshot.showProgressMarkers)
            .putBoolean(SettingsContract.KEY_SKIP_ON_SEEK, snapshot.skipOnSeek)
            .putString(SettingsContract.KEY_MIN_DURATION, snapshot.minDurationSeconds.toString())
            .putBoolean(SettingsContract.KEY_SHOW_SUBMISSION_BUTTON, snapshot.showSubmissionButton)
            .putString(SettingsContract.KEY_USER_ID, snapshot.userId)
            .apply {
                SettingsContract.CATEGORIES.forEach { category ->
                    putString(
                        SettingsContract.categoryModeKey(category),
                        snapshot.categoryMode(category).persistedValue,
                    )
                }
            }
            .apply()
        current = snapshot
        Log.d(
            "embedded settings updated: enabled=${snapshot.enabled}; " +
                "submission=${snapshot.showSubmissionButton}; " +
                "userIdConfigured=${Identity.isValid(snapshot.userId)}",
        )
        application.sendBroadcast(
            Intent(SettingsContract.ACTION_UPDATE_MODULE_SETTINGS)
                .setPackage(SettingsContract.MODULE_PACKAGE)
                .putExtra(SettingsContract.EXTRA_SETTINGS, SettingsContract.settingsBundle(snapshot)),
        )
        onLocalStatsSyncRequested?.invoke()
    }

    init {
        registerMirrorReceiver()
        requestModuleSettings()
    }

    private fun requestModuleSettings() {
        application.sendBroadcast(
            Intent(SettingsContract.ACTION_REQUEST_MODULE_SETTINGS)
                .setPackage(SettingsContract.MODULE_PACKAGE)
                .putExtra(SettingsContract.EXTRA_TARGET_PACKAGE, application.packageName),
        )
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
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
                onLocalStatsSyncRequested?.invoke()
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
        return snapshot(
            mirrorPreferences::getBoolean,
            { key, default -> mirrorPreferences.getString(key, default) ?: default },
            mirrorPreferences::contains,
        )
    }

    private fun readFromLegacyPreferences(): SettingsSnapshot {
        preferences.reload()
        return snapshot(
            preferences::getBoolean,
            { key, default -> preferences.getString(key, default) ?: default },
            preferences::contains,
        )
    }

    private fun snapshot(
        getBoolean: (String, Boolean) -> Boolean,
        getString: (String, String) -> String,
        contains: (String) -> Boolean,
    ) = SettingsSnapshot(
        enabled = getBoolean(SettingsContract.KEY_ENABLED, true),
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
        categoryModes = SettingsContract.CATEGORIES.associateWith { category ->
            val default = SettingsContract.defaultCategoryMode(category)
            when {
                contains(SettingsContract.categoryModeKey(category)) -> CategoryMode.fromPersisted(
                    getString(SettingsContract.categoryModeKey(category), default.persistedValue),
                    default,
                )
                contains(SettingsContract.categoryKey(category)) -> if (
                    getBoolean(SettingsContract.categoryKey(category), category == "sponsor")
                ) {
                    if (getBoolean(SettingsContract.KEY_AUTO_SKIP, true)) {
                        CategoryMode.AUTO_SKIP
                    } else {
                        CategoryMode.MANUAL_SKIP
                    }
                } else {
                    CategoryMode.DISABLED
                }
                else -> default
            }
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
    const val ACTION_UPDATE_MODULE_SETTINGS = "com.retrsoft.bilisponsorskip.UPDATE_MODULE_SETTINGS"
    const val ACTION_REQUEST_MODULE_SETTINGS = "com.retrsoft.bilisponsorskip.REQUEST_MODULE_SETTINGS"
    const val ACTION_RECORD_LOCAL_SKIP = "com.retrsoft.bilisponsorskip.RECORD_LOCAL_SKIP"
    const val EXTRA_SETTINGS = "settings"
    const val EXTRA_TARGET_PACKAGE = "target_package"
    const val EXTRA_SAVED_DURATION_MS = "saved_duration_ms"
    const val EXTRA_STATS_PACKAGE = "stats_package"
    const val EXTRA_STATS_PROCESS = "stats_process"
    const val EXTRA_STATS_GENERATION = "stats_generation"
    const val EXTRA_STATS_COUNT = "stats_count"
    const val EXTRA_STATS_SAVED_MS = "stats_saved_ms"
    const val KEY_LOCAL_SKIP_COUNT = "local_skip_count"
    const val KEY_LOCAL_SAVED_MS = "local_saved_ms"
    const val KEY_LOCAL_STATS_SOURCE_PREFIX = "local_stats_source_v2_"

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

    val DEFAULT_CATEGORY_MODES: Map<String, CategoryMode> = linkedMapOf(
        "sponsor" to CategoryMode.AUTO_SKIP,
        "selfpromo" to CategoryMode.MANUAL_SKIP,
        "interaction" to CategoryMode.MANUAL_SKIP,
        "intro" to CategoryMode.MANUAL_SKIP,
        "outro" to CategoryMode.MANUAL_SKIP,
        "preview" to CategoryMode.SHOW_OVERLAY,
        "filler" to CategoryMode.DISABLED,
        "padding" to CategoryMode.AUTO_SKIP,
        "music_offtopic" to CategoryMode.AUTO_SKIP,
    )

    fun categoryKey(category: String) = "category_$category"

    fun categoryModeKey(category: String) = "category_mode_$category"

    fun defaultCategoryMode(category: String) = DEFAULT_CATEGORY_MODES[category] ?: CategoryMode.DISABLED

    fun legacyCategoryMode(enabled: Boolean, autoSkip: Boolean): CategoryMode = when {
        !enabled -> CategoryMode.DISABLED
        autoSkip -> CategoryMode.AUTO_SKIP
        else -> CategoryMode.MANUAL_SKIP
    }

    fun settingsBundle(snapshot: SettingsSnapshot) = Bundle().apply {
        putBoolean(KEY_ENABLED, snapshot.enabled)
        putBoolean(KEY_NOTIFY_FOUND, snapshot.notifyFound)
        putBoolean(KEY_NOTIFY_SKIPPED, snapshot.notifySkipped)
        putBoolean(KEY_NOTIFY_FETCH_FAILURE, snapshot.notifyFetchFailure)
        putBoolean(KEY_SHOW_TITLE_LABEL, snapshot.showTitleLabel)
        putBoolean(KEY_SHOW_PROGRESS_MARKERS, snapshot.showProgressMarkers)
        putBoolean(KEY_SKIP_ON_SEEK, snapshot.skipOnSeek)
        putString(KEY_MIN_DURATION, snapshot.minDurationSeconds.toString())
        putBoolean(KEY_SHOW_SUBMISSION_BUTTON, snapshot.showSubmissionButton)
        putString(KEY_USER_ID, snapshot.userId)
        CATEGORIES.forEach { category ->
            putString(categoryModeKey(category), snapshot.categoryMode(category).persistedValue)
        }
    }

    val BOOLEAN_SETTING_KEYS = listOf(
        KEY_ENABLED,
        KEY_NOTIFY_FOUND,
        KEY_NOTIFY_SKIPPED,
        KEY_NOTIFY_FETCH_FAILURE,
        KEY_SHOW_TITLE_LABEL,
        KEY_SHOW_PROGRESS_MARKERS,
        KEY_SKIP_ON_SEEK,
        KEY_SHOW_SUBMISSION_BUTTON,
    )

    val STRING_SETTING_KEYS: List<String>
        get() = listOf(KEY_MIN_DURATION, KEY_USER_ID, KEY_USERNAME) + CATEGORIES.map(::categoryModeKey)

    fun sourceStatsKey(source: String, value: String) = "$KEY_LOCAL_STATS_SOURCE_PREFIX${source}_$value"
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
