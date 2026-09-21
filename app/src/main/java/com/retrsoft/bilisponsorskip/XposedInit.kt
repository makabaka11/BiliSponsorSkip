package com.retrsoft.bilisponsorskip

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

class XposedInit : IXposedHookLoadPackage, IXposedHookZygoteInit {
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        loadedModulePath = startupParam.modulePath
        Log.d("module loaded from ${startupParam.modulePath}")
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName !in TARGET_PACKAGES || lpparam.processName != lpparam.packageName) return

        val uiLifecycle = UiLifecycleRelay()
        installInstrumentationActivityHooks(uiLifecycle)
        val initialized = AtomicBoolean(false)
        val lifecycleCallbacksRegistered = AtomicBoolean(false)
        XposedHelpers.findAndHookMethod(
            Instrumentation::class.java,
            "callApplicationOnCreate",
            Application::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!initialized.compareAndSet(false, true)) return
                    initialize(lpparam, param.args[0] as Application, uiLifecycle)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!lifecycleCallbacksRegistered.compareAndSet(false, true)) return
                    registerActivityLifecycleCallbacks(param.args[0] as Application, uiLifecycle)
                }
            },
        )
    }

    private fun installInstrumentationActivityHooks(uiLifecycle: UiLifecycleRelay) {
        XposedHelpers.findAndHookMethod(
            Instrumentation::class.java,
            "callActivityOnResume",
            Activity::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    uiLifecycle.onActivityResumed(param.args[0] as Activity)
                }
            },
        )
        XposedHelpers.findAndHookMethod(
            Instrumentation::class.java,
            "callActivityOnPause",
            Activity::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    uiLifecycle.onActivityPaused(param.args[0] as Activity)
                }
            },
        )
        XposedHelpers.findAndHookMethod(
            Instrumentation::class.java,
            "callActivityOnDestroy",
            Activity::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    uiLifecycle.onActivityDestroyed(param.args[0] as Activity)
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
        val settings = SettingsRepository(application, loadedModulePath)
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
        installTargetActivityHooks(lpparam.classLoader, uiLifecycle)

        runCatching {
            BiliSettingsEntryInjector(settings, lpparam.classLoader).install()
        }.onFailure { Log.e("failed to install Bili settings entry hook", it) }

        runCatching {
            VideoIdentityHook(lpparam.classLoader, controller).install()
        }.onFailure { Log.e("failed to install video identity hooks", it) }

        Thread({
            runCatching { playerNotice.install() }
                .onFailure { Log.e("failed to install interactive player notice bridge", it) }
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
        }, "BiliSponsorSkip-dex").apply { isDaemon = true }.start()
    }

    private fun installTargetActivityHooks(
        classLoader: ClassLoader,
        uiLifecycle: UiLifecycleRelay,
    ) {
        TARGET_ACTIVITY_CLASSES.forEach { className ->
            val activityClass = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach
            if (!Activity::class.java.isAssignableFrom(activityClass)) return@forEach

            val installed = mutableListOf<String>()
            listOf("onResume", "onPause", "onDestroy").forEach { methodName ->
                val method = findLifecycleMethod(activityClass, methodName) ?: return@forEach
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        when (methodName) {
                            "onResume" -> uiLifecycle.onActivityResumed(activity)
                            "onPause" -> uiLifecycle.onActivityPaused(activity)
                            "onDestroy" -> uiLifecycle.onActivityDestroyed(activity)
                        }
                    }
                })
                installed += "$methodName@${method.declaringClass.name}"
            }
            Log.d("target activity lifecycle hooks installed: $className (${installed.joinToString()})")
        }
    }

    private fun findLifecycleMethod(activityClass: Class<*>, name: String): Method? {
        var current: Class<*>? = activityClass
        while (current != null && Activity::class.java.isAssignableFrom(current)) {
            current.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun registerActivityLifecycleCallbacks(
        application: Application,
        uiLifecycle: UiLifecycleRelay,
    ) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = uiLifecycle.onActivityResumed(activity)

            override fun onActivityPaused(activity: Activity) = uiLifecycle.onActivityPaused(activity)

            override fun onActivityDestroyed(activity: Activity) = uiLifecycle.onActivityDestroyed(activity)

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
        Log.d("activity lifecycle callbacks registered")
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
        @Volatile
        var loadedModulePath: String? = null

        val TARGET_ACTIVITY_CLASSES = listOf(
            "com.bilibili.video.videodetail.VideoDetailsActivity",
        )

        val TARGET_PACKAGES = setOf(
            "tv.danmaku.bili",
            "com.bilibili.app.blue",
            "com.bilibili.app.in",
            "tv.danmaku.bilibilihd",
        )
    }
}
