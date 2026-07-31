package com.jimtime.wear.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionRepository {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    fun startSession(type: String, startedAt: Long) {
        val alreadyElapsed = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
        _state.value = SessionState(
            isActive       = true,
            activityType   = type,
            startedAt      = startedAt,
            elapsedSeconds = alreadyElapsed,
            isStandalone   = false,
            kind           = SessionKind.ACTIVITY,
            workout        = null,
        )
    }

    fun startStandaloneSession(type: String) {
        _state.value = SessionState(
            isActive     = true,
            activityType = type,
            startedAt    = System.currentTimeMillis(),
            isStandalone = true,
            kind         = SessionKind.ACTIVITY,
            workout      = null,
        )
    }

    fun stopSession() {
        _state.value = SessionState()
    }

    fun pauseSession() {
        _state.value = _state.value.copy(isPaused = true)
    }

    fun resumeSession() {
        _state.value = _state.value.copy(isPaused = false)
    }

    fun updateDistance(meters: Double) {
        _state.value = _state.value.copy(distanceMeters = meters)
    }

    fun tick() {
        val current = _state.value
        if (current.isActive && !current.isPaused) {
            _state.value = current.copy(elapsedSeconds = current.elapsedSeconds + 1)
        }
    }

    /// Crash recovery: ricostruisce una sessione ACTIVITY standalone da un
    /// checkpoint dopo che il processo (e quindi questo intero singleton)
    /// è stato ucciso e ricreato. elapsedSeconds è approssimato dal
    /// wall-clock (now - startedAt) perché il contatore in-memory che lo
    /// teneva preciso attraverso le pause è andato perso col processo —
    /// non torna esatto se c'erano state pause, ma è la sola sorgente di
    /// verità sopravvissuta al kill.
    fun restoreStandaloneSession(activityType: String, startedAt: Long, isPaused: Boolean) {
        val elapsed = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
        _state.value = SessionState(
            isActive       = true,
            isPaused       = isPaused,
            activityType   = activityType,
            startedAt      = startedAt,
            elapsedSeconds = elapsed,
            isStandalone   = true,
            kind           = SessionKind.ACTIVITY,
            workout        = null,
        )
    }

    // ── Workout protocol ─────────────────────────────────────────────────

    fun startWorkoutSession(
        startedAt: Long,
        context: WorkoutContext,
    ) {
        val alreadyElapsed = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
        _state.value = SessionState(
            isActive       = true,
            activityType   = "workout",
            startedAt      = startedAt,
            elapsedSeconds = alreadyElapsed,
            isStandalone   = false,
            kind           = SessionKind.WORKOUT,
            workout        = context,
        )
    }

    fun updateWorkoutCursor(
        cursor: WorkoutCursor,
        target: WorkoutTarget,
        completedExercises: Int,
        restEndAtMs: Long? = null,
    ) {
        val ctx = _state.value.workout ?: return
        _state.value = _state.value.copy(
            workout = ctx.copy(
                cursor = cursor,
                target = target,
                completedExercises = completedExercises,
                // Rest state rides along with the cursor: null = no rest
                // in progress, which also wipes any stale countdown.
                restEndAtMs = restEndAtMs,
            ),
        )
    }

    fun startWorkoutRest(restSeconds: Int, restStartedAtMs: Long) {
        val ctx = _state.value.workout ?: return
        _state.value = _state.value.copy(
            workout = ctx.copy(restEndAtMs = restStartedAtMs + restSeconds * 1000L),
        )
    }

    fun clearWorkoutRest() {
        val ctx = _state.value.workout ?: return
        _state.value = _state.value.copy(workout = ctx.copy(restEndAtMs = null))
    }
}
