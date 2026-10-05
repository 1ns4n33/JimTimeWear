package com.jimtime.wear.guided

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F7 (WP-WD, §2.9) — il repository della corsa guidata al polso con una
 * 6×800 sintetica: eventi del motore, segmenti (`finish`, `extra` ≥ 30 s),
 * record `stepSegments{source:'watch', variant?, plannedStepCount}`,
 * snapshot/restore senza `StepStart` ripetuto, `lap` via `nextStep()`.
 */
class GuidedRunRepositoryTest {

    private val structureJson = """
        { "v": 1, "steps": [
          { "id": "st_wu", "role": "warmup", "end": { "type": "time", "value": 600 },
            "target": { "kind": "pace", "low": 288, "high": 324, "zoneLabel": "easy" },
            "ui": { "label": "Riscaldamento", "spoken": "Riscaldamento", "target": "4:48–5:24 /km" } },
          { "id": "st_rep", "repeat": 6, "steps": [
            { "id": "st_w", "role": "work", "end": { "type": "distance", "value": 800 },
              "target": { "kind": "pace", "low": 235, "high": 245, "zoneLabel": "5k" },
              "ui": { "label": "Ripetuta {i}/{n}", "spoken": "Ripetuta {i} di {n}", "target": "3:55–4:05 /km" } },
            { "id": "st_r", "role": "recovery", "end": { "type": "time", "value": 120 },
              "target": { "kind": "none", "low": 0, "high": 0 },
              "ui": { "label": "Recupero {i}/{n}", "spoken": "Recupero", "target": "" } } ] },
          { "id": "st_cd", "role": "cooldown", "end": { "type": "lap", "value": 0 },
            "target": { "kind": "hr", "low": 114, "high": 133, "zoneLabel": "2" },
            "ui": { "label": "Defaticamento", "spoken": "Defaticamento", "target": "114–133 bpm" } } ] }
    """.trimIndent()

    private val planRef = JSONObject(
        """{ "planId": "ckx1", "weekNumber": 1, "dayIndex": 1, "dayLabel": "Giovedì – Ripetute", "activityId": "pa_1" }""",
    )
    private val profile = JSONObject("""{ "pace5kSecPerKm": 240, "hrMax": 190, "hrRest": 55 }""")

    /** Simulatore: 1 campione al secondo, velocità per ruolo della fase corrente. */
    private var activeSec = 0L
    private var distanceM = 0.0
    private val allEvents = mutableListOf<StepEvent>()

    private fun structure() = ResolvedStructure.fromWire(JSONObject(structureJson))!!

    private fun start(variant: String? = null, hasDistanceSignal: Boolean = true) {
        GuidedRunRepository.start(
            structure = structure(),
            planRef = planRef,
            lang = "it",
            title = "Ripetute 6×800",
            sport = "run",
            variant = variant,
            hasDistanceSignal = hasDistanceSignal,
            profile = profile,
        )
    }

    private fun speedFor(view: GuidedRunView?): Double = when {
        view == null || view.finished -> 2.5
        view.label.startsWith("Riscaldamento") -> 1000.0 / 300.0
        view.label.startsWith("Ripetuta") -> 1000.0 / 240.0
        view.label.startsWith("Recupero") -> 2.0
        else -> 2.5
    }

    private fun tickOnce(): List<StepEvent> {
        activeSec += 1
        distanceM += speedFor(GuidedRunRepository.current.value)
        val events = GuidedRunRepository.tick(
            nowMs = 1_759_300_000_000L + activeSec * 1000L,
            activeMs = activeSec * 1000L,
            distanceM = distanceM,
            hr = 150.0,
            paused = false,
        )
        allEvents += events
        return events
    }

    private fun tickUntil(maxSec: Int = 5000, cond: () -> Boolean) {
        var n = 0
        while (!cond()) {
            tickOnce()
            n++
            check(n < maxSec) { "condizione mai raggiunta" }
        }
    }

    @Before
    fun reset() {
        GuidedRunRepository.stop()
        activeSec = 0
        distanceM = 0.0
        allEvents.clear()
    }

    @Test
    fun `6x800 intera - eventi, segmenti, record round-trip`() {
        start()
        assertEquals(14, GuidedRunRepository.current.value!!.total)

        val first = tickOnce()
        assertEquals(StepEvent.StepStart(0), first.first())

        // Fino al defaticamento (index 13), poi «Fase ›».
        tickUntil { GuidedRunRepository.current.value!!.index == 13 }
        repeat(300) { tickOnce() }
        assertEquals("Defaticamento", GuidedRunRepository.current.value!!.label)
        assertEquals("fino al tasto", GuidedRunRepository.current.value!!.remainingSubtitle)
        GuidedRunRepository.nextStep()
        val lapEvents = tickOnce()
        val lapEnd = lapEvents.filterIsInstance<StepEvent.StepEnd>().single()
        assertEquals(StepEndedBy.LAP, lapEnd.endedBy)
        assertTrue(lapEvents.any { it is StepEvent.WorkoutEnd })
        assertTrue(GuidedRunRepository.current.value!!.finished)

        // Ogni fase è partita UNA volta (14 StepStart, nessun duplicato).
        val starts = allEvents.filterIsInstance<StepEvent.StepStart>().map { it.stepIndex }
        assertEquals((0 until 14).toList(), starts)

        // Corsa libera 45 s ⇒ segmento `extra`.
        repeat(45) { tickOnce() }
        val record = GuidedRunRepository.takeSegments(activeSec.toDouble(), distanceM)!!
        assertFalse(GuidedRunRepository.isActive)
        assertNull(GuidedRunRepository.current.value)

        assertEquals("watch", record.getString("source"))
        assertEquals(1, record.getInt("v"))
        assertEquals("pa_1", record.getString("planActivityId"))
        assertEquals(14, record.getInt("plannedStepCount"))
        assertEquals(240, record.getJSONObject("profile").getInt("pace5kSecPerKm"))
        assertFalse(record.has("variant"))

        val segs = record.getJSONArray("segments")
        assertEquals(15, segs.length())
        val wu = segs.getJSONObject(0)
        assertEquals("st_wu", wu.getString("stepId"))
        assertEquals("warmup", wu.getString("role"))
        assertEquals("auto", wu.getString("endedBy"))
        assertEquals(600.0, wu.getDouble("endActiveSec") - wu.getDouble("startActiveSec"), 1.0)
        assertEquals("time", wu.getJSONObject("plannedEnd").getString("type"))
        assertEquals(600.0, wu.getJSONObject("plannedEnd").getDouble("value"), 0.0)
        assertEquals(288.0, wu.getDouble("targetLow"), 0.0)

        val work1 = segs.getJSONObject(1)
        assertEquals("st_w#1", work1.getString("stepId"))
        assertEquals("work", work1.getString("role"))
        assertTrue(work1.getDouble("endDistanceM") - work1.getDouble("startDistanceM") >= 800.0)
        assertEquals("distance", work1.getJSONObject("plannedEnd").getString("type"))
        assertTrue(work1.has("avgPaceSecPerKm"))
        assertEquals(150.0, work1.getDouble("avgHr"), 0.001)

        val rec1 = segs.getJSONObject(2)
        assertEquals("recovery", rec1.getString("role"))
        assertEquals("none", rec1.getString("targetKind"))
        assertFalse(rec1.has("targetLow"))
        assertFalse(rec1.has("targetHigh"))

        val cd = segs.getJSONObject(13)
        assertEquals("cooldown", cd.getString("role"))
        assertEquals("lap", cd.getString("endedBy"))
        assertFalse(cd.getJSONObject("plannedEnd").has("value"))

        val extra = segs.getJSONObject(14)
        assertEquals("extra", extra.getString("stepId"))
        assertEquals("extra", extra.getString("role"))
        assertEquals("finish", extra.getString("endedBy"))
        assertEquals(14, extra.getInt("index"))
        assertTrue(extra.getDouble("endActiveSec") - extra.getDouble("startActiveSec") >= 30.0)

        // Round-trip del wire: ogni segmento si rilegge identico.
        for (i in 0 until segs.length()) {
            val raw = segs.getJSONObject(i)
            val parsed = ActivityStepSegmentWire.fromWire(raw)
            assertNotNull("segmento $i illeggibile", parsed)
            assertEquals(raw.toString(), parsed!!.toWire().toString())
        }
    }

    @Test
    fun `corsa libera sotto 30 s - nessun extra`() {
        start()
        tickUntil { GuidedRunRepository.current.value!!.index == 13 }
        GuidedRunRepository.nextStep()
        tickOnce()
        assertTrue(GuidedRunRepository.current.value!!.finished)
        repeat(20) { tickOnce() }
        val segs = GuidedRunRepository.takeSegments(activeSec.toDouble(), distanceM)!!.getJSONArray("segments")
        assertEquals(14, segs.length())
        assertEquals("cooldown", segs.getJSONObject(13).getString("role"))
    }

    @Test
    fun `stop a meta' - fase in corso chiusa finish, variant echeggiato`() {
        start(variant = "reduced")
        tickUntil { GuidedRunRepository.current.value!!.index == 3 } // Ripetuta 2/6
        repeat(30) { tickOnce() }
        assertEquals("Ripetuta 2/6", GuidedRunRepository.current.value!!.label)
        val record = GuidedRunRepository.takeSegments(activeSec.toDouble(), distanceM)!!
        assertEquals("reduced", record.getString("variant"))
        assertEquals(14, record.getInt("plannedStepCount"))
        val segs = record.getJSONArray("segments")
        assertEquals(4, segs.length())
        val last = segs.getJSONObject(3)
        assertEquals("st_w#2", last.getString("stepId"))
        assertEquals("finish", last.getString("endedBy"))
        assertEquals(activeSec.toDouble(), last.getDouble("endActiveSec"), 0.0)
    }

    @Test
    fun `nessun tick - nessun record`() {
        start()
        assertNull(GuidedRunRepository.takeSegments(0.0, 0.0))
        assertFalse(GuidedRunRepository.isActive)
    }

    @Test
    fun `restore dallo snapshot - nessun StepStart ripetuto, segmenti conservati`() {
        start(variant = "reduced")
        tickUntil { GuidedRunRepository.current.value!!.index == 2 } // Recupero 1/6
        repeat(10) { tickOnce() }
        val before = GuidedRunRepository.current.value!!
        val snapJson = GuidedRunRepository.snapshot()!!.toJson()
        // Le chiavi dell'appendice I.
        for (k in listOf("structure", "engine", "segments", "planRef", "lang", "title", "sport", "variant")) {
            assertTrue("manca $k", snapJson.has(k))
        }

        GuidedRunRepository.stop()
        val snap = GuidedRunSnapshot.fromJson(JSONObject(snapJson.toString()))!!
        GuidedRunRepository.restore(snap)
        val restored = GuidedRunRepository.current.value!!
        assertEquals(before.index, restored.index)
        assertEquals(before.label, restored.label)
        assertEquals("reduced", restored.variant)

        allEvents.clear()
        tickOnce()
        assertTrue(allEvents.none { it is StepEvent.StepStart })

        // Il resto della seduta prosegue: la fase successiva parte una volta.
        tickUntil { GuidedRunRepository.current.value!!.index == 3 }
        assertEquals(listOf(3), allEvents.filterIsInstance<StepEvent.StepStart>().map { it.stepIndex })

        val record = GuidedRunRepository.takeSegments(activeSec.toDouble(), distanceM)!!
        val segs = record.getJSONArray("segments")
        // warmup + work1 (dallo snapshot) + recovery1 + work2 chiuso finish.
        assertEquals(4, segs.length())
        assertEquals("st_wu", segs.getJSONObject(0).getString("stepId"))
        assertEquals("reduced", record.getString("variant"))
    }

    @Test
    fun `recovery a freddo - closedRecord chiude la fase in corso`() {
        start()
        tickUntil { GuidedRunRepository.current.value!!.index == 1 }
        repeat(20) { tickOnce() }
        val snap = GuidedRunSnapshot.fromJson(GuidedRunRepository.snapshot()!!.toJson())!!
        val record = GuidedRunRepository.closedRecord(snap)!!
        val segs = record.getJSONArray("segments")
        assertEquals(2, segs.length())
        assertEquals("finish", segs.getJSONObject(1).getString("endedBy"))
        // Il singleton non è toccato.
        assertTrue(GuidedRunRepository.isActive)
    }

    @Test
    fun `nextStep su una fase a tempo - chiusa lap al tick successivo`() {
        start()
        repeat(60) { tickOnce() }
        GuidedRunRepository.nextStep()
        val events = tickOnce()
        val end = events.filterIsInstance<StepEvent.StepEnd>().single()
        assertEquals(0, end.stepIndex)
        assertEquals(StepEndedBy.LAP, end.endedBy)
        assertTrue(events.contains(StepEvent.StepStart(1)))
        // Impulso one-shot: il tick dopo non chiude altro.
        assertTrue(tickOnce().none { it is StepEvent.StepEnd })
    }

    @Test
    fun `indoor senza distanza - fase a distanza fino al tasto (D20)`() {
        start(hasDistanceSignal = false)
        GuidedRunRepository.nextStep()
        tickOnce()
        val v = GuidedRunRepository.current.value!!
        assertEquals("Ripetuta 1/6", v.label)
        assertEquals(ResolvedEndType.LAP, v.endType)
        assertEquals("fino al tasto", v.remainingSubtitle)
    }

    @Test
    fun `vista e mirror - testi dal wire, platform wear`() {
        start()
        tickOnce()
        val v = GuidedRunRepository.current.value!!
        assertEquals(0, v.index)
        assertEquals("Riscaldamento", v.label)
        assertEquals("9:59", v.remainingText)
        assertEquals("4:48–5:24 /km", v.targetLabel)
        assertEquals("Ripetuta 1/6 · 800 m", v.nextLabel)
        assertEquals(ResolvedTargetKind.PACE, v.target!!.kind)

        assertTrue(GuidedRunRepository.mirrorDue(1000L, paused = false))
        assertFalse(GuidedRunRepository.mirrorDue(3000L, paused = false))
        assertTrue(GuidedRunRepository.mirrorDue(6000L, paused = false))
        assertFalse(GuidedRunRepository.mirrorDue(20_000L, paused = true))

        val m = GuidedRunRepository.mirrorPayload(startedAt = 42L, elapsedSec = 1, distanceM = distanceM, hr = 150.0)
        assertEquals("guidedState", m.getString("cmd"))
        assertEquals("wear", m.getString("platform"))
        assertEquals(42L, m.getLong("startedAt"))
        assertEquals(14, m.getInt("total"))
        assertEquals("9:59", m.getString("remaining"))
        assertEquals("Ripetuta 1/6 · 800 m", m.getString("next"))
        assertFalse(m.getBoolean("finished"))
    }

    // ── Correzioni verificatore ──────────────────────────────────────────

    @Test
    fun `countdownCrossings - soglie 3 2 1 attraversate una volta`() {
        assertEquals(0, countdownCrossings(5.0, 4.0))
        assertEquals(1, countdownCrossings(4.0, 3.0))
        assertEquals(1, countdownCrossings(3.0, 2.0))
        assertEquals(1, countdownCrossings(2.0, 1.0))
        assertEquals(0, countdownCrossings(1.0, 0.0))
        // Tick lento: due soglie in un colpo (il service fa comunque 1 click).
        assertEquals(2, countdownCrossings(3.5, 1.5))
        assertEquals(3, countdownCrossings(null, 0.5))
    }

    @Test
    fun `countdown 3-2-1 solo nelle fasi a tempo, mai per i metri`() {
        start()
        val clicksAt = mutableListOf<Long>()
        while (GuidedRunRepository.current.value!!.index < 3) {
            tickOnce()
            if (GuidedRunRepository.takeCountdownClicks() > 0) clicksAt += activeSec
        }
        // Riscaldamento a tempo (600 s): click a 597, 598, 599 s attivi;
        // la ripetuta 1 (800 m) non ne ha;
        // il recupero 1 (120 s) inizia dopo la ripetuta e finisce prima
        // dell'indice 3: 3 click alla sua fine.
        assertEquals(listOf(597L, 598L, 599L), clicksAt.take(3))
        assertEquals(6, clicksAt.size)
        val recEnd = clicksAt.drop(3)
        assertEquals(listOf(recEnd[0], recEnd[0] + 1, recEnd[0] + 2), recEnd)
    }

    @Test
    fun `activeMs che torna indietro - clampato, segmenti mai al contrario`() {
        start()
        tickUntil { GuidedRunRepository.current.value!!.index == 1 } // ripetuta 1, partita a 600 s
        repeat(5) { tickOnce() }
        // Ripresa sbagliata: tempo attivo sotto l'inizio della fase in corso.
        GuidedRunRepository.tick(0L, 500_000L, distanceM, 150.0, false)
        val record = GuidedRunRepository.takeSegments(500.0, distanceM)!!
        val segs = record.getJSONArray("segments")
        assertEquals(2, segs.length())
        for (i in 0 until segs.length()) {
            val sg = segs.getJSONObject(i)
            assertTrue(sg.toString(), sg.getDouble("endActiveSec") >= sg.getDouble("startActiveSec"))
        }
        assertEquals(activeSec.toDouble(), segs.getJSONObject(1).getDouble("endActiveSec"), 0.0)
    }
}
