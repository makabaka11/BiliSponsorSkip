package com.retrsoft.bilisponsorskip

import android.app.Activity
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

        val uiLifecycle = UiLifecycleRelay()
        installActivityLifecycleHooks(uiLifecycle)
        val initialized = AtomicBoolean(false)
        XposedHelpers.findAndHookMethod(
            Instrumentation::class.java,
            "callApplicationOnCreate",
            Application::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!initialized.compareAndSet(false, true)) return
                    initialize(lpparam, param.args[0] as Application, uiLifecycle)
                }
            },
        )
    }

    private fun installActivityLifecycleHooks(uiLifecycle: UiLifecycleRelay) {
        XposedHelpers.findAndHookMethod(
            Activity::class.java,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    uiLifecycle.onActivityResumed(param.thisObject as Activity)
                }
            },
        )
        XposedHelpers.findAndHookMethod(
            Activity::class.java,
            "onPause",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    uiLifecycle.onActivityPaused(param.thisObject as Activity)
                }
            },
        )
        XposedHelpers.findAndHookMethod(
            Activity::class.java,
            "onDestroy",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    uiLifecycle.onActivityDestroyed(param.thisObject as Activity)
                }
            },
        )
    }

    private fun initialize(
        lpparam: LoadPackageParam,
        application: Application,
        uiLifecycle: UiLifecycleRelay,
    ) {
        Log.d("initializing for ${lpparam.packageName} (${lpparam.appInfo.sourceDir})")
        val settings = SettingsRepository(application)
        val controller = SkipController(
            settings = settings,
            localStatsStore = LocalSkipStatsStore(application),
        )
        val playerNotice = BiliPlayerNoticeBridge(
            apkPath = lpparam.appInfo.sourceDir,
            packageName = lpparam.packageName,
            classLoader = lpparam.classLoader,
            ensureDexKitLoaded = { DexKitNativeLoader.ensureLoaded(application) },
        )
        controller.bindPlayerNotice(playerNotice)
        settings.onLocalStatsSyncRequested = controller::syncLocalStats
        controller.syncLocalStats()
        val playerUi = PlayerUiInjector(application, controller).also(PlayerUiInjector::start)
        val submissionUi = SubmissionUiInjector(application, controller).also(SubmissionUiInjector::start)
        uiLifecycle.attach(playerUi, submissionUi)

        runCatching {
            BiliSettingsEntryInjector(settings, lpparam.classLoader).install()
        }.onFailure { Log.e("failed to install Bili settings entry hook", it) }

        runCatching {
            VideoIdentityHook(lpparam.classLoader, controller).install()
        }.onFailure { Log.e("failed to install video identity hooks", it) }

        Thread({
            runCatching {
                PlayerHook(
                    lpparam.appInfo.sourceDir,
                    lpparam.classLoader,
                    controller,
                    ensureDexKitLoaded = { DexKitNativeLoader.ensureLoaded(application) },
                ).install()
            }.onFailure {
                controller.reportPlayerFailure("Hook 安装", it)
            }
            runCatching { playerNotice.install() }
                .onFailure { Log.e("failed to install interactive player notice bridge", it) }
        }, "BiliSponsorSkip-dex").apply { isDaemon = true }.start()
    }

    private class UiLifecycleRelay {
        @Volatile
        private var playerUi: PlayerUiInjector? = null

        @Volatile
        private var submissionUi: SubmissionUiInjector? = null

        fun attach(playerUi: PlayerUiInjector, submissionUi: SubmissionUiInjector) {
            this.playerUi = playerUi
            this.submissionUi = submissionUi
        }

        fun onActivityResumed(activity: Activity) {
            playerUi?.onActivityResumed(activity)
            submissionUi?.onActivityResumed(activity)
        }

        fun onActivityPaused(activity: Activity) {
            playerUi?.onActivityPaused(activity)
            submissionUi?.onActivityPaused(activity)
        }

        fun onActivityDestroyed(activity: Activity) {
            playerUi?.onActivityDestroyed(activity)
            submissionUi?.onActivityDestroyed(activity)
        }
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
