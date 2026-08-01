package com.retrsoft.bilisponsorskip

import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

internal fun Any.callOrNull(name: String, vararg args: Any?): Any? =
    runCatching { XposedHelpers.callMethod(this, name, *args) }.getOrNull()

internal fun Any.longOrNull(name: String): Long? = when (val value = callOrNull(name)) {
    is Number -> value.toLong()
    else -> null
}

internal fun Any.stringOrNull(name: String): String? = callOrNull(name) as? String

internal fun Method.invokeUnwrapped(receiver: Any, vararg args: Any?): Any? = try {
    isAccessible = true
    invoke(receiver, *args)
} catch (error: InvocationTargetException) {
    throw error.targetException
}
