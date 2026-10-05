package com.jimtime.wear.guided

import org.junit.Assert.assertEquals
import org.junit.Test

/** F7 (WP-KE, §2.4) — replay, stale, sessione viva, avvio. */
class GuidedStartPolicyTest {

    private val now = 1_759_300_000_000L

    @Test
    fun `no live session and fresh command starts`() {
        assertEquals(GuidedStartPolicy.Decision.Start, GuidedStartPolicy.decide(now, now, null, true))
        assertEquals(GuidedStartPolicy.Decision.Start, GuidedStartPolicy.decide(now, now + 5_000, now - 60_000, true))
    }

    @Test
    fun `same or older startedAt than the last applied is a replay`() {
        assertEquals(
            GuidedStartPolicy.Decision.Ignore("replay"),
            GuidedStartPolicy.decide(now, now, now, true),
        )
        assertEquals(
            GuidedStartPolicy.Decision.Ignore("replay"),
            GuidedStartPolicy.decide(now - 1, now, now, true),
        )
    }

    @Test
    fun `replay wins over stale and live session`() {
        assertEquals(
            GuidedStartPolicy.Decision.Ignore("replay"),
            GuidedStartPolicy.decide(now - 3_600_000, now, now, false),
        )
    }

    @Test
    fun `older than ten minutes is stale, exactly ten minutes is not`() {
        assertEquals(
            GuidedStartPolicy.Decision.Ignore("stale"),
            GuidedStartPolicy.decide(now - GuidedStartPolicy.STALE_WINDOW_MS - 1, now, null, true),
        )
        assertEquals(
            GuidedStartPolicy.Decision.Start,
            GuidedStartPolicy.decide(now - GuidedStartPolicy.STALE_WINDOW_MS, now, null, true),
        )
    }

    @Test
    fun `stale wins over live session`() {
        assertEquals(
            GuidedStartPolicy.Decision.Ignore("stale"),
            GuidedStartPolicy.decide(now - 11 * 60_000L, now, null, false),
        )
    }

    @Test
    fun `a live session is never replaced`() {
        assertEquals(
            GuidedStartPolicy.Decision.Ignore("liveSession"),
            GuidedStartPolicy.decide(now, now, null, false),
        )
        assertEquals(
            GuidedStartPolicy.Decision.Ignore(GuidedStartPolicy.REASON_LIVE_SESSION),
            GuidedStartPolicy.decide(now, now + 1_000, now - 1, false),
        )
    }

    @Test
    fun `startedAt in the future is not stale`() {
        // Orologi telefono/polso non allineati: un comando «dal futuro» non è vecchio.
        assertEquals(GuidedStartPolicy.Decision.Start, GuidedStartPolicy.decide(now + 30_000, now, null, true))
    }
}
