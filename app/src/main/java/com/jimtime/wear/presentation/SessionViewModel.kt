package com.jimtime.wear.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jimtime.wear.data.GpsPoint
import com.jimtime.wear.data.MessagePaths
import com.jimtime.wear.data.PendingRouteStore
import com.jimtime.wear.data.PhoneConnector
import com.jimtime.wear.data.PlanDay
import com.jimtime.wear.data.PlanDaysStore
import com.jimtime.wear.data.SessionRepository
import com.jimtime.wear.data.SessionState
import com.jimtime.wear.health.GpsTracker
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
    private val gpsTracker     = GpsTracker(application)

    val sessionState: StateFlow<SessionState> = SessionRepository.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionState())

    val heartRate: StateFlow<Double> = workoutManager.heartRate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    val planDays: StateFlow<List<PlanDay>> = PlanDaysStore.days
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val planName: StateFlow<String> = PlanDaysStore.planName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private var timerJob: Job? = null
    private var hrSendJob: Job? = null
    private var retryJob: Job? = null

    init {
        PlanDaysStore.load(application)
        // Wear messages aren't cached phone-side: ask for a fresh plan
        // list in case a push happened while we were disconnected.
        viewModelScope.launch {
            phoneConnector.sendToPhone(MessagePaths.CMD_REQUEST_PLAN_DAYS)
        }
        startPendingRouteRetry()
        viewModelScope.launch {
            sessionState.collect { state ->
                when {
                    state.isActive && !state.isPaused -> {
                        workoutManager.startMonitoring()
                        startTimer()
                        startHrSender()
                    }
                    state.isPaused -> {
                        stopTimer()
                        gpsTracker.stop()
                    }
                    !state.isActive -> {
                        stopTimer()
                        stopHrSender()
                        workoutManager.stopMonitoring()
                        gpsTracker.stop()
                    }
                }
            }
        }

        // Keep distance in sync with GPS tracker
        viewModelScope.launch {
            gpsTracker.points.collect { points ->
                val meters = computeDistance(points)
                SessionRepository.updateDistance(meters)
            }
        }
    }

    // ── Public actions ────────────────────────────────────────────────────────

    fun startFromWatch(activityType: String) {
        viewModelScope.launch {
            workoutManager.resetHrAccumulation()
            val isOutdoor = activityType in listOf("run", "walk", "bike")
            val reachable = phoneConnector.isPhoneReachable()
            if (reachable) {
                // Phone leads GPS — just notify it.
                val now = System.currentTimeMillis()
                SessionRepository.startSession(activityType, now)
                phoneConnector.sendToPhone(
                    buildStartCmd(activityType, now)
                )
            } else {
                // Standalone: Watch owns GPS.
                SessionRepository.startStandaloneSession(activityType)
                if (isOutdoor) gpsTracker.start()
            }
        }
    }

    fun stopFromWatch() {
        viewModelScope.launch {
            val state = SessionRepository.state.value
            if (state.isStandalone) {
                gpsTracker.stop()
                val points   = gpsTracker.points.value
                val endedAt  = System.currentTimeMillis()
                // Capture BEFORE stopSession(): the state collector will
                // stop the HR sensor when the session goes inactive.
                val avgHr = workoutManager.hrAverage
                val maxHr = workoutManager.hrMaxOrNull
                SessionRepository.stopSession()
                // Try to sync immediately; if phone unreachable, persist
                // and let the retry loop deliver it at reconnection.
                val sent = trySendRoute(
                    points, state.activityType, state.startedAt, endedAt, avgHr, maxHr,
                )
                if (!sent) {
                    PendingRouteStore.save(
                        getApplication(),
                        PendingRouteStore.PendingRoute(
                            points       = points,
                            activityType = state.activityType,
                            startedAt    = state.startedAt,
                            endedAt      = endedAt,
                            avgHr        = avgHr,
                            maxHr        = maxHr,
                        ),
                    )
                    startPendingRouteRetry()
                }
            } else {
                phoneConnector.sendToPhone(MessagePaths.CMD_STOP_FROM_WATCH)
                SessionRepository.stopSession()
            }
        }
    }

    fun pauseFromWatch() {
        viewModelScope.launch {
            val state = SessionRepository.state.value
            if (state.isStandalone) {
                gpsTracker.stop()
                SessionRepository.pauseSession()
            } else {
                phoneConnector.sendToPhone(MessagePaths.CMD_PAUSE_FROM_WATCH)
                SessionRepository.pauseSession()
            }
        }
    }

    fun resumeFromWatch() {
        viewModelScope.launch {
            val state = SessionRepository.state.value
            if (state.isStandalone && state.activityType in listOf("run", "walk", "bike")) {
                gpsTracker.start()
                SessionRepository.resumeSession()
            } else {
                phoneConnector.sendToPhone(MessagePaths.CMD_RESUME_FROM_WATCH)
                SessionRepository.resumeSession()
            }
        }
    }

    // ── Workout actions (kind == WORKOUT) ─────────────────────────────────

    /// "Fatto" tap on the watch. Reps/weight are sent only when the
    /// athlete adjusted them — null = use the plan target, which the
    /// phone-side action falls back to. The current cursor is echoed
    /// so the phone can DROP a tap that raced a group jump.
    fun completeSetFromWatch(reps: Int?, weight: Double?) {
        viewModelScope.launch {
            val cursor = SessionRepository.state.value.workout?.cursor
            phoneConnector.sendToPhone(
                MessagePaths.CMD_COMPLETE_SET,
                mapOf(
                    MessagePaths.KEY_KIND to MessagePaths.KIND_WORKOUT,
                    "reps"   to reps,
                    "weight" to weight,
                    "groupIndex" to cursor?.groupIndex,
                    "roundIndex" to cursor?.roundIndex,
                ),
            )
            // Cursor update will arrive from the phone via the
            // PhoneMessageService — no local state mutation needed.
        }
    }

    fun skipRestFromWatch() {
        viewModelScope.launch {
            phoneConnector.sendToPhone(
                MessagePaths.CMD_SKIP_REST,
                mapOf(MessagePaths.KEY_KIND to MessagePaths.KIND_WORKOUT),
            )
            // Optimistic local clear so the watch UI feels instant; the
            // phone will follow up with a fresh cursor anyway.
            SessionRepository.clearWorkoutRest()
        }
    }

    /// Wrist-initiated plan-day start. The session engine lives on the
    /// phone, so this only works while it's reachable — the callback
    /// reports false so the UI can tell the athlete to grab the phone.
    fun startPlanDayFromWatch(week: Int, day: Int, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            if (!phoneConnector.isPhoneReachable()) {
                onResult(false)
                return@launch
            }
            phoneConnector.sendToPhone(
                MessagePaths.CMD_START_PLAN_DAY,
                mapOf("week" to week, "day" to day),
            )
            onResult(true)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun startPendingRouteRetry() {
        if (retryJob?.isActive == true) return
        retryJob = viewModelScope.launch {
            while (true) {
                delay(30_000)
                val pending = PendingRouteStore.load(getApplication()) ?: break
                val sent = trySendRoute(
                    pending.points,
                    pending.activityType,
                    pending.startedAt,
                    pending.endedAt,
                    pending.avgHr,
                    pending.maxHr,
                )
                if (sent) {
                    PendingRouteStore.clear(getApplication())
                    break
                }
            }
            retryJob = null
        }
    }

    private suspend fun trySendRoute(
        points: List<GpsPoint>,
        activityType: String,
        startedAt: Long,
        endedAt: Long,
        avgHr: Double? = null,
        maxHr: Double? = null,
    ): Boolean {
        if (!phoneConnector.isPhoneReachable()) return false
        return phoneConnector.sendRouteToPhone(
            points, activityType, startedAt, endedAt, avgHr, maxHr,
        )
    }

    private fun buildStartCmd(type: String, startedAt: Long): String =
        """{"cmd":"sessionStarted","type":"$type","startedAt":"$startedAt"}"""

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

    private fun startHrSender() {
        if (hrSendJob?.isActive == true) return
        hrSendJob = viewModelScope.launch {
            while (true) {
                delay(3_000)
                val bpm = workoutManager.heartRate.value
                if (bpm > 0 && !SessionRepository.state.value.isStandalone) {
                    phoneConnector.sendToPhone(
                        """{"cmd":"hrUpdate","bpm":$bpm}"""
                    )
                }
            }
        }
    }

    private fun stopHrSender() {
        hrSendJob?.cancel()
        hrSendJob = null
    }

    private fun computeDistance(points: List<GpsPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLng = Math.toRadians(b.lng - a.lng)
            val h = Math.sin(dLat / 2).let { it * it } +
                    Math.cos(Math.toRadians(a.lat)) *
                    Math.cos(Math.toRadians(b.lat)) *
                    Math.sin(dLng / 2).let { it * it }
            total += 2 * 6371000.0 * Math.asin(Math.sqrt(h))
        }
        return total
    }

    override fun onCleared() {
        super.onCleared()
        workoutManager.stopMonitoring()
        gpsTracker.stop()
        retryJob?.cancel()
    }
}
