package com.jimtime.wear.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionRepository {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    fun startSession(type: String, startedAt: Long) {
        _state.value = SessionState(
            isActive = true,
            activityType = type,
            startedAt = startedAt,
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

    fun tick() {
        val current = _state.value
        if (current.isActive && !current.isPaused) {
            _state.value = current.copy(elapsedSeconds = current.elapsedSeconds + 1)
        }
    }
}
