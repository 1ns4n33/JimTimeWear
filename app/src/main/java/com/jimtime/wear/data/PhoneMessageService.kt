package com.jimtime.wear.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jimtime.wear.R
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.jimtime.wear.guided.GuidedRunRepository
import com.jimtime.wear.guided.GuidedStartPolicy
import com.jimtime.wear.guided.ResolvedStructure
import com.jimtime.wear.health.TrackingEngine
import com.jimtime.wear.health.TrackingService
import com.jimtime.wear.presentation.MainActivity
import com.jimtime.wear.presentation.vibrate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class PhoneMessageService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (!event.path.startsWith("/jimtime")) return

        val json = runCatching { JSONObject(String(event.data)) }.getOrNull() ?: return
        val cmd = json.optString("cmd")
        val kind = json.optString(MessagePaths.KEY_KIND, "activity")

        // ── Workout protocol ───────────────────────────────────────────
        if (kind == MessagePaths.KIND_WORKOUT) {
            when (cmd) {
                MessagePaths.CMD_START_SESSION -> handleWorkoutStart(json)
                MessagePaths.CMD_UPDATE_CURSOR -> handleWorkoutCursor(json)
                MessagePaths.CMD_START_REST    -> handleWorkoutStartRest(json)
                MessagePaths.CMD_CLEAR_REST    -> SessionRepository.clearWorkoutRest()
                MessagePaths.CMD_STOP_SESSION  -> SessionRepository.stopSession()
                MessagePaths.CMD_PAUSE_SESSION -> SessionRepository.pauseSession()
                MessagePaths.CMD_RESUME_SESSION -> SessionRepository.resumeSession()
            }
            return
        }

        // ── Activity protocol (legacy, no kind field) ──────────────────
        when (cmd) {
            MessagePaths.CMD_START_SESSION -> {
                // F7 (D5, §2.4) — il polso possiede la sessione (motore +
                // TrackingService); senza la chiave resta il companion di oggi.
                if (json.optBoolean(MessagePaths.KEY_WRIST_AUTHORITATIVE, false)) {
                    handleWristAuthoritativeStart(json)
                    return
                }
                val type = json.optString("type", "run")
                val startedAt = json.optLong("startedAt", System.currentTimeMillis())
                // F5b (Nuoto, D4/§7) — `{location, poolLengthM?}` quando
                // l'atleta ha avviato la sessione dal TELEFONO prima di
                // entrare in acqua. Anche in questo caso sott'acqua i
                // sensori restano SEMPRE del polso (SessionRepository.
                // startSession marca isStandalone=true quando swim != null,
                // vedi TrackingEngine invariant) — qui va solo AVVIATO
                // TrackingService, che il percorso "phone raggiungibile"
                // normalmente non tocca.
                val swimObj = json.optJSONObject("swim")
                val swim = swimObj?.let {
                    SwimData(
                        location    = it.optString("location", if (type == "swim_open_water") "OPEN_WATER" else "POOL"),
                        poolLengthM = if (it.has("poolLengthM")) it.optDouble("poolLengthM") else null,
                        // F5b (§7) — `startSession.swim.workout` (mirror
                        // `SwimWorkoutRecord.toWire()`): l'atleta ha scelto
                        // una serie del coach dal telefono prima di partire.
                        workout = it.optJSONObject("workout")?.let(SwimWorkoutWire::fromWire),
                    )
                }
                SessionRepository.startSession(type, startedAt, swim)
                if (swim != null) {
                    ContextCompat.startForegroundService(
                        applicationContext,
                        Intent(applicationContext, TrackingService::class.java),
                    )
                }
            }
            MessagePaths.CMD_STOP_SESSION  -> SessionRepository.stopSession()
            MessagePaths.CMD_PAUSE_SESSION -> SessionRepository.pauseSession()
            MessagePaths.CMD_RESUME_SESSION -> SessionRepository.resumeSession()
            MessagePaths.CMD_PLAN_DAYS -> {
                // F7 (§2.2) — payload v2 intero: `days` invariati più
                // `sportDays`/`lang`/`planId`. Un payload senza né `days` né
                // `sportDays` resta ignorato come prima.
                if (!json.has("days") && !json.has("sportDays")) return
                PlanDaysStore.applyPayload(applicationContext, json)
            }
            MessagePaths.CMD_GUIDED_STEP -> handleGuidedStep(json)
            // F5b (Nuoto, §7) — `WearableBridge.sendSwimWorkouts` inoltra
            // verbatim su questo stesso path, come planDays/intervalConfig
            // (vedi commento lì) — MAI più applicationContext: qui è un
            // messaggio, quindi arriva solo se il polso è raggiungibile al
            // momento del push; la cache locale (SwimWorkoutsStore) è
            // quello che copre il resto del tempo.
            MessagePaths.CMD_SWIM_WORKOUTS -> {
                val items = json.optJSONArray("items") ?: JSONArray()
                SwimWorkoutsStore.apply(applicationContext, items)
            }
            MessagePaths.CMD_SYNC_ACK -> handleSyncAck(json)
            MessagePaths.CMD_SYNC_STATUS -> handleSyncStatus(json)
            MessagePaths.CMD_REQUEST_PENDING_SYNC -> handleRequestPendingSync()
            MessagePaths.CMD_DAILY_SUMMARY -> handleDailySummary(json)
        }
    }

    /// F7 (D5, §2.4) — `startSession{wristAuthoritative:true, plan, guided}`.
    /// Arriva anche a processo morto (listener nel manifest). Prima si
    /// recupera un eventuale checkpoint interrotto (che conta come sessione
    /// viva), poi GuidedStartPolicy (replay/stale/sessione viva ⇒ ignora),
    /// poi l'avvio vero ([WristGuidedStart.apply]). Mai una sessione companion.
    private fun handleWristAuthoritativeStart(json: JSONObject) {
        val ctx = applicationContext
        TrackingEngine.init(ctx)
        TrackingEngine.recoverIfNeeded(ctx)
        if (SessionRepository.state.value.let { it.isActive && it.isStandalone }) {
            // Sessione ripresa dal checkpoint: il service va (ri)agganciato.
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, TrackingService::class.java))
            }
        }
        val prefs = ctx.getSharedPreferences(GUIDED_PREFS, Context.MODE_PRIVATE)
        val last = if (prefs.contains(KEY_LAST_GUIDED_START)) prefs.getLong(KEY_LAST_GUIDED_START, 0L) else null
        val now = System.currentTimeMillis()
        val startedAt = json.optLong("startedAt", now)
        val decision = GuidedStartPolicy.decide(
            startedAtMs = startedAt,
            nowMs = now,
            lastGuidedStartAtMs = last,
            sessionIsIdle = !SessionRepository.state.value.isActive,
        )
        if (decision is GuidedStartPolicy.Decision.Ignore) {
            Log.i(TAG, "startSession guidato ignorato: ${decision.reason}")
            // J17 — il telefono lo sa (toast solo per `liveSession`).
            WristGuidedStart.sendStartIgnored(ctx, startedAt, decision.reason)
            return
        }
        // Aggiornato SOLO su Start: un replay scartato non sposta la soglia.
        prefs.edit().putLong(KEY_LAST_GUIDED_START, startedAt).apply()
        WristGuidedStart.apply(ctx, json, fromForeground = false)
    }

    /// F7 (D24, F2 §2.4) — striscia fase del companion; l'istante di
    /// ricezione (orologio del polso) fa scadere la striscia dopo 60 s.
    private fun handleGuidedStep(json: JSONObject) {
        // `{finished, clear:true}`: il telefono ha chiuso la guida, la
        // striscia sparisce subito invece di restare vuota per 60 s.
        if (json.optBoolean("clear", false)) {
            SessionRepository.clearGuidedStep()
            return
        }
        SessionRepository.updateGuidedStep(
            GuidedStepMirror(
                index = json.optInt("index", 0),
                total = json.optInt("total", 0),
                label = json.optString("label"),
                remaining = json.optString("remaining"),
                target = json.optString("target"),
                next = (json.opt("next") as? String)?.takeIf { it.isNotEmpty() },
                endsAt = (json.opt("endsAt") as? Number)?.toLong(),
                finished = json.optBoolean("finished", false),
                receivedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /// Riepilogo "Oggi" — message-only (mai applicationContext, vedi
    /// spec-contract.md), quindi lo store fa da cache persistente letta
    /// dalla VM al prossimo foreground. `appliedWaterSyncIds` è un
    /// belt-and-suspenders rispetto al syncAck: la coda acqua droppa anche
    /// qui i delta che il telefono conferma di aver già applicato.
    private fun handleDailySummary(json: JSONObject) {
        DailySummaryStore.apply(applicationContext, json)
        val appliedArr = json.optJSONArray("appliedWaterSyncIds") ?: return
        val applied = (0 until appliedArr.length()).map { appliedArr.optString(it) }
        WaterQueueStore.removeAll(applicationContext, applied)
    }

    /// Il phone conferma di aver persistito una routeSync/sessionSync.
    /// Manifest-registered, quindi arriva anche se il processo watch è
    /// stato riavviato dopo lo stopFromWatch che ha scritto la entry.
    /// Clear SOLO qui — mai su un semplice invio riuscito a livello di
    /// trasporto (vedi SessionViewModel.stopFromWatch / retry loop).
    private fun handleSyncAck(json: JSONObject) {
        val ackSyncId = json.optString("syncId").takeIf { it.isNotEmpty() } ?: return
        // Rimuove solo la entry che corrisponde a questo ack — la coda può
        // contenere altre route ancora in attesa (vedi PendingRouteStore).
        PendingRouteStore.remove(applicationContext, ackSyncId)
        // Un syncAck ora conferma ANCHE un waterDelta (spec-contract.md) —
        // rimozione no-op se l'id non è in coda (route sync vs water sync
        // condividono lo stesso comando).
        WaterQueueStore.remove(applicationContext, ackSyncId)
        // La entry è sparita del tutto: motivo persistito e stato "in
        // invio" locale non hanno più senso — mai lasciarli orfani.
        PendingSyncStatusStore.clearStatus(applicationContext, ackSyncId)
        PendingSyncCoordinator.onResponseReceived(ackSyncId)
    }

    /// Il telefono ha RICEVUTO una routeSync/sessionSync ma non l'ha
    /// salvata (non loggato, sessione aperta sul telefono, salvataggio
    /// fallito, payload illeggibile) — l'item RESTA in coda, solo il
    /// motivo mostrato in PendingSyncScreen cambia. Mai una remove qui,
    /// a differenza di handleSyncAck.
    private fun handleSyncStatus(json: JSONObject) {
        val syncId = json.optString("syncId").takeIf { it.isNotEmpty() } ?: return
        val reason = json.optString("reason").takeIf { it.isNotEmpty() } ?: "unknown"
        PendingSyncCoordinator.onResponseReceived(syncId)
        PendingSyncStatusStore.setStatus(applicationContext, syncId, reason)
    }

    /// Il telefono chiede di ricevere di nuovo tutta la coda ora (dopo un
    /// login, o al ritorno in foreground) — stesso percorso del flush
    /// automatico/manuale, nessuna logica dedicata da mantenere. Una
    /// PhoneConnector locale basta: il transport-level è stateless.
    private fun handleRequestPendingSync() {
        PendingSyncCoordinator.sendAll(applicationContext, PhoneConnector(applicationContext))
    }

    // MARK: - Workout helpers

    private fun handleWorkoutStart(json: JSONObject) {
        val startedAtStr = json.optString("startedAt")
        // Phone may send ISO string OR millis depending on whether the
        // bridge serialised it as `DateTime.toIso8601String()` (iOS) or
        // `millisecondsSinceEpoch` (Android). Try both.
        val startedAt = startedAtStr.toLongOrNull()
            ?: runCatching { java.time.Instant.parse(startedAtStr).toEpochMilli() }
                .getOrDefault(System.currentTimeMillis())

        val plan   = json.optJSONObject("plan")
        val cursor = json.optJSONObject("cursor")
        val target = json.optJSONObject("target")
        val ctx = WorkoutContext(
            planName  = plan?.optString("planName")  ?: "",
            weekLabel = plan?.optString("weekLabel") ?: "",
            dayLabel  = plan?.optString("dayLabel")  ?: "",
            cursor = cursorFrom(cursor),
            target = targetFrom(target),
        )
        SessionRepository.startWorkoutSession(startedAt, ctx)
    }

    private fun handleWorkoutCursor(json: JSONObject) {
        val cursor = cursorFrom(json.optJSONObject("cursor"))
        val target = targetFrom(json.optJSONObject("target"))
        val done   = json.optInt("completedExercises", 0)
        // Rest rides along with the cursor (atomic with the set change);
        // absent keys = no rest in progress.
        val restEndAtMs = if (json.has("restSeconds")) {
            val total = json.optInt("restSeconds")
            val startedAtStr = json.optString("restStartedAt")
            val startedMs = startedAtStr.toLongOrNull()
                ?: runCatching { java.time.Instant.parse(startedAtStr).toEpochMilli() }
                    .getOrNull()
            startedMs?.plus(total * 1000L)
        } else null
        SessionRepository.updateWorkoutCursor(cursor, target, done, restEndAtMs)
    }

    private fun handleWorkoutStartRest(json: JSONObject) {
        val total = json.optInt("restSeconds", 0)
        val startedAtStr = json.optString("restStartedAt")
        val startedAtMs = startedAtStr.toLongOrNull()
            ?: runCatching { java.time.Instant.parse(startedAtStr).toEpochMilli() }
                .getOrDefault(System.currentTimeMillis())
        if (total <= 0) return
        SessionRepository.startWorkoutRest(total, startedAtMs)
    }

    private fun cursorFrom(o: JSONObject?): WorkoutCursor {
        if (o == null) return WorkoutCursor()
        return WorkoutCursor(
            groupIndex         = o.optInt("groupIndex", 0),
            roundIndex         = o.optInt("roundIndex", 0),
            exerciseIndex      = o.optInt("exerciseIndex", 0),
            totalGroups        = o.optInt("totalGroups", 1),
            totalRoundsInGroup = o.optInt("totalRoundsInGroup", 1),
        )
    }

    private fun targetFrom(o: JSONObject?): WorkoutTarget {
        if (o == null) return WorkoutTarget()
        return WorkoutTarget(
            exerciseName    = o.optString("exerciseName"),
            reps            = if (o.has("reps")) o.optInt("reps") else null,
            weight          = if (o.has("weight")) o.optDouble("weight") else null,
            durationSeconds = if (o.has("durationSeconds")) o.optInt("durationSeconds") else null,
            restSeconds     = if (o.has("restSeconds")) o.optInt("restSeconds") else null,
        )
    }

    companion object {
        private const val TAG = "PhoneMessageService"

        /// GuidedStartPolicy (§2.4): ultimo `startedAt` guidato APPLICATO.
        internal const val GUIDED_PREFS = "jimtime_guided"
        private const val KEY_LAST_GUIDED_START = "lastGuidedStartAtMs"
    }
}

/**
 * F7 (D5 + correzioni verificatore) — avvio di una sessione posseduta dal
 * polso a partire dal payload `startSession{wristAuthoritative}`, dal
 * listener (background) o da MainActivity (primo piano, avvio in sospeso).
 *
 * Wear OS 4/5 può negare la FGS avviata da background
 * (`ForegroundServiceStartNotAllowedException`) o il suo `startForeground
 * (location)` senza il permesso «durante l'uso» (SecurityException nel
 * service). In entrambi i casi la sessione torna a riposo, il payload resta
 * come AVVIO IN SOSPESO e una notifica «Tocca per iniziare» riapre l'app:
 * in primo piano MainActivity lo consuma ([consumePending]) se è ancora
 * valido per GuidedStartPolicy (10 min).
 */
object WristGuidedStart {

    private const val TAG = "WristGuidedStart"
    private const val KEY_PENDING = "pendingStart"
    private const val CHANNEL_ID = "guided_start_channel"
    private const val NOTIFICATION_ID = 1002

    /// Risposte best-effort (planActivityStarted) fuori dal thread del
    /// chiamante: il processo resta vivo grazie alla FGS appena avviata.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Apre la sessione, arma il motore, avvia TrackingService. `false` se la
     * FGS è stata rifiutata: stato riportato a riposo, avvio in sospeso
     * salvato e notificato, nessuna risposta al telefono.
     */
    fun apply(ctx: Context, json: JSONObject, fromForeground: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val startedAt = json.optLong("startedAt", now)
        val type = json.optString("type", "run")
        val plan = json.optJSONObject(MessagePaths.KEY_PLAN)
        val guided = json.optJSONObject(MessagePaths.KEY_GUIDED)
        val structure = guided?.optJSONObject("structure")
            ?.let { runCatching { ResolvedStructure.fromWire(it) }.getOrNull() }
            ?.takeIf { !it.isEmpty }
        val variant = (guided?.opt("variant") as? String)?.takeIf { it.isNotEmpty() }
        val title = (guided?.opt("title") as? String)?.takeIf { it.isNotEmpty() }
        val sport = (guided?.opt("sport") as? String)?.takeIf { it.isNotEmpty() } ?: type
        if (PlanDaysStore.lang.value.isEmpty()) PlanDaysStore.load(ctx)
        val lang = (guided?.opt("lang") as? String)?.takeIf { it.isNotEmpty() }
            ?: PlanDaysStore.lang.value.ifEmpty { Locale.getDefault().language }

        // Salvato PRIMA dell'avvio: se TrackingService.startForeground
        // fallisce dopo (in modo asincrono) lo ritrova e lo notifica.
        savePending(ctx, json)

        TrackingEngine.init(ctx)
        TrackingEngine.workoutManager.resetHrAccumulation()
        if (structure != null) {
            GuidedRunRepository.start(
                structure = structure,
                planRef = plan ?: JSONObject(),
                lang = lang,
                title = title,
                sport = sport,
                variant = variant,
                hasDistanceSignal = TrackingEngine.isGpsEligible(type),
                profile = guided.optJSONObject("profile"),
            )
        }
        SessionRepository.startStandaloneSession(
            type,
            planRefJson = plan?.toString(),
            guided = structure != null,
            guidedVariant = if (structure != null) variant else null,
            startedAt = startedAt,
        )
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TrackingService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "FGS rifiutata (${if (fromForeground) "primo piano" else "background"}): $e")
            onForegroundRefused(ctx)
            return false
        }
        ctx.vibrate(200)
        if (!fromForeground) {
            try {
                ctx.startActivity(
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MessagePaths.EXTRA_OPEN_SESSION, true),
                )
            } catch (e: Exception) {
                // BAL: avvio da background negato — il chip del service resta.
                Log.w(TAG, "startActivity da background negato: $e")
            }
        }

        val reply = JSONObject()
            .put("cmd", MessagePaths.CMD_PLAN_ACTIVITY_STARTED)
            .put("platform", MessagePaths.PLATFORM_WEAR)
            .put("sport", sport)
            .put("startedAt", startedAt)
            .put("guided", structure != null)
        plan?.let { reply.put(MessagePaths.KEY_PLAN, it) }
        title?.let { reply.put("title", it) }
        scope.launch { PhoneConnector(ctx).sendMirror(reply) }
        return true
    }

    /// F7 (J17) — `planActivityStartIgnored`, puro (testato).
    fun startIgnoredMessage(startedAt: Long, reason: String): JSONObject =
        JSONObject()
            .put("cmd", MessagePaths.CMD_PLAN_ACTIVITY_START_IGNORED)
            .put("platform", MessagePaths.PLATFORM_WEAR)
            .put("startedAt", startedAt)
            .put("reason", reason)

    /// Best-effort: solo se il telefono è raggiungibile, nessun retry.
    fun sendStartIgnored(ctx: Context, startedAt: Long, reason: String) {
        val msg = startIgnoredMessage(startedAt, reason)
        scope.launch { runCatching { PhoneConnector(ctx).sendMirror(msg) } }
    }

    /**
     * TrackingService (o [apply]) non è riuscito a mettersi in primo piano:
     * se la sessione è quella dell'avvio in sospeso la riporta a riposo e
     * posta la notifica «Tocca per iniziare». No-op per le altre sessioni.
     */
    fun onForegroundRefused(ctx: Context) {
        val pending = loadPending(ctx) ?: return
        val state = SessionRepository.state.value
        val startedAt = pending.optLong("startedAt", -1L)
        if (state.isActive && state.startedAt != startedAt) return
        GuidedRunRepository.stop()
        if (state.isActive) SessionRepository.stopSession()
        notifyPending(ctx, pending)
    }

    /// TrackingService è in primo piano: l'avvio è riuscito, niente più sospeso.
    fun onForegroundStarted(ctx: Context) {
        val pending = loadPending(ctx) ?: return
        if (SessionRepository.state.value.startedAt == pending.optLong("startedAt", -1L)) clearPending(ctx)
    }

    /**
     * MainActivity in primo piano (tocco sulla notifica): avvia l'avvio in
     * sospeso se ancora valido (GuidedStartPolicy: non oltre 10 min, nessuna
     * sessione viva). `true` se partito. Il sospeso si consuma comunque.
     */
    fun consumePending(ctx: Context): Boolean {
        val pending = loadPending(ctx) ?: return false
        clearPending(ctx)
        cancelNotification(ctx)
        val now = System.currentTimeMillis()
        val decision = GuidedStartPolicy.decide(
            startedAtMs = pending.optLong("startedAt", now),
            nowMs = now,
            // Già deduplicato all'arrivo; qui contano solo età e sessione viva.
            lastGuidedStartAtMs = null,
            sessionIsIdle = !SessionRepository.state.value.isActive,
        )
        if (decision is GuidedStartPolicy.Decision.Ignore) {
            Log.i(TAG, "avvio in sospeso scartato: ${decision.reason}")
            return false
        }
        return apply(ctx, pending, fromForeground = true)
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PhoneMessageService.GUIDED_PREFS, Context.MODE_PRIVATE)

    private fun savePending(ctx: Context, json: JSONObject) {
        prefs(ctx).edit().putString(KEY_PENDING, json.toString()).commit()
    }

    private fun loadPending(ctx: Context): JSONObject? =
        prefs(ctx).getString(KEY_PENDING, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun clearPending(ctx: Context) {
        prefs(ctx).edit().remove(KEY_PENDING).apply()
    }

    /// Testo IT come il resto dell'app Wear (solo GuidedStrings è tradotto).
    private fun notifyPending(ctx: Context, pending: JSONObject) {
        val title = pending.optJSONObject(MessagePaths.KEY_GUIDED)?.optString("title")?.takeIf { it.isNotEmpty() }
            ?: SportsCatalog.label(pending.optString("type", "run"))
        try {
            val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Avvio dal telefono", NotificationManager.IMPORTANCE_HIGH),
            )
            val intent = PendingIntent.getActivity(
                ctx, NOTIFICATION_ID,
                Intent(ctx, MainActivity::class.java)
                    // Activity già viva: CLEAR_TOP+SINGLE_TOP consegnano
                    // l'extra a onNewIntent invece di limitarsi a portarla
                    // in primo piano.
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MessagePaths.EXTRA_PENDING_START, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_tracking)
                .setContentTitle("Tocca per iniziare")
                .setContentText(title)
                .setContentIntent(intent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // POST_NOTIFICATIONS negato: resta la vibrazione del telefono.
            Log.w(TAG, "notifica avvio in sospeso non postata: $e")
        }
    }

    private fun cancelNotification(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }
}
