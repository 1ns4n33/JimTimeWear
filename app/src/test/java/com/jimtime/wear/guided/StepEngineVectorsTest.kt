package com.jimtime.wear.guided

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * F7 (WP-KE, D9) — esegue ogni vettore di `step_engine_vectors.json` (copia
 * GENERATA da `FE/tool/sync_shared_vectors.dart` in `src/test/resources`,
 * mai a mano) contro [StepEngine]: stessa logica di `VectorRunner.swift`.
 * I vettori con `requires` sono saltati e dichiarati, tranne
 * `profileResolver` risolvibile da [TrivialResolver] (oggi la zona `walk`).
 * Confronto a sottoinsieme, numeri con tolleranza 0.5.
 */
class StepEngineVectorsTest {

    private class SuiteResult {
        var passed = 0
        val skipped = mutableListOf<String>()
        val failures = mutableListOf<String>()
    }

    @Test
    fun allVectors() {
        val text = javaClass.classLoader!!.getResourceAsStream("step_engine_vectors.json")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: run { fail("step_engine_vectors.json non trovato nel classpath di test"); return }
        val result = runVectorSuite(JSONObject(text))
        for (skip in result.skipped) println("[skip] $skip")
        for (failure in result.failures) println("[fail] $failure")
        println(
            "[step_engine_kotlin] ${result.passed} passed, ${result.skipped.size} skipped, " +
                "${result.failures.size} failed",
        )
        assertTrue(result.failures.joinToString("\n"), result.failures.isEmpty())
        assertTrue("nessun vettore eseguito", result.passed > 0)
    }

    private fun runVectorSuite(root: JSONObject): SuiteResult {
        val result = SuiteResult()
        val vectors = root.opt("vectors") as? JSONArray
        if (vectors == null) {
            result.failures.add("vettori non leggibili")
            return result
        }
        for (k in 0 until vectors.length()) {
            val vector = vectors.opt(k) as? JSONObject ?: continue
            val name = vector.opt("name") as? String ?: continue

            val requires = vector.opt("requires") as? String
            if (requires != null) {
                if (requires == "profileResolver") {
                    val structureJson = vector.opt("structure") as? JSONObject
                    val resolvable = structureJson != null && TrivialResolver.resolveSteps(
                        structureJson,
                        vector.opt("profile") as? JSONObject,
                    ) != null
                    if (!resolvable) {
                        result.skipped.add("$name (requires: $requires, phone-only)")
                        continue
                    }
                } else {
                    // `unitConversion`/`treadmillHelper`: solo lato telefono (D2/D16).
                    result.skipped.add("$name (requires: $requires, phone-only)")
                    continue
                }
            }

            runVector(name, vector, result)
            result.passed += 1
        }
        return result
    }

    private fun runVector(name: String, vector: JSONObject, result: SuiteResult) {
        if (vector.opt("helper") is String && !vector.has("structure")) {
            result.failures.add("$name: vettore helper senza `requires` (incoerente)")
            return
        }
        val structureJson = vector.opt("structure") as? JSONObject
        if (structureJson == null) {
            result.failures.add("$name: `structure` mancante")
            return
        }

        val expectedExpandedCount = (vector.opt("expectExpandedCount") as? Number)?.toInt()
        if (expectedExpandedCount != null) {
            val actual = TrivialResolver.expandedCount(structureJson)
            if (actual != expectedExpandedCount) {
                result.failures.add("$name: expandedCount atteso $expectedExpandedCount, trovato $actual")
            }
        }

        val resolved = TrivialResolver.resolveSteps(structureJson, vector.opt("profile") as? JSONObject)
        if (resolved == null) {
            result.failures.add("$name: impossibile risolvere (nessun `requires` ma target/fine non assoluto?)")
            return
        }

        val expectedResolved = vector.opt("expectResolved") as? JSONArray
        if (expectedResolved != null) {
            if (resolved.size < expectedResolved.length()) {
                result.failures.add("$name: resolved.size ${resolved.size} < atteso ${expectedResolved.length()}")
            }
            for (i in 0 until expectedResolved.length()) {
                if (i >= resolved.size) break
                val expected = expectedResolved.opt(i) as? JSONObject ?: continue
                assertSubset(resolvedStepToMap(resolved[i]), expected, "$name[resolved $i]", emptySet(), result)
            }
        }

        val samples = vector.opt("samples") as? JSONArray
        if (samples != null) runSimulation(name, resolved, samples, vector, result)
    }

    private fun runSimulation(
        name: String,
        steps: List<ResolvedStep>,
        samples: JSONArray,
        vector: JSONObject,
        result: SuiteResult,
    ) {
        val expectEventsRaw = vector.opt("expectEvents") as? JSONArray ?: JSONArray()
        val expectEvents = (0 until expectEventsRaw.length()).mapNotNull { expectEventsRaw.opt(it) as? JSONObject }
        val expectStateAfter = vector.opt("expectStateAfter") as? JSONObject

        var state = StepEngineState.INITIAL
        for (i in 0 until samples.length()) {
            val s = samples.opt(i) as? JSONObject ?: JSONObject()
            val sample = StepSample(
                activeMs = (s.opt("t") as? Number)?.toLong() ?: 0L,
                distanceM = (s.opt("d") as? Number)?.toDouble() ?: 0.0,
                hr = (s.opt("hr") as? Number)?.toDouble(),
                cadenceSpm = (s.opt("cad") as? Number)?.toDouble(),
                powerW = (s.opt("pw") as? Number)?.toDouble(),
                paused = s.opt("paused") as? Boolean ?: false,
                lap = s.opt("lap") as? Boolean ?: false,
            )
            val (next, actualEvents) = StepEngine.advance(steps, state, sample)
            state = next
            val expectedForThis = expectEvents.filter { (it.opt("at") as? Number)?.toInt() == i }

            if (expectedForThis.size == 1 && expectedForThis[0].opt("type") == "__none__") {
                if (actualEvents.isNotEmpty()) {
                    result.failures.add("$name at=$i: previsti 0 eventi, trovati ${actualEvents.map(::eventType)}")
                }
            } else if (actualEvents.size != expectedForThis.size) {
                result.failures.add(
                    "$name at=$i: atteso ${expectedForThis.size} eventi, trovati ${actualEvents.size} " +
                        "(${actualEvents.map(::eventType)})",
                )
            } else {
                for ((j, expected) in expectedForThis.withIndex()) {
                    assertSubset(eventToMap(actualEvents[j]), expected, "$name at=$i[$j]", setOf("at"), result)
                }
            }

            if (expectStateAfter != null && (expectStateAfter.opt("at") as? Number)?.toInt() == i) {
                assertSubset(stateToMap(state), expectStateAfter, "$name stateAfter at=$i", setOf("at"), result)
            }
        }
    }

    // ── Conversione in mappe (per il confronto col JSON) ─────────────────

    private fun resolvedStepToMap(step: ResolvedStep): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "id" to step.id,
            "sourceId" to step.sourceId,
            "role" to step.role.wire,
            "end" to mapOf("type" to step.end.type.wire, "value" to step.end.value),
            "target" to targetToMap(step.target),
        )
        step.name?.let { m["name"] = it }
        step.cue?.let { m["cue"] = it }
        step.repeatIteration?.let { m["repeatIteration"] = it }
        step.repeatCount?.let { m["repeatCount"] = it }
        step.blockId?.let { m["blockId"] = it }
        return m
    }

    private fun targetToMap(t: ResolvedTarget): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("kind" to t.kind.wire, "low" to t.low, "high" to t.high)
        t.zoneLabel?.let { m["zoneLabel"] = it }
        t.inclinePct?.let { m["inclinePct"] = it }
        return m
    }

    private fun eventType(e: StepEvent): String = when (e) {
        is StepEvent.StepStart -> "stepStart"
        is StepEvent.StepHalfway -> "stepHalfway"
        is StepEvent.StepLastPart -> "stepLastPart"
        is StepEvent.StepOffTarget -> "stepOffTarget"
        is StepEvent.StepBackOnTarget -> "stepBackOnTarget"
        is StepEvent.StepEnd -> "stepEnd"
        is StepEvent.WorkoutEnd -> "workoutEnd"
    }

    private fun eventToMap(e: StepEvent): Map<String, Any?> = when (e) {
        is StepEvent.StepLastPart -> linkedMapOf<String, Any?>("type" to "stepLastPart", "step" to e.stepIndex).apply {
            e.seconds?.let { this["seconds"] = it }
            e.meters?.let { this["meters"] = it }
        }
        is StepEvent.StepOffTarget -> mapOf(
            "type" to "stepOffTarget", "step" to e.stepIndex, "direction" to e.direction.wire, "avg" to e.avg,
        )
        is StepEvent.StepEnd -> mapOf(
            "type" to "stepEnd",
            "step" to e.stepIndex,
            "endedBy" to e.endedBy.wire,
            "avgPace" to e.stats.avgPaceSecPerKm,
            "avgPower" to e.stats.avgPowerW,
            "inRangePct" to e.stats.inRangePct,
        )
        else -> mapOf("type" to eventType(e), "step" to e.stepIndex)
    }

    private fun stateToMap(s: StepEngineState): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "started" to s.started,
            "index" to s.index,
            "stepStartActiveMs" to s.stepStartActiveMs,
            "stepStartDistanceM" to s.stepStartDistanceM,
            "hrSum" to s.hrSum,
            "hrCount" to s.hrCount,
            "cadSum" to s.cadSum,
            "cadCount" to s.cadCount,
            "inBandCount" to s.inBandCount,
            "evalCount" to s.evalCount,
            "offTarget" to s.offTarget,
            "halfwayFired" to s.halfwayFired,
            "lastPartFired" to s.lastPartFired,
            "lastActiveMs" to s.lastActiveMs,
            "lastDistanceM" to s.lastDistanceM,
        )
        s.lastOffTargetCueMs?.let { m["lastOffTargetCueMs"] = it }
        return m
    }

    /**
     * Confronto a SOTTOINSIEME (= `assertSubset` Swift / `_expectSubset`
     * Dart): ogni chiave attesa deve combaciare; numeri con tolleranza 0.5;
     * `null` atteso ⇒ assente o null.
     */
    private fun assertSubset(
        actual: Map<String, Any?>,
        expected: JSONObject,
        reason: String,
        skipKeys: Set<String>,
        result: SuiteResult,
    ) {
        for (key in expected.keys()) {
            if (key in skipKeys) continue
            val expectedValue = expected.opt(key)
            val actualValue = actual[key]
            when {
                expectedValue == null || expectedValue == JSONObject.NULL -> {
                    if (actualValue != null) result.failures.add("$reason.$key: atteso null, trovato $actualValue")
                }
                expectedValue is JSONObject -> {
                    @Suppress("UNCHECKED_CAST")
                    val actDict = actualValue as? Map<String, Any?>
                    if (actDict == null) {
                        result.failures.add("$reason.$key: atteso un dizionario, trovato $actualValue")
                    } else {
                        assertSubset(actDict, expectedValue, "$reason.$key", emptySet(), result)
                    }
                }
                expectedValue is Number -> {
                    val actNum = actualValue as? Number
                    if (actNum == null) {
                        result.failures.add("$reason.$key: atteso numero $expectedValue, trovato $actualValue")
                    } else if (Math.abs(actNum.toDouble() - expectedValue.toDouble()) > 0.5) {
                        result.failures.add("$reason.$key: atteso ~${expectedValue.toDouble()}, trovato ${actNum.toDouble()}")
                    }
                }
                else -> {
                    val actualStr = actualValue?.toString() ?: "nil"
                    val expectedStr = expectedValue.toString()
                    if (actualStr != expectedStr) {
                        result.failures.add("$reason.$key: atteso $expectedStr, trovato $actualStr")
                    }
                }
            }
        }
    }
}
