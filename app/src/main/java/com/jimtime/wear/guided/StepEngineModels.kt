package com.jimtime.wear.guided

import org.json.JSONObject

/*
 * F7 (Schede multisport: al polso, WP-KE, D7) — port letterale dei tipi di
 * `FE/lib/modules/session/guided/step_engine.dart` (Dart, verità del motore
 * passi), con `StepEngineModels.swift` come riferimento strutturale.
 * Kotlin puro: nessun import Android; `org.json` solo in `toJson`/`fromJson`.
 *
 * Chiavi JSON IDENTICHE al Dart; `Long` per i millisecondi, `Double` per
 * metri, passo e medie.
 */

// ── Arrotondamento ───────────────────────────────────────────────────────

/**
 * `num.round()` del Dart: metà LONTANO DA ZERO (2.5 → 3, -2.5 → -3).
 * `Math.round` di Java/Kotlin arrotonda la metà verso +∞ (-2.5 → -2) e
 * `kotlin.math.round` alla pari (2.5 → 2): nessuno dei due va bene qui.
 */
fun dartRound(x: Double): Long {
    val a = Math.abs(x)
    val f = Math.floor(a)
    val r = if (a - f >= 0.5) f + 1.0 else f
    return (if (x < 0) -r else r).toLong()
}

// ── Fine/obiettivo risolti ───────────────────────────────────────────────

enum class ResolvedEndType(val wire: String) {
    TIME("time"), DISTANCE("distance"), LAP("lap"), HR_BELOW("hrBelow");

    companion object {
        /** Stretto (come Swift `rawValue`): sconosciuto ⇒ `null`. */
        fun fromWireOrNull(raw: String?): ResolvedEndType? = entries.firstOrNull { it.wire == raw }

        /** = Dart `resolvedEndTypeFromJson`: sconosciuto ⇒ `LAP`. */
        fun fromJson(raw: String?): ResolvedEndType = fromWireOrNull(raw) ?: LAP
    }
}

enum class ResolvedTargetKind(val wire: String) {
    PACE("pace"), HR("hr"), RPE("rpe"), CADENCE("cadence"), NONE("none"), POWER("power");

    companion object {
        /** Stretto (come Swift `rawValue`): sconosciuto ⇒ `null`. */
        fun fromWireOrNull(raw: String?): ResolvedTargetKind? = entries.firstOrNull { it.wire == raw }

        /** = Dart `resolvedTargetKindFromJson`: sconosciuto ⇒ `NONE`. */
        fun fromJson(raw: String?): ResolvedTargetKind = fromWireOrNull(raw) ?: NONE
    }
}

/** Vocabolario chiuso dei ruoli (= `PlanStepRole` del Dart). */
enum class PlanStepRole(val wire: String) {
    WARMUP("warmup"), WORK("work"), RECOVERY("recovery"), REST("rest"), COOLDOWN("cooldown");

    companion object {
        fun fromWireOrNull(raw: String?): PlanStepRole? = entries.firstOrNull { it.wire == raw }
    }
}

/** Fine risolta — `value` = sec | m | bpm secondo [type] (`0` per `lap`). */
data class ResolvedEnd(val type: ResolvedEndType, val value: Double)

/**
 * Obiettivo risolto — pace: `low` = veloce (sec/km), `high` = lento (sec/km);
 * power: banda in Watt; altri: banda diretta. [inclinePct] è solo
 * passthrough (tapis roulant), mai valutato dal motore.
 */
data class ResolvedTarget(
    val kind: ResolvedTargetKind,
    val low: Double,
    val high: Double,
    val zoneLabel: String? = null,
    val inclinePct: Double? = null,
)

/** Testi già composti sul telefono (template `{i}`/`{n}`), presenti sul wire. */
data class StepUi(val label: String, val spoken: String, val target: String)

/** Uno step risolto — target/fine in numeri, pronto per il motore. */
data class ResolvedStep(
    /** Id univoco dello step ESPANSO (`${sourceId}#$iterazione` dentro una ripetuta). */
    val id: String,
    /** Id dello step SORGENTE nella struttura. */
    val sourceId: String,
    val role: PlanStepRole,
    val name: String? = null,
    val cue: String? = null,
    val end: ResolvedEnd,
    val target: ResolvedTarget,
    val repeatIteration: Int? = null,
    val repeatCount: Int? = null,
    val blockId: String? = null,
    /** Assente nei vettori (motore puro); presente dal wire del telefono. */
    val ui: StepUi? = null,
) {
    /** Etichetta per lo schermo, `{i}`/`{n}` sostituiti; senza `ui` ricade sul ruolo. */
    fun uiLabel(): String = ui?.let { substitutePlaceholders(it.label) } ?: role.wire

    /** Frase per la voce, stessa sostituzione di [uiLabel]. */
    fun uiSpoken(): String = ui?.let { substitutePlaceholders(it.spoken) } ?: role.wire

    private fun substitutePlaceholders(text: String): String {
        var result = text
        repeatIteration?.let { result = result.replace("{i}", it.toString()) }
        repeatCount?.let { result = result.replace("{n}", it.toString()) }
        return result
    }
}

// ── Campione / stato / eventi ────────────────────────────────────────────

/**
 * Campione passato a [StepEngine.advance] — `activeMs`/`distanceM` sono
 * cumulativi (già al netto delle pause); `lap` è un impulso one-shot
 * («Fase successiva»).
 */
data class StepSample(
    val activeMs: Long,
    val distanceM: Double,
    val hr: Double? = null,
    val cadenceSpm: Double? = null,
    val powerW: Double? = null,
    val paused: Boolean = false,
    val lap: Boolean = false,
)

/** Stato del motore — 18 campi, stesse chiavi JSON del Dart. */
data class StepEngineState(
    /** `true` dopo la primissima chiamata ad `advance` (anche se in pausa). */
    val started: Boolean = true,
    /** == `steps.size` ⇒ sessione strutturata finita (corsa libera). */
    val index: Int,
    val stepStartActiveMs: Long,
    val stepStartDistanceM: Double,
    val hrSum: Double,
    val hrCount: Int,
    val cadSum: Double,
    val cadCount: Int,
    val powerSum: Double = 0.0,
    val powerCount: Int = 0,
    val inBandCount: Int,
    val evalCount: Int,
    val offTarget: Boolean,
    val lastOffTargetCueMs: Long?,
    val halfwayFired: Boolean,
    val lastPartFired: Boolean,
    val lastActiveMs: Long,
    val lastDistanceM: Double,
) {
    /** = Dart `toJson` (`lastOffTargetCueMs` solo se non null). */
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("started", started)
        o.put("index", index)
        o.put("stepStartActiveMs", stepStartActiveMs)
        o.put("stepStartDistanceM", stepStartDistanceM)
        o.put("hrSum", hrSum)
        o.put("hrCount", hrCount)
        o.put("cadSum", cadSum)
        o.put("cadCount", cadCount)
        o.put("powerSum", powerSum)
        o.put("powerCount", powerCount)
        o.put("inBandCount", inBandCount)
        o.put("evalCount", evalCount)
        o.put("offTarget", offTarget)
        if (lastOffTargetCueMs != null) o.put("lastOffTargetCueMs", lastOffTargetCueMs)
        o.put("halfwayFired", halfwayFired)
        o.put("lastPartFired", lastPartFired)
        o.put("lastActiveMs", lastActiveMs)
        o.put("lastDistanceM", lastDistanceM)
        return o
    }

    companion object {
        val INITIAL = StepEngineState(
            started = false,
            index = 0,
            stepStartActiveMs = 0L,
            stepStartDistanceM = 0.0,
            hrSum = 0.0,
            hrCount = 0,
            cadSum = 0.0,
            cadCount = 0,
            powerSum = 0.0,
            powerCount = 0,
            inBandCount = 0,
            evalCount = 0,
            offTarget = false,
            lastOffTargetCueMs = null,
            halfwayFired = false,
            lastPartFired = false,
            lastActiveMs = 0L,
            lastDistanceM = 0.0,
        )

        /**
         * = Dart `fromJson`, tollerante: chiavi mancanti ⇒ default del
         * costruttore (`started` default `true`, a differenza di [INITIAL]).
         */
        fun fromJson(o: JSONObject): StepEngineState = StepEngineState(
            started = o.opt("started") as? Boolean ?: true,
            index = num(o, "index")?.toInt() ?: 0,
            stepStartActiveMs = num(o, "stepStartActiveMs")?.toLong() ?: 0L,
            stepStartDistanceM = num(o, "stepStartDistanceM")?.toDouble() ?: 0.0,
            hrSum = num(o, "hrSum")?.toDouble() ?: 0.0,
            hrCount = num(o, "hrCount")?.toInt() ?: 0,
            cadSum = num(o, "cadSum")?.toDouble() ?: 0.0,
            cadCount = num(o, "cadCount")?.toInt() ?: 0,
            powerSum = num(o, "powerSum")?.toDouble() ?: 0.0,
            powerCount = num(o, "powerCount")?.toInt() ?: 0,
            inBandCount = num(o, "inBandCount")?.toInt() ?: 0,
            evalCount = num(o, "evalCount")?.toInt() ?: 0,
            offTarget = o.opt("offTarget") == true,
            lastOffTargetCueMs = num(o, "lastOffTargetCueMs")?.toLong(),
            halfwayFired = o.opt("halfwayFired") == true,
            lastPartFired = o.opt("lastPartFired") == true,
            lastActiveMs = num(o, "lastActiveMs")?.toLong() ?: 0L,
            lastDistanceM = num(o, "lastDistanceM")?.toDouble() ?: 0.0,
        )

        private fun num(o: JSONObject, key: String): Number? = o.opt(key) as? Number
    }
}

/** Nomi = Dart `OffTargetDirection.name`. */
enum class OffTargetDirection(val wire: String) {
    TOO_FAST("tooFast"), TOO_SLOW("tooSlow"),
    HR_HIGH("hrHigh"), HR_LOW("hrLow"),
    CADENCE_HIGH("cadenceHigh"), CADENCE_LOW("cadenceLow"),
    POWER_HIGH("powerHigh"), POWER_LOW("powerLow"),
}

/** Nomi = Dart `StepEndedBy.name`. */
enum class StepEndedBy(val wire: String) {
    AUTO("auto"), LAP("lap"), HR_BELOW("hrBelow"), FINISH("finish");

    companion object {
        fun fromWireOrNull(raw: String?): StepEndedBy? = entries.firstOrNull { it.wire == raw }
    }
}

data class StepSegmentStats(
    val startActiveMs: Long,
    val endActiveMs: Long,
    val startDistanceM: Double,
    val endDistanceM: Double,
    val avgPaceSecPerKm: Double? = null,
    val avgHr: Double? = null,
    val avgCadence: Double? = null,
    /** Media potenza dello step (W); `null` se nessun campione con `powerW`. */
    val avgPowerW: Double? = null,
    val inRangePct: Double? = null,
)

/** Eventi emessi da [StepEngine.advance] — stessa semantica del Dart. */
sealed class StepEvent {
    abstract val stepIndex: Int

    data class StepStart(override val stepIndex: Int) : StepEvent()
    data class StepHalfway(override val stepIndex: Int) : StepEvent()
    data class StepLastPart(override val stepIndex: Int, val seconds: Int? = null, val meters: Int? = null) : StepEvent()
    data class StepOffTarget(override val stepIndex: Int, val direction: OffTargetDirection, val avg: Double) : StepEvent()
    data class StepBackOnTarget(override val stepIndex: Int) : StepEvent()
    data class StepEnd(override val stepIndex: Int, val endedBy: StepEndedBy, val stats: StepSegmentStats) : StepEvent()
    data class WorkoutEnd(override val stepIndex: Int) : StepEvent()
}

// ── Progresso (per l'UI) ─────────────────────────────────────────────────

data class StepProgress(
    val remainingSec: Double? = null,
    val remainingM: Double? = null,
    val fraction: Double,
    val currentAvgPace: Double? = null,
    val currentAvgHr: Double? = null,
    val currentAvgPower: Double? = null,
    val inBand: Boolean? = null,
)
