package com.jimtime.wear.data

import androidx.health.services.client.data.DataTypeAvailability
import com.jimtime.wear.health.WristEvent
import com.jimtime.wear.health.wristEventFor
import com.jimtime.wear.presentation.combatRemainingSec
import com.jimtime.wear.presentation.formatCombatClock
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP4 (Arti marziali M0, N9) — parser del mirror con e senza campi combat. */
class GuidedStepMirrorCombatTest {

    private val legacy = """{"cmd":"guidedStep","index":3,"total":14,"label":"Ripetuta 3/6",
        "remaining":"4:32","target":"3:55","next":"Recupero","endsAt":1760000000000,"finished":false}"""

    @Test
    fun `payload F1 senza campi combat resta retrocompatibile`() {
        val m = GuidedStepMirror.fromWire(JSONObject(legacy), 42L)
        assertEquals(3, m.index)
        assertEquals("Ripetuta 3/6", m.label)
        assertEquals(1760000000000L, m.endsAt)
        assertEquals(42L, m.receivedAtMs)
        assertFalse(m.isCombat)
        assertNull(m.kind); assertNull(m.phase); assertNull(m.roundType)
        assertEquals(0, m.roundIndex); assertEquals(0, m.roundTotal)
        assertFalse(m.paused)
    }

    @Test
    fun `payload combat legge fase, round, tipo e pausa`() {
        val m = GuidedStepMirror.fromWire(
            JSONObject("""{"index":4,"total":10,"label":"Round 2","remaining":"1:37",
                "endsAt":1760000097000,"finished":false,"kind":"combat","phase":"work",
                "roundIndex":2,"roundTotal":5,"roundType":"sparring","paused":true}"""),
            1L,
        )
        assertTrue(m.isCombat)
        assertEquals("work", m.phase)
        assertEquals(2, m.roundIndex)
        assertEquals(5, m.roundTotal)
        assertEquals("sparring", m.roundType)
        assertTrue(m.paused)
        assertEquals(1760000097000L, m.endsAt)
    }

    @Test
    fun `startSession combat presente o assente`() {
        val c = CombatMeta.fromWire(JSONObject("""{"discipline":"boxing","rounds":5,"workSec":180,"restSec":60}"""))!!
        assertEquals("boxing", c.discipline)
        assertEquals(5, c.rounds); assertEquals(180, c.workSec); assertEquals(60, c.restSec)
        assertNull(CombatMeta.fromWire(null))
        assertNull(CombatMeta.fromWire(JSONObject("""{"rounds":3}""")))
    }

    @Test
    fun `conto alla rovescia da endsAt, da remaining in pausa, ignoto se finito`() {
        val base = GuidedStepMirror.fromWire(JSONObject(legacy), 0L)
        assertEquals(10, combatRemainingSec(base.copy(endsAt = 11_000L), nowMs = 1_000L))
        assertEquals(1, combatRemainingSec(base.copy(endsAt = 1_001L), nowMs = 1_000L)) // per eccesso
        assertEquals(0, combatRemainingSec(base.copy(endsAt = 500L), nowMs = 1_000L))
        assertEquals(272, combatRemainingSec(base.copy(paused = true), nowMs = 1_000L)) // "4:32"
        assertNull(combatRemainingSec(base.copy(finished = true), nowMs = 0L))
        assertNull(combatRemainingSec(null, nowMs = 0L))
        assertEquals("1:37", formatCombatClock(97))
        assertEquals("--:--", formatCombatClock(null))
    }

    @Test
    fun `HEART_RATE off-body e available mappano su eventi polso`() {
        assertEquals(WristEvent.OFF_BODY, wristEventFor(true, DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY))
        assertEquals(WristEvent.BACK, wristEventFor(true, DataTypeAvailability.AVAILABLE))
        assertNull(wristEventFor(true, DataTypeAvailability.ACQUIRING))
        assertNull(wristEventFor(true, DataTypeAvailability.UNAVAILABLE))
        assertNull(wristEventFor(false, DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY))
    }
}

/** M2 — fasi `timed`/`reps`, `phaseSec`/`reps`, `plan`, e vecchi telefoni. */
class GuidedStepMirrorCombatPlanTest {
    @Test
    fun `timed legge phaseSec e fase`() {
        val m = GuidedStepMirror.fromWire(
            JSONObject("""{"index":1,"total":6,"label":"Riscaldamento","remaining":"12:00",
                "endsAt":1760000720000,"finished":false,"kind":"combat","phase":"timed",
                "phaseSec":720,"reps":0,"roundIndex":0,"roundTotal":7}"""), 1L)
        assertEquals("timed", m.phase); assertEquals(720, m.phaseSec); assertEquals(0, m.reps)
        assertEquals(0, m.roundIndex); assertEquals(7, m.roundTotal)
        val v = com.jimtime.wear.presentation.resolveCombatView(m.copy(endsAt = 5_000L), null, 1_000L)
        assertEquals("Riscaldamento", v.label); assertEquals(4, v.remainingSec)
        // scaduto: fermo a 0:00, niente fasi inventate
        assertEquals(0, com.jimtime.wear.presentation.resolveCombatView(m.copy(endsAt = 5_000L), null, 9_000L).remainingSec)
    }

    @Test
    fun `reps senza endsAt legge ripetizioni e non ha countdown`() {
        val m = GuidedStepMirror.fromWire(
            JSONObject("""{"index":2,"total":6,"label":"Tecnica · 20 rip.","remaining":"",
                "finished":false,"kind":"combat","phase":"reps","phaseSec":0,"reps":20,
                "roundIndex":0,"roundTotal":7}"""), 1L)
        assertEquals("reps", m.phase); assertEquals(20, m.reps); assertNull(m.endsAt)
        val v = com.jimtime.wear.presentation.resolveCombatView(m, null, 1_000L)
        assertEquals(20, v.reps); assertNull(v.remainingSec); assertFalse(v.finished)
        assertEquals("Tecnica · 20 rip.", v.label)
    }

    @Test
    fun `campi mancanti o negativi restano a zero`() {
        val m = GuidedStepMirror.fromWire(
            JSONObject("""{"index":1,"total":2,"label":"x","remaining":"","finished":false,
                "kind":"combat","phase":"work","roundIndex":1,"roundTotal":3,"phaseSec":-5}"""), 1L)
        assertEquals(0, m.phaseSec); assertEquals(0, m.reps)
    }

    @Test
    fun `startSession combat plan`() {
        val c = CombatMeta.fromWire(JSONObject("""{"discipline":"boxing","rounds":7,"workSec":0,"restSec":0,"plan":true}"""))!!
        assertTrue(c.plan); assertEquals(0, c.workSec)
        assertFalse(CombatMeta.fromWire(JSONObject("""{"discipline":"boxing","rounds":5,"workSec":180,"restSec":60}"""))!!.plan)
    }
}
