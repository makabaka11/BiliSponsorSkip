package com.retrsoft.bilisponsorskip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSpeedPolicyTest {
    @Test
    fun persistenceIsOptInAndIncludedInSettingsSynchronization() {
        assertFalse(SettingsSnapshot().persistPlaybackSpeed)
        assertTrue(SettingsContract.KEY_PERSIST_PLAYBACK_SPEED in SettingsContract.BOOLEAN_SETTING_KEYS)
        assertEquals(
            1,
            SettingsContract.BOOLEAN_SETTING_KEYS.count {
                it == SettingsContract.KEY_PERSIST_PLAYBACK_SPEED
            },
        )
    }

    @Test
    fun acceptsSupportedFinitePlaybackSpeeds() {
        assertEquals(0.25f, PlaybackSpeedPolicy.persistedValue(0.25f))
        assertEquals(1.0f, PlaybackSpeedPolicy.persistedValue(1.0f))
        assertEquals(3.0f, PlaybackSpeedPolicy.persistedValue(3.0f))
        assertEquals(4.0f, PlaybackSpeedPolicy.persistedValue(4.0f))
    }

    @Test
    fun rejectsInvalidOrImplausiblePlaybackSpeeds() {
        assertNull(PlaybackSpeedPolicy.persistedValue(0.0f))
        assertNull(PlaybackSpeedPolicy.persistedValue(4.01f))
        assertNull(PlaybackSpeedPolicy.persistedValue(Float.NaN))
        assertNull(PlaybackSpeedPolicy.persistedValue(Float.POSITIVE_INFINITY))
    }
}
