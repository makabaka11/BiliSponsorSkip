package com.retrsoft.bilisponsorskip

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.InputType
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.zip.ZipFile

internal class BiliSettingsEntryInjector(
    private val settings: SettingsRepository,
    private val classLoader: ClassLoader,
) {
    fun install() {
        val fragmentClass = Class.forName(PREFERENCES_FRAGMENT_CLASS, false, classLoader)
        XposedHelpers.findAndHookMethod(
            fragmentClass,
            "onCreatePreferences",
            Bundle::class.java,
            String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { injectEntry(param.thisObject) }
                        .onFailure { Log.e("failed to inject Bili settings entry", it) }
                }
            },
        )
        Log.d("Bili settings entry hook installed: $PREFERENCES_FRAGMENT_CLASS")
    }

    private fun injectEntry(fragment: Any) {
        val screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen") ?: return
        if (XposedHelpers.callMethod(screen, "findPreference", ENTRY_KEY) != null) return
        val activity = XposedHelpers.callMethod(fragment, "getActivity") as? Activity ?: return
        val preferenceClass = Class.forName(PREFERENCE_CLASS, false, classLoader)
        val preference = preferenceClass.getConstructor(Context::class.java).newInstance(activity)
        XposedHelpers.callMethod(preference, "setKey", ENTRY_KEY)
        XposedHelpers.callMethod(preference, "setTitle", ENTRY_TITLE)
        XposedHelpers.callMethod(preference, "setSummary", ENTRY_SUMMARY)
        normalizeRootOrder(screen)
        XposedHelpers.callMethod(preference, "setOrder", 0)
        runCatching { XposedHelpers.callMethod(preference, "setIconSpaceReserved", false) }
        installClickListener(preferenceClass, preference, activity)
        val added = XposedHelpers.callMethod(screen, "addPreference", preference) as? Boolean ?: false
        val firstKey = runCatching {
            val first = XposedHelpers.callMethod(screen, "getPreference", 0)
            XposedHelpers.callMethod(first, "getKey") as? String
        }.getOrNull()
        Log.d("Bili settings entry injected: activity=${activity.javaClass.name}; added=$added; first=$firstKey")
    }

    private fun normalizeRootOrder(screen: Any) {
        val count = XposedHelpers.callMethod(screen, "getPreferenceCount") as? Int ?: return
        val existing = List(count) { index -> XposedHelpers.callMethod(screen, "getPreference", index) }
        existing.forEachIndexed { index, preference ->
            XposedHelpers.callMethod(preference, "setOrder", index + 1)
        }
    }

    private fun installClickListener(preferenceClass: Class<*>, preference: Any, activity: Activity) {
        val setter = preferenceClass.methods.firstOrNull { method ->
            method.name == "setOnPreferenceClickListener" &&
                method.parameterTypes.size == 1 && method.parameterTypes[0].isInterface
        } ?: error("Preference click listener setter not found")
        val listenerType = setter.parameterTypes[0]
        val listener = Proxy.newProxyInstance(classLoader, arrayOf(listenerType)) { proxy, method, args ->
            when {
                method.name == "toString" -> "BiliSponsorSkipSettingsEntry"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.returnType == Boolean::class.javaPrimitiveType -> {
                    openSettings(activity)
                    true
                }
                else -> null
            }
        }
        setter.invokeUnwrapped(preference, listener)
    }

    private fun openSettings(activity: Activity) {
        val signals = SettingsLaunchSignals(
            hostApkVerifiedNotLspatch = hostApkVerifiedNotLspatch(activity),
            frameworkApiAvailable = frameworkApiAvailable(),
            standaloneModuleScopeConfirmed = settings.standaloneModuleScopeConfirmed,
        )
        if (selectSettingsLaunchMode(signals) == SettingsLaunchMode.STANDALONE) {
            val standalone = Intent().setClassName(
                SettingsContract.MODULE_PACKAGE,
                "${SettingsContract.MODULE_PACKAGE}.SettingsActivity",
            ).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            try {
                activity.startActivity(standalone)
                Log.d("opened verified standalone module settings from Bili settings")
                return
            } catch (error: ActivityNotFoundException) {
                Log.e("verified standalone module settings activity not found; using embedded settings", error)
            } catch (error: SecurityException) {
                Log.e("verified standalone module settings activity denied; using embedded settings", error)
            }
        }
        EmbeddedSettingsDialog(activity, settings).show()
        Log.d("opened embedded module settings: $signals")
    }

    private fun hostApkVerifiedNotLspatch(activity: Activity): Boolean {
        val sourceDir = activity.applicationInfo.sourceDir ?: return false
        return runCatching {
            ZipFile(sourceDir).use { apk ->
                !containsLspatchMarker(apk.entries().asSequence().map { it.name })
            }
        }.onFailure { error ->
            Log.e("failed to inspect host APK for LSPatch markers; keeping embedded settings", error)
        }.getOrDefault(false)
    }

    private fun frameworkApiAvailable(): Boolean = runCatching {
        XposedBridge.getXposedVersion() > 0
    }.onFailure { error ->
        Log.e("Xposed framework API unavailable; keeping embedded settings", error)
    }.getOrDefault(false)

    private companion object {
        const val PREFERENCES_FRAGMENT_CLASS =
            "com.bilibili.app.preferences.BiliPreferencesActivity\$BiliPreferencesFragment"
        const val PREFERENCE_CLASS = "androidx.preference.Preference"
        const val ENTRY_KEY = "bilisponsorskip_settings_entry"
        const val ENTRY_TITLE = "哔哩空降助手"
        const val ENTRY_SUMMARY = "SponsorBlock 片段跳过、提交与投票设置"
    }
}

private class EmbeddedSettingsDialog(
    private val activity: Activity,
    private val settings: SettingsRepository,
) {
    private val initial = settings.refresh()
    private val colors = embeddedSettingsColors(isDarkDialogTheme(activity))
    private val switches = linkedMapOf<String, Switch>()
    private val categorySpinners = linkedMapOf<String, Spinner>()
    private lateinit var minimumDurationSpinner: Spinner
    private lateinit var usernameInput: EditText
    private lateinit var userIdInput: EditText
    private lateinit var remoteStatsText: TextView
    private lateinit var localStatsText: TextView
    private lateinit var versionSummaryText: TextView

    fun show() {
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.surface)
            setPadding(activity.dp(20), activity.dp(8), activity.dp(20), activity.dp(12))
        }
        body.addView(info("当前使用内嵌设置，配置会保存在此 B 站客户端中。仅在确认框架可用且独立模块已安装时，才会打开完整设置页。"))
        body.addView(section("基本功能"))
        addSwitch(body, SettingsContract.KEY_ENABLED, "启用模块功能", "关闭后仅保留 Hook，不请求片段数据", initial.enabled)
        addSwitch(body, SettingsContract.KEY_NOTIFY_FOUND, "发现片段时提示", "打开含特殊片段的视频后显示 Toast", initial.notifyFound)
        addSwitch(body, SettingsContract.KEY_NOTIFY_SKIPPED, "执行跳过后提示", "显示跳过的分类和时长", initial.notifySkipped)
        addSwitch(body, SettingsContract.KEY_NOTIFY_FETCH_FAILURE, "请求失败时提示", "片段服务器暂时不可用时显示 Toast", initial.notifyFetchFailure)
        addSwitch(body, SettingsContract.KEY_SHOW_TITLE_LABEL, "在标题前显示片段标签", "按已启用的片段分类显示彩色标签", initial.showTitleLabel)
        addSwitch(body, SettingsContract.KEY_SHOW_PROGRESS_MARKERS, "在进度条标记片段", "使用分类颜色标出特殊片段所在区间", initial.showProgressMarkers)
        addSwitch(body, SettingsContract.KEY_SKIP_ON_SEEK, "快进到片段中间时仍跳过", "对应网页端的“快进到片段中间时仍然跳过”", initial.skipOnSeek)
        minimumDurationSpinner = spinner(MINIMUM_DURATION_LABELS).apply {
            setSelection(MINIMUM_DURATION_VALUES.indexOf(initial.minDurationSeconds).coerceAtLeast(0))
        }
        body.addView(spinnerRow("最短片段时长", "更短的片段不会提示或跳过", minimumDurationSpinner))

        body.addView(section("定制功能"))
        addSwitch(
            body,
            SettingsContract.KEY_PERSIST_PLAYBACK_SPEED,
            "播放倍速持久化",
            "记住视频中选择的倍速，并自动应用到后续视频及重启后的 B 站；首次启用后请重启 B 站",
            initial.persistPlaybackSpeed,
        )

        body.addView(section("片段提交与投票"))
        addSwitch(
            body,
            SettingsContract.KEY_SHOW_SUBMISSION_BUTTON,
            "显示提交按钮",
            "在视频播放器控制栏显示片段提交与投票入口",
            initial.showSubmissionButton,
        )
        body.addView(fieldLabel("用户名"))
        usernameInput = textInput(
            hint = "公开用户名",
            value = initial.username,
            inputTypeValue = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        )
        body.addView(usernameInput)
        body.addView(fieldLabel("私有用户 ID（高级）"))
        userIdInput = EditText(activity).apply {
            hint = "至少 32 位"
            setText(initial.userId)
            setTextColor(colors.primaryText)
            setHintTextColor(colors.secondaryText)
            backgroundTintList = fieldTintList()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
        }
        body.addView(userIdInput, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = activity.dp(4)
            bottomMargin = activity.dp(8)
        })
        body.addView(info("私有用户 ID 默认在本机随机生成；日常只需设置上方的公开用户名。这是提交和投票使用的私有凭据，应像密码一样保密。"))

        body.addView(section("数据展示"))
        val refresh = Button(activity).apply {
            text = "刷新数据"
            setTextColor(colors.accent)
            backgroundTintList = ColorStateList.valueOf(colors.popupSurface)
            setOnClickListener { loadStats() }
        }
        body.addView(refresh, LinearLayout.LayoutParams(-1, activity.dp(48)))
        remoteStatsText = info("正在加载您的贡献数据…")
        localStatsText = info("")
        body.addView(remoteStatsText)
        body.addView(localStatsText)

        body.addView(section("片段分类行为"))
        body.addView(info("默认行为与原版浏览器插件一致；“手动跳过”会在进入片段时提供跳过按钮。"))
        SettingsContract.CATEGORIES.forEach { category ->
            val spinner = spinner(CATEGORY_MODE_LABELS).apply {
                setSelection(CATEGORY_MODES.indexOf(initial.categoryMode(category)).coerceAtLeast(0))
            }
            categorySpinners[category] = spinner
            body.addView(spinnerRow(category.categoryLabel(), categorySummary(category), spinner))
        }

        body.addView(section("使用说明"))
        body.addView(info("修改设置后重新打开视频即可生效。LSPosed 中需启用模块并勾选对应的 B 站客户端。"))

        body.addView(section("关于"))
        val versionRow = actionRow("版本", moduleVersionName()) {
            openLink(UpdateChecker.LATEST_RELEASE_PAGE_URL)
        }
        versionSummaryText = versionRow.getChildAt(1) as TextView
        body.addView(versionRow)
        body.addView(actionRow("支持开发者", "给开发者买瓶冰可乐", ::showDeveloperSupportDialog))
        body.addView(actionRow("作者", "github.com/makabaka11") { openLink("https://github.com/makabaka11") })
        body.addView(actionRow("Telegram 频道", "t.me/bilisponsorskip") { openLink("https://t.me/bilisponsorskip") })
        body.addView(actionRow("联系", "ded000@retr0.xyz") { openLink("mailto:ded000@retr0.xyz") })

        val scroll = ScrollView(activity).apply {
            setBackgroundColor(colors.surface)
            addView(body)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("哔哩空降助手")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ -> save() }
            .create()
        dialog.setOnShowListener { styleDialogChrome(dialog) }
        dialog.show()
        loadStats()
        checkForUpdates()
    }

    private fun save() {
        val enteredUsername = usernameInput.text?.toString()?.trim().orEmpty()
        val username = enteredUsername.ifBlank {
            Toast.makeText(activity, "用户名不能为空，已保留原值", Toast.LENGTH_LONG).show()
            initial.username
        }
        val enteredUserId = userIdInput.text?.toString()?.trim().orEmpty()
        if (enteredUserId.isNotEmpty() && !Identity.isValid(enteredUserId)) {
            Toast.makeText(activity, "私有用户 ID 至少需要 32 位，已保留原值", Toast.LENGTH_LONG).show()
        }
        val userId = when {
            Identity.isValid(enteredUserId) -> enteredUserId
            Identity.isValid(initial.userId) -> initial.userId
            else -> Identity.generate()
        }
        val modes = SettingsContract.CATEGORIES.associateWith { category ->
            val spinner = requireNotNull(categorySpinners[category])
            CATEGORY_MODES[spinner.selectedItemPosition.coerceIn(CATEGORY_MODES.indices)]
        }
        val saved = initial.copy(
                enabled = switch(SettingsContract.KEY_ENABLED),
                notifyFound = switch(SettingsContract.KEY_NOTIFY_FOUND),
                notifySkipped = switch(SettingsContract.KEY_NOTIFY_SKIPPED),
                notifyFetchFailure = switch(SettingsContract.KEY_NOTIFY_FETCH_FAILURE),
                showTitleLabel = switch(SettingsContract.KEY_SHOW_TITLE_LABEL),
                showProgressMarkers = switch(SettingsContract.KEY_SHOW_PROGRESS_MARKERS),
                skipOnSeek = switch(SettingsContract.KEY_SKIP_ON_SEEK),
                minDurationSeconds = MINIMUM_DURATION_VALUES[
                    minimumDurationSpinner.selectedItemPosition.coerceIn(MINIMUM_DURATION_VALUES.indices)
                ],
                persistPlaybackSpeed = switch(SettingsContract.KEY_PERSIST_PLAYBACK_SPEED),
                showSubmissionButton = switch(SettingsContract.KEY_SHOW_SUBMISSION_BUTTON),
                username = username,
                userId = userId,
                categoryModes = modes,
        )
        settings.updateFromEmbeddedSettings(saved)
        if (username != initial.username) updateUsername(saved, initial.username)
        Toast.makeText(activity, "哔哩空降助手设置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun addSwitch(
        parent: LinearLayout,
        key: String,
        title: String,
        summary: String,
        checked: Boolean,
    ) {
        val view = Switch(activity).apply {
            text = titleAndSummary(title, summary)
            setTextColor(colors.primaryText)
            thumbTintList = switchThumbTintList()
            trackTintList = switchTrackTintList()
            isChecked = checked
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, activity.dp(8), 0, activity.dp(8))
            minHeight = activity.dp(64)
        }
        switches[key] = view
        parent.addView(view, LinearLayout.LayoutParams(-1, -2))
    }

    private fun switch(key: String) = requireNotNull(switches[key]).isChecked

    private fun spinner(values: Array<String>) = Spinner(activity).apply {
        adapter = EmbeddedSettingsSpinnerAdapter(activity, values, colors)
        setPopupBackgroundDrawable(android.graphics.drawable.ColorDrawable(colors.popupSurface))
        backgroundTintList = ColorStateList.valueOf(colors.secondaryText)
    }

    private fun spinnerRow(title: String, summary: String, spinner: Spinner) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, activity.dp(6), 0, activity.dp(6))
        addView(TextView(activity).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(colors.primaryText)
        })
        addView(info(summary))
        addView(spinner, LinearLayout.LayoutParams(-1, activity.dp(48)))
    }

    private fun textInput(hint: String, value: String, inputTypeValue: Int) =
        EditText(activity).apply {
            this.hint = hint
            setText(value)
            setTextColor(colors.primaryText)
            setHintTextColor(colors.secondaryText)
            backgroundTintList = fieldTintList()
            inputType = inputTypeValue
            isSingleLine = true
        }

    private fun fieldLabel(value: String) = TextView(activity).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(colors.primaryText)
        setPadding(0, activity.dp(10), 0, 0)
    }

    private fun titleAndSummary(title: String, summary: String): CharSequence {
        val value = "$title\n$summary"
        return SpannableString(value).apply {
            val start = title.length + 1
            setSpan(ForegroundColorSpan(colors.secondaryText), start, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.82f), start, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun actionRow(title: String, summary: String, onClick: () -> Unit) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, activity.dp(10), 0, activity.dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(TextView(activity).apply {
                text = "$title  ›"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(colors.primaryText)
            })
            addView(TextView(activity).apply {
                text = summary
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(colors.secondaryText)
                setPadding(0, activity.dp(3), 0, 0)
            })
        }

    private fun section(title: String) = TextView(activity).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(colors.accent)
        setPadding(0, activity.dp(18), 0, activity.dp(6))
    }

    private fun info(value: String) = TextView(activity).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(colors.secondaryText)
        setPadding(0, activity.dp(4), 0, activity.dp(4))
    }

    private fun updateUsername(saved: SettingsSnapshot, oldUsername: String) {
        if (!Identity.isValid(saved.userId)) {
            Toast.makeText(activity, "私有用户 ID 尚未就绪", Toast.LENGTH_LONG).show()
            return
        }
        Thread({
            val result = SponsorBlockClient().setUsername(saved.userId, saved.username)
            activity.runOnUiThread {
                if (result.successful) {
                    Toast.makeText(activity, "用户名已更新", Toast.LENGTH_SHORT).show()
                } else {
                    settings.updateFromEmbeddedSettings(saved.copy(username = oldUsername))
                    Toast.makeText(
                        activity,
                        "用户名更新失败：${result.message.replace('\n', ' ').take(100)}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }, "BiliSponsorSkip-embedded-username").apply { isDaemon = true }.start()
    }

    private fun loadStats() {
        settings.onLocalStatsSyncRequested?.invoke()
        val local = LocalSkipStatsStore(activity.application).currentSnapshot()
        localStatsText.text = if (local == null) {
            "您已跳过 0 片段（0 分钟）"
        } else {
            "您已跳过 ${local.count} 片段（${formatMinutes(local.savedMs / 60_000.0)} 分钟）"
        }

        val userId = userIdInput.text?.toString()?.trim().orEmpty()
        if (!Identity.isValid(userId)) {
            remoteStatsText.text = "私有用户 ID 尚未就绪，无法获取贡献数据"
            return
        }
        remoteStatsText.text = "正在加载您的贡献数据…"
        Thread({
            val result = SponsorBlockClient().getUserContributionStats(userId)
            activity.runOnUiThread {
                remoteStatsText.text = when (result) {
                    is SponsorBlockClient.UserStatsResult.Success ->
                        "您为大家节省了 ${result.stats.viewCount} 片段的数据" +
                            "（${formatMinutes(result.stats.minutesSaved)} 分钟的生命）"
                    is SponsorBlockClient.UserStatsResult.Failure ->
                        "贡献数据暂时无法获取（${result.message.take(80)}）"
                }
            }
        }, "BiliSponsorSkip-embedded-user-stats").apply { isDaemon = true }.start()
    }

    private fun checkForUpdates() {
        val installedVersion = moduleVersionName()
        Thread({
            val release = UpdateChecker().check(installedVersion)
            if (release?.updateAvailable != true) return@Thread
            activity.runOnUiThread {
                versionSummaryText.text = "$installedVersion · 有新版本 ${release.tagName}"
                versionSummaryText.setTypeface(versionSummaryText.typeface, Typeface.BOLD)
            }
        }, "BiliSponsorSkip-embedded-update-check").apply { isDaemon = true }.start()
    }

    private fun openLink(uri: String) {
        runCatching {
            val action = if (uri.startsWith("mailto:")) Intent.ACTION_SENDTO else Intent.ACTION_VIEW
            activity.startActivity(Intent(action, Uri.parse(uri)))
        }.onFailure {
            Toast.makeText(activity, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun moduleVersionName(): String {
        val embedded = settings.moduleApkPath?.let { path ->
            runCatching { activity.packageManager.getPackageArchiveInfo(path, 0)?.versionName }.getOrNull()
        }
        if (!embedded.isNullOrBlank()) return embedded
        val installed = runCatching {
            activity.packageManager.getPackageInfo(SettingsContract.MODULE_PACKAGE, 0).versionName
        }.getOrNull()
        return installed.orEmpty().ifBlank { "未知" }
    }

    private fun showDeveloperSupportDialog() {
        val bitmap = loadDonationBitmap()
        if (bitmap == null) {
            Toast.makeText(activity, "无法读取赞赏码图片", Toast.LENGTH_LONG).show()
            return
        }
        val image = ImageView(activity).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            maxHeight = (resources.displayMetrics.heightPixels * 0.65f).toInt()
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "开发者赞赏码"
        }
        val container = FrameLayout(activity).apply {
            setBackgroundColor(colors.surface)
            setPadding(activity.dp(16), 0, activity.dp(16), 0)
            addView(image, FrameLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("支持开发者")
            .setView(container)
            .setPositiveButton("关闭", null)
            .create()
        dialog.setOnShowListener { styleDialogChrome(dialog) }
        dialog.show()
    }

    private fun loadDonationBitmap() = runCatching {
        val apkPath = settings.moduleApkPath ?: return@runCatching null
        val packageInfo = activity.packageManager.getPackageArchiveInfo(apkPath, 0)
            ?: return@runCatching null
        val applicationInfo = packageInfo.applicationInfo ?: return@runCatching null
        applicationInfo.sourceDir = apkPath
        applicationInfo.publicSourceDir = apkPath
        val resources = activity.packageManager.getResourcesForApplication(applicationInfo)
        val resourceId = resources.getIdentifier(
            "developer_donation_qr",
            "drawable",
            packageInfo.packageName,
        )
        resourceId.takeIf { it != 0 }?.let { BitmapFactory.decodeResource(resources, it) }
    }.getOrNull() ?: runCatching {
        val moduleContext = activity.createPackageContext(
            SettingsContract.MODULE_PACKAGE,
            Context.CONTEXT_IGNORE_SECURITY,
        )
        val resourceId = moduleContext.resources.getIdentifier(
            "developer_donation_qr",
            "drawable",
            SettingsContract.MODULE_PACKAGE,
        )
        resourceId.takeIf { it != 0 }?.let { BitmapFactory.decodeResource(moduleContext.resources, it) }
    }.getOrNull()

    private fun formatMinutes(value: Double): String =
        String.format(Locale.US, "%.2f", value.coerceAtLeast(0.0)).trimEnd('0').trimEnd('.')

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

    private fun styleDialogChrome(dialog: AlertDialog) {
        listOf("parentPanel", "topPanel", "contentPanel", "customPanel", "buttonPanel").forEach { name ->
            val id = activity.resources.getIdentifier(name, "id", "android")
            if (id != 0) dialog.findViewById<View>(id)?.setBackgroundColor(colors.surface)
        }
        val titleId = activity.resources.getIdentifier("alertTitle", "id", "android")
        if (titleId != 0) dialog.findViewById<TextView>(titleId)?.setTextColor(colors.primaryText)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(colors.accent)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(colors.accent)
    }

    private fun fieldTintList() = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
        intArrayOf(colors.accent, colors.secondaryText),
    )

    private fun switchThumbTintList() = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked)),
        intArrayOf(colors.accent, colors.switchThumbUnchecked),
    )

    private fun switchTrackTintList() = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked)),
        intArrayOf(colors.switchTrackChecked, colors.switchTrackUnchecked),
    )

    private companion object {
        val MINIMUM_DURATION_LABELS = arrayOf("不限制", "1 秒", "2 秒", "5 秒", "10 秒")
        val MINIMUM_DURATION_VALUES = intArrayOf(0, 1, 2, 5, 10)
        val CATEGORY_MODE_LABELS = arrayOf("禁用", "在进度条中显示", "手动跳过", "自动跳过")
        val CATEGORY_MODES = arrayOf(
            CategoryMode.DISABLED,
            CategoryMode.SHOW_OVERLAY,
            CategoryMode.MANUAL_SKIP,
            CategoryMode.AUTO_SKIP,
        )
    }
}

private class EmbeddedSettingsSpinnerAdapter(
    private val activity: Activity,
    values: Array<String>,
    private val colors: EmbeddedSettingsColors,
) : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, values) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        label(getItem(position).orEmpty(), dropdown = false)

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
        label(getItem(position).orEmpty(), dropdown = true)

    private fun label(value: String, dropdown: Boolean) = TextView(activity).apply {
        text = if (dropdown) value else "$value  ▾"
        gravity = Gravity.CENTER_VERTICAL
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(colors.primaryText)
        setBackgroundColor(if (dropdown) colors.popupSurface else Color.TRANSPARENT)
        setPadding(activity.dp(12), 0, activity.dp(12), 0)
        minHeight = activity.dp(48)
    }
}

private fun Activity.dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

private fun isDarkDialogTheme(context: Context): Boolean {
    val value = TypedValue()
    if (context.theme.resolveAttribute(android.R.attr.colorBackground, value, true)) {
        return isDarkColor(value.data)
    }
    return context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
}
