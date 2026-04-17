package com.jimtime.wear.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jimtime.wear.data.MessagePaths
import com.jimtime.wear.data.PhoneConnector
import com.jimtime.wear.data.SessionRepository
import com.jimtime.wear.data.SessionState
import com.jimtime.wear.health.WearWorkoutManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SessionViewModel(application: Application) : AndroidViewModel(application) {

    private val workoutManager = WearWorkoutManager(application)
    private val phoneConnector = PhoneConnector(application)

    val sessionState: StateFlow<SessionState> = SessionRepository.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionState())

    val heartRate: StateFlow<Double> = workoutManager.heartRate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    private var timerJob: Job? = null

    init {
        viewModelScope.launch {
            sessionState.collect { state ->
                when {
                    state.isActive && !state.isPaused -> {
                        workoutManager.startMonitoring()
                        startTimer()
                    }
                    state.isPaused -> stopTimer()
                    !state.isActive -> {
                        stopTimer()
                        workoutManager.stopMonitoring()
                    }
                }
            }
        }
    }

    private fun startTimer() {
        if (timerJob?.isActive == true) return
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1_000)
                SessionRepository.tick()
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    fun stopFromWatch() {
        viewModelScope.launch {
            phoneConnector.sendToPhone(MessagePaths.CMD_STOP_FROM_WATCH)
            SessionRepository.stopSession()
        }
    }

    fun pauseFromWatch() {
        viewModelScope.launch {
            phoneConnector.sendToPhone(MessagePaths.CMD_PAUSE_FROM_WATCH)
            SessionRepository.pauseSession()
        }
    }

    fun resumeFromWatch() {
        viewModelScope.launch {
            phoneConnector.sendToPhone(MessagePaths.CMD_RESUME_FROM_WATCH)
            SessionRepository.resumeSession()
        }
    }

    override fun onCleared() {
        super.onCleared()
        workoutManager.stopMonitoring()
    }
}
