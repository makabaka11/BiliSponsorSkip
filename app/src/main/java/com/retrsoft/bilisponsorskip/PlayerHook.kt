package com.retrsoft.bilisponsorskip

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class PlayerHook(
    private val apkPath: String,
    private val classLoader: ClassLoader,
    private val controller: SkipController,
) {
    private data class Resolution(
        val seekMethod: Method,
        val positionMethods: List<Method>,
        val durationMethods: List<Method>,
        val stateMethods: List<Method>,
    )

    private data class PollTarget(
        val player: WeakReference<Any>,
        val positionMethod: Method,
        val durationMethod: Method?,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollTarget = AtomicReference<PollTarget?>()
    private val pollStarted = AtomicBoolean(false)
    private val firstPositionLogged = AtomicBoolean(false)

    fun install() {
        System.loadLibrary("dexkit")
        DexKitBridge.create(apkPath).use { bridge ->
            val resolution = resolvePlayerMethods(bridge)
            val seekMethod = resolution.seekMethod
            val positionMethods = resolution.positionMethods
            val durationMethods = resolution.durationMethods
            val stateMethods = resolution.stateMethods
            val playerClass = seekMethod.declaringClass

            positionMethods.forEach { positionMethod ->
                XposedBridge.hookMethod(positionMethod, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val position = (param.result as? Number)?.toInt() ?: return
                        bindAndPoll(param.thisObject, seekMethod, positionMethod, durationMethods.firstOrNull())
                        if (firstPositionLogged.compareAndSet(false, true)) {
                            Log.d("first player position received: $position ms")
                        }
                        controller.onPosition(position)
                    }
                })
            }

            stateMethods.forEach { stateMethod ->
                XposedBridge.hookMethod(stateMethod, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        bindAndPoll(
                            param.thisObject,
                            seekMethod,
                            positionMethods.first(),
                            durationMethods.firstOrNull(),
                        )
                    }
                })
            }

            if (!playerClass.isInterface && !Modifier.isAbstract(playerClass.modifiers)) {
                XposedBridge.hookAllConstructors(playerClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        bindAndPoll(
                            param.thisObject,
                            seekMethod,
                            positionMethods.first(),
                            durationMethods.firstOrNull(),
                        )
                    }
                })
            }
            Log.d(
                "player resolved: ${playerClass.name}; seek=${seekMethod.name}${seekMethod.parameterTypes.contentToString()}; " +
                    "position=${positionMethods.joinToString { "${it.declaringClass.simpleName}.${it.name}" }}; " +
                    "duration=${durationMethods.joinToString { "${it.declaringClass.simpleName}.${it.name}" }.ifEmpty { "none" }}; " +
                    "state=${stateMethods.joinToString { it.name }.ifEmpty { "none" }}",
            )
            controller.onPlayerHookInstalled(
                "${playerClass.name}; seek=${seekMethod.name}; state=" +
                    stateMethods.joinToString { it.name }.ifEmpty { "none" },
            )
        }
    }

    private fun resolvePlayerMethods(bridge: DexKitBridge): Resolution {
        val logMethods = bridge.findMethod {
            matcher {
                returnType = "void"
                usingStrings("[player]seek to")
            }
        }

        val candidates = LinkedHashMap<String, MethodData>()
        logMethods.filter(::isCompatibleSeek).forEach { logMethod ->
            // Newer Bilibili versions put the log in an internal doSeek(int, boolean)
            // method. BiliRoaming follows its same-class invokes to the public
            // player-core seekTo(int); preserve the log method as the final fallback.
            logMethod.invokes
                .filter { it.className == logMethod.className && isCompatibleSeek(it) }
                .forEach { candidates[it.descriptor] = it }
            candidates[logMethod.descriptor] = logMethod
        }

        val diagnostics = mutableListOf<String>()
        val resolutions = candidates.values.mapNotNull { data ->
            val method = runCatching { data.getMethodInstance(classLoader) }
                .onFailure { diagnostics += "${data.descriptor}: ${it.javaClass.simpleName}" }
                .getOrNull() ?: return@mapNotNull null
            if (Modifier.isStatic(method.modifiers)) return@mapNotNull null
            val positions = findPositionMethods(method.declaringClass)
            if (positions.isEmpty()) {
                diagnostics += "${data.descriptor}: no concrete getCurrentPosition()"
                null
            } else {
                Resolution(
                    method,
                    positions,
                    findDurationMethods(method.declaringClass),
                    findStateMethods(bridge, method.declaringClass),
                )
            }
        }

        return resolutions.minWithOrNull(
            compareBy<Resolution> { it.seekMethod.parameterCount }
                .thenByDescending { Modifier.isPublic(it.seekMethod.modifiers) },
        ) ?: error(
            "player methods not found; log=${logMethods.joinToString { it.descriptor }}; " +
                "checked=${diagnostics.joinToString()}",
        )
    }

    private fun isCompatibleSeek(method: MethodData): Boolean =
        method.returnTypeName == "void" &&
            (method.paramTypeNames == listOf("int") || method.paramTypeNames == listOf("int", "boolean"))

    private fun findPositionMethods(playerClass: Class<*>): List<Method> {
        return findTimeMethods(playerClass, "getCurrentPosition")
    }

    private fun findDurationMethods(playerClass: Class<*>): List<Method> =
        findTimeMethods(playerClass, "getDuration")

    private fun findTimeMethods(playerClass: Class<*>, name: String): List<Method> {
        val result = LinkedHashSet<Method>()
        var current: Class<*>? = playerClass
        while (current != null && current != Any::class.java) {
            current.declaredMethods.filterTo(result) { method ->
                method.name == name &&
                    method.parameterCount == 0 &&
                    !Modifier.isAbstract(method.modifiers) &&
                    (method.returnType == Int::class.javaPrimitiveType ||
                        method.returnType == Long::class.javaPrimitiveType)
            }
            current = current.superclass
        }
        return result.toList()
    }

    private fun findStateMethods(bridge: DexKitBridge, playerClass: Class<*>): List<Method> =
        bridge.findMethod {
            matcher {
                declaredClass = playerClass.name
                returnType = "void"
                paramTypes = listOf("int")
                usingStrings("state change, target state")
            }
        }.mapNotNull { data ->
            runCatching { data.getMethodInstance(classLoader) }.getOrNull()
        }

    private fun bindAndPoll(
        player: Any,
        seekMethod: Method,
        positionMethod: Method,
        durationMethod: Method?,
    ) {
        controller.bindPlayer(player, seekMethod)
        pollTarget.set(PollTarget(WeakReference(player), positionMethod, durationMethod))
        if (pollStarted.compareAndSet(false, true)) mainHandler.post(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            val target = pollTarget.get()
            val player = target?.player?.get()
            if (target != null && player != null) {
                runCatching {
                    val duration = (target.durationMethod?.invokeUnwrapped(player) as? Number)?.toInt()
                    if (duration != null) controller.updateDuration(duration)
                    val position = (target.positionMethod.invokeUnwrapped(player) as? Number)?.toInt()
                    if (position != null) controller.onPosition(position)
                }.onFailure { error ->
                    controller.reportPlayerFailure(
                        stage = "播放进度",
                        error = error,
                        detail = "${target.positionMethod.declaringClass.name}.${target.positionMethod.name}()",
                    )
                    pollTarget.compareAndSet(target, null)
                }
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 500L
    }
}
