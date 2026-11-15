package com.retrsoft.bilisponsorskip

import java.lang.reflect.Modifier
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

private val storyTitleBindingFields = ConcurrentHashMap<Class<*>, List<Field>>()

/** Read the title's own binding, never the identity of a neighbouring preloaded item. */
internal fun resolveStoryTitleIdentity(
    widget: Any,
    requireActive: Boolean,
    onUnsupported: (String) -> Unit = {},
): ResolvedVideoIdentity? {
    val fields = storyTitleBindingFields.getOrPut(widget.javaClass) {
        generateSequence(widget.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && hasStoryDataContract(it.type) }
            .toList()
    }
    // Ambiguous bindings must not inherit the current video's badge.
    if (fields.size != 1) {
        onUnsupported("controller fields=${fields.size}")
        return null
    }
    return runCatching {
        val field = fields.single().apply { isAccessible = true }
        val binding = field.get(widget) ?: return null
        if (requireActive && field.type.getMethod("isActive").invoke(binding) != true) return null
        val getter = field.type.getMethod("getData")
        val data = getter.invoke(binding) ?: return null
        val type = getter.returnType
        resolveVideoIdentity(
            type.getMethod("getBvid").invoke(data) as? String,
            (type.getMethod("getAid").invoke(data) as? Number)?.toLong(),
            (type.getMethod("getCid").invoke(data) as? Number)?.toLong(),
        )
    }.onFailure { onUnsupported("controller read failed: ${it.javaClass.simpleName}") }.getOrNull()
}

internal fun hasStoryDataContract(type: Class<*>): Boolean = type.methods.any { getter ->
    getter.name == "getData" && !Modifier.isStatic(getter.modifiers) && getter.parameterCount == 0 &&
        getter.returnType.methods.let { methods ->
            val dataMethods = methods.filter { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 }
            dataMethods.any { it.name == "getBvid" && it.returnType == String::class.java } &&
                listOf("getAid", "getCid").all { name ->
                    dataMethods.any {
                        it.name == name && it.returnType in listOf(
                            Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                            java.lang.Long::class.java, java.lang.Integer::class.java,
                        )
                    }
                }
        }
}
