package com.retrsoft.bilisponsorskip

import org.junit.Assert.assertEquals
import org.junit.Test

class SponsorBlockClientTest {
    private val client = SponsorBlockClient()

    @Test
    fun keepsCurrentCidSkippableSegmentsAndTheirCategories() {
        val result = client.parseSegments(
            json = """
                [
                  {
                    "videoID": "BV17x411w7KC",
                    "segments": [
                      {"segment":[10.5,20.25],"cid":"123","UUID":"keep","category":"sponsor","actionType":"skip"},
                      {"segment":[1,2],"cid":"999","UUID":"other-part","category":"sponsor","actionType":"skip"},
                      {"segment":[3,4],"cid":"123","UUID":"intro","category":"intro","actionType":"skip"},
                      {"segment":[5,6],"cid":"123","UUID":"full","category":"sponsor","actionType":"full"}
                    ]
                  }
                ]
            """.trimIndent(),
            bvid = "BV17x411w7KC",
            cid = "123",
        )

        assertEquals(2, result.size)
        assertEquals(3_000, result[0].startMs)
        assertEquals("intro", result[0].category)
        assertEquals(10_500, result[1].startMs)
        assertEquals(20_250, result[1].endMs)
        assertEquals("keep", result[1].uuid)
        assertEquals("sponsor", result[1].category)
    }
}
