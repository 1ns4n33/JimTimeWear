package com.jimtime.wear.health

import android.content.Context
import com.jimtime.wear.data.ActiveSessionStore
import com.jimtime.wear.data.PendingRouteStore
import com.jimtime.wear.data.SessionRepository
import com.jimtime.wear.presentation.SessionViewModel
import java.util.UUID

/**
 * Punto di verità unico per GpsTracker/WearWorkoutManager: sia il
 * SessionViewModel (companion/workout/intervalli) sia TrackingService
 * (attività ACTIVITY standalone in background) leggono e pilotano le
 * STESSE istanze — altrimenti la UI non vedrebbe quello che il service sta
 * effettivamente registrando a schermo spento.
 */
object TrackingEngine {

    lateinit var gpsTracker: GpsTracker
        private set
    lateinit var workoutManager: WearWorkoutManager
        private set

    private var initialized = false

    // startedAt della sessione per cui gpsTracker._points è attualmente
    // popolato. Permette a QUALUNQUE chiamante (TrackingService in vita
    // normale, o TrackingEngine.recoverIfNeeded chiamato a freddo da
    // MainActivity) di sapere se un successivo beginOrResumeGps deve
    // start() (nuova sessione: reset) o resume() (stessa sessione, punti
    // già raccolti da non toccare) — senza un flag locale per-istanza che
    // si perderebbe ad ogni ricreazione del service.
    private var trackedSessionStartedAt: Long? = null

    private const val RECOVERY_WINDOW_MS = 30 * 60 * 1000L // 30 minuti

    fun init(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        gpsTracker = GpsTracker(appContext)
        workoutManager = WearWorkoutManager(appContext)
        initialized = true
    }

    /// Da chiamare quando una sessione ACTIVITY standalone GPS-eligible
    /// torna active&&!paused. Sceglie start() vs resume() da solo.
    fun beginOrResumeGps(sessionStartedAt: Long) {
        if (trackedSessionStartedAt != sessionStartedAt) {
            gpsTracker.start()
        } else {
            gpsTracker.resume()
        }
        trackedSessionStartedAt = sessionStartedAt
    }

    /// Il recovery ripristina i punti da un checkpoint (restore(), non
    /// start()) — questa chiamata marca la sessione come "già tracciata"
    /// così che il prossimo beginOrResumeGps() faccia resume() e non
    /// cancelli quanto appena ripristinato.
    private fun markGpsRestored(sessionStartedAt: Long) {
        trackedSessionStartedAt = sessionStartedAt
    }

    fun resetGpsTracking() {
        trackedSessionStartedAt = null
    }

    /// Recovery dopo un process death: usata sia dal restart STICKY di
    /// TrackingService (intent nullo) sia da MainActivity all'apertura
    /// dell'app (un crash mentre il service non era stato ancora
    /// resuscitato da STICKY deve comunque essere recuperato). Idempotente:
    /// no-op se una sessione è già attiva o non c'è alcun checkpoint.
    fun recoverIfNeeded(context: Context) {
        if (SessionRepository.state.value.isActive) return
        val checkpoint = ActiveSessionStore.load(context) ?: return

        // Se questa stessa sessione è già stata finalizzata in
        // PendingRouteStore (process kill tra SessionRepository.stopSession()
        // e ActiveSessionStore.clear() in SessionViewModel.stopFromWatch), il
        // checkpoint è solo un residuo stale: l'utente l'ha già fermata, non
        // va resuscitata come sessione live.
        if (PendingRouteStore.loadAll(context).any { it.startedAt == checkpoint.startedAt }) {
            ActiveSessionStore.clear(context)
            return
        }

        val ageMs = System.currentTimeMillis() - checkpoint.lastUpdateMs
        if (ageMs < RECOVERY_WINDOW_MS) {
            // Checkpoint "caldo": riprendiamo la sessione dal vivo. Il
            // chiamante (TrackingService.observeSession, o MainActivity
            // che riavvia il service) farà partire GPS/HR in avanti.
            gpsTracker.restore(checkpoint.points)
            workoutManager.restore(
                WearWorkoutManager.HrSnapshot(checkpoint.hrSum, checkpoint.hrCount, checkpoint.hrMax)
            )
            markGpsRestored(checkpoint.startedAt)
            SessionRepository.restoreStandaloneSession(
                activityType = checkpoint.activityType,
                startedAt    = checkpoint.startedAt,
                isPaused     = checkpoint.isPaused,
            )
        } else {
            // Troppo vecchio per fingere sia "live": lo finalizziamo come
            // una qualunque sessione conclusa da stopFromWatch, così il
            // dato raggiunge comunque il phone invece di sparire nel
            // nulla. Il retry loop del SessionViewModel (già avviato al
            // suo init) lo raccoglie da PendingRouteStore appena parte.
            val endedAt = checkpoint.points.lastOrNull()?.timestampMs ?: checkpoint.lastUpdateMs
            PendingRouteStore.save(
                context,
                PendingRouteStore.PendingRoute(
                    points       = checkpoint.points,
                    activityType = checkpoint.activityType,
                    startedAt    = checkpoint.startedAt,
                    endedAt      = endedAt,
                    avgHr        = if (checkpoint.hrCount > 0) checkpoint.hrSum / checkpoint.hrCount else null,
                    maxHr        = if (checkpoint.hrMax > 0) checkpoint.hrMax else null,
                    syncId       = UUID.randomUUID().toString(),
                ),
            )
            ActiveSessionStore.clear(context)
            resetGpsTracking()
        }
    }

    /// Vero per activityType tracciabili via GPS quando standalone —
    /// stessi id offerti da IdleScreen per le attività "outdoor". Esposto
    /// qui perché sia TrackingService sia questo motore ne hanno bisogno;
    /// la costante canonica vive in SessionViewModel (un solo posto).
    fun isGpsEligible(activityType: String): Boolean =
        activityType in SessionViewModel.GPS_ACTIVITY_TYPES
}
