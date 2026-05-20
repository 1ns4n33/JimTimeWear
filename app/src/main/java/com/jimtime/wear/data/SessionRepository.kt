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
    ) {
        val ctx = _state.value.workout ?: return
        _state.value = _state.value.copy(
            workout = ctx.copy(
                cursor = cursor,
                target = target,
                completedExercises = completedExercises,
                // New cursor = new exercise/set, so wipe any stale rest
                // countdown that was sitting around.
                restEndAtMs = null,
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
