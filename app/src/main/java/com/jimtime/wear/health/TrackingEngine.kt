package com.jimtime.wear.health

import android.content.Context
import com.jimtime.wear.data.ActiveClock
import com.jimtime.wear.data.ActiveSessionStore
import com.jimtime.wear.data.GpsPoint
import com.jimtime.wear.data.PendingRouteStore
import com.jimtime.wear.data.SessionRepository
import com.jimtime.wear.data.SportsCatalog
import com.jimtime.wear.data.SwimData
import com.jimtime.wear.guided.GuidedRunRepository
import com.jimtime.wear.guided.GuidedRunSnapshot
import org.json.JSONObject
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

    /// F5b (Nuoto) — sorgente Health Services dedicata a swim_pool/
    /// swim_open_water, SEPARATA da gpsTracker/workoutManager: usata solo
    /// quando `isSwim(activityType)` è vero.
    lateinit var swimSource: HealthServicesExerciseSource
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
        swimSource = HealthServicesExerciseSource(appContext)
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
        val isSwim = isSwimActivity(checkpoint.activityType)
        if (ageMs < RECOVERY_WINDOW_MS) {
            // Checkpoint "caldo": riprendiamo la sessione dal vivo. Il
            // chiamante (TrackingService.observeSession, o MainActivity
            // che riavvia il service) farà partire GPS/HR in avanti.
            //
            // F5b: per il nuoto non c'è un checkpoint di vasche/bracciate da
            // ripristinare (ExerciseClient le tiene lui, non noi) — un
            // process death qui riparte da 0 su quei due contatori quando
            // TrackingService richiama swimSource.start(); distanza/HR
            // riprendono a salire normalmente dal nuovo start. Degrado
            // accettato: raro (serve un kill di processo proprio durante
            // una sessione swim) e comunque preferibile a perdere l'intera
            // sessione.
            if (!isSwim) {
                gpsTracker.restore(checkpoint.points)
                workoutManager.restore(
                    WearWorkoutManager.HrSnapshot(checkpoint.hrSum, checkpoint.hrCount, checkpoint.hrMax)
                )
                markGpsRestored(checkpoint.startedAt)
            }
            // F7 (D18) — motore a fasi dallo snapshot: `engine.started` ⇒
            // nessuna frase/aptica di avvio ripetuta al primo tick.
            val snapshot = guidedSnapshotOf(checkpoint)
            if (snapshot != null) GuidedRunRepository.restore(snapshot)
            SessionRepository.restoreStandaloneSession(
                activityType = checkpoint.activityType,
                startedAt    = checkpoint.startedAt,
                isPaused     = checkpoint.isPaused,
                swim = if (isSwim) SwimData(
                    location    = checkpoint.swimLocation ?: "POOL",
                    poolLengthM = checkpoint.swimPoolLengthM,
                ) else null,
                planRefJson   = checkpoint.planJson ?: snapshot?.planRef?.toString(),
                guided        = snapshot != null,
                guidedVariant = snapshot?.variant,
                // F7 (correzioni) — tempo attivo dal checkpoint, mai sotto
                // quanto il motore guidato aveva già visto.
                activeMs = checkpoint.activeMs?.let { saved ->
                    ActiveClock.resumeActiveMs(
                        savedActiveMs = saved,
                        savedAtWallMs = checkpoint.lastUpdateMs,
                        wasPaused = checkpoint.isPaused,
                        nowWallMs = System.currentTimeMillis(),
                        floorActiveMs = snapshot?.engine?.lastActiveMs ?: 0L,
                    )
                },
                distanceMeters = if (isSwim) 0.0 else routeDistanceM(checkpoint.points),
            )
        } else {
            // Troppo vecchio per fingere sia "live": lo finalizziamo come
            // una qualunque sessione conclusa da stopFromWatch, così il
            // dato raggiunge comunque il phone invece di sparire nel
            // nulla. Il retry loop del SessionViewModel (già avviato al
            // suo init) lo raccoglie da PendingRouteStore appena parte.
            val endedAt = checkpoint.points.lastOrNull()?.timestampMs ?: checkpoint.lastUpdateMs
            // F7 (D18) — `plan` + segmenti con la fase in corso chiusa
            // `finish`, così l'attività arriva comunque agganciata al piano.
            val snapshot = guidedSnapshotOf(checkpoint)
            val stepSegments = snapshot?.let { GuidedRunRepository.closedRecord(it) }
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
                    planJson     = checkpoint.planJson ?: snapshot?.planRef?.toString(),
                    stepSegmentsJson = stepSegments?.toString(),
                ),
            )
            ActiveSessionStore.clear(context)
            resetGpsTracking()
        }
    }

    private fun guidedSnapshotOf(checkpoint: ActiveSessionStore.Checkpoint): GuidedRunSnapshot? =
        checkpoint.guidedJson?.let { raw ->
            runCatching { GuidedRunSnapshot.fromJson(JSONObject(raw)) }.getOrNull()
        }

    /// Distanza totale di una rotta (somma haversine), in metri.
    fun routeDistanceM(points: List<GpsPoint>): Double {
        var total = 0.0
        for (i in 1 until points.size) total += haversineM(points[i - 1], points[i])
        return total
    }

    /// F7 (D22) — distanza haversine fra due fix, in metri. Usata dal
    /// ticker di TrackingService (incrementale) e dalla VM (companion).
    fun haversineM(a: GpsPoint, b: GpsPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(a.lat)) *
            Math.cos(Math.toRadians(b.lat)) *
            Math.sin(dLng / 2).let { it * it }
        return 2 * 6371000.0 * Math.asin(Math.sqrt(h))
    }

    /// Vero per activityType tracciabili via GPS quando standalone. F7
    /// (D22): dal catalogo generato (`watchWear && outdoorGps`), non più
    /// un elenco a mano — mtb/skate ora inclusi.
    fun isGpsEligible(activityType: String): Boolean =
        activityType in SportsCatalog.watchOutdoorIds

    /// F5b — vero per swim_pool/swim_open_water: sensori posseduti da
    /// [swimSource] (Health Services), mai da gpsTracker/workoutManager.
    fun isSwimActivity(activityType: String): Boolean =
        activityType == "swim_pool" || activityType == "swim_open_water"
}
