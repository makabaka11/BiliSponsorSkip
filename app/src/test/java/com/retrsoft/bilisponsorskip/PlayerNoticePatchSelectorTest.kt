package com.retrsoft.bilisponsorskip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlayerNoticePatchSelectorTest {
    private class FakePlayerToast

    private interface White3182Service {
        fun D1(toast: FakePlayerToast)
        fun q(toast: FakePlayerToast)
    }

    private interface ReusedN0ClassNameShape {
        fun b(value: String): String
    }

    private interface White3204Service {
        fun i2(toast: FakePlayerToast)
        fun D0(toast: FakePlayerToast)
    }

    private interface ReadableService {
        fun showToast(toast: FakePlayerToast)
        fun dismissToast(toast: FakePlayerToast)
    }

    private interface T2RService {
        fun t2(toast: FakePlayerToast)
        fun r(toast: FakePlayerToast)
    }

    private interface AmbiguousService {
        fun D1(toast: FakePlayerToast)
        fun q(toast: FakePlayerToast)
        fun i2(toast: FakePlayerToast)
        fun D0(toast: FakePlayerToast)
    }

    @Test
    fun selects3182PatchFromItsObservedMethodPair() {
        val binding = select(listOf(White3182Service::class.java))

        assertEquals("D1-q", binding.patchId)
        assertSame(White3182Service::class.java, binding.serviceInterface)
        assertEquals("D1", binding.showMethod.name)
        assertEquals("q", binding.dismissMethod.name)
    }

    @Test
    fun selectsReadablePatchFromItsObservedMethodPair() {
        val binding = select(listOf(ReadableService::class.java))

        assertEquals("showToast-dismissToast", binding.patchId)
        assertSame(ReadableService::class.java, binding.serviceInterface)
    }

    @Test
    fun selectsT2RPatchFromItsObservedMethodPair() {
        val binding = select(listOf(T2RService::class.java))

        assertEquals("t2-r", binding.patchId)
        assertSame(T2RService::class.java, binding.serviceInterface)
    }

    @Test
    fun rejectsReusedN0ShapeAndSelects3204Patch() {
        val binding = select(
            listOf(
                ReusedN0ClassNameShape::class.java,
                White3204Service::class.java,
            ),
        )

        assertEquals("i2-D0", binding.patchId)
        assertSame(White3204Service::class.java, binding.serviceInterface)
        assertEquals("i2", binding.showMethod.name)
        assertEquals("D0", binding.dismissMethod.name)
    }

    @Test
    fun candidateOrderDoesNotChangeSelectedPatch() {
        val forward = select(
            listOf(ReusedN0ClassNameShape::class.java, White3204Service::class.java),
        )
        val reversed = select(
            listOf(White3204Service::class.java, ReusedN0ClassNameShape::class.java),
        )

        assertEquals(forward.patchId, reversed.patchId)
        assertSame(forward.serviceInterface, reversed.serviceInterface)
    }

    @Test(expected = IllegalStateException::class)
    fun ambiguousSymptomsFailInsteadOfUsingPatchOrder() {
        select(listOf(AmbiguousService::class.java))
    }

    @Test(expected = IllegalStateException::class)
    fun unsupportedSymptomsFailInsteadOfUsingCandidateOrder() {
        select(listOf(ReusedN0ClassNameShape::class.java))
    }

    private fun select(candidates: List<Class<*>>): PlayerNoticeServiceBinding =
        PlayerNoticePatchSelector.select(
            playerToastClass = FakePlayerToast::class.java,
            serviceCandidates = candidates,
            context = "test",
        )
}
