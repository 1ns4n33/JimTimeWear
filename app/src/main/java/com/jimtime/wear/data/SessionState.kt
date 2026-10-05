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
) {
    fun isFresh(nowMs: Long): Boolean = nowMs - receivedAtMs < VALID_MS

    companion object {
        const val VALID_MS = 60_000L
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
