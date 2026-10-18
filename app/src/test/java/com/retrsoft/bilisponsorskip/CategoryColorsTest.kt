package com.retrsoft.bilisponsorskip

import org.junit.Assert.*
import org.junit.Test

class CategoryColorsTest {
    @Test fun normalizesValidRgbAndRejectsMalformedOrTransparentValues() {
        assertEquals("#AABBCC", CategoryColors.normalize(" aabbcc "))
        assertEquals("#00D400", CategoryColors.normalize("#00d400"))
        listOf(null, "", "#123", "#AAFFFFFF", "red", "#ZZ0000").forEach {
            assertNull(CategoryColors.normalize(it))
        }
    }

    @Test fun defaultsAndResetRetainOriginalTrackAndLabelColors() {
        assertEquals(0xFF00D400.toInt(), CategoryColors.resolve("sponsor"))
        assertEquals(0xFF007800.toInt(), CategoryColors.resolve("sponsor", preview = true))
        assertEquals(0xFFBFBF35.toInt(), CategoryColors.resolve("selfpromo", preview = true))
        assertEquals(CategoryColors.resolve("sponsor"), CategoryColors.resolve("sponsor", ""))
        assertEquals(CategoryColors.resolve("sponsor"), CategoryColors.resolve("sponsor", "invalid"))
    }

    @Test fun categoryOverridesAreIndependentAndTitleEligibilityIsUnchanged() {
        val snapshot = SettingsSnapshot(categoryColors = mapOf("sponsor" to "#448AFF", "interaction" to "#FF5252"))
        assertEquals(0xFF448AFF.toInt(), snapshot.categoryColor("sponsor"))
        assertEquals(0xFFFF5252.toInt(), snapshot.categoryColor("interaction"))
        assertEquals(CategoryColors.resolve("intro"), snapshot.categoryColor("intro"))
        assertNotEquals(snapshot.categoryColor("sponsor"), snapshot.categoryColor("sponsor", preview = true))
        assertEquals(0xFF000000.toInt(), CategoryColors.resolve("sponsor", "#000000"))
        assertEquals("#000000", CategoryColors.hex(0xFF000000.toInt()))
    }

    @Test fun textAlwaysChoosesAnAccessibleBlackOrWhiteForeground() {
        listOf("#FFFF00", "#00D400", "#FFFFFF", "#000000", "#448AFF", "#CC00FF").forEach {
            val background = CategoryColors.resolve("sponsor", it, preview = true)
            assertTrue(contrastRatio(CategoryColors.foreground(background), background) >= 4.5)
        }
    }
}
