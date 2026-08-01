@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.retrsoft.bilisponsorskip

import android.os.Bundle
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.PreferenceCategory
import android.preference.PreferenceScreen
import android.preference.SwitchPreference

class SettingsActivity : PreferenceActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings_title)
        preferenceScreen = createScreen()
    }

    private fun createScreen(): PreferenceScreen = preferenceManager.createPreferenceScreen(this).apply {
        addPreference(category("基本功能"))
        addPreference(toggle(SettingsContract.KEY_ENABLED, "启用模块功能", "关闭后仅保留 Hook，不请求片段数据", true))
        addPreference(toggle(SettingsContract.KEY_AUTO_SKIP, "自动跳过片段", "进入已选择的片段时跳转到片段末尾", true))
        addPreference(toggle(SettingsContract.KEY_NOTIFY_FOUND, "发现片段时提示", "打开含特殊片段的视频后显示 Toast", true))
        addPreference(toggle(SettingsContract.KEY_NOTIFY_SKIPPED, "执行跳过后提示", "显示跳过的分类和时长", true))
        addPreference(toggle(SettingsContract.KEY_NOTIFY_FETCH_FAILURE, "请求失败时提示", "片段服务器暂时不可用时显示 Toast", false))
        addPreference(toggle(SettingsContract.KEY_SKIP_ON_SEEK, "快进到片段中间时仍跳过", "对应网页端的“快进到片段中间时仍然跳过”", true))
        addPreference(ListPreference(this@SettingsActivity).apply {
            key = SettingsContract.KEY_MIN_DURATION
            title = "最短片段时长"
            summary = "%s；更短的片段不会提示或跳过"
            entries = arrayOf("不限制", "1 秒", "2 秒", "5 秒", "10 秒")
            entryValues = arrayOf("0", "1", "2", "5", "10")
            setDefaultValue("0")
        })

        addPreference(category("自动跳过的片段分类"))
        addPreference(info("为避免改变观看习惯，默认只启用“赞助/恰饭”；其余分类可按需开启。"))
        SettingsContract.CATEGORIES.forEach { name ->
            addPreference(toggle(SettingsContract.categoryKey(name), name.categoryLabel(), categorySummary(name), name == "sponsor"))
        }

        addPreference(category("使用说明"))
        addPreference(info("修改设置后重新打开视频即可生效。LSPosed 中需启用模块并勾选对应的 B 站客户端。"))
    }

    private fun category(title: String) = PreferenceCategory(this).apply { this.title = title }

    private fun toggle(key: String, title: String, summary: String, default: Boolean) =
        SwitchPreference(this).apply {
            this.key = key
            this.title = title
            this.summary = summary
            setDefaultValue(default)
        }

    private fun info(summary: String) = Preference(this).apply {
        this.summary = summary
        isSelectable = false
    }

    private fun categorySummary(category: String): String = when (category) {
        "sponsor" -> "付费推广、推荐和直接广告"
        "selfpromo" -> "捐赠、会员、周边或无偿推广"
        "interaction" -> "点赞、投币、关注等提醒"
        "intro" -> "没有实际内容的过场或开场动画"
        "outro" -> "没有实际内容的致谢或片尾画面"
        "preview" -> "回顾、概要或稍后内容的预告"
        "filler" -> "对理解主题非必需的离题内容"
        "padding" -> "黑屏或与主体无关的填充画面"
        "music_offtopic" -> "音乐视频中的非音乐部分"
        else -> category
    }
}
