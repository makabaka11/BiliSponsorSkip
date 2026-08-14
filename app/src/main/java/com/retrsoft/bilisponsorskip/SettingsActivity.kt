package com.retrsoft.bilisponsorskip

import android.content.Intent
import android.content.SharedPreferences
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.InputType
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.DynamicColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.lang.ref.WeakReference
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    @Volatile
    private var userIdGenerationInProgress = false

    private val settingsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        window?.decorView?.post {
            if (key == SettingsContract.KEY_LOCAL_SKIP_COUNT || key == SettingsContract.KEY_LOCAL_SAVED_MS) {
                settingsFragment()?.refreshLocalStats()
            } else {
                pushSettingsToTargets()
                if (key == SettingsContract.KEY_USER_ID) {
                    settingsFragment()?.refreshUserId()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        ensureUserId()

        val containerId = View.generateViewId()
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.settings_title)
            subtitle = "播放器片段跳过、提交与投票"
            setTitleTextAppearance(this@SettingsActivity, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
        }
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(view.paddingLeft, topInset, view.paddingRight, view.paddingBottom)
            view.layoutParams = view.layoutParams.apply { height = dp(72) + topInset }
            insets
        }
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(resolveThemeColor(com.google.android.material.R.attr.colorSurface))
            addView(toolbar, android.widget.LinearLayout.LayoutParams(-1, dp(72)))
            addView(FrameLayout(this@SettingsActivity).apply { id = containerId },
                android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(containerId, SettingsFragment())
                .commit()
        }

        modulePreferences().registerOnSharedPreferenceChangeListener(settingsChangeListener)
        pushSettingsToTargets()
    }

    override fun onDestroy() {
        modulePreferences().unregisterOnSharedPreferenceChangeListener(settingsChangeListener)
        super.onDestroy()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private val host get() = requireActivity() as SettingsActivity
        private lateinit var dataCategory: RefreshPreferenceCategory
        private lateinit var remoteStats: Preference
        private lateinit var localStats: Preference
        private var remoteLoading = false
        private var remoteLoadingStartedAt = 0L

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceScreen = createScreen()
            refreshLocalStats()
            loadRemoteStats()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.setPadding(host.dp(8), 0, host.dp(8), host.dp(24))
            listView.clipToPadding = false
            listView.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setDivider(null)
        }

        private fun createScreen(): PreferenceScreen = preferenceManager.createPreferenceScreen(host).apply {
            addPreference(category("基本功能"))
            addPreference(toggle(SettingsContract.KEY_ENABLED, "启用模块功能", "关闭后仅保留 Hook，不请求片段数据", true))
            addPreference(toggle(SettingsContract.KEY_AUTO_SKIP, "自动跳过片段", "进入已选择的片段时跳转到片段末尾", true))
            addPreference(toggle(SettingsContract.KEY_NOTIFY_FOUND, "发现片段时提示", "打开含特殊片段的视频后显示 Toast", true))
            addPreference(toggle(SettingsContract.KEY_NOTIFY_SKIPPED, "执行跳过后提示", "显示跳过的分类和时长", true))
            addPreference(toggle(SettingsContract.KEY_NOTIFY_FETCH_FAILURE, "请求失败时提示", "片段服务器暂时不可用时显示 Toast", false))
            addPreference(toggle(SettingsContract.KEY_SHOW_TITLE_LABEL, "在标题前显示片段标签", "按已启用的片段分类显示彩色标签", true))
            addPreference(toggle(SettingsContract.KEY_SHOW_PROGRESS_MARKERS, "在进度条标记片段", "使用分类颜色标出特殊片段所在区间", true))
            addPreference(toggle(SettingsContract.KEY_SKIP_ON_SEEK, "快进到片段中间时仍跳过", "对应网页端的“快进到片段中间时仍然跳过”", true))
            addPreference(ListPreference(host).apply {
                key = SettingsContract.KEY_MIN_DURATION
                title = "最短片段时长"
                summary = "%s；更短的片段不会提示或跳过"
                isIconSpaceReserved = false
                widgetLayoutResource = R.layout.preference_widget_chevron
                entries = arrayOf("不限制", "1 秒", "2 秒", "5 秒", "10 秒")
                entryValues = arrayOf("0", "1", "2", "5", "10")
                setDefaultValue("0")
            })

            addPreference(category("片段提交与投票"))
            addPreference(toggle(SettingsContract.KEY_SHOW_SUBMISSION_BUTTON, "显示提交按钮",
                "在视频播放器控制栏显示片段提交与投票入口", false))
            addPreference(EditTextPreference(host).apply {
                key = SettingsContract.KEY_USERNAME
                title = "用户名"
                isIconSpaceReserved = false
                widgetLayoutResource = R.layout.preference_widget_edit
                dialogTitle = "设置公开用户名"
                dialogMessage = "该名称会绑定到本机的私有用户 ID，并显示在空降助手的用户信息中。"
                summary = host.usernameSummary(host.modulePreferences().getString(key, "").orEmpty())
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    it.isSingleLine = true
                }
                setOnPreferenceChangeListener { preference, newValue ->
                    val value = newValue?.toString()?.trim().orEmpty()
                    if (value.isBlank()) {
                        host.toast("用户名不能为空")
                        false
                    } else {
                        val oldValue = host.modulePreferences().getString(key, "").orEmpty()
                        preference.summary = host.usernameSummary(value)
                        host.updateUsername(value, oldValue, preference as EditTextPreference)
                        true
                    }
                }
            })
            addPreference(EditTextPreference(host).apply {
                key = SettingsContract.KEY_USER_ID
                title = "私有用户 ID（高级）"
                isIconSpaceReserved = false
                widgetLayoutResource = R.layout.preference_widget_edit
                dialogTitle = "导入或更换私有用户 ID"
                dialogMessage = "这是提交和投票使用的私有凭据，应像密码一样保密。至少 32 位，请勿填写用户名、账号或手机号。"
                summary = host.userIdSummary(host.modulePreferences().getString(key, "").orEmpty())
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                }
                setOnPreferenceChangeListener { preference, newValue ->
                    val value = newValue?.toString()?.trim().orEmpty()
                    if (!Identity.isValid(value)) {
                        host.toast("私有用户 ID 至少需要 32 位")
                        false
                    } else {
                        preference.summary = host.userIdSummary(value)
                        true
                    }
                }
            })
            addPreference(info("私有用户 ID 默认在本机随机生成；日常只需设置上方的公开用户名。"))

            dataCategory = RefreshPreferenceCategory(host) {
                refreshLocalStats()
                loadRemoteStats()
            }
            addPreference(dataCategory)
            remoteStats = info("正在加载您的贡献数据…")
            localStats = info("")
            addPreference(remoteStats)
            addPreference(localStats)

            addPreference(category("自动跳过的片段分类"))
            addPreference(info("为避免改变观看习惯，默认只启用“赞助/恰饭”；其余分类可按需开启。"))
            SettingsContract.CATEGORIES.forEach { name ->
                addPreference(toggle(SettingsContract.categoryKey(name), name.categoryLabel(), host.categorySummary(name), name == "sponsor"))
            }

            addPreference(category("使用说明"))
            addPreference(info("修改设置后重新打开视频即可生效。LSPosed 中需启用模块并勾选对应的 B 站客户端。"))
            addPreference(category("关于"))
            addPreference(info("版本", host.installedVersionName()))
            addPreference(link("作者", "github.com/makabaka11", "https://github.com/makabaka11"))
            addPreference(link("联系", "ded000@retr0.xyz", "mailto:ded000@retr0.xyz"))
        }

        private fun category(value: String) = PreferenceCategory(host).apply {
            title = value
            isIconSpaceReserved = false
        }

        private fun toggle(keyValue: String, titleValue: String, summaryValue: String, default: Boolean) =
            SwitchPreferenceCompat(host).apply {
                key = keyValue
                title = titleValue
                summary = summaryValue
                isIconSpaceReserved = false
                setDefaultValue(default)
            }

        private fun info(summaryValue: String) = Preference(host).apply {
            summary = summaryValue
            isSelectable = false
            isIconSpaceReserved = false
        }

        private fun info(titleValue: String, summaryValue: String) = info(summaryValue).apply { title = titleValue }

        private fun link(titleValue: String, summaryValue: String, uri: String) = Preference(host).apply {
            title = titleValue
            summary = summaryValue
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                runCatching {
                    val action = if (uri.startsWith("mailto:")) Intent.ACTION_SENDTO else Intent.ACTION_VIEW
                    startActivity(Intent(action, Uri.parse(uri)))
                }.onFailure { host.toast("无法打开链接") }
                true
            }
        }

        fun refreshLocalStats() {
            if (!::localStats.isInitialized) return
            val preferences = host.modulePreferences()
            val count = preferences.getLong(SettingsContract.KEY_LOCAL_SKIP_COUNT, 0L).coerceAtLeast(0L)
            val minutes = preferences.getLong(SettingsContract.KEY_LOCAL_SAVED_MS, 0L)
                .coerceAtLeast(0L) / 60_000.0
            localStats.summary = localStatsSummary(count, minutes)
        }

        fun refreshUserId() {
            val preference = findPreference<EditTextPreference>(SettingsContract.KEY_USER_ID) ?: return
            val userId = host.modulePreferences()
                .getString(SettingsContract.KEY_USER_ID, "")
                .orEmpty()
            preference.text = userId
            preference.summary = host.userIdSummary(userId)
            if (Identity.isValid(userId) && ::remoteStats.isInitialized) loadRemoteStats()
        }

        private fun loadRemoteStats() {
            if (remoteLoading) return
            remoteLoading = true
            remoteLoadingStartedAt = SystemClock.uptimeMillis()
            dataCategory.setLoading(true)
            val userId = host.modulePreferences().getString(SettingsContract.KEY_USER_ID, "").orEmpty()
            if (!Identity.isValid(userId)) {
                remoteStats.summary = "私有用户 ID 尚未就绪，无法获取贡献数据"
                remoteLoading = false
                dataCategory.setLoading(false)
                return
            }
            Thread({
                val result = SponsorBlockClient().getUserContributionStats(userId)
                host.runOnUiThread {
                    val elapsed = SystemClock.uptimeMillis() - remoteLoadingStartedAt
                    val remaining = (MINIMUM_LOADING_VISIBLE_MS - elapsed).coerceAtLeast(0L)
                    host.window.decorView.postDelayed({
                        if (!isAdded || !::remoteStats.isInitialized) return@postDelayed
                        remoteLoading = false
                        dataCategory.setLoading(false)
                        remoteStats.summary = when (result) {
                            is SponsorBlockClient.UserStatsResult.Success ->
                                contributionStatsSummary(result.stats.viewCount, result.stats.minutesSaved)
                            is SponsorBlockClient.UserStatsResult.Failure ->
                                "贡献数据暂时无法获取（${result.message.take(80)}）"
                        }
                    }, remaining)
                }
            }, "BiliSponsorSkip-user-stats").apply { isDaemon = true }.start()
        }

        private fun formatMinutes(value: Double): String {
            val formatted = String.format(Locale.US, "%.2f", value.coerceAtLeast(0.0))
            return formatted.trimEnd('0').trimEnd('.')
        }

        private fun contributionStatsSummary(count: Long, minutes: Double): CharSequence {
            val countText = count.toString()
            val minutesText = formatMinutes(minutes)
            return boldValues(
                "您为大家节省了 $countText 片段的数据（$minutesText 分钟的生命）",
                countText,
                minutesText,
            )
        }

        private fun localStatsSummary(count: Long, minutes: Double): CharSequence {
            val countText = count.toString()
            val minutesText = formatMinutes(minutes)
            return boldValues("您已跳过 $countText 片段（$minutesText 分钟）", countText, minutesText)
        }

        private fun boldValues(text: String, vararg values: String): CharSequence {
            val result = SpannableString(text)
            var searchFrom = 0
            values.forEach { value ->
                val start = text.indexOf(value, searchFrom)
                if (start >= 0) {
                    result.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        start + value.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    searchFrom = start + value.length
                }
            }
            return result
        }

        private class RefreshPreferenceCategory(
            context: Context,
            private val onRefresh: () -> Unit,
        ) : PreferenceCategory(context) {
            private var loading = false
            private var refreshView = WeakReference<View>(null)
            private var progressView = WeakReference<CircularProgressIndicator>(null)

            init {
                title = "数据展示"
                layoutResource = R.layout.preference_category_refresh
                isIconSpaceReserved = false
                isSelectable = false
            }

            fun setLoading(value: Boolean) {
                if (loading == value) return
                loading = value
                renderLoadingState()
                notifyChanged()
            }

            override fun onBindViewHolder(holder: PreferenceViewHolder) {
                super.onBindViewHolder(holder)
                val refresh = holder.findViewById(R.id.data_refresh_button) as View
                val progress = holder.findViewById(R.id.data_refresh_progress) as CircularProgressIndicator
                refreshView = WeakReference(refresh)
                progressView = WeakReference(progress)
                renderLoadingState()
                refresh.setOnClickListener { onRefresh() }
            }

            private fun renderLoadingState() {
                val refresh = refreshView.get() ?: return
                val progress = progressView.get() ?: return
                refresh.visibility = if (loading) View.INVISIBLE else View.VISIBLE
                refresh.isEnabled = !loading
                if (loading) {
                    progress.isIndeterminate = true
                    progress.visibility = View.VISIBLE
                } else {
                    progress.visibility = View.GONE
                }
            }
        }

        private companion object {
            const val MINIMUM_LOADING_VISIBLE_MS = 700L
        }
    }

    private fun installedVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "未知"

    private fun ensureUserId() {
        val preferences = modulePreferences()
        if (
            Identity.isValid(preferences.getString(SettingsContract.KEY_USER_ID, "").orEmpty()) ||
            userIdGenerationInProgress
        ) return

        userIdGenerationInProgress = true
        Thread({
            val client = SponsorBlockClient()
            var failureMessage: String? = null
            var cleanUserId: String? = null
            for (attempt in 1..MAX_USER_ID_GENERATION_ATTEMPTS) {
                val candidate = Identity.generate()
                when (val result = client.getUserContributionStats(candidate)) {
                    is SponsorBlockClient.UserStatsResult.Success -> {
                        if (result.stats.viewCount == 0L && result.stats.minutesSaved == 0.0) {
                            cleanUserId = candidate
                            break
                        }
                        Log.d(
                            "generated user ID was already in use; " +
                                "attempt=$attempt, views=${result.stats.viewCount}, " +
                                "minutes=${result.stats.minutesSaved}",
                        )
                    }
                    is SponsorBlockClient.UserStatsResult.Failure -> {
                        failureMessage = result.message
                        break
                    }
                }
            }
            runOnUiThread {
                userIdGenerationInProgress = false
                if (
                    cleanUserId != null &&
                    !Identity.isValid(preferences.getString(SettingsContract.KEY_USER_ID, "").orEmpty())
                ) {
                    preferences.edit()
                        .putString(SettingsContract.KEY_USER_ID, cleanUserId)
                        .apply()
                    window.decorView.post { settingsFragment()?.refreshUserId() }
                    toast("已生成并确认全新的私有用户 ID")
                } else if (cleanUserId == null) {
                    settingsFragment()?.refreshUserId()
                    val reason = failureMessage?.replace('\n', ' ')?.take(80)
                        ?: "多次生成的 ID 均已有数据"
                    toast("私有用户 ID 校验失败：$reason", Toast.LENGTH_LONG)
                }
            }
        }, "BiliSponsorSkip-user-id").apply { isDaemon = true }.start()
    }

    private fun modulePreferences() = PreferenceManager.getDefaultSharedPreferences(this)

    private fun settingsFragment(): SettingsFragment? =
        supportFragmentManager.fragments.filterIsInstance<SettingsFragment>().firstOrNull()

    private fun pushSettingsToTargets() {
        val values = settingsBundle()
        SettingsContract.TARGET_PACKAGES.forEach { targetPackage ->
            runCatching {
                sendBroadcast(Intent(SettingsContract.ACTION_UPDATE_SETTINGS).setPackage(targetPackage)
                    .putExtra(SettingsContract.EXTRA_SETTINGS, values))
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
            putString(SettingsContract.KEY_USERNAME, preferences.getString(SettingsContract.KEY_USERNAME, ""))
            SettingsContract.CATEGORIES.forEach { category ->
                putBoolean(SettingsContract.categoryKey(category),
                    preferences.getBoolean(SettingsContract.categoryKey(category), category == "sponsor"))
            }
        }
    }

    private fun updateUsername(value: String, oldValue: String, preference: EditTextPreference) {
        val userId = modulePreferences().getString(SettingsContract.KEY_USER_ID, "").orEmpty()
        if (!Identity.isValid(userId)) return toast("私有用户 ID 尚未就绪")
        Thread({
            val result = SponsorBlockClient().setUsername(userId, value)
            runOnUiThread {
                if (result.successful) {
                    preference.summary = usernameSummary(value)
                    toast("用户名已更新")
                } else {
                    modulePreferences().edit().putString(SettingsContract.KEY_USERNAME, oldValue).apply()
                    preference.text = oldValue
                    preference.summary = usernameSummary(oldValue)
                    toast("用户名更新失败：${result.message.replace('\n', ' ').take(100)}", Toast.LENGTH_LONG)
                }
            }
        }, "BiliSponsorSkip-username").apply { isDaemon = true }.start()
    }

    private fun userIdSummary(value: String) = when {
        Identity.isValid(value) -> "已设置（${value.take(4)}••••${value.takeLast(4)}）"
        userIdGenerationInProgress -> "正在生成并通过 API 校验…"
        else -> "未设置"
    }
    private fun usernameSummary(value: String) = value.trim().ifBlank { "未设置" }
    private fun toast(value: String, duration: Int = Toast.LENGTH_SHORT) = Toast.makeText(this, value, duration).show()
    private fun resolveThemeColor(attr: Int): Int = android.util.TypedValue().let { out ->
        theme.resolveAttribute(attr, out, true)
        out.data
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

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

    private companion object {
        const val MAX_USER_ID_GENERATION_ATTEMPTS = 5
    }
}
