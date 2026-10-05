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
import com.jimtime.wear.data.MessagePaths
import com.jimtime.wear.data.PendingRouteStore
import com.jimtime.wear.data.PhoneConnector
import com.jimtime.wear.data.SessionKind
import com.jimtime.wear.data.SessionRepository
import com.jimtime.wear.data.SessionState
import com.jimtime.wear.data.WristGuidedStart
import com.jimtime.wear.guided.GuidedRunRepository
import com.jimtime.wear.guided.StepEvent
import com.jimtime.wear.presentation.GuidedHaptics
import com.jimtime.wear.presentation.GuidedVoice
import com.jimtime.wear.presentation.MainActivity
import com.jimtime.wear.presentation.vibrate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    // F7 (D22) — distanza INCREMENTALE dai fix GPS: indice del prossimo
    // punto da sommare e totale accumulato, per la sessione [distSessionStartedAt].
    // Nuova sessione o rotta azzerata (gpsTracker.start) ⇒ ripartono da 0;
    // dopo un recovery il service è nuovo ⇒ primo tick ricalcola sui punti
    // ripristinati.
    private var distNextIndex = 0
    private var distTotalM = 0.0
    private var distSessionStartedAt: Long? = null

    private val phoneConnector by lazy { PhoneConnector(applicationContext) }

    // F7 (correzioni) — i salvataggi del checkpoint girano fuori dal tick,
    // uno alla volta (l'ordine conta: l'ultimo scritto è il più recente).
    private val checkpointMutex = Mutex()
    // Ultimo stato di pausa per cui è stato scritto un checkpoint: a ogni
    // pausa/ripresa se ne scrive uno, così il recovery sa quanto tempo
    // reale contare come attivo.
    private var checkpointedPaused: Boolean? = null

    /// `startForeground` rifiutato (permesso «durante l'uso» mancante, FGS
    /// da background negata): la sessione è tornata a riposo, il service
    /// non deve fare altro.
    private var foregroundFailed = false

    override fun onCreate() {
        super.onCreate()
        TrackingEngine.init(applicationContext)
        startForegroundNow()
        if (!foregroundFailed) acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (foregroundFailed) return START_NOT_STICKY
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

                if (state.isActive && checkpointedPaused != state.isPaused) {
                    // Pausa/ripresa (o primo stato visto): checkpoint subito.
                    checkpointedPaused = state.isPaused
                    saveCheckpointAsync()
                }

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
                if (swimStartedAt == null) {
                    updateDistanceFromGps()
                    tickGuided()
                }
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

    /// F7 (D22) — la distanza la calcola il service, non più (solo) la VM:
    /// con la VM distrutta a schermo spento il motore vedrebbe la distanza
    /// congelata e le fasi a distanza non finirebbero mai.
    private fun updateDistanceFromGps() {
        val state = SessionRepository.state.value
        if (!state.isActive || !TrackingEngine.isGpsEligible(state.activityType)) return
        if (distSessionStartedAt != state.startedAt) {
            distSessionStartedAt = state.startedAt
            distNextIndex = 0
            distTotalM = 0.0
        }
        val points = TrackingEngine.gpsTracker.points.value
        if (points.size < distNextIndex) {
            // Rotta azzerata sotto di noi (start() di una nuova raccolta).
            distNextIndex = 0
            distTotalM = 0.0
        }
        var i = distNextIndex.coerceAtLeast(1)
        while (i < points.size) {
            distTotalM += TrackingEngine.haversineM(points[i - 1], points[i])
            i++
        }
        if (points.size > distNextIndex) distNextIndex = points.size
        SessionRepository.updateDistance(distTotalM)
    }

    /// F7 (§2.9) — un campione al motore a fasi (puro + stato), poi gli
    /// effetti qui: aptica, voce, mirror al telefono, checkpoint a fine fase.
    private suspend fun tickGuided() {
        if (!GuidedRunRepository.isActive) return
        val state = SessionRepository.state.value
        if (!state.isActive) return
        val now = System.currentTimeMillis()
        val hr = TrackingEngine.workoutManager.heartRate.value.takeIf { it > 0 }
        // Tempo attivo dall'orologio monotono (pause escluse), al ms: il
        // ritmo del ticker non conta più.
        val activeMs = SessionRepository.activeMs()
        val events = GuidedRunRepository.tick(
            nowMs = now,
            activeMs = activeMs,
            distanceM = state.distanceMeters,
            hr = hr,
            paused = state.isPaused,
        )
        // 3-2-1 delle fasi a tempo (mai per i metri): un click per tick.
        if (GuidedRunRepository.takeCountdownClicks() > 0) {
            runCatching { GuidedHaptics.countdownClick(applicationContext) }
        }
        val lang = GuidedRunRepository.lang
        var stepEnded = false
        for (e in events) {
            runCatching { GuidedHaptics.play(applicationContext, e) }
            runCatching { GuidedVoice.speak(applicationContext, e, GuidedRunRepository.stepAt(e.stepIndex), lang) }
            if (e is StepEvent.StepEnd) stepEnded = true
        }
        if (stepEnded) saveCheckpointAsync()
        if (GuidedRunRepository.mirrorDue(now, state.isPaused)) {
            val payload = GuidedRunRepository.mirrorPayload(
                startedAt = state.startedAt,
                elapsedSec = (activeMs / 1000L).toInt(),
                distanceM = state.distanceMeters,
                hr = hr,
            )
            // Mai atteso nel ticker: un Bluetooth lento non deve fermarlo.
            scope.launch { runCatching { phoneConnector.sendMirror(payload) } }
        }
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
        // F7 — i segmenti li ha già presi stopFromWatch (takeSegments PRIMA
        // di stopSession); qui solo pulizia, idempotente.
        GuidedRunRepository.stop()
        runCatching { GuidedVoice.shutdown() }
        checkpointedPaused = null
        distSessionStartedAt = null
        distNextIndex = 0
        distTotalM = 0.0
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
                saveCheckpointAsync()
            }
        }
    }

    /// Ogni 60 s, a ogni pausa/ripresa e, F7 (D18), a ogni fine fase
    /// guidata. Lo stato si fotografa SUBITO (coerente con l'istante),
    /// la scrittura gira fuori dal ticker, serializzata.
    private fun saveCheckpointAsync() {
        val checkpoint = buildCheckpoint() ?: return
        scope.launch {
            checkpointMutex.withLock {
                // Una sessione chiusa nel frattempo non va resuscitata da
                // una scrittura arrivata in ritardo.
                if (SessionRepository.state.value.startedAt != checkpoint.startedAt ||
                    !SessionRepository.state.value.isActive
                ) return@withLock
                ActiveSessionStore.save(applicationContext, checkpoint)
            }
        }
    }

    private fun buildCheckpoint(): ActiveSessionStore.Checkpoint? {
        val state = SessionRepository.state.value
        if (!(state.isActive && state.isStandalone && state.kind == SessionKind.ACTIVITY)) return null
        val hr = TrackingEngine.workoutManager.snapshot()
        return ActiveSessionStore.Checkpoint(
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
                guidedJson   = GuidedRunRepository.snapshot()?.toJson()?.toString(),
                planJson     = state.planRefJson,
                activeMs     = SessionRepository.activeMs(),
            )
    }

    // ── Notification / OngoingActivity ──────────────────────────────────

    private fun startForegroundNow() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Allenamento", NotificationManager.IMPORTANCE_LOW)
            )
        }
        // F7 (J12) — il tocco sul chip riporta alla sessione in corso.
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MessagePaths.EXTRA_OPEN_SESSION, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
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
            WristGuidedStart.onForegroundStarted(applicationContext)
        } catch (e: Exception) {
            // F7 (correzioni) — senza FGS niente GPS/timer in background: la
            // sessione torna a riposo invece di restare «attiva» col
            // cronometro fermo. Un checkpoint su disco (recovery) resta lì
            // per il prossimo avvio dell'app.
            Log.e(TAG, "startForeground(location) failed — stopping", e)
            foregroundFailed = true
            // Avvio dal telefono: torna «in sospeso» con la notifica.
            WristGuidedStart.onForegroundRefused(applicationContext)
            val st = SessionRepository.state.value
            if (st.isActive && st.isStandalone && st.kind == SessionKind.ACTIVITY) {
                GuidedRunRepository.stop()
                SessionRepository.stopSession()
            }
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
