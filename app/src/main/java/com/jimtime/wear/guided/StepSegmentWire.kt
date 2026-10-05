package com.jimtime.wear.guided

import org.json.JSONArray
import org.json.JSONObject

/*
 * F7 (Schede multisport: al polso, WP-KE, D10/D16) — eco del Dart
 * `ActivityStepSegment`/`ActivityStepsRecord`
 * (`FE/lib/modules/session/models/activity_steps_record.dart`), port di
 * `ActivityStepSegmentWire.swift`: chiavi e semantica di [toWire] IDENTICHE
 * al DTO; nessuna chiave `null` (assente invece, come il Dart).
 */

/** Ruolo di un segmento ESEGUITO — i 5 di [PlanStepRole] più `extra` (corsa libera oltre l'ultimo step). */
enum class StepSegmentRole(val wire: String) {
    WARMUP("warmup"), WORK("work"), RECOVERY("recovery"), REST("rest"), COOLDOWN("cooldown"), EXTRA("extra");

    companion object {
        fun fromWireOrNull(raw: String?): StepSegmentRole? = entries.firstOrNull { it.wire == raw }

        fun of(role: PlanStepRole): StepSegmentRole = when (role) {
            PlanStepRole.WARMUP -> WARMUP
            PlanStepRole.WORK -> WORK
            PlanStepRole.RECOVERY -> RECOVERY
            PlanStepRole.REST -> REST
            PlanStepRole.COOLDOWN -> COOLDOWN
        }
    }
}

/** Fine PIANIFICATA del segmento — forma piatta `{type, value?}` (`value` assente per `lap`). */
data class StepSegmentPlannedEnd(val type: ResolvedEndType, val value: Double? = null) {
    fun toWire(): JSONObject {
        val m = JSONObject().put("type", type.wire)
        if (type != ResolvedEndType.LAP && value != null) m.put("value", value)
        return m
    }
}

/** Una fase eseguita — round-trip esatto con `ActivityStepSegmentDto`. */
data class ActivityStepSegmentWire(
    val stepId: String,
    val index: Int,
    val role: StepSegmentRole,
    val name: String? = null,
    val startActiveSec: Double,
    val endActiveSec: Double,
    val startDistanceM: Double,
    val endDistanceM: Double,
    val avgPaceSecPerKm: Double? = null,
    val avgHr: Double? = null,
    val avgCadenceSpm: Double? = null,
    /** Media potenza (W), sul wire SOLO se non null. */
    val avgPowerW: Double? = null,
    val targetKind: ResolvedTargetKind,
    /** Il chiamante li lascia `null` per `targetKind == NONE` (chiavi assenti). */
    val targetLow: Double? = null,
    val targetHigh: Double? = null,
    val targetZone: String? = null,
    val inRangePct: Double? = null,
    val endedBy: StepEndedBy,
    val plannedEnd: StepSegmentPlannedEnd,
) {
    fun toWire(): JSONObject {
        val m = JSONObject()
            .put("stepId", stepId)
            .put("index", index)
            .put("role", role.wire)
            .put("startActiveSec", startActiveSec)
            .put("endActiveSec", endActiveSec)
            .put("startDistanceM", startDistanceM)
            .put("endDistanceM", endDistanceM)
            .put("targetKind", targetKind.wire)
            .put("endedBy", endedBy.wire)
            .put("plannedEnd", plannedEnd.toWire())
        name?.let { m.put("name", it) }
        avgPaceSecPerKm?.let { m.put("avgPaceSecPerKm", it) }
        avgHr?.let { m.put("avgHr", it) }
        avgCadenceSpm?.let { m.put("avgCadenceSpm", it) }
        avgPowerW?.let { m.put("avgPowerW", it) }
        targetLow?.let { m.put("targetLow", it) }
        targetHigh?.let { m.put("targetHigh", it) }
        targetZone?.let { m.put("targetZone", it) }
        inRangePct?.let { m.put("inRangePct", it) }
        return m
    }

    companion object {
        /**
         * Lettura tollerante dal checkpoint (= `segmentFromWire` di
         * `GuidedRunSession.swift`): voce malformata ⇒ `null`.
         */
        fun fromWire(m: JSONObject): ActivityStepSegmentWire? {
            val stepId = m.opt("stepId") as? String ?: return null
            val index = (m.opt("index") as? Number)?.toInt() ?: return null
            val role = StepSegmentRole.fromWireOrNull(m.opt("role") as? String) ?: return null
            val startActiveSec = dbl(m, "startActiveSec") ?: return null
            val endActiveSec = dbl(m, "endActiveSec") ?: return null
            val startDistanceM = dbl(m, "startDistanceM") ?: return null
            val endDistanceM = dbl(m, "endDistanceM") ?: return null
            val targetKind = ResolvedTargetKind.fromWireOrNull(m.opt("targetKind") as? String) ?: return null
            val endedBy = StepEndedBy.fromWireOrNull(m.opt("endedBy") as? String) ?: return null
            val plannedEndRaw = m.opt("plannedEnd") as? JSONObject ?: return null
            val plannedEndType = ResolvedEndType.fromWireOrNull(plannedEndRaw.opt("type") as? String) ?: return null
            return ActivityStepSegmentWire(
                stepId = stepId,
                index = index,
                role = role,
                name = m.opt("name") as? String,
                startActiveSec = startActiveSec,
                endActiveSec = endActiveSec,
                startDistanceM = startDistanceM,
                endDistanceM = endDistanceM,
                avgPaceSecPerKm = dbl(m, "avgPaceSecPerKm"),
                avgHr = dbl(m, "avgHr"),
                avgCadenceSpm = dbl(m, "avgCadenceSpm"),
                avgPowerW = dbl(m, "avgPowerW"),
                targetKind = targetKind,
                targetLow = dbl(m, "targetLow"),
                targetHigh = dbl(m, "targetHigh"),
                targetZone = m.opt("targetZone") as? String,
                inRangePct = dbl(m, "inRangePct"),
                endedBy = endedBy,
                plannedEnd = StepSegmentPlannedEnd(plannedEndType, dbl(plannedEndRaw, "value")),
            )
        }

        private fun dbl(m: JSONObject, key: String): Double? = (m.opt(key) as? Number)?.toDouble()
    }
}

/**
 * `ActivityStepsRecord.toJson()` + `"source": "watch"` — il telefono lo
 * riceve come `stepSegments` in `routeSync`/`sessionSync`.
 * `profile` solo se non vuoto; `variant` solo se non null (`'reduced'`);
 * `plannedStepCount` SEMPRE (= fasi espanse davvero eseguite, D16).
 */
fun buildStepsRecordWire(
    planActivityId: String?,
    profile: JSONObject?,
    segments: List<ActivityStepSegmentWire>,
    variant: String?,
    plannedStepCount: Int,
): JSONObject {
    val out = JSONObject().put("v", 1).put("source", "watch")
    planActivityId?.let { out.put("planActivityId", it) }
    if (profile != null && profile.length() > 0) out.put("profile", profile)
    variant?.let { out.put("variant", it) }
    out.put("plannedStepCount", plannedStepCount)
    val arr = JSONArray()
    segments.forEach { arr.put(it.toWire()) }
    out.put("segments", arr)
    return out
}
