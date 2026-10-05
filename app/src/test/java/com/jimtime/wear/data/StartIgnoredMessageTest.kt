package com.jimtime.wear.data

import com.jimtime.wear.guided.GuidedStartPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/** F7 (J17) — risposta del Wear a un `startSession{wristAuthoritative}` scartato. */
class StartIgnoredMessageTest {

    @Test
    fun `planActivityStartIgnored - chiavi del contratto J17`() {
        for (reason in listOf(
            GuidedStartPolicy.REASON_LIVE_SESSION,
            GuidedStartPolicy.REASON_REPLAY,
            GuidedStartPolicy.REASON_STALE,
        )) {
            val m = WristGuidedStart.startIgnoredMessage(1_759_300_000_000L, reason)
            assertEquals("planActivityStartIgnored", m.getString("cmd"))
            assertEquals("wear", m.getString("platform"))
            assertEquals(1_759_300_000_000L, m.getLong("startedAt"))
            assertEquals(reason, m.getString("reason"))
            assertEquals(4, m.length())
        }
        assertEquals(listOf("liveSession", "replay", "stale"),
            listOf(GuidedStartPolicy.REASON_LIVE_SESSION, GuidedStartPolicy.REASON_REPLAY, GuidedStartPolicy.REASON_STALE))
    }
}
