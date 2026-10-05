package com.jimtime.wear.data

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * F7 (correzioni verificatore) — tempo ATTIVO della sessione da un orologio
 * monotono: somma i delta solo fuori pausa. Puro (l'istante arriva dal
 * chiamante), quindi testabile. Prima `elapsedSeconds` contava i giri del
 * ticker (un ritardo del ciclo allungava le fasi a tempo) e il recovery lo
 * rifaceva da `now - startedAt` (pause e ritardi inclusi).
 */
class ActiveClock {
    private var baseMs = 0L
    /** Istante monotono dell'ultima ripresa; `null` = fermo (pausa o nessuna sessione). */
    private var runningSinceMono: Long? = null

    fun reset(baseActiveMs: Long, running: Boolean, nowMono: Long) {
        baseMs = baseActiveMs.coerceAtLeast(0L)
        runningSinceMono = if (running) nowMono else null
    }

    fun pause(nowMono: Long) {
        val since = runningSinceMono ?: return
        baseMs += (nowMono - since).coerceAtLeast(0L)
        runningSinceMono = null
    }

    fun resume(nowMono: Long) {
        if (runningSinceMono == null) runningSinceMono = nowMono
    }

    fun activeMs(nowMono: Long): Long =
        baseMs + (runningSinceMono?.let { (nowMono - it).coerceAtLeast(0L) } ?: 0L)

    companion object {
        /**
         * Tempo attivo alla ripresa da un checkpoint: quello salvato, più il
         * tempo reale trascorso da allora SOLO se la sessione non era in
         * pausa; mai meno di quanto il motore guidato aveva già visto
         * ([floorActiveMs] = `engine.lastActiveMs`), così le fasi non si
         * sovrappongono.
         */
        fun resumeActiveMs(
            savedActiveMs: Long,
            savedAtWallMs: Long,
            wasPaused: Boolean,
            nowWallMs: Long,
            floorActiveMs: Long = 0L,
        ): Long {
            val gap = if (wasPaused) 0L else (nowWallMs - savedAtWallMs).coerceAtLeast(0L)
            return maxOf(savedActiveMs.coerceAtLeast(0L) + gap, floorActiveMs)
        }
    }
}

object SessionRepository {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val clock = ActiveClock()

    /// Orologio monotono (sostituibile solo per i test).
    internal var monoNow: () -> Long = { SystemClock.elapsedRealtime() }

    /// Tempo attivo corrente in ms (pause escluse) — `elapsedSeconds` ne è
    /// la parte intera, aggiornata da [tick].
    fun activeMs(): Long = synchronized(clock) { clock.activeMs(monoNow()) }

    private fun resetClock(baseActiveMs: Long, running: Boolean) =
        synchronized(clock) { clock.reset(baseActiveMs, running, monoNow()) }

    fun startSession(type: String, startedAt: Long, swim: SwimData? = null) {
        val alreadyElapsed = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
        resetClock(alreadyElapsed * 1000L, running = true)
        _state.value = SessionState(
            isActive       = true,
            activityType   = type,
            startedAt      = startedAt,
            elapsedSeconds = alreadyElapsed,
            // F5b: il nuoto è SEMPRE posseduto localmente da TrackingService
            // (ExerciseClient) — mai dal telefono, che sott'acqua non ha
            // modo di sapere cosa sta succedendo. Anche quando questa
            // sessione arriva da un comando `startSession` del telefono
            // (l'atleta l'ha avviata dall'app prima di entrare in acqua),
            // il polso resta la sola sorgente di verità dei sensori.
            isStandalone   = swim != null,
            kind           = SessionKind.ACTIVITY,
            workout        = null,
            swim           = swim,
        )
    }

    /// F7 — `planRefJson`/`guided`/`guidedVariant` per una sessione nata da
    /// un giorno di piano (dal polso o da `startSession{wristAuthoritative}`
    /// del telefono, D3/D5). `startedAt` = quello del comando del telefono
    /// quando c'è (il mirror lo usa come chiave); il cronometro parte
    /// comunque da 0 qui, come ogni altra sessione standalone.
    fun startStandaloneSession(
        type: String,
        swim: SwimData? = null,
        planRefJson: String? = null,
        guided: Boolean = false,
        guidedVariant: String? = null,
        startedAt: Long = System.currentTimeMillis(),
    ) {
        resetClock(0L, running = true)
        _state.value = SessionState(
            isActive      = true,
            activityType  = type,
            startedAt     = startedAt,
            isStandalone  = true,
            kind          = SessionKind.ACTIVITY,
            workout       = null,
            swim          = swim,
            guided        = guided,
            planRefJson   = planRefJson,
            guidedVariant = guidedVariant,
        )
    }

    /// F7 (D24) — striscia fase del companion. Solo su una sessione viva NON
    /// guidata al polso: con il motore al polso la fase è già sullo schermo.
    fun updateGuidedStep(step: GuidedStepMirror) {
        val current = _state.value
        if (!current.isActive || current.guided) return
        _state.value = current.copy(guidedStep = step)
    }

    /// F5b — aggregati correnti dal `HealthServicesExerciseSource` di
    /// `TrackingService`. No-op se la sessione attiva non è swim (difesa
    /// contro un update tardivo dopo uno stop/cambio attività).
    fun updateSwimMetrics(distanceMeters: Double, laps: Int, strokes: Int) {
        val swim = _state.value.swim ?: return
        _state.value = _state.value.copy(
            swim = swim.copy(distanceMeters = distanceMeters, laps = laps, strokes = strokes),
        )
    }

    /// F7 — `guidedStep{clear:true}` dal telefono: la striscia sparisce subito.
    fun clearGuidedStep() {
        val current = _state.value
        if (current.guidedStep == null) return
        _state.value = current.copy(guidedStep = null)
    }

    fun stopSession() {
        resetClock(0L, running = false)
        _state.value = SessionState()
    }

    fun pauseSession() {
        val activeMs = synchronized(clock) {
            clock.pause(monoNow())
            clock.activeMs(monoNow())
        }
        _state.value = _state.value.copy(isPaused = true, elapsedSeconds = activeMs / 1000L)
    }

    fun resumeSession() {
        synchronized(clock) { clock.resume(monoNow()) }
        _state.value = _state.value.copy(isPaused = false)
    }

    fun updateDistance(meters: Double) {
        _state.value = _state.value.copy(distanceMeters = meters)
    }

    /// Riallinea `elapsedSeconds` all'orologio monotono — idempotente: due
    /// chiamanti (VM e service) non contano più due volte, e un giro del
    /// ticker in ritardo non allunga il tempo.
    fun tick() {
        val current = _state.value
        if (current.isActive && !current.isPaused) {
            val sec = activeMs() / 1000L
            if (sec != current.elapsedSeconds) _state.value = current.copy(elapsedSeconds = sec)
        }
    }

    /// Crash recovery: ricostruisce una sessione ACTIVITY standalone da un
    /// checkpoint dopo che il processo (e quindi questo intero singleton)
    /// è stato ucciso e ricreato. F7: `activeMs` = tempo attivo calcolato
    /// dal chiamante con [ActiveClock.resumeActiveMs] (pause escluse);
    /// `null` (checkpoint scritto prima di F7) ⇒ vecchia approssimazione
    /// dal wall-clock (now - startedAt). `distanceMeters` = dai punti
    /// ripristinati, visibile subito anche se la sessione riparte in pausa.
    fun restoreStandaloneSession(
        activityType: String,
        startedAt: Long,
        isPaused: Boolean,
        swim: SwimData? = null,
        planRefJson: String? = null,
        guided: Boolean = false,
        guidedVariant: String? = null,
        activeMs: Long? = null,
        distanceMeters: Double = 0.0,
    ) {
        val restoredMs = activeMs
            ?: ((System.currentTimeMillis() - startedAt).coerceAtLeast(0L))
        resetClock(restoredMs, running = !isPaused)
        _state.value = SessionState(
            isActive       = true,
            isPaused       = isPaused,
            activityType   = activityType,
            startedAt      = startedAt,
            elapsedSeconds = restoredMs / 1000L,
            distanceMeters = distanceMeters,
            isStandalone   = true,
            kind           = SessionKind.ACTIVITY,
            workout        = null,
            swim           = swim,
            guided         = guided,
            planRefJson    = planRefJson,
            guidedVariant  = guidedVariant,
        )
    }

    // ── Workout protocol ─────────────────────────────────────────────────

    fun startWorkoutSession(
        startedAt: Long,
        context: WorkoutContext,
    ) {
        val alreadyElapsed = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
        resetClock(alreadyElapsed * 1000L, running = true)
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
