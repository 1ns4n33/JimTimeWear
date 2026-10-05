package com.jimtime.wear.guided

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F7 (WP-KE, D7/D10/D16) — `ResolvedStructure.fromWire/toWire` (tollerante
 * come lo Swift), tetto 60 di `expand`, `uiLabel/uiSpoken`, e il record
 * `stepSegments` scritto dal polso.
 */
class ResolvedStructureWireTest {

    private fun step(
        id: String,
        role: String = "work",
        end: JSONObject = JSONObject().put("type", "time").put("value", 60),
        target: JSONObject = JSONObject().put("kind", "none").put("low", 0).put("high", 0),
    ): JSONObject = JSONObject().put("id", id).put("role", role).put("end", end).put("target", target)

    private fun repeat(id: String, n: Int, vararg children: JSONObject): JSONObject =
        JSONObject().put("id", id).put("repeat", n).put("steps", JSONArray(children.toList()))

    private fun structure(vararg entries: JSONObject): JSONObject =
        JSONObject().put("v", 1).put("steps", JSONArray(entries.toList()))

    private fun sampleWire(): JSONObject = structure(
        step("st_wu", role = "warmup", end = JSONObject().put("type", "time").put("value", 600))
            .put("name", "Riscaldamento")
            .put(
                "ui",
                JSONObject().put("label", "Riscaldamento").put("spoken", "Riscaldamento, 10 minuti").put("target", "facile"),
            ),
        repeat(
            "st_rep", 6,
            step(
                "st_w",
                end = JSONObject().put("type", "distance").put("value", 800),
                target = JSONObject().put("kind", "pace").put("low", 235).put("high", 245).put("zoneLabel", "interval"),
            ).put("cue", "Spingi").put(
                "ui",
                JSONObject().put("label", "Ripetuta {i}/{n}").put("spoken", "Ripetuta {i} di {n}").put("target", "3:55–4:05 /km"),
            ),
            step(
                "st_r",
                role = "recovery",
                end = JSONObject().put("type", "hrBelow").put("value", 130),
                target = JSONObject().put("kind", "hr").put("low", 110).put("high", 130),
            ),
        ),
        step(
            "st_cd",
            role = "cooldown",
            end = JSONObject().put("type", "lap").put("value", 0),
            target = JSONObject().put("kind", "pace").put("low", 330).put("high", 360).put("inclinePct", 1.5),
        ),
    )

    @Test
    fun `fromWire then toWire round-trips`() {
        val parsed = ResolvedStructure.fromWire(sampleWire())
        assertNotNull(parsed)
        parsed!!
        assertEquals(3, parsed.entries.size)
        assertEquals(1 + 6 * 2 + 1, parsed.expandedCount)

        val again = ResolvedStructure.fromWire(JSONObject(parsed.toWire().toString()))
        assertEquals(parsed, again)

        val wire = parsed.toWire()
        assertEquals(1, wire.getInt("v"))
        val block = wire.getJSONArray("steps").getJSONObject(1)
        assertEquals(6, block.getInt("repeat"))
        assertEquals(2, block.getJSONArray("steps").length())
        val cd = wire.getJSONArray("steps").getJSONObject(2)
        assertEquals(1.5, cd.getJSONObject("target").getDouble("inclinePct"), 0.0)
        assertFalse(cd.has("ui"))
    }

    @Test
    fun `expand names steps like the Dart resolver and substitutes placeholders`() {
        val steps = ResolvedStructure.fromWire(sampleWire())!!.expand()
        assertEquals(14, steps.size)
        val w3 = steps[5]
        assertEquals("st_w#3", w3.id)
        assertEquals("st_w", w3.sourceId)
        assertEquals(3, w3.repeatIteration)
        assertEquals(6, w3.repeatCount)
        assertEquals("st_rep", w3.blockId)
        assertEquals("Ripetuta 3/6", w3.uiLabel())
        assertEquals("Ripetuta 3 di 6", w3.uiSpoken())
        assertEquals(ResolvedEndType.HR_BELOW, steps[6].end.type)
        // Senza `ui` ricade sul ruolo (come Swift).
        assertEquals("recovery", steps[6].uiLabel())
        assertEquals("Riscaldamento", steps[0].uiLabel())
    }

    @Test
    fun `expand is capped at 60 steps while expandedCount is not`() {
        val s = ResolvedStructure.fromWire(
            structure(step("a"), repeat("r", 50, step("w"), step("rc", role = "recovery")), step("z")),
        )!!
        assertEquals(1 + 100 + 1, s.expandedCount)
        val expanded = s.expand()
        assertEquals(60, expanded.size)
        assertEquals("a", expanded[0].id)
        assertEquals("w#30", expanded[59].id)
        assertEquals("rc#29", expanded[58].id)
    }

    @Test
    fun `unknown kind, role or end type drops only that entry`() {
        val s = ResolvedStructure.fromWire(
            structure(
                step("ok1"),
                step("badKind", target = JSONObject().put("kind", "speed").put("low", 1).put("high", 2)),
                step("badRole", role = "sprint"),
                step("badEnd", end = JSONObject().put("type", "lengths").put("value", 4)),
                JSONObject().put("role", "work"), // senza id
                step("ok2"),
            ),
        )!!
        assertEquals(listOf("ok1", "ok2"), s.expand().map { it.id })
    }

    @Test
    fun `repeat outside 2-50 or with 0 or more than 10 valid children is dropped`() {
        val eleven = (1..11).map { step("c$it") }.toTypedArray()
        val ten = (1..10).map { step("d$it") }.toTypedArray()
        val s = ResolvedStructure.fromWire(
            structure(
                repeat("r1", 1, step("x")),
                repeat("r51", 51, step("y")),
                repeat("r11", 3, *eleven),
                repeat("r0", 3, step("bad", role = "nope")),
                repeat("r10", 2, *ten),
                repeat("r2", 2, step("w"), step("bad2", target = JSONObject().put("kind", "zzz"))),
            ),
        )!!
        assertEquals(2, s.entries.size)
        val blocks = s.entries.filterIsInstance<ResolvedStructureEntry.RepeatBlock>()
        assertEquals(listOf("r10", "r2"), blocks.map { it.id })
        assertEquals(1, blocks[1].steps.size)
    }

    @Test
    fun `no valid entry or no steps array yields null`() {
        assertNull(ResolvedStructure.fromWire(JSONObject().put("v", 1)))
        assertNull(ResolvedStructure.fromWire(structure(step("x", role = "nope"))))
        assertNull(ResolvedStructure.fromWire(JSONObject().put("steps", "nope")))
    }

    @Test
    fun `incomplete ui is ignored, step kept`() {
        val s = ResolvedStructure.fromWire(
            structure(step("a").put("ui", JSONObject().put("label", "L").put("spoken", "S"))),
        )!!
        assertNull(s.expand()[0].ui)
    }

    // ── stepSegments ─────────────────────────────────────────────────────

    private fun segment(kind: ResolvedTargetKind, plannedEnd: StepSegmentPlannedEnd, name: String? = null) =
        ActivityStepSegmentWire(
            stepId = "st_w#1",
            index = 1,
            role = StepSegmentRole.WORK,
            name = name,
            startActiveSec = 600.0,
            endActiveSec = 790.5,
            startDistanceM = 1980.4,
            endDistanceM = 2780.4,
            avgPaceSecPerKm = 238.1,
            avgHr = 171.2,
            targetKind = kind,
            targetLow = if (kind == ResolvedTargetKind.NONE) null else 235.0,
            targetHigh = if (kind == ResolvedTargetKind.NONE) null else 245.0,
            inRangePct = 80.0,
            endedBy = StepEndedBy.AUTO,
            plannedEnd = plannedEnd,
        )

    @Test
    fun `segment wire omits absent keys like the Dart DTO`() {
        val full = segment(ResolvedTargetKind.PACE, StepSegmentPlannedEnd(ResolvedEndType.DISTANCE, 800.0), name = "Ripetuta")
            .toWire()
        assertEquals("Ripetuta", full.getString("name"))
        assertEquals(235.0, full.getDouble("targetLow"), 0.0)
        assertEquals(800.0, full.getJSONObject("plannedEnd").getDouble("value"), 0.0)
        assertFalse(full.has("avgCadenceSpm"))
        assertFalse(full.has("avgPowerW"))
        assertFalse(full.has("targetZone"))

        val none = segment(ResolvedTargetKind.NONE, StepSegmentPlannedEnd(ResolvedEndType.LAP, 0.0)).toWire()
        assertFalse(none.has("name"))
        assertFalse(none.has("targetLow"))
        assertFalse(none.has("targetHigh"))
        assertEquals("lap", none.getJSONObject("plannedEnd").getString("type"))
        assertFalse(none.getJSONObject("plannedEnd").has("value"))
    }

    @Test
    fun `segment fromWire round-trips`() {
        val seg = segment(ResolvedTargetKind.PACE, StepSegmentPlannedEnd(ResolvedEndType.TIME, 600.0), name = "W")
            .copy(avgPowerW = 250.0, avgCadenceSpm = 180.0, targetZone = "interval")
        assertEquals(seg, ActivityStepSegmentWire.fromWire(JSONObject(seg.toWire().toString())))
        assertNull(ActivityStepSegmentWire.fromWire(JSONObject().put("stepId", "x")))
    }

    @Test
    fun `steps record carries source watch, plannedStepCount always, variant only when set`() {
        val seg = segment(ResolvedTargetKind.PACE, StepSegmentPlannedEnd(ResolvedEndType.TIME, 600.0))
        val profile = JSONObject().put("pace5kSecPerKm", 240)

        val reduced = buildStepsRecordWire("pa_1", profile, listOf(seg), "reduced", 14)
        assertEquals(1, reduced.getInt("v"))
        assertEquals("watch", reduced.getString("source"))
        assertEquals("pa_1", reduced.getString("planActivityId"))
        assertEquals("reduced", reduced.getString("variant"))
        assertEquals(14, reduced.getInt("plannedStepCount"))
        assertEquals(240, reduced.getJSONObject("profile").getInt("pace5kSecPerKm"))
        assertEquals(1, reduced.getJSONArray("segments").length())

        val full = buildStepsRecordWire(null, JSONObject(), emptyList(), null, 0)
        assertFalse(full.has("variant"))
        assertFalse(full.has("planActivityId"))
        assertFalse(full.has("profile"))
        assertTrue(full.has("plannedStepCount"))
        assertEquals(0, full.getInt("plannedStepCount"))
        assertEquals(0, full.getJSONArray("segments").length())
    }

    @Test
    fun `engine state json round-trips and tolerates missing keys`() {
        val s = StepEngineState.INITIAL.copy(
            started = true, index = 3, stepStartActiveMs = 5_000_000_000L, hrSum = 1234.5, hrCount = 9,
            offTarget = true, lastOffTargetCueMs = 4_999_999_000L, lastDistanceM = 2650.25,
        )
        assertEquals(s, StepEngineState.fromJson(JSONObject(s.toJson().toString())))
        assertFalse(StepEngineState.INITIAL.toJson().has("lastOffTargetCueMs"))
        val legacy = StepEngineState.fromJson(JSONObject().put("index", 2))
        assertTrue(legacy.started)
        assertEquals(2, legacy.index)
        assertEquals(0, legacy.powerCount)
    }

    @Test
    fun `dartRound is half away from zero`() {
        assertEquals(3L, dartRound(2.5))
        assertEquals(-3L, dartRound(-2.5))
        assertEquals(2L, dartRound(2.4999))
        assertEquals(0L, dartRound(0.49999999999999994))
        assertEquals(10L, dartRound(9.5))
    }
}
