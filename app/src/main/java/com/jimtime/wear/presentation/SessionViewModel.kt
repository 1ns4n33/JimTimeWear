package com.jimtime.wear.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jimtime.wear.data.GpsPoint
import com.jimtime.wear.data.IntervalSpec
import com.jimtime.wear.data.MessagePaths
import com.jimtime.wear.data.PendingGymStore
import com.jimtime.wear.data.WorkoutContext
import com.jimtime.wear.data.WorkoutCursor
import com.jimtime.wear.data.WorkoutTarget
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
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
        // Sync intervalli rimasto in outbox da una sessione chiusa offline.
        viewModelScope.launch {
            PendingGymStore.load(application)?.let { pending ->
                if (phoneConnector.isPhoneReachable()) {
                    phoneConnector.sendToPhone(
                        MessagePaths.CMD_GYM_SESSION_SYNC, jsonToMap(pending)
                    )
                    PendingGymStore.clear(application)
                }
            }
        }
        // Wear messages aren't cached phone-side: ask for a fresh plan
        // list in case a push happened while we were disconnected.
        viewModelScope.launch {
            phoneConnector.sendToPhone(MessagePaths.CMD_REQUEST_PLAN_DAYS)
        }
        startPendingRouteRetry()
        // Sync intervalli rimasto in outbox da una sessione chiusa offline.
        viewModelScope.launch {
            PendingGymStore.load(application)?.let { pending ->
                if (phoneConnector.isPhoneReachable()) {
                    phoneConnector.sendToPhone(
                        MessagePaths.CMD_GYM_SESSION_SYNC, jsonToMap(pending)
                    )
                    PendingGymStore.clear(application)
                }
            }
        }
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
        // Sessione intervalli standalone: il "Fatto" è locale (AMRAP/ForTime
        // contano il giro; le auto-timed lo trattano come fine anticipata
        // dell'intervallo di lavoro).
        if (intervalSpec != null) {
            advanceIntervalStandalone()
            return
        }
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
        if (intervalSpec != null) {
            SessionRepository.clearWorkoutRest()
            intervalWorkEndAt =
                System.currentTimeMillis() + (intervalSpec?.workSeconds ?: 0) * 1000L
            return
        }
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
    // ── Intervalli standalone (quick-start dal polso, nessun telefono) ──────

    private var intervalSpec: IntervalSpec? = null
    private var intervalRound = 0
    private var intervalWorkEndAt = 0L
    private var intervalTotalEndAt = 0L
    private var intervalStartedAt = 0L
    private var intervalTickerJob: Job? = null

    fun startIntervalStandalone(spec: IntervalSpec) {
        if (SessionRepository.state.value.isActive) return
        intervalSpec = spec
        intervalRound = 0
        intervalStartedAt = System.currentTimeMillis()
        intervalWorkEndAt = if (spec.isAutoTimed)
            intervalStartedAt + spec.workSeconds * 1000L else 0L
        intervalTotalEndAt = spec.totalSeconds
            ?.let { intervalStartedAt + it * 1000L } ?: 0L
        SessionRepository.startWorkoutSession(
            startedAt = intervalStartedAt,
            context = intervalContext(),
        )
        intervalTickerJob?.cancel()
        intervalTickerJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                intervalTick()
            }
        }
    }

    private fun intervalContext(restEndAtMs: Long? = null): WorkoutContext {
        val spec = intervalSpec ?: return WorkoutContext()
        val totalRounds = if (spec.mode == "amrap") intervalRound + 1 else spec.rounds
        return WorkoutContext(
            planName = spec.modeLabel,
            weekLabel = "",
            dayLabel = "${spec.modeLabel} · ${spec.compactLabel}",
            cursor = WorkoutCursor(
                groupIndex = 0,
                roundIndex = intervalRound.coerceAtMost(totalRounds - 1),
                exerciseIndex = 0,
                totalGroups = 1,
                totalRoundsInGroup = totalRounds,
            ),
            target = WorkoutTarget(
                exerciseName = spec.modeLabel,
                durationSeconds = if (spec.isAutoTimed) spec.workSeconds else null,
                restSeconds = if (spec.restSeconds > 0) spec.restSeconds else null,
            ),
            restEndAtMs = restEndAtMs,
            completedExercises = intervalRound,
        )
    }

    private fun intervalTick() {
        val spec = intervalSpec ?: run { intervalTickerJob?.cancel(); return }
        val st = SessionRepository.state.value
        if (!st.isActive) {
            // Stop da qualsiasi percorso (Controls, ecc.) → sincronizza e chiudi.
            finishIntervalStandalone(alreadyStopped = true)
            return
        }
        if (st.isPaused) {
            // Congela le scadenze shiftandole del tick in pausa.
            if (intervalWorkEndAt > 0) intervalWorkEndAt += 1000
            if (intervalTotalEndAt > 0) intervalTotalEndAt += 1000
            st.workout?.restEndAtMs?.let {
                SessionRepository.startWorkoutRest(
                    ((it + 1000 - System.currentTimeMillis()) / 1000L).toInt()
                        .coerceAtLeast(1),
                    System.currentTimeMillis(),
                )
            }
            return
        }
        val now = System.currentTimeMillis()
        if (intervalTotalEndAt > 0 && now >= intervalTotalEndAt) {
            finishIntervalStandalone(alreadyStopped = false)
            return
        }
        if (!spec.isAutoTimed) return
        val restEnd = st.workout?.restEndAtMs
        if (restEnd != null) {
            if (now >= restEnd) {
                SessionRepository.clearWorkoutRest()
                intervalWorkEndAt = now + spec.workSeconds * 1000L
            }
            return
        }
        if (now >= intervalWorkEndAt) advanceIntervalStandalone()
    }

    /// Chiude un intervallo di lavoro: logga il round, poi riposo o fine.
    private fun advanceIntervalStandalone() {
        val spec = intervalSpec ?: return
        intervalRound++
        val done = spec.mode != "amrap" && intervalRound >= spec.rounds
        if (done) {
            finishIntervalStandalone(alreadyStopped = false)
            return
        }
        val now = System.currentTimeMillis()
        if (spec.isAutoTimed && spec.restSeconds > 0) {
            SessionRepository.updateWorkoutCursor(
                intervalContext(restEndAtMs = now + spec.restSeconds * 1000L).cursor,
                intervalContext().target,
                intervalRound,
                now + spec.restSeconds * 1000L,
            )
        } else {
            intervalWorkEndAt = now + spec.workSeconds * 1000L
            SessionRepository.updateWorkoutCursor(
                intervalContext().cursor,
                intervalContext().target,
                intervalRound,
                null,
            )
        }
    }

    private fun finishIntervalStandalone(alreadyStopped: Boolean) {
        val spec = intervalSpec ?: return
        intervalSpec = null
        intervalTickerJob?.cancel()
        intervalTickerJob = null
        val rounds = intervalRound
        val endedAt = System.currentTimeMillis()
        if (!alreadyStopped) SessionRepository.stopSession()
        if (rounds <= 0) return // niente sessione fantasma

        val payload = mutableMapOf<String, Any?>(
            "syncId" to UUID.randomUUID().toString(),
            "planId" to "interval-workout",
            "planName" to spec.modeLabel,
            "weekLabel" to "",
            "dayLabel" to "${spec.modeLabel} · ${spec.compactLabel}",
            "week" to 0,
            "day" to 0,
            "startedAt" to intervalStartedAt,
            "endedAt" to endedAt,
            "groups" to listOf(
                mapOf(
                    "type" to "single",
                    "planGroupIndex" to 0,
                    "exercises" to listOf(
                        mapOf(
                            "exerciseName" to spec.modeLabel,
                            "sets" to (1..rounds).map { mapOf("setNumber" to it) },
                        )
                    ),
                )
            ),
        )
        workoutManager.hrAverage?.let { payload["avgHr"] = it }
        workoutManager.hrMaxOrNull?.let { payload["maxHr"] = it }

        viewModelScope.launch {
            if (phoneConnector.isPhoneReachable()) {
                phoneConnector.sendToPhone(MessagePaths.CMD_GYM_SESSION_SYNC, payload)
            } else {
                PendingGymStore.save(getApplication(), JSONObject(payload))
            }
        }
    }

    /// JSONObject → Map annidata (org.json non ha toMap; serve per il retry
    /// dell'outbox gymSessionSync).
    private fun jsonToMap(obj: JSONObject): Map<String, Any?> =
        obj.keys().asSequence().associateWith { k ->
            when (val v = obj.get(k)) {
                is JSONObject -> jsonToMap(v)
                is JSONArray -> (0 until v.length()).map { i ->
                    when (val e = v.get(i)) {
                        is JSONObject -> jsonToMap(e)
                        else -> e
                    }
                }
                JSONObject.NULL -> null
                else -> v
            }
        }

}
