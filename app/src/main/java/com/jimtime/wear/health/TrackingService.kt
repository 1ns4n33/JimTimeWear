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
import com.jimtime.wear.presentation.MainActivity
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
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ── Sensor/timer ownership per l'attività ACTIVITY standalone ───────

    private fun observeSession() {
        stateJob = scope.launch {
            SessionRepository.state.collect { state ->
                val owned = state.isStandalone && state.kind == SessionKind.ACTIVITY
                if (!owned) return@collect
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

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (true) {
                delay(1_000)
                SessionRepository.tick()
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun finishAndStop() {
        stopTicker()
        TrackingEngine.workoutManager.stopMonitoring()
        TrackingEngine.gpsTracker.stop()
        TrackingEngine.resetGpsTracking()
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
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
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
