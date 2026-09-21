package com.retrsoft.bilisponsorskip

internal enum class SettingsLaunchMode {
    EMBEDDED,
    STANDALONE,
}

internal data class SettingsLaunchSignals(
    val hostApkVerifiedNotLspatch: Boolean,
    val frameworkApiAvailable: Boolean,
    val standaloneModuleScopeConfirmed: Boolean,
)

internal fun selectSettingsLaunchMode(signals: SettingsLaunchSignals): SettingsLaunchMode =
    if (
        signals.hostApkVerifiedNotLspatch &&
        signals.frameworkApiAvailable &&
        signals.standaloneModuleScopeConfirmed
    ) {
        SettingsLaunchMode.STANDALONE
    } else {
        SettingsLaunchMode.EMBEDDED
    }

internal fun containsLspatchMarker(entryNames: Sequence<String>): Boolean =
    entryNames.any { name ->
        name == "assets/lspatch/config.json" ||
            name == "assets/lspatch/origin.apk" ||
            name.startsWith("assets/lspatch/so/")
    }
