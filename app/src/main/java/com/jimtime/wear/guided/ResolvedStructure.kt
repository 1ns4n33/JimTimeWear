package com.jimtime.wear.guided

import org.json.JSONArray
import org.json.JSONObject

/*
 * F7 (Schede multisport: al polso, WP-KE, D7) — `ResolvedStructureWire`
 * ricevuta dal telefono: target già risolti in numeri (il polso NON calcola
 * MAI zone), blocchi `repeat` conservati fino all'espansione. Port di
 * `ResolvedStructure.swift`: parsing tollerante, voci malformate scartate,
 * mai un'eccezione.
 */

/** Una voce di primo livello: uno step singolo o un blocco ripetuto (mai annidato). */
sealed class ResolvedStructureEntry {
    data class Step(val step: ResolvedStep) : ResolvedStructureEntry()
    data class RepeatBlock(val id: String, val repeat: Int, val steps: List<ResolvedStep>) : ResolvedStructureEntry()
}

data class ResolvedStructure(val v: Int = 1, val entries: List<ResolvedStructureEntry>) {

    val isEmpty: Boolean get() = entries.isEmpty()

    /** Σ step di primo livello + Σ repeat×figli (nessun tetto: il tetto 60 vale solo in [expand]). */
    val expandedCount: Int
        get() = entries.sumOf { entry ->
            when (entry) {
                is ResolvedStructureEntry.Step -> 1
                is ResolvedStructureEntry.RepeatBlock -> entry.repeat * entry.steps.size
            }
        }

    /**
     * Espansione identica a `resolveSteps` del Dart: un blocco produce
     * `repeat × steps` step con `id = "$stepId#$i"` (1-based), `sourceId`,
     * `repeatIteration`, `repeatCount`, `blockId`. Oltre 60 step tronca.
     */
    fun expand(): List<ResolvedStep> {
        val out = mutableListOf<ResolvedStep>()
        for (entry in entries) {
            when (entry) {
                is ResolvedStructureEntry.Step -> out.add(entry.step)
                is ResolvedStructureEntry.RepeatBlock -> {
                    if (entry.repeat >= 1) {
                        for (i in 1..entry.repeat) {
                            for (child in entry.steps) {
                                out.add(
                                    child.copy(
                                        id = "${child.id}#$i",
                                        sourceId = child.id,
                                        repeatIteration = i,
                                        repeatCount = entry.repeat,
                                        blockId = entry.id,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            if (out.size >= MAX_EXPANDED_STEPS) break
        }
        return if (out.size > MAX_EXPANDED_STEPS) out.subList(0, MAX_EXPANDED_STEPS).toList() else out
    }

    /** Ricostruisce il wire (checkpoint e ogni punto che deve rispedire la struttura). */
    fun toWire(): JSONObject {
        val steps = JSONArray()
        for (entry in entries) {
            when (entry) {
                is ResolvedStructureEntry.Step -> steps.put(resolvedStepToWire(entry.step))
                is ResolvedStructureEntry.RepeatBlock -> {
                    val children = JSONArray()
                    entry.steps.forEach { children.put(resolvedStepToWire(it)) }
                    steps.put(
                        JSONObject()
                            .put("id", entry.id)
                            .put("repeat", entry.repeat)
                            .put("steps", children),
                    )
                }
            }
        }
        return JSONObject().put("v", v).put("steps", steps)
    }

    companion object {
        const val MAX_EXPANDED_STEPS = 60
        const val MIN_REPEAT = 2
        const val MAX_REPEAT = 50
        const val MAX_REPEAT_CHILDREN = 10

        /**
         * Parsing tollerante: voci malformate SCARTATE (ruolo/fine/`kind`
         * sconosciuti, `repeat` fuori da 2..50, blocchi con 0 o più di 10
         * figli validi); nessuna voce valida ⇒ `null`.
         */
        fun fromWire(o: JSONObject): ResolvedStructure? {
            val rawSteps = o.opt("steps") as? JSONArray ?: return null
            val entries = mutableListOf<ResolvedStructureEntry>()
            for (k in 0 until rawSteps.length()) {
                val raw = rawSteps.opt(k) as? JSONObject ?: continue
                if (raw.has("repeat")) {
                    val id = raw.opt("id") as? String ?: continue
                    val repeat = intValue(raw.opt("repeat")) ?: continue
                    if (repeat < MIN_REPEAT || repeat > MAX_REPEAT) continue
                    val rawChildren = raw.opt("steps") as? JSONArray ?: continue
                    val children = (0 until rawChildren.length()).mapNotNull { j ->
                        (rawChildren.opt(j) as? JSONObject)?.let(::resolvedStepFromWire)
                    }
                    if (children.isEmpty() || children.size > MAX_REPEAT_CHILDREN) continue
                    entries.add(ResolvedStructureEntry.RepeatBlock(id, repeat, children))
                } else {
                    resolvedStepFromWire(raw)?.let { entries.add(ResolvedStructureEntry.Step(it)) }
                }
            }
            if (entries.isEmpty()) return null
            return ResolvedStructure(intValue(o.opt("v")) ?: 1, entries)
        }

        private fun resolvedStepFromWire(m: JSONObject): ResolvedStep? {
            val id = m.opt("id") as? String ?: return null
            val role = PlanStepRole.fromWireOrNull(m.opt("role") as? String) ?: return null
            val end = (m.opt("end") as? JSONObject)?.let(::resolvedEndFromWire) ?: return null
            val target = (m.opt("target") as? JSONObject)?.let(::resolvedTargetFromWire) ?: return null

            var ui: StepUi? = null
            val uiRaw = m.opt("ui") as? JSONObject
            if (uiRaw != null) {
                val label = uiRaw.opt("label") as? String
                val spoken = uiRaw.opt("spoken") as? String
                val uiTarget = uiRaw.opt("target") as? String
                if (label != null && spoken != null && uiTarget != null) {
                    ui = StepUi(label, spoken, uiTarget)
                }
            }

            return ResolvedStep(
                id = id,
                sourceId = m.opt("sourceId") as? String ?: id,
                role = role,
                name = m.opt("name") as? String,
                cue = m.opt("cue") as? String,
                end = end,
                target = target,
                repeatIteration = intValue(m.opt("repeatIteration")),
                repeatCount = intValue(m.opt("repeatCount")),
                blockId = m.opt("blockId") as? String,
                ui = ui,
            )
        }

        private fun resolvedStepToWire(step: ResolvedStep): JSONObject {
            val m = JSONObject()
                .put("id", step.id)
                .put("sourceId", step.sourceId)
                .put("role", step.role.wire)
                .put("end", JSONObject().put("type", step.end.type.wire).put("value", step.end.value))
                .put("target", resolvedTargetToWire(step.target))
            step.name?.let { m.put("name", it) }
            step.cue?.let { m.put("cue", it) }
            step.repeatIteration?.let { m.put("repeatIteration", it) }
            step.repeatCount?.let { m.put("repeatCount", it) }
            step.blockId?.let { m.put("blockId", it) }
            step.ui?.let {
                m.put("ui", JSONObject().put("label", it.label).put("spoken", it.spoken).put("target", it.target))
            }
            return m
        }

        private fun resolvedEndFromWire(m: JSONObject): ResolvedEnd? {
            val type = ResolvedEndType.fromWireOrNull(m.opt("type") as? String) ?: return null
            return ResolvedEnd(type, doubleValue(m.opt("value")) ?: 0.0)
        }

        private fun resolvedTargetFromWire(m: JSONObject): ResolvedTarget? {
            val kind = ResolvedTargetKind.fromWireOrNull(m.opt("kind") as? String) ?: return null
            return ResolvedTarget(
                kind = kind,
                low = doubleValue(m.opt("low")) ?: 0.0,
                high = doubleValue(m.opt("high")) ?: 0.0,
                zoneLabel = m.opt("zoneLabel") as? String,
                // Passthrough: decodificato se presente, mai valutato dal motore.
                inclinePct = doubleValue(m.opt("inclinePct")),
            )
        }

        private fun resolvedTargetToWire(t: ResolvedTarget): JSONObject {
            val m = JSONObject().put("kind", t.kind.wire).put("low", t.low).put("high", t.high)
            t.zoneLabel?.let { m.put("zoneLabel", it) }
            t.inclinePct?.let { m.put("inclinePct", it) }
            return m
        }

        // Helper numerici tolleranti (Integer/Long/Double del wire → tipo voluto).
        internal fun doubleValue(any: Any?): Double? = (any as? Number)?.toDouble()
        internal fun intValue(any: Any?): Int? = (any as? Number)?.toInt()
    }
}
