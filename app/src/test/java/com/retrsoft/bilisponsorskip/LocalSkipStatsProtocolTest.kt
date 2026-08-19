package com.retrsoft.bilisponsorskip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LocalSkipStatsProtocolTest {
    @Test
    fun catchesUpFromLatestCumulativeSnapshot() {
        val delta = LocalSkipStatsProtocol.delta(
            previousCount = 2L,
            previousSavedMs = 10_000L,
            snapshot = snapshot(count = 5L, savedMs = 42_000L),
        )

        assertEquals(3L, delta.count)
        assertEquals(32_000L, delta.savedMs)
    }

    @Test
    fun duplicateAndOlderSnapshotsDoNotAddAgain() {
        val duplicate = LocalSkipStatsProtocol.delta(5L, 42_000L, snapshot(5L, 42_000L))
        val older = LocalSkipStatsProtocol.delta(5L, 42_000L, snapshot(3L, 20_000L))

        assertEquals(LocalSkipStatsDelta(0L, 0L), duplicate)
        assertEquals(LocalSkipStatsDelta(0L, 0L), older)
    }

    @Test
    fun aNewGenerationUsesIndependentMergeState() {
        val first = snapshot(1L, 2_000L, generation = "a".repeat(36))
        val second = snapshot(1L, 2_000L, generation = "b".repeat(36))

        assertNotEquals(
            LocalSkipStatsProtocol.sourceKey(first),
            LocalSkipStatsProtocol.sourceKey(second),
        )
    }

    private fun snapshot(
        count: Long,
        savedMs: Long,
        generation: String = "g".repeat(36),
    ) = LocalSkipStatsSnapshot(
        packageName = "com.bilibili.app.in",
        processName = "com.bilibili.app.in",
        generation = generation,
        count = count,
        savedMs = savedMs,
    )
}
