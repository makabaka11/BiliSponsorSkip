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

    @Test
    fun longPressSpeedPlaybackCountsAsContinuousSegmentEntry() {
        val enteredContinuously = segmentEnteredContinuously(
            previousPositionMs = 9_000,
            currentPositionMs = 12_500,
            segmentStartMs = 10_000,
            elapsedMs = 1_200,
        )

        assertTrue(enteredContinuously)
        assertTrue(
            shouldAutoSkipSegment(
                skipOnSeek = false,
                positionMs = 12_500,
                segmentStartMs = 10_000,
                enteredContinuously = enteredContinuously,
            ),
        )
        assertTrue(
            shouldPresentManualSkipNotice(
                skipOnSeek = false,
                positionMs = 12_500,
                segmentStartMs = 10_000,
                noticeAlreadyActive = false,
                enteredContinuously = enteredContinuously,
            ),
        )
    }

    @Test
    fun realSeekIntoMiddleRemainsSuppressed() {
        val enteredContinuously = segmentEnteredContinuously(
            previousPositionMs = 8_000,
            currentPositionMs = 25_000,
            segmentStartMs = 10_000,
            elapsedMs = 800,
        )

        assertFalse(enteredContinuously)
        assertFalse(
            shouldAutoSkipSegment(
                skipOnSeek = false,
                positionMs = 25_000,
                segmentStartMs = 10_000,
                enteredContinuously = enteredContinuously,
            ),
        )
    }

    @Test
    fun stalePlaybackSampleDoesNotHideASeek() {
        assertFalse(
            segmentEnteredContinuously(
                previousPositionMs = 9_000,
                currentPositionMs = 12_500,
                segmentStartMs = 10_000,
                elapsedMs = 6_000,
            ),
        )
    }
}
