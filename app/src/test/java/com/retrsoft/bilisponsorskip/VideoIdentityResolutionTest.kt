package com.retrsoft.bilisponsorskip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoIdentityResolutionTest {
    @Test
    fun keepsValidBvidFromLegacyVideoDetail() {
        assertEquals(
            ResolvedVideoIdentity("BV17x411w7KC", "12345"),
            resolveVideoIdentity("  BV17x411w7KC  ", 170001L, 12345L),
        )
    }

    @Test
    fun convertsAidWhenLegacyPlayerSourceHasNoBvid() {
        assertEquals(
            ResolvedVideoIdentity("BV17x411w7KC", "67890"),
            resolveVideoIdentity(null, 170001L, 67890L),
        )
    }

    @Test
    fun fallsBackToAidWhenBvidIsInvalid() {
        assertEquals(
            ResolvedVideoIdentity("BV1Q541167Qg", "7"),
            resolveVideoIdentity("av455017605", 455017605L, 7L),
        )
    }

    @Test
    fun rejectsMissingOrInvalidIdentityParts() {
        assertNull(resolveVideoIdentity(null, null, 1L))
        assertNull(resolveVideoIdentity("BV17x411w7KC", 170001L, 0L))
        assertNull(resolveVideoIdentity(null, -1L, 1L))
    }
}
