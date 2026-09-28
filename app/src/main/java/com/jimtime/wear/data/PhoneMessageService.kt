package com.jimtime.wear.data

import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.jimtime.wear.health.TrackingService
import org.json.JSONArray
import org.json.JSONObject

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
                val days = json.optJSONArray("days") ?: return
                PlanDaysStore.apply(applicationContext, json.optString("planName"), days)
            }
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
            MessagePaths.CMD_DAILY_SUMMARY -> handleDailySummary(json)
        }
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
}
