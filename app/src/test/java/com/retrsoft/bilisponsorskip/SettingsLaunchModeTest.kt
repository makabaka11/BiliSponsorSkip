package com.retrsoft.bilisponsorskip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLaunchModeTest {
    @Test
    fun defaultsToEmbeddedUntilEveryStandaloneSignalIsConfirmed() {
        val confirmed = SettingsLaunchSignals(
            hostApkVerifiedNotLspatch = true,
            frameworkApiAvailable = true,
            standaloneModuleScopeConfirmed = true,
        )

        assertEquals(SettingsLaunchMode.STANDALONE, selectSettingsLaunchMode(confirmed))
        confirmed.copy(hostApkVerifiedNotLspatch = false).assertEmbedded()
        confirmed.copy(frameworkApiAvailable = false).assertEmbedded()
        confirmed.copy(standaloneModuleScopeConfirmed = false).assertEmbedded()
    }

    @Test
    fun standaloneScopeRequiresTheLoadedAndInstalledApkToBeTheSameFile() {
        assertTrue(sameApkPath("/data/app/module/base.apk", "/data/app/module/base.apk"))
        assertTrue(sameApkPath("/data/app/module/../module/base.apk", "/data/app/module/base.apk"))
        assertFalse(sameApkPath("/data/user/0/host/lspatch/module.apk", "/data/app/module/base.apk"))
        assertFalse(sameApkPath(null, "/data/app/module/base.apk"))
    }

    @Test
    fun recognizesLspatchHostApkMarkers() {
        assertTrue(containsLspatchMarker(sequenceOf("assets/lspatch/config.json")))
        assertTrue(containsLspatchMarker(sequenceOf("assets/lspatch/origin.apk")))
        assertTrue(containsLspatchMarker(sequenceOf("assets/lspatch/so/arm64-v8a/liblspatch.so")))
        assertFalse(containsLspatchMarker(sequenceOf("assets/dexopt/baseline.prof")))
    }

    private fun SettingsLaunchSignals.assertEmbedded() {
        assertEquals(SettingsLaunchMode.EMBEDDED, selectSettingsLaunchMode(this))
    }
}
