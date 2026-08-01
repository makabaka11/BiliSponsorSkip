package com.retrsoft.bilisponsorskip

import de.robv.android.xposed.XSharedPreferences

internal data class SettingsSnapshot(
    val enabled: Boolean = true,
    val autoSkip: Boolean = true,
    val notifyFound: Boolean = true,
    val notifySkipped: Boolean = true,
    val notifyFetchFailure: Boolean = false,
    val skipOnSeek: Boolean = true,
    val minDurationSeconds: Int = 0,
    val enabledCategories: Set<String> = setOf("sponsor"),
)

internal class SettingsRepository {
    private val preferences = XSharedPreferences(MODULE_PACKAGE)

    @Volatile
    var current = SettingsSnapshot()
        private set

    fun refresh(): SettingsSnapshot {
        current = runCatching {
            preferences.reload()
            SettingsSnapshot(
                enabled = preferences.getBoolean(SettingsContract.KEY_ENABLED, true),
                autoSkip = preferences.getBoolean(SettingsContract.KEY_AUTO_SKIP, true),
                notifyFound = preferences.getBoolean(SettingsContract.KEY_NOTIFY_FOUND, true),
                notifySkipped = preferences.getBoolean(SettingsContract.KEY_NOTIFY_SKIPPED, true),
                notifyFetchFailure = preferences.getBoolean(SettingsContract.KEY_NOTIFY_FETCH_FAILURE, false),
                skipOnSeek = preferences.getBoolean(SettingsContract.KEY_SKIP_ON_SEEK, true),
                minDurationSeconds = preferences.getString(SettingsContract.KEY_MIN_DURATION, "0")
                    ?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                enabledCategories = SettingsContract.CATEGORIES
                    .filterTo(linkedSetOf()) {
                        preferences.getBoolean(SettingsContract.categoryKey(it), it == "sponsor")
                    },
            )
        }.onFailure { Log.e("failed to read module settings; using defaults", it) }
            .getOrDefault(SettingsSnapshot())
        return current
    }

    private companion object {
        const val MODULE_PACKAGE = "com.retrsoft.bilisponsorskip"
    }
}

internal object SettingsContract {
    const val KEY_ENABLED = "enabled"
    const val KEY_AUTO_SKIP = "auto_skip"
    const val KEY_NOTIFY_FOUND = "notify_found"
    const val KEY_NOTIFY_SKIPPED = "notify_skipped"
    const val KEY_NOTIFY_FETCH_FAILURE = "notify_fetch_failure"
    const val KEY_SKIP_ON_SEEK = "skip_on_seek"
    const val KEY_MIN_DURATION = "min_duration"

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
