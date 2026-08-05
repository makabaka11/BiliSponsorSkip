@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.retrsoft.bilisponsorskip

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.preference.ListPreference
import android.preference.EditTextPreference
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.PreferenceCategory
import android.preference.PreferenceManager
import android.preference.PreferenceScreen
import android.preference.SwitchPreference
import android.widget.Toast
import android.text.InputType

class SettingsActivity : PreferenceActivity() {
    private val settingsChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        window?.decorView?.post { pushSettingsToTargets() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings_title)
        preferenceScreen = createScreen()
        ensureUserId()
        findPreference(SettingsContract.KEY_USER_ID)?.summary = userIdSummary(
            modulePreferences().getString(SettingsContract.KEY_USER_ID, "").orEmpty(),
        )
        findPreference(SettingsContract.KEY_USERNAME)?.summary = usernameSummary(
            modulePreferences().getString(SettingsContract.KEY_USERNAME, "").orEmpty(),
        )
        modulePreferences().registerOnSharedPreferenceChangeListener(settingsChangeListener)
        pushSettingsToTargets()
    }

    override fun onDestroy() {
        modulePreferences().unregisterOnSharedPreferenceChangeListener(settingsChangeListener)
        super.onDestroy()
    }

    private fun createScreen(): PreferenceScreen = preferenceManager.createPreferenceScreen(this).apply {
        addPreference(category("基本功能"))
        addPreference(toggle(SettingsContract.KEY_ENABLED, "启用模块功能", "关闭后仅保留 Hook，不请求片段数据", true))
        addPreference(toggle(SettingsContract.KEY_AUTO_SKIP, "自动跳过片段", "进入已选择的片段时跳转到片段末尾", true))
        addPreference(toggle(SettingsContract.KEY_NOTIFY_FOUND, "发现片段时提示", "打开含特殊片段的视频后显示 Toast", true))
        addPreference(toggle(SettingsContract.KEY_NOTIFY_SKIPPED, "执行跳过后提示", "显示跳过的分类和时长", true))
        addPreference(toggle(SettingsContract.KEY_NOTIFY_FETCH_FAILURE, "请求失败时提示", "片段服务器暂时不可用时显示 Toast", false))
        addPreference(toggle(SettingsContract.KEY_SHOW_TITLE_LABEL, "在标题前显示片段标签", "按已启用的片段分类显示彩色标签", true))
        addPreference(toggle(SettingsContract.KEY_SHOW_PROGRESS_MARKERS, "在进度条标记片段", "使用分类颜色标出特殊片段所在区间", true))
        addPreference(toggle(SettingsContract.KEY_SKIP_ON_SEEK, "快进到片段中间时仍跳过", "对应网页端的“快进到片段中间时仍然跳过”", true))
        addPreference(ListPreference(this@SettingsActivity).apply {
            key = SettingsContract.KEY_MIN_DURATION
            title = "最短片段时长"
            summary = "%s；更短的片段不会提示或跳过"
            entries = arrayOf("不限制", "1 秒", "2 秒", "5 秒", "10 秒")
            entryValues = arrayOf("0", "1", "2", "5", "10")
            setDefaultValue("0")
        })

        addPreference(category("片段提交与投票"))
        addPreference(toggle(
            SettingsContract.KEY_SHOW_SUBMISSION_BUTTON,
            "显示提交按钮",
            "在视频播放器控制栏显示片段提交与投票入口",
            false,
        ))
        addPreference(EditTextPreference(this@SettingsActivity).apply {
            key = SettingsContract.KEY_USERNAME
            title = "用户名"
            dialogTitle = "设置公开用户名"
            dialogMessage = "该名称会绑定到本机的私有用户 ID，并显示在空降助手的用户信息中。"
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            editText.isSingleLine = true
            summary = usernameSummary(modulePreferences().getString(key, "").orEmpty())
            setOnPreferenceChangeListener { preference, newValue ->
                val value = newValue?.toString()?.trim().orEmpty()
                if (value.isBlank()) {
                    Toast.makeText(this@SettingsActivity, "用户名不能为空", Toast.LENGTH_SHORT).show()
                    false
                } else {
                    val oldValue = modulePreferences().getString(key, "").orEmpty()
                    preference.summary = usernameSummary(value)
                    updateUsername(value, oldValue, preference as EditTextPreference)
                    true
                }
            }
        })
        addPreference(EditTextPreference(this@SettingsActivity).apply {
            key = SettingsContract.KEY_USER_ID
            title = "私有用户 ID（高级）"
            dialogTitle = "导入或更换私有用户 ID"
            dialogMessage = "这是提交和投票使用的私有凭据，应像密码一样保密。至少 32 位，请勿填写用户名、账号或手机号。"
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            summary = userIdSummary(modulePreferences().getString(key, "").orEmpty())
            setOnPreferenceChangeListener { preference, newValue ->
                val value = newValue?.toString()?.trim().orEmpty()
                if (!Identity.isValid(value)) {
                    Toast.makeText(this@SettingsActivity, "私有用户 ID 至少需要 32 位", Toast.LENGTH_SHORT).show()
                    false
                } else {
                    preference.summary = userIdSummary(value)
                    true
                }
            }
        })
        addPreference(info("私有用户 ID 默认在本机随机生成；日常只需设置上方的公开用户名。"))

        addPreference(category("自动跳过的片段分类"))
        addPreference(info("为避免改变观看习惯，默认只启用“赞助/恰饭”；其余分类可按需开启。"))
        SettingsContract.CATEGORIES.forEach { name ->
            addPreference(toggle(SettingsContract.categoryKey(name), name.categoryLabel(), categorySummary(name), name == "sponsor"))
        }

        addPreference(category("使用说明"))
        addPreference(info("修改设置后重新打开视频即可生效。LSPosed 中需启用模块并勾选对应的 B 站客户端。"))

        addPreference(category("关于"))
        addPreference(info("版本", installedVersionName()))
        addPreference(link("作者", "github.com/makabaka11", "https://github.com/makabaka11"))
        addPreference(link("联系", "ded000@retr0.xyz", "mailto:ded000@retr0.xyz"))
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

    private fun info(title: String, summary: String) = Preference(this).apply {
        this.title = title
        this.summary = summary
        isSelectable = false
    }

    private fun link(title: String, summary: String, uri: String) = Preference(this).apply {
        this.title = title
        this.summary = summary
        setOnPreferenceClickListener {
            runCatching {
                val action = if (uri.startsWith("mailto:")) Intent.ACTION_SENDTO else Intent.ACTION_VIEW
                startActivity(Intent(action, Uri.parse(uri)))
            }.onFailure {
                Toast.makeText(this@SettingsActivity, "无法打开链接", Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    private fun installedVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "未知"

    private fun ensureUserId() {
        val preferences = modulePreferences()
        val existing = preferences.getString(SettingsContract.KEY_USER_ID, "").orEmpty()
        if (!Identity.isValid(existing)) {
            preferences.edit().putString(SettingsContract.KEY_USER_ID, Identity.generate()).apply()
        }
    }

    private fun modulePreferences() = PreferenceManager.getDefaultSharedPreferences(this)

    private fun pushSettingsToTargets() {
        val values = settingsBundle()
        SettingsContract.TARGET_PACKAGES.forEach { targetPackage ->
            runCatching {
                sendBroadcast(
                    Intent(SettingsContract.ACTION_UPDATE_SETTINGS)
                        .setPackage(targetPackage)
                        .putExtra(SettingsContract.EXTRA_SETTINGS, values),
                )
            }.onFailure { Log.e("failed to push settings to $targetPackage", it) }
        }
    }

    private fun settingsBundle(): Bundle {
        val preferences = modulePreferences()
        return Bundle().apply {
            putBoolean(SettingsContract.KEY_ENABLED, preferences.getBoolean(SettingsContract.KEY_ENABLED, true))
            putBoolean(SettingsContract.KEY_AUTO_SKIP, preferences.getBoolean(SettingsContract.KEY_AUTO_SKIP, true))
            putBoolean(SettingsContract.KEY_NOTIFY_FOUND, preferences.getBoolean(SettingsContract.KEY_NOTIFY_FOUND, true))
            putBoolean(SettingsContract.KEY_NOTIFY_SKIPPED, preferences.getBoolean(SettingsContract.KEY_NOTIFY_SKIPPED, true))
            putBoolean(SettingsContract.KEY_NOTIFY_FETCH_FAILURE, preferences.getBoolean(SettingsContract.KEY_NOTIFY_FETCH_FAILURE, false))
            putBoolean(SettingsContract.KEY_SHOW_TITLE_LABEL, preferences.getBoolean(SettingsContract.KEY_SHOW_TITLE_LABEL, true))
            putBoolean(SettingsContract.KEY_SHOW_PROGRESS_MARKERS, preferences.getBoolean(SettingsContract.KEY_SHOW_PROGRESS_MARKERS, true))
            putBoolean(SettingsContract.KEY_SKIP_ON_SEEK, preferences.getBoolean(SettingsContract.KEY_SKIP_ON_SEEK, true))
            putString(SettingsContract.KEY_MIN_DURATION, preferences.getString(SettingsContract.KEY_MIN_DURATION, "0"))
            putBoolean(SettingsContract.KEY_SHOW_SUBMISSION_BUTTON, preferences.getBoolean(SettingsContract.KEY_SHOW_SUBMISSION_BUTTON, false))
            putString(SettingsContract.KEY_USER_ID, preferences.getString(SettingsContract.KEY_USER_ID, ""))
            SettingsContract.CATEGORIES.forEach { category ->
                putBoolean(
                    SettingsContract.categoryKey(category),
                    preferences.getBoolean(SettingsContract.categoryKey(category), category == "sponsor"),
                )
            }
        }
    }

    private fun updateUsername(value: String, oldValue: String, preference: EditTextPreference) {
        val userId = modulePreferences().getString(SettingsContract.KEY_USER_ID, "").orEmpty()
        if (!Identity.isValid(userId)) {
            Toast.makeText(this, "私有用户 ID 尚未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        Thread({
            val result = SponsorBlockClient().setUsername(userId, value)
            runOnUiThread {
                if (result.successful) {
                    preference.summary = usernameSummary(value)
                    Toast.makeText(this, "用户名已更新", Toast.LENGTH_SHORT).show()
                } else {
                    modulePreferences().edit().putString(SettingsContract.KEY_USERNAME, oldValue).apply()
                    preference.text = oldValue
                    preference.summary = usernameSummary(oldValue)
                    val detail = result.message.replace('\n', ' ').take(100)
                    Toast.makeText(this, "用户名更新失败：$detail", Toast.LENGTH_LONG).show()
                }
            }
        }, "BiliSponsorSkip-username").apply { isDaemon = true }.start()
    }

    private fun userIdSummary(value: String): String = if (Identity.isValid(value)) {
        "已设置（${value.take(4)}••••${value.takeLast(4)}）"
    } else {
        "未设置"
    }

    private fun usernameSummary(value: String): String = value.trim().ifBlank { "未设置" }

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
