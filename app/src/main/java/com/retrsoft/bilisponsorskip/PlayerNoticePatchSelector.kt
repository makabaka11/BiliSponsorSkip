package com.retrsoft.bilisponsorskip

import java.lang.reflect.Method

internal data class PlayerNoticeServiceBinding(
    val patchId: String,
    val serviceInterface: Class<*>,
    val showMethod: Method,
    val dismissMethod: Method,
)

/**
 * Chooses a compatibility patch from the methods that are actually present at runtime.
 *
 * Bilibili reuses short obfuscated class names between versions, so a class-name hit is only a
 * candidate and never an ordering signal. Every patch must match both action method signatures;
 * zero matches is unsupported and multiple matches is ambiguous rather than silently preferring
 * whichever patch happened to be listed first.
 */
internal object PlayerNoticePatchSelector {
    private data class SymptomPatch(
        val id: String,
        val showMethodName: String,
        val dismissMethodName: String,
    )

    private val symptomPatches = listOf(
        SymptomPatch("showToast-dismissToast", "showToast", "dismissToast"),
        SymptomPatch("t2-r", "t2", "r"),
        SymptomPatch("D1-q", "D1", "q"),
        SymptomPatch("i2-D0", "i2", "D0"),
    )

    fun select(
        playerToastClass: Class<*>,
        serviceCandidates: Iterable<Class<*>>,
        context: String,
    ): PlayerNoticeServiceBinding {
        val candidates = serviceCandidates.distinctBy(Class<*>::getName)
        val matches = candidates.flatMap { candidate ->
            symptomPatches.mapNotNull { patch ->
                val showMethod = candidate.playerToastMethodOrNull(patch.showMethodName, playerToastClass)
                    ?: return@mapNotNull null
                val dismissMethod = candidate.playerToastMethodOrNull(patch.dismissMethodName, playerToastClass)
                    ?: return@mapNotNull null
                PlayerNoticeServiceBinding(
                    patchId = patch.id,
                    serviceInterface = candidate,
                    showMethod = showMethod,
                    dismissMethod = dismissMethod,
                )
            }
        }
        return when (matches.size) {
            1 -> matches.single()
            0 -> error(
                "no player notice symptom patch matched for $context; candidates=" +
                    candidates.joinToString { it.name },
            )
            else -> error(
                "ambiguous player notice symptom patches for $context: " +
                    matches.joinToString { "${it.serviceInterface.name}[${it.patchId}]" },
            )
        }
    }

    private fun Class<*>.playerToastMethodOrNull(name: String, playerToastClass: Class<*>): Method? =
        methods.singleOrNull { method ->
            method.name == name &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(playerToastClass))
        }
}
