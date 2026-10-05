package com.jimtime.wear.presentation

import com.jimtime.wear.guided.ResolvedEndType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F7 (Schede multisport: al polso, WP-WU, §2.8/J15) — copre le funzioni
 * pure di [GuidedSessionScreen.kt], puro come [PendingSyncScreenTest]:
 * nessun Context/Compose, solo Kotlin JVM.
 */
class GuidedSessionScreenTest {

    // ── markerFraction — geometria J15 (banda 25 %–75 %, marcatore) ─────

    @Test
    fun `non-inverted value at low bound maps to 0_25, at high bound to 0_75`() {
        assertEquals(0.25f, markerFraction(value = 240.0, low = 240.0, high = 260.0, inverted = false), 0.001f)
        assertEquals(0.75f, markerFraction(value = 260.0, low = 240.0, high = 260.0, inverted = false), 0.001f)
    }

    @Test
    fun `non-inverted value halfway between low and high maps to the band center (0_5)`() {
        assertEquals(0.5f, markerFraction(value = 250.0, low = 240.0, high = 260.0, inverted = false), 0.001f)
    }

    @Test
    fun `non-inverted values outside the band stay visible, clamped at the two gauge extremes`() {
        // Molto oltre high: clamp a t=1.5 -> 0.25+1.5*0.5=1.0 (estremo destro del gauge).
        assertEquals(1.0f, markerFraction(value = 1000.0, low = 240.0, high = 260.0, inverted = false), 0.001f)
        // Molto sotto low: clamp a t=-0.5 -> 0.25-0.5*0.5=0.0 (estremo sinistro).
        assertEquals(0.0f, markerFraction(value = -1000.0, low = 240.0, high = 260.0, inverted = false), 0.001f)
    }

    @Test
    fun `degenerate band (low == high) falls back to the dead center, inverted or not`() {
        assertEquals(0.5f, markerFraction(value = 250.0, low = 250.0, high = 250.0, inverted = false), 0.001f)
        assertEquals(0.5f, markerFraction(value = 250.0, low = 250.0, high = 250.0, inverted = true), 0.001f)
    }

    // ── J15 — passo (pace) INVERTITO: più veloce (sec/km più basso) va a destra ──

    @Test
    fun `inverted (pace) — the faster bound (low) maps to the right side of the band (0_75)`() {
        // low = veloce (sec/km più basso), high = lento: t=(high-value)/(high-low).
        assertEquals(0.75f, markerFraction(value = 235.0, low = 235.0, high = 245.0, inverted = true), 0.001f)
    }

    @Test
    fun `inverted (pace) — the slower bound (high) maps to the left side of the band (0_25)`() {
        assertEquals(0.25f, markerFraction(value = 245.0, low = 235.0, high = 245.0, inverted = true), 0.001f)
    }

    @Test
    fun `inverted (pace) — a pace faster than the whole band still clamps to the right extreme`() {
        // Molto più veloce di low (value << low): t=(high-value)/(high-low) >> 1 -> clamp 1.5 -> 1.0.
        assertEquals(1.0f, markerFraction(value = 100.0, low = 235.0, high = 245.0, inverted = true), 0.001f)
    }

    @Test
    fun `inverted (pace) — a pace slower than the whole band still clamps to the left extreme`() {
        assertEquals(0.0f, markerFraction(value = 1000.0, low = 235.0, high = 245.0, inverted = true), 0.001f)
    }

    @Test
    fun `inverted true vs false disagree for the same pace sample (sanity check against regressing to the old Swift bug)`() {
        val value = 235.0
        val low = 235.0
        val high = 245.0
        val plain = markerFraction(value, low, high, inverted = false)
        val inverted = markerFraction(value, low, high, inverted = true)
        assertEquals(0.25f, plain, 0.001f)
        assertEquals(0.75f, inverted, 0.001f)
    }

    // ── remainingText — port di GuidedStepView.remainingValue (Swift) ──

    @Test
    fun `time-based remaining formats as mm colon ss`() {
        assertEquals("4:32", remainingText(remainingSec = 272.0, remainingM = null, endType = ResolvedEndType.TIME))
        assertEquals("0:05", remainingText(remainingSec = 5.0, remainingM = null, endType = ResolvedEndType.TIME))
    }

    @Test
    fun `distance-based remaining formats as N m`() {
        assertEquals("420 m", remainingText(remainingSec = null, remainingM = 420.0, endType = ResolvedEndType.DISTANCE))
    }

    @Test
    fun `lap and hrBelow end types are always a dash in this pure helper`() {
        assertEquals("—", remainingText(remainingSec = 30.0, remainingM = null, endType = ResolvedEndType.LAP))
        assertEquals("—", remainingText(remainingSec = null, remainingM = null, endType = ResolvedEndType.HR_BELOW))
    }

    @Test
    fun `no remaining value at all falls back to a dash`() {
        assertEquals("—", remainingText(remainingSec = null, remainingM = null, endType = ResolvedEndType.TIME))
    }

    @Test
    fun `negative remaining is clamped to zero, never a negative duration`() {
        assertEquals("0:00", remainingText(remainingSec = -3.0, remainingM = null, endType = ResolvedEndType.TIME))
    }
}
