package com.retrsoft.bilisponsorskip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedSettingsColorsTest {
    @Test
    fun lightPaletteKeepsEveryTextRoleReadable() {
        val colors = embeddedSettingsColors(dark = false)

        assertFalse(isDarkColor(colors.surface))
        assertTrue(contrastRatio(colors.primaryText, colors.surface) >= 4.5)
        assertTrue(contrastRatio(colors.secondaryText, colors.surface) >= 4.5)
        assertTrue(contrastRatio(colors.accent, colors.surface) >= 4.5)
        assertTrue(contrastRatio(colors.primaryText, colors.popupSurface) >= 4.5)
    }

    @Test
    fun darkPaletteKeepsEveryTextRoleReadable() {
        val colors = embeddedSettingsColors(dark = true)

        assertTrue(isDarkColor(colors.surface))
        assertTrue(contrastRatio(colors.primaryText, colors.surface) >= 4.5)
        assertTrue(contrastRatio(colors.secondaryText, colors.surface) >= 4.5)
        assertTrue(contrastRatio(colors.accent, colors.surface) >= 4.5)
        assertTrue(contrastRatio(colors.primaryText, colors.popupSurface) >= 4.5)
    }
}
