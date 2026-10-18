package com.retrsoft.bilisponsorskip

import java.lang.reflect.Modifier

/** Match the observed controller/data contract rather than an obfuscated bind method name. */
internal fun isStoryControllerType(type: Class<*>): Boolean {
    val methods = type.methods.filter { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 }
    if (methods.none { it.name == "isActive" && it.returnType == Boolean::class.javaPrimitiveType }) return false
    return methods.filter { it.name == "getData" }.any { getter ->
        val dataMethods = getter.returnType.methods.filter {
            !Modifier.isStatic(it.modifiers) && it.parameterCount == 0
        }
        dataMethods.any { it.name == "getBvid" && it.returnType == String::class.java } &&
            listOf("getAid", "getCid").all { name ->
                dataMethods.any { it.name == name && it.returnType in listOf(
                    Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    java.lang.Long::class.java, java.lang.Integer::class.java,
                ) }
            }
    }
}
