package com.retrsoft.bilisponsorskip

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryModeTest {
    @Test
    fun defaultsMatchOriginalBrowserExtension() {
        assertEquals(CategoryMode.AUTO_SKIP, SettingsContract.defaultCategoryMode("sponsor"))
        assertEquals(CategoryMode.MANUAL_SKIP, SettingsContract.defaultCategoryMode("selfpromo"))
        assertEquals(CategoryMode.MANUAL_SKIP, SettingsContract.defaultCategoryMode("interaction"))
        assertEquals(CategoryMode.MANUAL_SKIP, SettingsContract.defaultCategoryMode("intro"))
        assertEquals(CategoryMode.MANUAL_SKIP, SettingsContract.defaultCategoryMode("outro"))
        assertEquals(CategoryMode.SHOW_OVERLAY, SettingsContract.defaultCategoryMode("preview"))
        assertEquals(CategoryMode.DISABLED, SettingsContract.defaultCategoryMode("filler"))
        assertEquals(CategoryMode.AUTO_SKIP, SettingsContract.defaultCategoryMode("padding"))
        assertEquals(CategoryMode.AUTO_SKIP, SettingsContract.defaultCategoryMode("music_offtopic"))
    }

    @Test
    fun invalidPersistedModeFallsBackToCategoryDefault() {
        val default = SettingsContract.defaultCategoryMode("preview")
        assertEquals(default, CategoryMode.fromPersisted("unexpected", default))
        assertEquals(CategoryMode.MANUAL_SKIP, CategoryMode.fromPersisted("manual_skip", default))
    }

    @Test
    fun everySupportedCategoryHasAnExplicitDefault() {
        assertEquals(SettingsContract.CATEGORIES.toSet(), SettingsContract.DEFAULT_CATEGORY_MODES.keys)
    }

    @Test
    fun unknownServerCategoryIsDisabled() {
        assertEquals(CategoryMode.DISABLED, SettingsSnapshot().categoryMode("unknown"))
    }

    @Test
    fun legacySwitchesPreserveTheirPreviousBehavior() {
        assertEquals(CategoryMode.DISABLED, SettingsContract.legacyCategoryMode(enabled = false, autoSkip = true))
        assertEquals(CategoryMode.AUTO_SKIP, SettingsContract.legacyCategoryMode(enabled = true, autoSkip = true))
        assertEquals(CategoryMode.MANUAL_SKIP, SettingsContract.legacyCategoryMode(enabled = true, autoSkip = false))
    }
}
