package com.retrsoft.bilisponsorskip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryIdentityContractTest {
    class Detail {
        fun getBvid(): String = "BV1SYht6GEs7"
        fun getAid(): Long = 1L
        fun getCid(): Long = 2L
    }
    interface Controller {
        fun isActive(): Boolean
        fun getData(): Detail
    }
    interface IncompleteController {
        fun isActive(): Boolean
        fun getData(): String
    }
    interface PreloadData {
        fun getData(): Detail
    }

    @Test fun acceptsObservedStoryControllerContract() {
        assertTrue(isStoryControllerType(Controller::class.java))
    }
    @Test fun rejectsUnrelatedSingleArgumentBindTargets() {
        assertFalse(isStoryControllerType(IncompleteController::class.java))
        assertFalse(isStoryControllerType(PreloadData::class.java))
        assertFalse(isStoryControllerType(Int::class.javaPrimitiveType!!))
    }
}
