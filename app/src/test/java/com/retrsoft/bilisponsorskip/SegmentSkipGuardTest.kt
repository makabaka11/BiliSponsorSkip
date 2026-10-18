package com.retrsoft.bilisponsorskip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentSkipGuardTest {
    @Test fun ignoredOrClampedSeekDoesNotRepeatAfterCooldown() {
        val guard = SegmentSkipGuard()
        assertTrue(guard.tryAcquire("ad", 10_000, 20_000))
        listOf(10_000, 10_100, 15_000, 19_999, 19_999).forEach {
            guard.observe(it)
            assertFalse(guard.tryAcquire("ad", 10_000, 20_000))
        }
    }

    @Test fun manualRewindBeforeSegmentAllowsAnotherTraversal() {
        val guard = SegmentSkipGuard()
        assertTrue(guard.tryAcquire("ad", 10_000, 20_000))
        guard.observe(9_000)
        guard.observe(10_100)
        assertTrue(guard.tryAcquire("ad", 10_000, 20_000))
    }

    @Test fun completedSeekAndLaterReentryCanSkipAgain() {
        val guard = SegmentSkipGuard()
        assertTrue(guard.tryAcquire("ad", 10_000, 20_000))
        guard.observe(20_000)
        guard.observe(12_000)
        assertTrue(guard.tryAcquire("ad", 10_000, 20_000))
    }

    @Test fun changingVideoClearsAttemptsButOtherSegmentsAreIndependent() {
        val guard = SegmentSkipGuard()
        assertTrue(guard.tryAcquire("ad", 10_000, 20_000))
        assertTrue(guard.tryAcquire("other", 15_000, 25_000))
        guard.clear()
        assertTrue(guard.tryAcquire("ad", 10_000, 20_000))
    }
}
