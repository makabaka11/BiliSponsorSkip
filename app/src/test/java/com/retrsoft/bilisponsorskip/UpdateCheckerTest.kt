package com.retrsoft.bilisponsorskip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun detectsNewerSemanticReleaseTag() {
        assertTrue(isVersionNewer("v1.4.0", "1.3.3"))
        assertTrue(isVersionNewer("v2.0.0", "1.99.99"))
    }

    @Test
    fun doesNotReportSameOrOlderRelease() {
        assertFalse(isVersionNewer("v1.3.3", "1.3.3"))
        assertFalse(isVersionNewer("v1.3.2", "1.3.3"))
    }

    @Test
    fun malformedVersionsDoNotCreateFalseUpdatePrompts() {
        assertFalse(isVersionNewer("latest", "1.3.3"))
        assertFalse(isVersionNewer("v1.4.0", "debug"))
    }
}
