package com.jimtime.wear.presentation

import com.jimtime.wear.data.CombatMeta
import com.jimtime.wear.data.GuidedStepMirror
import com.jimtime.wear.health.HR_FRESH_MS
import com.jimtime.wear.health.hrUpdateToSend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FX-WATCH-WEAR — hrUpdate fresco/off-body, fallback locale delle fasi, stato finito, meta dal mirror. */
class CombatFallbackTest {

    private val meta = CombatMeta("boxing", rounds = 5, workSec = 180, restSec = 60)

    private fun step(
        phase: String, round: Int, endsAt: Long?, finished: Boolean = false, paused: Boolean = false,
        remaining: String = "", total: Int = 5,
    ) = GuidedStepMirror(
        index = 1, total = 10, label = "", remaining = remaining, target = "", next = null,
        endsAt = endsAt, finished = finished, receivedAtMs = 0L, kind = "combat", phase = phase,
        roundIndex = round, roundTotal = total, paused = paused,
    )

    // ── hrUpdate ─────────────────────────────────────────────────────────
    @Test
    fun `nessun hrUpdate in offBody`() {
        assertNull(hrUpdateToSend(120.0, sampleAtMs = 9_000L, nowMs = 10_000L, offBody = true))
    }

    @Test
    fun `nessun bpm vecchio ri-inviato come fresco`() {
        assertEquals(120.0, hrUpdateToSend(120.0, 10_000L, 10_000L + HR_FRESH_MS, false)!!, 0.0)
        assertNull(hrUpdateToSend(120.0, 10_000L, 10_000L + HR_FRESH_MS + 1, false))
        assertNull(hrUpdateToSend(120.0, 0L, 10_000L, false)) // mai campionato
        assertNull(hrUpdateToSend(0.0, 9_999L, 10_000L, false))
    }

    // ── fallback locale ──────────────────────────────────────────────────
    @Test
    fun `mirror valido prevale, conto da endsAt`() {
        val v = resolveCombatView(step("work", 2, endsAt = 100_000L), meta, nowMs = 40_000L)
        assertEquals("work", v.phase); assertEquals(2, v.roundIndex); assertEquals(60, v.remainingSec)
        assertFalse(v.finished)
    }

    @Test
    fun `mirror scaduto avanza work - rest - work, mai senza tempo`() {
        // work r2 finito a t=0; riposo 60 s → t=60k; round 3 (180 s) → t=240k. now=100k → 140 s.
        val v = resolveCombatView(step("work", 2, endsAt = 0L), meta, nowMs = 100_000L)
        assertEquals("work", v.phase); assertEquals(3, v.roundIndex); assertEquals(140, v.remainingSec)
        // dentro il riposo
        val r = resolveCombatView(step("work", 2, endsAt = 0L), meta, nowMs = 20_000L)
        assertEquals("rest", r.phase); assertEquals(2, r.roundIndex); assertEquals(40, r.remainingSec)
    }

    @Test
    fun `prep passa a round 1 e ultimo round porta a finito`() {
        val p = resolveCombatView(step("prep", 0, endsAt = 0L), meta, nowMs = 10_000L)
        assertEquals("work", p.phase); assertEquals(1, p.roundIndex); assertEquals(170, p.remainingSec)
        val f = resolveCombatView(step("work", 5, endsAt = 0L), meta, nowMs = 1_000L)
        assertTrue(f.finished)
    }

    @Test
    fun `durate ignote fermano a 0 e non a null, pausa non avanza`() {
        val unknown = CombatMeta("boxing", 5, 0, 0)
        val v = resolveCombatView(step("work", 2, endsAt = 0L), unknown, nowMs = 100_000L)
        assertEquals(0, v.remainingSec); assertEquals(2, v.roundIndex)
        val p = resolveCombatView(step("work", 2, endsAt = 0L, paused = true, remaining = "1:05"), meta, 100_000L)
        assertEquals(65, p.remainingSec); assertEquals(2, p.roundIndex)
    }

    // ── stato finito ─────────────────────────────────────────────────────
    @Test
    fun `finished dal telefono e schermata Fine`() {
        val v = resolveCombatView(step("rest", 5, endsAt = null, finished = true), meta, nowMs = 0L)
        assertTrue(v.finished); assertNull(v.remainingSec)
    }

    // ── CombatMeta dal mirror ────────────────────────────────────────────
    @Test
    fun `CombatMeta minimo dal mirror combat`() {
        val m = CombatMeta.fromMirror(step("work", 1, 1L, total = 6), "muay_thai")
        assertNotNull(m)
        assertEquals("muay_thai", m!!.discipline); assertEquals(6, m.rounds)
        assertEquals(0, m.workSec); assertEquals(0, m.restSec)
        assertNull(CombatMeta.fromMirror(step("work", 1, 1L, total = 0), "boxing"))
    }

    // ── M2 FX2: scheda ⇒ mai «FINE» dedotto in locale ────────────────────
    @Test
    fun `scheda non deduce mai FINE, solo il mirror finished`() {
        val plan = CombatMeta("boxing", rounds = 7, workSec = 180, restSec = 60, plan = true)
        // Mirror scaduto da ore, fase work all'ultimo round: con una seduta libera
        // sarebbe FINE; con plan resta in fase, a 0, non finita.
        val stale = step("work", 7, endsAt = 0L, total = 7)
        val v = resolveCombatView(stale, plan, nowMs = 10_000_000L)
        assertFalse(v.finished)
        assertEquals("work", v.phase)
        assertEquals(0, v.remainingSec)
        // Controprova: la stessa situazione senza plan deriva FINE.
        assertTrue(resolveCombatView(stale, plan.copy(plan = false), nowMs = 10_000_000L).finished)
        // Solo il mirror lo dice.
        assertTrue(resolveCombatView(step("work", 7, endsAt = null, finished = true, total = 7), plan, 10_000_000L).finished)
    }
}
