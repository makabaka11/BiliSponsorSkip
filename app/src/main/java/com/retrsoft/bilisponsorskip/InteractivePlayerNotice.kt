package com.retrsoft.bilisponsorskip

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference

internal interface InteractivePlayerNotice {
    fun showAction(
        message: String,
        actionText: String,
        durationMs: Long,
        onAction: () -> Unit,
        onDismiss: () -> Unit,
    ): Boolean

    fun dismiss()
}

internal class BiliPlayerNoticeBridge(
    private val apkPath: String,
    private val packageName: String,
    private val classLoader: ClassLoader,
    private val ensureDexKitLoaded: () -> Unit,
) : InteractivePlayerNotice {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceRef = AtomicReference<WeakReference<Any>?>()
    private val activeToastRef = AtomicReference<Any?>()

    private lateinit var playerToastClass: Class<*>
    private lateinit var serviceInterface: Class<*>
    private lateinit var showMethod: Method
    private lateinit var dismissMethod: Method

    fun install() {
        playerToastClass = Class.forName(PLAYER_TOAST_CLASS, false, classLoader)
        serviceInterface = resolveServiceInterface()
        showMethod = resolveToastMethod(show = true)
        dismissMethod = resolveToastMethod(show = false)
        ensureDexKitLoaded()
        DexKitBridge.create(apkPath).use { bridge ->
            if (!Process.is64Bit()) {
                bridge.setThreadNum(2)
                bridge.setMaxConcurrentQueries(1)
            }
            installServiceCaptureHooks(bridge)
        }
        Log.d(
            "interactive player notice bridge installed: service=${serviceInterface.name}; " +
                "show=${showMethod.name}; dismiss=${dismissMethod.name}",
        )
    }

    override fun showAction(
        message: String,
        actionText: String,
        durationMs: Long,
        onAction: () -> Unit,
        onDismiss: () -> Unit,
    ): Boolean {
        val service = serviceRef.get()?.get() ?: return false
        mainHandler.post {
            runCatching {
                activeToastRef.getAndSet(null)?.let { dismissMethod.invokeUnwrapped(service, it) }
                val toast = createActionToast(message, actionText, durationMs, onAction, onDismiss)
                activeToastRef.set(toast)
                showMethod.invokeUnwrapped(service, toast)
            }.onFailure {
                activeToastRef.set(null)
                Log.e("failed to show interactive player notice", it)
            }
        }
        return true
    }

    override fun dismiss() {
        val service = serviceRef.get()?.get() ?: return
        val toast = activeToastRef.getAndSet(null) ?: return
        mainHandler.post {
            runCatching { dismissMethod.invokeUnwrapped(service, toast) }
                .onFailure { Log.e("failed to dismiss interactive player notice", it) }
        }
    }

    private fun resolveServiceInterface(): Class<*> {
        if (packageName != INTERNATIONAL_PACKAGE) {
            runCatching {
                return Class.forName(PINK_TOAST_SERVICE_INTERFACE, false, classLoader)
            }
        }
        val implementation = Class.forName(WHITE_TOAST_SERVICE_IMPLEMENTATION, false, classLoader)
        return implementation.interfaces.firstOrNull { candidate ->
            candidate.methods.count { method ->
                method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(arrayOf(playerToastClass))
            } >= 2
        } ?: error("white player toast service interface not found")
    }

    private fun resolveToastMethod(show: Boolean): Method {
        val preferredName = when {
            packageName == INTERNATIONAL_PACKAGE && show -> WHITE_SHOW_METHOD
            packageName == INTERNATIONAL_PACKAGE -> WHITE_DISMISS_METHOD
            show -> "showToast"
            else -> "dismissToast"
        }
        return serviceInterface.methods.firstOrNull { method ->
            method.name == preferredName && method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(playerToastClass))
        } ?: error("player toast ${if (show) "show" else "dismiss"} method not found")
    }

    private fun installServiceCaptureHooks(bridge: DexKitBridge) {
        val hookedMethods = LinkedHashSet<String>()
        val getterMethods = bridge.findMethod {
            matcher {
                returnType = serviceInterface.name
                paramTypes = emptyList()
            }
        }
        getterMethods.forEach { data ->
            val method = runCatching { data.getMethodInstance(classLoader) }.getOrNull() ?: return@forEach
            if (Modifier.isStatic(method.modifiers) || Modifier.isAbstract(method.modifiers)) return@forEach
            if (!hookedMethods.add(method.toGenericString())) return@forEach
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    captureService(param.result)
                }
            })
        }

        val concreteShowMethods = bridge.findMethod {
            matcher {
                name = showMethod.name
                returnType = "void"
                paramTypes = listOf(PLAYER_TOAST_CLASS)
            }
        }
        val hookedClasses = LinkedHashSet<Class<*>>()
        concreteShowMethods.forEach { data ->
            val method = runCatching { data.getMethodInstance(classLoader) }.getOrNull() ?: return@forEach
            val declaringClass = method.declaringClass
            if (!declaringClass.isInterface && !Modifier.isAbstract(declaringClass.modifiers) && hookedClasses.add(declaringClass)) {
                XposedBridge.hookAllConstructors(declaringClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        captureService(param.thisObject)
                    }
                })
            }
            if (Modifier.isAbstract(method.modifiers) || !hookedMethods.add(method.toGenericString())) return@forEach
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    captureService(param.thisObject)
                }
            })
        }
        Log.d(
            "interactive notice capture hooks: getters=${getterMethods.size}; " +
                "showMethods=${concreteShowMethods.size}; classes=${hookedClasses.size}",
        )
    }

    private fun captureService(candidate: Any?) {
        if (candidate == null || !serviceInterface.isInstance(candidate)) return
        val previous = serviceRef.get()?.get()
        if (previous === candidate) return
        serviceRef.set(WeakReference(candidate))
        Log.d("interactive player notice service captured: ${candidate.javaClass.name}")
    }

    private fun createActionToast(
        message: String,
        actionText: String,
        durationMs: Long,
        onAction: () -> Unit,
        onDismiss: () -> Unit,
    ): Any {
        val constructor = playerToastClass.declaredConstructors.firstOrNull {
            it.parameterTypes.firstOrNull() == Bundle::class.java
        } ?: error("PlayerToast Bundle constructor not found")
        constructor.isAccessible = true
        val toast = when (constructor.parameterTypes.size) {
            1 -> constructor.newInstance(Bundle())
            2 -> constructor.newInstance(Bundle(), null)
            else -> error("unsupported PlayerToast constructor")
        }
        playerToastClass.getMethod("setLevel", Int::class.javaPrimitiveType).invokeUnwrapped(toast, 2)
        playerToastClass.getMethod("setQueueType", Int::class.javaPrimitiveType).invokeUnwrapped(toast, 48)
        playerToastClass.getMethod("setToastType", Int::class.javaPrimitiveType).invokeUnwrapped(toast, 18)
        playerToastClass.getMethod("setLocation", Int::class.javaPrimitiveType).invokeUnwrapped(toast, 32)
        playerToastClass.getMethod("setDuration", Long::class.javaPrimitiveType).invokeUnwrapped(
            toast,
            durationMs.coerceIn(1_000L, 100_000L),
        )
        playerToastClass.getMethod("setCreateTime", Long::class.javaPrimitiveType)
            .invokeUnwrapped(toast, System.currentTimeMillis())
        playerToastClass.getMethod("setExtraString", String::class.java, String::class.java)
            .invokeUnwrapped(toast, "extra_title", message)
        playerToastClass.getMethod("setExtraString", String::class.java, String::class.java)
            .invokeUnwrapped(toast, "extra_action_text", actionText)
        playerToastClass.getMethod("setExtraBoolean", String::class.java, Boolean::class.javaPrimitiveType)
            .invokeUnwrapped(toast, "extra_need_close", true)

        val listenerMethod = playerToastClass.methods.firstOrNull {
            it.name == "setClickListener" && it.parameterCount == 1 && it.parameterTypes[0].isInterface
        } ?: error("PlayerToast click listener setter not found")
        val listenerType = listenerMethod.parameterTypes[0]
        val listener = Proxy.newProxyInstance(classLoader, arrayOf(listenerType)) { proxy, method, args ->
            when {
                method.name == "toString" -> "BiliSponsorSkipPlayerToastListener"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.name == "onDismiss" -> {
                    activeToastRef.compareAndSet(toast, null)
                    onDismiss()
                    null
                }
                method.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType),
                ) -> {
                    activeToastRef.compareAndSet(toast, null)
                    onAction()
                    null
                }
                else -> null
            }
        }
        listenerMethod.invokeUnwrapped(toast, listener)
        return toast
    }

    private companion object {
        const val PLAYER_TOAST_CLASS = "tv.danmaku.biliplayerv2.widget.toast.PlayerToast"
        const val PINK_TOAST_SERVICE_INTERFACE = "tv.danmaku.biliplayerv2.service.IToastService"
        const val WHITE_TOAST_SERVICE_IMPLEMENTATION = "tv.danmaku.biliplayerimpl.toast.ToastService"
        const val INTERNATIONAL_PACKAGE = "com.bilibili.app.in"
        const val WHITE_SHOW_METHOD = "i2"
        const val WHITE_DISMISS_METHOD = "D0"
    }
}
