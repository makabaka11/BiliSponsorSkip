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

    @Test
    fun parsesVotesAndBuildsCompatibleSubmissionPayload() {
        val parsed = client.parseSegments(
            """[{"videoID":"BV1test","segments":[{"segment":[1,2],"cid":"7","UUID":"u","category":"sponsor","actionType":"skip","votes":4}]}]""",
            "BV1test",
            "7",
        )
        assertEquals(4, parsed.single().votes)

        val body = client.createSubmitPayload(
            bvid = "BV1test",
            cid = "7",
            userId = "a".repeat(36),
            category = "intro",
            startMs = 1_234,
            endMs = 5_678,
            durationMs = 60_000,
        )
        assertEquals("BV1test", body.getString("videoID"))
        assertEquals("7", body.getString("cid"))
        assertEquals(60.0, body.getDouble("videoDuration"), 0.0)
        val segment = body.getJSONArray("segments").getJSONObject(0)
        assertEquals("intro", segment.getString("category"))
        assertEquals("skip", segment.getString("actionType"))
        assertEquals(1.234, segment.getJSONArray("segment").getDouble(0), 0.0)
        assertEquals(5.678, segment.getJSONArray("segment").getDouble(1), 0.0)
        assertEquals(36, segment.getString("UUID").length)
    }
}
