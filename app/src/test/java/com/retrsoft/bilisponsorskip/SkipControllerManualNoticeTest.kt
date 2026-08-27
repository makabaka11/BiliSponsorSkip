package com.retrsoft.bilisponsorskip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipControllerManualNoticeTest {
    @Test
    fun naturalEntryShowsNoticeWhenSeekIntoSegmentIsDisabled() {
        assertTrue(
            shouldPresentManualSkipNotice(
                skipOnSeek = false,
                positionMs = 10_500,
                segmentStartMs = 10_000,
                noticeAlreadyActive = false,
            ),
        )
    }

    @Test
    fun activeNoticeRemainsForTheWholeSegment() {
        assertTrue(
            shouldPresentManualSkipNotice(
                skipOnSeek = false,
                positionMs = 25_000,
                segmentStartMs = 10_000,
                noticeAlreadyActive = true,
            ),
        )
    }

    @Test
    fun firstDetectionAfterSeekingIntoMiddleRemainsSuppressed() {
        assertFalse(
            shouldPresentManualSkipNotice(
                skipOnSeek = false,
                positionMs = 25_000,
                segmentStartMs = 10_000,
                noticeAlreadyActive = false,
            ),
        )
    }

    @Test
    fun seekIntoSegmentSettingAllowsLateFirstDetection() {
        assertTrue(
            shouldPresentManualSkipNotice(
                skipOnSeek = true,
                positionMs = 25_000,
                segmentStartMs = 10_000,
                noticeAlreadyActive = false,
            ),
        )
    }
}
