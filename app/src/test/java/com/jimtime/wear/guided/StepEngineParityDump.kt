package com.jimtime.wear.guided

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * F7 (WP-KE → WP-PAR, §5.2) — banco di parità Dart ↔ Kotlin. Inerte di
 * default: gira solo con
 * `-Djt.parity.cases=<cases.json> -Djt.parity.out=<kotlin_results.json>`
 * (inoltrate al test JVM da `app/build.gradle.kts`). Stesso formato di
 * ingresso e STESSE chiavi d'uscita di `scratchpad/parity/dart_runner.dart`
 * (`eventToJson`, `progress`, `finalState` = `StepEngineState.toJson`),
 * `null` scritti come `null` (le chiavi ci sono sempre, come in Dart) — ECCETTO
 * `avgPowerW` (stepEnd) e `currentAvgPower` (progress), F7 (WP-PAR, fase 1b):
 * presenti SOLO quando non null, STESSA convenzione in `dart_runner.dart`.
 * L'input `samples[].pw` (alias `powerW`) alimenta `StepSample.powerW`.
 */
class StepEngineParityDump {

    @Test
    fun dump() {
        val casesPath = System.getProperty("jt.parity.cases").orEmpty()
        if (casesPath.isBlank()) return
        val outPath = System.getProperty("jt.parity.out").orEmpty()
            .ifBlank { File(File(casesPath).parentFile, "kotlin_results.json").path }

        val input = JSONObject(File(casesPath).readText(Charsets.UTF_8))
        val cases = input.getJSONArray("cases")
        val results = JSONArray()
        for (c in 0 until cases.length()) {
            val cm = cases.getJSONObject(c)
            val stepsJson = cm.getJSONArray("steps")
            val steps = (0 until stepsJson.length()).map { i ->
                val sm = stepsJson.getJSONObject(i)
                val end = sm.getJSONObject("end")
                val target = sm.getJSONObject("target")
                ResolvedStep(
                    id = "st_$i",
                    sourceId = "st_$i",
                    role = PlanStepRole.WORK,
                    end = ResolvedEnd(
                        ResolvedEndType.fromJson(end.opt("type") as? String),
                        (end.get("value") as Number).toDouble(),
                    ),
                    target = ResolvedTarget(
                        ResolvedTargetKind.fromJson(target.opt("kind") as? String),
                        (target.get("low") as Number).toDouble(),
                        (target.get("high") as Number).toDouble(),
                    ),
                )
            }

            var state = StepEngineState.INITIAL
            val samplesJson = cm.getJSONArray("samples")
            val perSampleEvents = JSONArray()
            val perProgress = JSONArray()
            for (k in 0 until samplesJson.length()) {
                val sm = samplesJson.getJSONObject(k)
                val sample = StepSample(
                    activeMs = (sm.get("activeMs") as Number).toLong(),
                    distanceM = (sm.get("distanceM") as Number).toDouble(),
                    hr = (sm.opt("hr") as? Number)?.toDouble(),
                    cadenceSpm = (sm.opt("cadenceSpm") as? Number)?.toDouble(),
                    // F7 (WP-PAR, fase 1b) — `gen_cases.py` scrive `pw`; `powerW`
                    // accettato come alias per compatibilità futura.
                    powerW = (sm.opt("pw") as? Number)?.toDouble()
                        ?: (sm.opt("powerW") as? Number)?.toDouble(),
                    paused = sm.getBoolean("paused"),
                    lap = sm.getBoolean("lap"),
                )
                val (next, events) = StepEngine.advance(steps, state, sample)
                state = next
                val evs = JSONArray()
                events.forEach { evs.put(eventToJson(it)) }
                perSampleEvents.put(evs)
                val prog = StepEngine.progressOf(steps, state, sample)
                val progJson = JSONObject()
                    .put("remainingSec", orNull(prog.remainingSec))
                    .put("remainingM", orNull(prog.remainingM))
                    .put("fraction", prog.fraction)
                    .put("currentAvgPace", orNull(prog.currentAvgPace))
                    .put("currentAvgHr", orNull(prog.currentAvgHr))
                    .put("inBand", orNull(prog.inBand))
                // F7 (WP-PAR, fase 1b) — chiave presente SOLO se non null (come
                // `dart_runner.dart`), per confrontare davvero la potenza.
                if (prog.currentAvgPower != null) progJson.put("currentAvgPower", prog.currentAvgPower)
                perProgress.put(progJson)
            }
            results.put(
                JSONObject()
                    .put("id", cm.opt("id"))
                    .put("events", perSampleEvents)
                    .put("progress", perProgress)
                    .put("finalState", state.toJson()),
            )
        }
        File(outPath).writeText(JSONObject().put("results", results).toString(), Charsets.UTF_8)
        println("[parity] ${cases.length()} casi → $outPath")
    }

    private fun orNull(v: Any?): Any = v ?: JSONObject.NULL

    /** = `eventToJson` di `dart_runner.dart` (stesse chiavi, `null` espliciti). */
    private fun eventToJson(e: StepEvent): JSONObject = when (e) {
        is StepEvent.StepStart -> JSONObject().put("type", "stepStart").put("step", e.stepIndex)
        is StepEvent.StepHalfway -> JSONObject().put("type", "stepHalfway").put("step", e.stepIndex)
        is StepEvent.StepLastPart -> JSONObject()
            .put("type", "stepLastPart").put("step", e.stepIndex)
            .put("seconds", orNull(e.seconds)).put("meters", orNull(e.meters))
        is StepEvent.StepOffTarget -> JSONObject()
            .put("type", "stepOffTarget").put("step", e.stepIndex)
            .put("direction", e.direction.wire).put("avg", e.avg)
        is StepEvent.StepBackOnTarget -> JSONObject().put("type", "stepBackOnTarget").put("step", e.stepIndex)
        is StepEvent.StepEnd -> {
            val o = JSONObject()
                .put("type", "stepEnd")
                .put("step", e.stepIndex)
                .put("endedBy", e.endedBy.wire)
                .put("startActiveMs", e.stats.startActiveMs)
                .put("endActiveMs", e.stats.endActiveMs)
                .put("startDistanceM", e.stats.startDistanceM)
                .put("endDistanceM", e.stats.endDistanceM)
                .put("avgPaceSecPerKm", orNull(e.stats.avgPaceSecPerKm))
                .put("avgHr", orNull(e.stats.avgHr))
                .put("avgCadence", orNull(e.stats.avgCadence))
                .put("inRangePct", orNull(e.stats.inRangePct))
            // F7 (WP-PAR, fase 1b) — chiave presente SOLO se non null.
            if (e.stats.avgPowerW != null) o.put("avgPowerW", e.stats.avgPowerW)
            o
        }
        is StepEvent.WorkoutEnd -> JSONObject().put("type", "workoutEnd").put("step", e.stepIndex)
    }
}
