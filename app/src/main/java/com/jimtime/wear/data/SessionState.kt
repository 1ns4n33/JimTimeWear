package com.jimtime.wear.data

/// Discriminator on the wire — kind == WORKOUT routes the watch UI to
/// `WorkoutSessionScreen`; otherwise the existing outdoor/indoor flow
/// renders via `SessionScreen`.
enum class SessionKind { ACTIVITY, WORKOUT }

/// Cursor inside the active workout plan (kind == WORKOUT only).
/// Indices are 0-based on the wire; UI converts to 1-based labels.
data class WorkoutCursor(
    val groupIndex: Int = 0,
    val roundIndex: Int = 0,
    val exerciseIndex: Int = 0,
    val totalGroups: Int = 1,
    val totalRoundsInGroup: Int = 1,
)

/// What the athlete should do on the next "Fatto" tap. All numeric
/// fields are nullable — null = "not applicable for this slot".
data class WorkoutTarget(
    val exerciseName: String = "",
    val reps: Int? = null,
    val weight: Double? = null,
    val durationSeconds: Int? = null,
    val restSeconds: Int? = null,
)

/// Workout-session payload pushed from the phone. Held inside
/// [SessionState.workout] when kind == WORKOUT.
data class WorkoutContext(
    val planName: String = "",
    val weekLabel: String = "",
    val dayLabel: String = "",
    val cursor: WorkoutCursor = WorkoutCursor(),
    val target: WorkoutTarget = WorkoutTarget(),
    /// Wall-clock millis at which the current rest interval ends. The
    /// watch derives the countdown locally from this — no per-second
    /// tick from the phone.
    val restEndAtMs: Long? = null,
    val completedExercises: Int = 0,
)

/// F5b (Nuoto, D2/D12) — aggregati correnti di una sessione swim, popolati
/// da [com.jimtime.wear.health.HealthServicesExerciseSource] via
/// `SessionRepository.updateSwimMetrics`. `location`/`poolLengthM` sono
/// fissati all'avvio (SwimStartScreen) e non cambiano più durante la
/// sessione.
data class SwimData(
    val location: String = "POOL", // "POOL" | "OPEN_WATER" — mirror SwimSessionDto
    val poolLengthM: Double? = null,
    val distanceMeters: Double = 0.0,
    val laps: Int = 0,
    val strokes: Int = 0,
    /// F5b (§7) — allenamento del coach seguito in questa sessione, se
    /// scelto su SwimStartScreen o arrivato via `startSession.swim.workout`
    /// dal telefono. `null` = nessuna guida, SwimSessionScreen mostra solo
    /// gli aggregati.
    val workout: SwimWorkoutWire? = null,
)

/// WP4 (Arti marziali M0, N9) — `startSession.combat` dal telefono: serve al
/// titolo prima del primo mirror `guidedStep`.
data class CombatMeta(
    val discipline: String,
    val rounds: Int,
    val workSec: Int,
    val restSec: Int,
    /// M2 — `true` per una scheda a blocchi (workSec/restSec = 0: niente
    /// estrapolazione locale delle fasi). Assente = seduta libera.
    val plan: Boolean = false,
) {
    companion object {
        /// Rete di sicurezza: un mirror `kind:"combat"` senza `startSession.combat`
        /// (telefono che lo scartava) → meta minimo; workSec/restSec ignoti = 0
        /// (il fallback locale delle fasi allora non avanza da solo).
        fun fromMirror(step: GuidedStepMirror, activityType: String): CombatMeta? {
            if (!step.isCombat || step.roundTotal <= 0) return null
            return CombatMeta(discipline = activityType, rounds = step.roundTotal, workSec = 0, restSec = 0)
        }

        /// `null` se il payload non ha `combat` (build vecchie del telefono).
        fun fromWire(o: org.json.JSONObject?): CombatMeta? {
            if (o == null) return null
            val discipline = o.optString("discipline")
            if (discipline.isEmpty()) return null
            return CombatMeta(
                discipline = discipline,
                rounds = o.optInt("rounds", 0),
                workSec = o.optInt("workSec", 0),
                restSec = o.optInt("restSec", 0),
                plan = o.optBoolean("plan", false),
            )
        }
    }
}

/// F7 (D24, F2 §2.4) — fase corrente di una corsa guidata DAL TELEFONO
/// (companion F1), spinta con `guidedStep` ogni ~5 s e al cambio fase.
/// [receivedAtMs] = orologio del polso alla ricezione: la striscia vale
/// [VALID_MS] senza aggiornamenti, poi la UI la nasconde ([isFresh]).
data class GuidedStepMirror(
    val index: Int,
    val total: Int,
    val label: String,
    val remaining: String,
    val target: String,
    val next: String?,
    val endsAt: Long?,
    val finished: Boolean,
    val receivedAtMs: Long,
    // WP4 (Arti marziali M0, N9) — tutti opzionali: assenti = mirror F1 di prima.
    /// `"combat"` per i round; `null` per le altre guide del telefono.
    val kind: String? = null,
    /// `"prep" | "work" | "rest"` (solo kind combat); M2: `"timed"` (blocco a
    /// tempo senza round) e `"reps"` (blocco a ripetizioni, chiuso da «Fatto»).
    val phase: String? = null,
    /// 1-based, 0 durante `prep`.
    val roundIndex: Int = 0,
    val roundTotal: Int = 0,
    val roundType: String? = null,
    val paused: Boolean = false,
    /// M2 — secondi pianificati dello step (0 = ignoto / `reps`).
    val phaseSec: Int = 0,
    /// M2 — ripetizioni del blocco (0 = non a ripetizioni).
    val reps: Int = 0,
) {
    fun isFresh(nowMs: Long): Boolean = nowMs - receivedAtMs < VALID_MS

    val isCombat: Boolean get() = kind == "combat"

    companion object {
        const val VALID_MS = 60_000L

        /// Parser unico del mirror `guidedStep` (PhoneMessageService); i campi
        /// combat mancanti restano ai default ⇒ payload F1 invariato.
        fun fromWire(json: org.json.JSONObject, receivedAtMs: Long): GuidedStepMirror =
            GuidedStepMirror(
                index = json.optInt("index", 0),
                total = json.optInt("total", 0),
                label = json.optString("label"),
                remaining = json.optString("remaining"),
                target = json.optString("target"),
                next = (json.opt("next") as? String)?.takeIf { it.isNotEmpty() },
                endsAt = (json.opt("endsAt") as? Number)?.toLong(),
                finished = json.optBoolean("finished", false),
                receivedAtMs = receivedAtMs,
                kind = (json.opt("kind") as? String)?.takeIf { it.isNotEmpty() },
                phase = (json.opt("phase") as? String)?.takeIf { it.isNotEmpty() },
                roundIndex = json.optInt("roundIndex", 0),
                roundTotal = json.optInt("roundTotal", 0),
                roundType = (json.opt("roundType") as? String)?.takeIf { it.isNotEmpty() },
                paused = json.optBoolean("paused", false),
                phaseSec = json.optInt("phaseSec", 0).coerceAtLeast(0),
                reps = json.optInt("reps", 0).coerceAtLeast(0),
            )
    }
}

data class SessionState(
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val isStandalone: Boolean = false,
    val activityType: String = "run",
    val startedAt: Long = 0L,
    val elapsedSeconds: Long = 0L,
    val distanceMeters: Double = 0.0,
    // ── Workout extension ───────────────────────────────────────────────
    val kind: SessionKind = SessionKind.ACTIVITY,
    val workout: WorkoutContext? = null,
    // ── F5b Swim extension ───────────────────────────────────────────────
    val swim: SwimData? = null,
    // ── F7 Schede multisport al polso ────────────────────────────────────
    /// `true` = sessione standalone con il motore a fasi armato
    /// (GuidedRunRepository): la rotta `session` mostra GuidedSessionScreen.
    val guided: Boolean = false,
    /// `plan` del wire (PlanActivityRef.toUploadJson) se la sessione nasce
    /// da un giorno di piano, guidata o no — rispedito in routeSync.
    val planRefJson: String? = null,
    /// `'reduced'` per la ridotta proposta dal telefono (D16), altrimenti null.
    val guidedVariant: String? = null,
    /// Companion (D24): ultima fase spinta dal telefono, null se nessuna.
    val guidedStep: GuidedStepMirror? = null,
    // ── WP4 Arti marziali M0 ─────────────────────────────────────────────
    /// Sessione a round guidata dal telefono (`startSession.combat`).
    val combat: CombatMeta? = null,
) {
    val paceSecondsPerKm: Double
        get() = if (distanceMeters > 100 && elapsedSeconds > 0)
            elapsedSeconds / (distanceMeters / 1000.0) else 0.0

    fun formattedElapsed(): String {
        val h = elapsedSeconds / 3600
        val m = (elapsedSeconds % 3600) / 60
        val s = elapsedSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%02d:%02d".format(m, s)
    }

    fun formattedDistance(): String {
        val km = distanceMeters / 1000.0
        return if (km >= 10) "%.1f km".format(km) else "%.2f km".format(km)
    }

    fun formattedPace(): String {
        if (paceSecondsPerKm <= 0) return "--:--"
        val m = (paceSecondsPerKm / 60).toInt()
        val s = (paceSecondsPerKm % 60).toInt()
        return "%d:%02d/km".format(m, s)
    }

    /// F0: emoji dal catalogo sport unico (`data.SportsCatalog`, generato da
    /// tool/sports_catalog.json nel repo Flutter) — prima una quarta copia a
    /// mano dello stesso elenco. Nota: "skate" era 🛼 qui contro ⛸ nel
    /// catalogo/telefono/Apple Watch — allineato a ⛸ (l'emoji condivisa).
    fun activityIcon(): String = SportsCatalog.emoji(activityType)

    fun isIndoor(): Boolean = SportsCatalog.isIndoor(activityType)

    fun isWorkout(): Boolean = kind == SessionKind.WORKOUT

    fun isSwim(): Boolean = activityType == "swim_pool" || activityType == "swim_open_water"

    fun formattedSwimDistance(): String {
        val m = swim?.distanceMeters ?: 0.0
        return if (m >= 1000) "%.2f km".format(m / 1000.0) else "%.0f m".format(m)
    }
}
