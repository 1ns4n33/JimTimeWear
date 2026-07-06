package com.jimtime.wear.data

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
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
                SessionRepository.startSession(type, startedAt)
            }
            MessagePaths.CMD_STOP_SESSION  -> SessionRepository.stopSession()
            MessagePaths.CMD_PAUSE_SESSION -> SessionRepository.pauseSession()
            MessagePaths.CMD_RESUME_SESSION -> SessionRepository.resumeSession()
            MessagePaths.CMD_PLAN_DAYS -> {
                val days = json.optJSONArray("days") ?: return
                PlanDaysStore.apply(applicationContext, json.optString("planName"), days)
            }
        }
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
