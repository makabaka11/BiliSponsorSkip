package com.retrsoft.bilisponsorskip

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Proxy

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
        val standalone = Intent().setClassName(
            SettingsContract.MODULE_PACKAGE,
            "${SettingsContract.MODULE_PACKAGE}.SettingsActivity",
        ).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            activity.startActivity(standalone)
            Log.d("opened standalone module settings from Bili settings")
        } catch (_: ActivityNotFoundException) {
            EmbeddedSettingsDialog(activity, settings).show()
            Log.d("opened embedded module settings from Bili settings")
        } catch (_: SecurityException) {
            EmbeddedSettingsDialog(activity, settings).show()
            Log.d("standalone module settings unavailable; opened embedded settings")
        }
    }

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
    private val initial = settings.current
    private val switches = linkedMapOf<String, Switch>()
    private val categorySpinners = linkedMapOf<String, Spinner>()
    private lateinit var minimumDurationSpinner: Spinner
    private lateinit var userIdInput: EditText

    fun show() {
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(8), activity.dp(20), activity.dp(12))
        }
        body.addView(info("LSPatch 内嵌设置会保存在当前 B 站客户端中。若已独立安装模块，将优先打开完整设置页。"))
        body.addView(section("基本功能"))
        addSwitch(body, SettingsContract.KEY_ENABLED, "启用模块功能", initial.enabled)
        addSwitch(body, SettingsContract.KEY_NOTIFY_FOUND, "发现片段时提示", initial.notifyFound)
        addSwitch(body, SettingsContract.KEY_NOTIFY_SKIPPED, "执行跳过后提示", initial.notifySkipped)
        addSwitch(body, SettingsContract.KEY_NOTIFY_FETCH_FAILURE, "请求失败时提示", initial.notifyFetchFailure)
        addSwitch(body, SettingsContract.KEY_SHOW_TITLE_LABEL, "在标题前显示片段标签", initial.showTitleLabel)
        addSwitch(body, SettingsContract.KEY_SHOW_PROGRESS_MARKERS, "在进度条标记片段", initial.showProgressMarkers)
        addSwitch(body, SettingsContract.KEY_SKIP_ON_SEEK, "快进到片段中间时仍跳过", initial.skipOnSeek)
        minimumDurationSpinner = spinner(MINIMUM_DURATION_LABELS).apply {
            setSelection(MINIMUM_DURATION_VALUES.indexOf(initial.minDurationSeconds).coerceAtLeast(0))
        }
        body.addView(spinnerRow("最短片段时长", minimumDurationSpinner))

        body.addView(section("片段提交与投票"))
        addSwitch(
            body,
            SettingsContract.KEY_SHOW_SUBMISSION_BUTTON,
            "显示提交与投票按钮",
            initial.showSubmissionButton,
        )
        userIdInput = EditText(activity).apply {
            hint = "私有用户 ID（至少 32 位）"
            setText(initial.userId)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
        }
        body.addView(userIdInput, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = activity.dp(4)
            bottomMargin = activity.dp(8)
        })
        body.addView(info("留空时将在保存后自动生成新的私有用户 ID。"))

        body.addView(section("片段分类行为"))
        SettingsContract.CATEGORIES.forEach { category ->
            val spinner = spinner(CATEGORY_MODE_LABELS).apply {
                setSelection(CATEGORY_MODES.indexOf(initial.categoryMode(category)).coerceAtLeast(0))
            }
            categorySpinners[category] = spinner
            body.addView(spinnerRow(category.categoryLabel(), spinner))
        }

        val scroll = ScrollView(activity).apply { addView(body) }
        AlertDialog.Builder(activity)
            .setTitle("哔哩空降助手")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ -> save() }
            .show()
    }

    private fun save() {
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
        settings.updateFromEmbeddedSettings(
            initial.copy(
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
                showSubmissionButton = switch(SettingsContract.KEY_SHOW_SUBMISSION_BUTTON),
                userId = userId,
                categoryModes = modes,
            ),
        )
        Toast.makeText(activity, "哔哩空降助手设置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun addSwitch(parent: LinearLayout, key: String, title: String, checked: Boolean) {
        val view = Switch(activity).apply {
            text = title
            isChecked = checked
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, activity.dp(5), 0, activity.dp(5))
        }
        switches[key] = view
        parent.addView(view, LinearLayout.LayoutParams(-1, activity.dp(48)))
    }

    private fun switch(key: String) = requireNotNull(switches[key]).isChecked

    private fun spinner(values: Array<String>) = Spinner(activity).apply {
        adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, values).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun spinnerRow(title: String, spinner: Spinner) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, activity.dp(6), 0, activity.dp(6))
        addView(TextView(activity).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        addView(spinner, LinearLayout.LayoutParams(-1, activity.dp(48)))
    }

    private fun section(title: String) = TextView(activity).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(resolveAccentColor(activity))
        setPadding(0, activity.dp(18), 0, activity.dp(6))
    }

    private fun info(value: String) = TextView(activity).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(resolveSecondaryTextColor(activity))
        setPadding(0, activity.dp(4), 0, activity.dp(4))
    }

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

private fun Activity.dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

private fun resolveAccentColor(context: Context): Int {
    val value = TypedValue()
    return if (context.theme.resolveAttribute(android.R.attr.colorAccent, value, true)) value.data else Color.MAGENTA
}

private fun resolveSecondaryTextColor(context: Context): Int {
    val values = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
    return try {
        values.getColor(0, Color.GRAY)
    } finally {
        values.recycle()
    }
}
