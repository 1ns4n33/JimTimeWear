package com.jimtime.wear.health

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F5b (Nuoto, §7) — copre il contratto wire di `sessionSync.swim` costruito
 * da [SwimPayloadBuilder]: aggregati soltanto (mai vasche/serie inventate),
 * `laps`/`sets` sempre vuoti (D9), `poolLengthM` omesso quando non
 * applicabile (acque libere, o vasca non impostata).
 */
class SwimPayloadBuilderTest {

    @Test
    fun `pool session carries aggregates and pool length`() {
        val payload = SwimPayloadBuilder.build(
            location = "POOL",
            poolLengthM = 25.0,
            distanceMeters = 1000.0,
            laps = 40,
            strokes = 612,
        )

        assertEquals("POOL", payload["location"])
        assertEquals("WATCH", payload["source"])
        assertEquals(25.0, payload["poolLengthM"])
        assertEquals(612, payload["strokeCount"])
        assertEquals(40, payload["lapCount"])
        assertEquals(1000.0, payload["distanceM"])
    }

    @Test
    fun `laps and sets are always empty — Health Services has no per-lap split`() {
        val payload = SwimPayloadBuilder.build(
            location = "POOL",
            poolLengthM = 50.0,
            distanceMeters = 500.0,
            laps = 10,
            strokes = 150,
        )

        assertEquals(emptyList<Any>(), payload["laps"])
        assertEquals(emptyList<Any>(), payload["sets"])
    }

    @Test
    fun `open water omits poolLengthM entirely — not just null`() {
        val payload = SwimPayloadBuilder.build(
            location = "OPEN_WATER",
            poolLengthM = null,
            distanceMeters = 1800.0,
            laps = 0,
            strokes = 0,
        )

        assertFalse("poolLengthM must be absent, not present-as-null", payload.containsKey("poolLengthM"))
        assertEquals("OPEN_WATER", payload["location"])
        assertNull(payload["poolLengthM"])
    }

    @Test
    fun `zero or negative pool length is treated as unset`() {
        val payload = SwimPayloadBuilder.build(
            location = "POOL",
            poolLengthM = 0.0,
            distanceMeters = 100.0,
            laps = 4,
            strokes = 60,
        )
        assertFalse(payload.containsKey("poolLengthM"))
    }

    @Test
    fun `distanceM and lapCount survive as-is with zero aggregates`() {
        // A session stopped within the first second, before the first
        // ExerciseUpdate arrived — must not crash or fabricate data.
        val payload = SwimPayloadBuilder.build(
            location = "POOL",
            poolLengthM = 25.0,
            distanceMeters = 0.0,
            laps = 0,
            strokes = 0,
        )
        assertEquals(0.0, payload["distanceM"])
        assertEquals(0, payload["lapCount"])
        assertEquals(0, payload["strokeCount"])
    }

    @Test
    fun `swim payload round-trips through real JSON without losing aggregates`() {
        // Mirrors what SessionViewModel.stopSwimFromWatch actually sends:
        // JSONObject(SwimPayloadBuilder.build(...)).toString() over the
        // wire, JSONObject(String) parsed back on the other side.
        val payload = SwimPayloadBuilder.build(
            location = "POOL",
            poolLengthM = 25.0,
            distanceMeters = 1500.0,
            laps = 60,
            strokes = 900,
        )
        val roundTripped = JSONObject(JSONObject(payload).toString())

        assertEquals("POOL", roundTripped.getString("location"))
        assertEquals(60, roundTripped.getInt("lapCount"))
        assertEquals(900, roundTripped.getInt("strokeCount"))
        assertEquals(1500.0, roundTripped.getDouble("distanceM"), 0.0)
        assertEquals(0, roundTripped.getJSONArray("laps").length())
        assertEquals(0, roundTripped.getJSONArray("sets").length())
    }

    @Test
    fun `epoch millis stay a 64-bit Long through JSON, never truncated to Int or Double`() {
        // Regression guard for the millis-precision class of bug this plan
        // calls out on the iOS side (NSNumber/Double round-tripping loses
        // precision on ms-since-epoch): on Kotlin/Android there is no
        // NSNumber, but the same failure mode exists if a Long ever gets
        // coerced through a Double or Int on its way to org.json.
        val startedAt: Long = 1_758_000_000_123L
        val json = JSONObject().apply { put("startedAt", startedAt) }
        val roundTripped = JSONObject(json.toString())

        assertEquals(startedAt, roundTripped.getLong("startedAt"))
        // A Double can't hold this exactly-that's the historical bug shape;
        // assert the round trip did NOT go through one.
        assertTrue(roundTripped.get("startedAt") is Long)
    }
}
