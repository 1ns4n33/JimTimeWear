package com.jimtime.wear.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F7 (WP-WD, §2.2) — `planDays` v2 come lo manda il telefono (= forma Apple
 * di F2 §2.1): parse tollerante di `sportDays`, filtro sul catalogo Wear,
 * `days` letti esattamente come prima.
 */
class PlanDaysStoreSportDaysTest {

    private val payload = JSONObject(
        """
        { "cmd": "planDays", "v": 2, "lang": "it", "planId": "ckx1", "planName": "Mezza in 12 settimane",
          "days": [ { "week": 1, "day": 0, "label": "Giorno A – Push", "exercises": 6, "groups": [] },
                    { "week": 1, "day": 2, "label": "", "exercises": 4 } ],
          "sportDays": [
            { "week": 1, "day": 1, "label": "Giovedì – Ripetute",
              "activities": [
                { "id": "pa_3f9a1c77b2d4", "sport": "run", "title": "Ripetute 6×800", "timeOfDay": "morning",
                  "goal": { "durationSec": 2700, "distanceM": 8500, "rpe": 7 },
                  "summary": "6×800 m · ≈ 8,5 km · ≈ 45 min", "guided": true,
                  "structure": { "v": 1, "steps": [
                    { "id": "st_7a1b2c", "role": "warmup", "end": { "type": "time", "value": 600 },
                      "target": { "kind": "pace", "low": 288, "high": 324, "zoneLabel": "easy" },
                      "ui": { "label": "Riscaldamento", "spoken": "Riscaldamento, 10 minuti", "target": "4:48–5:24 /km" } } ] },
                  "profile": { "pace5kSecPerKm": 240, "hrMax": 190, "hrRest": 55 } },
                { "id": "pa_a1b2c3d4e5f6", "sport": "padel", "title": "Partita", "timeOfDay": "evening",
                  "goal": { "durationSec": 5400 }, "summary": "90 min", "guided": false },
                { "id": "pa_rowing", "sport": "rowing_machine", "summary": "20 min", "guided": false },
                { "id": "pa_unknown", "sport": "curling", "summary": "x", "guided": false }
              ] },
            { "week": 2, "day": 3, "label": "Solo vogatore",
              "activities": [ { "id": "pa_r2", "sport": "rowing_machine", "summary": "30 min", "guided": false } ] },
            { "week": 2, "day": 4, "label": "Malformato" },
            { "day": 5, "label": "Senza settimana", "activities": [ { "id": "x", "sport": "run", "summary": "", "guided": false } ] }
          ] }
        """.trimIndent(),
    )

    @Test
    fun `sportDays del payload v2 parsati con structure e profile`() {
        val days = PlanDaysStore.parseSportDays(payload.getJSONArray("sportDays").toString())
        assertEquals(1, days.size)
        val d = days[0]
        assertEquals(1, d.week)
        assertEquals(1, d.day)
        assertEquals("Giovedì – Ripetute", d.label)
        assertEquals(listOf("pa_3f9a1c77b2d4", "pa_a1b2c3d4e5f6"), d.activities.map { it.id })

        val run = d.activities[0]
        assertEquals("run", run.sport)
        assertEquals("Ripetute 6×800", run.title)
        assertEquals("6×800 m · ≈ 8,5 km · ≈ 45 min", run.summary)
        assertTrue(run.guided)
        val structure = JSONObject(run.structureJson!!)
        assertEquals(1, structure.getJSONArray("steps").length())
        assertEquals(240, JSONObject(run.profileJson!!).getInt("pace5kSecPerKm"))
        assertEquals(1, run.week)
        assertEquals(1, run.day)
        assertEquals("Giovedì – Ripetute", run.dayLabel)

        val padel = d.activities[1]
        assertFalse(padel.guided)
        assertNull(padel.structureJson)
        assertNull(padel.profileJson)
    }

    @Test
    fun `filtro watchWear - sport non avviabili sul Wear e giorni vuoti scartati`() {
        val days = PlanDaysStore.parseSportDays(payload.getJSONArray("sportDays").toString())
        val ids = days.flatMap { it.activities }.map { it.id }
        // rowing_machine: nel catalogo ma watchWear=false; curling: sconosciuto.
        assertFalse("pa_rowing" in ids)
        assertFalse("pa_unknown" in ids)
        // Il giorno col solo vogatore resta senza attività ⇒ sparisce.
        assertFalse(days.any { it.week == 2 && it.day == 3 })
    }

    @Test
    fun `chiavi assenti o malformate - lista vuota mai eccezione`() {
        assertTrue(PlanDaysStore.parseSportDays(null).isEmpty())
        assertTrue(PlanDaysStore.parseSportDays("non json").isEmpty())
        assertTrue(PlanDaysStore.parseSportDays("{}").isEmpty())
        assertTrue(PlanDaysStore.parseSportDays("[]").isEmpty())
        // Attività senza id/sport scartata, il giorno con nulla di valido pure.
        val raw = """[ { "week": 1, "day": 0, "activities": [ { "sport": "run" }, { "id": "a" }, 7 ] } ]"""
        assertTrue(PlanDaysStore.parseSportDays(raw).isEmpty())
        // guided:true senza structure: avviabile come sessione semplice.
        val noStructure = """[ { "week": 1, "day": 0, "activities": [ { "id": "a", "sport": "walk", "guided": true } ] } ]"""
        val a = PlanDaysStore.parseSportDays(noStructure).single().activities.single()
        assertTrue(a.guided)
        assertNull(a.structureJson)
        assertEquals("", a.summary)
        assertNull(a.title)
        assertEquals("Giorno 1", PlanDaysStore.parseSportDays(noStructure).single().label)
    }

    @Test
    fun `days invariati rispetto al parse di prima`() {
        val days = PlanDaysStore.parse(payload.getJSONArray("days").toString())
        assertEquals(
            listOf(
                PlanDay(week = 1, day = 0, label = "Giorno A – Push", exercises = 6),
                PlanDay(week = 1, day = 2, label = "Giorno 3", exercises = 4),
            ),
            days,
        )
    }

    @Test
    fun `planRef dell'attivita' = PlanActivityRef del telefono`() {
        val a = PlanDaysStore.parseSportDays(payload.getJSONArray("sportDays").toString())[0].activities[0]
        val ref = a.planRef("ckx1")
        assertEquals("ckx1", ref.getString("planId"))
        assertEquals(1, ref.getInt("weekNumber"))
        assertEquals(1, ref.getInt("dayIndex"))
        assertEquals("Giovedì – Ripetute", ref.getString("dayLabel"))
        assertEquals("pa_3f9a1c77b2d4", ref.getString("activityId"))
        assertFalse(a.planRef(null).has("planId"))
    }

    @Test
    fun `nextSportDay del dailySummary - tollerante`() {
        val ok = DailySummaryStore.parseNextSportDay(
            JSONObject(
                """{ "week": 1, "day": 1, "label": "Giovedì – Ripetute", "activityId": "pa_1", "sport": "run",
                     "title": "Ripetute 6×800", "summary": "6×800 m", "guided": true }""",
            ),
        )!!
        assertEquals(DailySummaryNextSportDay(1, 1, "Giovedì – Ripetute", "pa_1", "run", "Ripetute 6×800", "6×800 m", true), ok)
        assertNull(DailySummaryStore.parseNextSportDay(null))
        assertNull(DailySummaryStore.parseNextSportDay(JSONObject("""{ "week": 1, "day": 1, "sport": "run" }""")))
        val minimal = DailySummaryStore.parseNextSportDay(JSONObject("""{ "week": 2, "day": 0, "activityId": "a", "sport": "walk" }"""))!!
        assertNull(minimal.title)
        assertFalse(minimal.guided)
        assertEquals("", minimal.summary)
    }
}
