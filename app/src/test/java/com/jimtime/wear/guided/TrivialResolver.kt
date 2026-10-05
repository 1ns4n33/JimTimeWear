package com.jimtime.wear.guided

import org.json.JSONArray
import org.json.JSONObject

/**
 * F7 (WP-KE, D9) — SOLO test. Port di `TrivialResolver.swift`: risolve la
 * parte ASSOLUTA di `resolveSteps` del Dart (fine `time/distance/lap/
 * hrBelow.bpm`, target `none|pace|hr|rpe|cadence|power`). Le zone di
 * profilo restano `null` (il polso non calcola zone, D2), TRANNE
 * `paceZone.zone == "walk"` (H3): formula fissa ±10 % di
 * `walkPaceSecPerKm` (default 720), indipendente dal profilo reale.
 */
object TrivialResolver {

    fun resolveSteps(structureJson: JSONObject, profileOverride: JSONObject?): List<ResolvedStep>? {
        val rawSteps = structureJson.opt("steps") as? JSONArray ?: return null
        val out = mutableListOf<ResolvedStep>()
        for (k in 0 until rawSteps.length()) {
            val raw = rawSteps.opt(k) as? JSONObject ?: return null
            if (raw.has("repeat")) {
                val repeatCount = intValue(raw.opt("repeat")) ?: return null
                val blockId = raw.opt("id") as? String ?: return null
                val childrenRaw = raw.opt("steps") as? JSONArray ?: return null
                for (i in 1..maxOf(repeatCount, 1)) {
                    for (j in 0 until childrenRaw.length()) {
                        val childRaw = childrenRaw.opt(j) as? JSONObject ?: return null
                        val childId = childRaw.opt("id") as? String ?: return null
                        val child = resolveOne(
                            childRaw,
                            idOverride = "$childId#$i",
                            repeatIteration = i,
                            repeatCount = repeatCount,
                            blockId = blockId,
                            profileOverride = profileOverride,
                        ) ?: return null
                        out.add(child)
                    }
                }
            } else {
                out.add(resolveOne(raw, null, null, null, null, profileOverride) ?: return null)
            }
        }
        return out
    }

    /** = `PlanActivityStructure.expandedCount` (Dart): nessun tetto. */
    fun expandedCount(structureJson: JSONObject): Int {
        val rawSteps = structureJson.opt("steps") as? JSONArray ?: return 0
        var total = 0
        for (k in 0 until rawSteps.length()) {
            val raw = rawSteps.opt(k) as? JSONObject
            val repeatCount = intValue(raw?.opt("repeat"))
            val children = raw?.opt("steps") as? JSONArray
            total += if (repeatCount != null && children != null) repeatCount * children.length() else 1
        }
        return total
    }

    private fun resolveOne(
        raw: JSONObject,
        idOverride: String?,
        repeatIteration: Int?,
        repeatCount: Int?,
        blockId: String?,
        profileOverride: JSONObject?,
    ): ResolvedStep? {
        val id = raw.opt("id") as? String ?: return null
        val role = PlanStepRole.fromWireOrNull(raw.opt("role") as? String) ?: return null
        val endRaw = raw.opt("end") as? JSONObject ?: return null
        val targetRaw = raw.opt("target") as? JSONObject ?: return null
        val end = resolveEnd(endRaw) ?: return null
        val target = resolveTarget(targetRaw, profileOverride) ?: return null
        return ResolvedStep(
            id = idOverride ?: id,
            sourceId = id,
            role = role,
            name = raw.opt("name") as? String,
            cue = raw.opt("cue") as? String,
            end = end,
            target = target,
            repeatIteration = repeatIteration,
            repeatCount = repeatCount,
            blockId = blockId,
        )
    }

    private fun resolveEnd(raw: JSONObject): ResolvedEnd? = when (raw.opt("type") as? String) {
        "time" -> doubleValue(raw.opt("sec"))?.let { ResolvedEnd(ResolvedEndType.TIME, it) }
        "distance" -> doubleValue(raw.opt("m"))?.let { ResolvedEnd(ResolvedEndType.DISTANCE, it) }
        "lap" -> ResolvedEnd(ResolvedEndType.LAP, 0.0)
        // Solo `bpm` assoluto — `zone` richiede il resolver di profilo.
        "hrBelow" -> doubleValue(raw.opt("bpm"))?.let { ResolvedEnd(ResolvedEndType.HR_BELOW, it) }
        else -> null
    }

    private fun resolveTarget(raw: JSONObject, profileOverride: JSONObject?): ResolvedTarget? {
        fun band(kind: ResolvedTargetKind, lowKey: String, highKey: String): ResolvedTarget? {
            val low = doubleValue(raw.opt(lowKey)) ?: return null
            val high = doubleValue(raw.opt(highKey)) ?: return null
            return ResolvedTarget(kind, low, high)
        }
        return when (raw.opt("type") as? String) {
            "none" -> ResolvedTarget(ResolvedTargetKind.NONE, 0.0, 0.0)
            "pace" -> band(ResolvedTargetKind.PACE, "fastSecPerKm", "slowSecPerKm")
            "hr" -> band(ResolvedTargetKind.HR, "min", "max")
            "rpe" -> band(ResolvedTargetKind.RPE, "min", "max")
            "cadence" -> band(ResolvedTargetKind.CADENCE, "min", "max")
            "power" -> band(ResolvedTargetKind.POWER, "min", "max")
            "paceZone" -> {
                if (raw.opt("zone") as? String != "walk") return null
                val wp = doubleValue(profileOverride?.opt("walkPaceSecPerKm")) ?: 720.0
                ResolvedTarget(
                    ResolvedTargetKind.PACE,
                    dartRound(wp * 0.90).toDouble(),
                    dartRound(wp * 1.10).toDouble(),
                    zoneLabel = "walk",
                )
            }
            else -> null
        }
    }

    private fun doubleValue(any: Any?): Double? = (any as? Number)?.toDouble()
    private fun intValue(any: Any?): Int? = (any as? Number)?.toInt()
}
