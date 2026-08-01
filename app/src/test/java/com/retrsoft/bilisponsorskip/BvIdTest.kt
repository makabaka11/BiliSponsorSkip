package com.retrsoft.bilisponsorskip

import org.junit.Assert.assertEquals
import org.junit.Test

class BvIdTest {
    @Test
    fun convertsKnownAid() {
        assertEquals("BV17x411w7KC", BvId.fromAid(170001))
        assertEquals("BV1Q541167Qg", BvId.fromAid(455017605))
    }
}
