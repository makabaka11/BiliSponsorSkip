package com.retrsoft.bilisponsorskip

import android.app.Application
import android.app.Instrumentation
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.concurrent.atomic.AtomicBoolean

class XposedInit : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName !in TARGET_PACKAGES || lpparam.processName != lpparam.packageName) return

        val initialized = AtomicBoolean(false)
        XposedHelpers.findAndHookMethod(
            Instrumentation::class.java,
            "callApplicationOnCreate",
            Application::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!initialized.compareAndSet(false, true)) return
                    initialize(lpparam)
                }
            },
        )
    }

    private fun initialize(lpparam: LoadPackageParam) {
        Log.d("initializing for ${lpparam.packageName} (${lpparam.appInfo.sourceDir})")
        val controller = SkipController(settings = SettingsRepository())

        runCatching {
            VideoIdentityHook(lpparam.classLoader, controller).install()
        }.onFailure { Log.e("failed to install video identity hooks", it) }

        Thread({
            runCatching {
                PlayerHook(lpparam.appInfo.sourceDir, lpparam.classLoader, controller).install()
            }.onFailure {
                controller.reportPlayerFailure("Hook 安装", it)
            }
        }, "BiliSponsorSkip-dex").apply { isDaemon = true }.start()
    }

    private companion object {
        val TARGET_PACKAGES = setOf(
            "tv.danmaku.bili",
            "com.bilibili.app.blue",
            "com.bilibili.app.in",
            "tv.danmaku.bilibilihd",
        )
    }
}
