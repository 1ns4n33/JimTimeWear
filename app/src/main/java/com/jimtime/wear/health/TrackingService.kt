package com.jimtime.wear.health

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.jimtime.wear.R
import com.jimtime.wear.data.ActiveSessionStore
import com.jimtime.wear.data.PendingRouteStore
import com.jimtime.wear.data.SessionKind
import com.jimtime.wear.data.SessionRepository
import com.jimtime.wear.data.SessionState
import com.jimtime.wear.presentation.MainActivity
import com.jimtime.wear.presentation.vibrate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service che possiede sensori GPS/HR e timer per UNA sessione
 * ACTIVITY standalone (nessun telefono raggiungibile): deve sopravvivere a
 * schermo spento/Doze per ore, quindi gira come FGS di tipo "location" con
 * wake lock parziale, e si auto-checkpointa per sopravvivere anche a un
 * process death (vedi onStartCommand/TrackingEngine.recoverIfNeeded).
 *
 * Le sessioni companion/workout/intervalli NON passano da qui: restano
 * possedute dal SessionViewModel esattamente come prima — questo service
 * agisce solo quando SessionRepository.state ha isStandalone && kind ==
 * ACTIVITY.
 */
class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var stateJob: Job? = null
    private var tickerJob: Job? = null
    private var checkpointJob: Job? = null

    // F5b (Nuoto) — guardia idempotenza: SessionRepository.state riemette
    // ad OGNI update (tick, updateSwimMetrics), ma startExerciseAsync/
    // pauseExerciseAsync non lo sono — vanno chiamati una volta sola per
    // transizione, non ad ogni ricomposizione dell'observer.
    private var swimJob: Job? = null
    private var swimStartedAt: Long? = null
    private var swimPaused = false

    override fun onCreate() {
        super.onCreate()
        TrackingEngine.init(applicationContext)
        startForegroundNow()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Il processo è stato ucciso e il sistema ci ha ricreati per
            // START_STICKY: SessionRepository (singleton in-memory) è
            // tornato allo stato di default, va ricostruito dal checkpoint
            // PRIMA di agganciare l'observer, altrimenti quest'ultimo vede
            // solo "nessuna sessione attiva" e si ferma subito.
            TrackingEngine.recoverIfNeeded(applicationContext)
        }
        if (stateJob == null) observeSession()
        if (checkpointJob == null) startCheckpointLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stateJob?.cancel()
        tickerJob?.cancel()
        checkpointJob?.cancel()
        swimJob?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ── Sensor/timer ownership per l'attività ACTIVITY standalone ───────

    private fun observeSession() {
        stateJob = scope.launch {
            SessionRepository.state.collect { state ->
                val owned = state.isStandalone && state.kind == SessionKind.ACTIVITY
                if (!owned) return@collect

                if (TrackingEngine.isSwimActivity(state.activityType)) {
                    observeSwim(state)
                    return@collect
                }

                val gpsEligible = TrackingEngine.isGpsEligible(state.activityType)
                when {
                    state.isActive && !state.isPaused -> {
                        TrackingEngine.workoutManager.startMonitoring()
                        if (gpsEligible) TrackingEngine.beginOrResumeGps(state.startedAt)
                        startTicker()
                    }
                    state.isPaused -> {
                        // La FC resta monitorata (semantica invariata):
                        // solo il GPS si ferma in pausa.
                        if (gpsEligible) TrackingEngine.gpsTracker.stop()
                        stopTicker()
                    }
                    !state.isActive -> finishAndStop()
                }
            }
        }
    }

    /// F5b — variante swim di [observeSession]: nessun gpsTracker/
    /// workoutManager, sensori posseduti da [TrackingEngine.swimSource]
    /// (ExerciseClient). Auto-pause OFF (D4): pausa/resume sono SOLO
    /// comandi espliciti dal polso, mai automatici.
    private suspend fun observeSwim(state: SessionState) {
        when {
            state.isActive && !state.isPaused -> {
                if (swimStartedAt != state.startedAt) {
                    TrackingEngine.swimSource.start(
                        openWater   = state.activityType == "swim_open_water",
                        poolLengthM = state.swim?.poolLengthM,
                    )
                    swimStartedAt = state.startedAt
                    swimPaused = false
                    startSwimMetricsCollector()
                    // F5b (§7) — la guida parte SOLO se una serie è stata
                    // scelta (SwimStartScreen) o è arrivata incorporata in
                    // startSession.swim.workout; SwimGuideRepository.start
                    // con workout null è un no-op esplicito (nessuna guida).
                    SwimGuideRepository.start(state.swim?.workout, state.startedAt)
                } else if (swimPaused) {
                    TrackingEngine.swimSource.resume()
                    swimPaused = false
                }
                startTicker()
            }
            state.isPaused -> {
                if (swimStartedAt == state.startedAt && !swimPaused) {
                    TrackingEngine.swimSource.pause()
                    swimPaused = true
                }
                stopTicker()
            }
            !state.isActive -> finishAndStop()
        }
    }

    private fun startSwimMetricsCollector() {
        if (swimJob?.isActive == true) return
        swimJob = scope.launch {
            TrackingEngine.swimSource.metrics.collect { m ->
                SessionRepository.updateSwimMetrics(m.distanceMeters, m.laps, m.strokes)
            }
        }
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (true) {
                delay(1_000)
                SessionRepository.tick()
                // F5b (§7) — avanza la guida SOLO per una sessione swim in
                // corso; questo stesso ticker è condiviso con GPS/HR, dove
                // la guida non esiste (SwimGuideRepository.tick su nessuna
                // serie attiva è comunque un no-op, ma il check evita di
                // interrogarla ad ogni secondo per niente sulle altre attività).
                if (swimStartedAt != null) {
                    when (SwimGuideRepository.tick(System.currentTimeMillis())) {
                        SwimGuideEvent.REP_START -> applicationContext.vibrate(40)
                        SwimGuideEvent.SET_CHANGE -> applicationContext.vibrate(150)
                        SwimGuideEvent.NONE -> {}
                    }
                }
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private suspend fun finishAndStop() {
        stopTicker()
        if (swimStartedAt != null) {
            // F5b — endExercise + clearUpdateCallback; il payload swim per
            // il phone è già stato costruito PRIMA di questa transizione
            // (SessionViewModel.stopFromWatch legge swimSource.metrics.value
            // sincrono, poi chiama SessionRepository.stopSession()) — qui è
            // solo cleanup del client, mai la fonte del dato inviato.
            runCatching { TrackingEngine.swimSource.stop() }
            swimJob?.cancel()
            swimJob = null
            swimStartedAt = null
            swimPaused = false
            SwimGuideRepository.stop()
        } else {
            TrackingEngine.workoutManager.stopMonitoring()
            TrackingEngine.gpsTracker.stop()
            TrackingEngine.resetGpsTracking()
        }
        // Il checkpoint va cancellato SOLO se una copia definitiva è già
        // stata scritta in PendingRouteStore (stopFromWatch la scrive
        // PRIMA di flippare lo stato) — altrimenti un crash/kill che porta
        // qui senza passare da stopFromWatch cancellerebbe l'unica copia
        // recuperabile.
        if (PendingRouteStore.load(applicationContext) != null) {
            ActiveSessionStore.clear(applicationContext)
        }
        checkpointJob?.cancel()
        checkpointJob = null
        stopSelf()
    }

    // ── Checkpoint periodico ──────────────────────────────────────────────

    private fun startCheckpointLoop() {
        checkpointJob = scope.launch {
            while (true) {
                delay(60_000)
                val state = SessionRepository.state.value
                if (!(state.isActive && state.isStandalone && state.kind == SessionKind.ACTIVITY)) {
                    continue
                }
                val hr = TrackingEngine.workoutManager.snapshot()
                ActiveSessionStore.save(
                    applicationContext,
                    ActiveSessionStore.Checkpoint(
                        activityType = state.activityType,
                        startedAt    = state.startedAt,
                        isPaused     = state.isPaused,
                        lastUpdateMs = System.currentTimeMillis(),
                        // Risoluzione piena: il downsample per il limite
                        // ~100KB del MessageClient avviene solo all'invio
                        // (PhoneConnector.sendRouteToPhone), non qui.
                        points       = TrackingEngine.gpsTracker.points.value,
                        hrSum        = hr.hrSum,
                        hrCount      = hr.hrCount,
                        hrMax        = hr.hrMax,
                        swimLocation    = state.swim?.location,
                        swimPoolLengthM = state.swim?.poolLengthM,
                    ),
                )
            }
        }
    }

    // ── Notification / OngoingActivity ──────────────────────────────────

    private fun startForegroundNow() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Allenamento", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Allenamento in corso")
            .setContentText("Registrazione GPS/FC attiva — puoi spegnere lo schermo")
            .setSmallIcon(R.drawable.ic_notification_tracking)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Chip sul quadrante — richiede androidx.wear:wear-ongoing.
        val status = Status.Builder().addTemplate("Allenamento in corso").build()
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_notification_tracking)
            .setStatus(status)
            .setTouchIntent(contentIntent)
            .build()
            .apply(applicationContext)

        val notification: Notification = builder.build()
        try {
            // minSdk 30 => sempre >= Q: il parametro di tipo è obbligatorio.
            // Su API 34+ una FGS "location" senza fine/coarse location già
            // concessa lancia SecurityException qui — senza permesso il
            // GPS non servirebbe comunque a nulla, meglio chiudersi che
            // crashare il processo.
            //
            // F5b: per swim_pool/swim_open_water si dichiara ANCHE "health"
            // (ExerciseClient) oltre a "location" — quest'ultimo resta utile
            // per swim_open_water (GPS via Health Services). Il tipo
            // FOREGROUND_SERVICE_TYPE_HEALTH esiste solo da API 34: sotto,
            // "location" da solo basta comunque a tenere viva la FGS.
            val state = SessionRepository.state.value
            val type = if (TrackingEngine.isSwimActivity(state.activityType) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            ) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground(location) failed — stopping", e)
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JimTimeWear:TrackingService")
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    companion object {
        private const val TAG = "TrackingService"
        private const val CHANNEL_ID = "tracking_channel"
        private const val NOTIFICATION_ID = 1001

        // Backstop: se onDestroy() non gira mai (processo ucciso a forza),
        // il wake lock si rilascia comunque da solo dopo ~7h invece di
        // restare agganciato per sempre.
        private const val WAKE_LOCK_TIMEOUT_MS = 7 * 60 * 60 * 1000L
    }
}
