package com.jimtime.wear.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Checkpoint periodico di una sessione ACTIVITY standalone ANCORA IN
 * CORSO — a differenza di PendingRouteStore (sessione già conclusa, in
 * attesa di sync col phone), questo store esiste solo per sopravvivere a
 * un process death (kill di sistema mentre TrackingService girava in
 * background) senza perdere i punti GPS/HR accumulati finora. Scritto da
 * TrackingService ogni ~60s, letto da TrackingEngine.recoverIfNeeded al
 * restart sticky del service o alla riapertura dell'app.
 */
object ActiveSessionStore {

    private const val PREFS_NAME     = "jimtime_active_session"
    private const val KEY_POINTS     = "points"
    private const val KEY_TYPE       = "activityType"
    private const val KEY_STARTED    = "startedAt"
    private const val KEY_PAUSED     = "isPaused"
    private const val KEY_LAST_UPDATE = "lastUpdateMs"
    private const val KEY_HR_SUM     = "hrSum"
    private const val KEY_HR_COUNT   = "hrCount"
    private const val KEY_HR_MAX     = "hrMax"

    data class Checkpoint(
        val activityType: String,
        val startedAt: Long,
        val isPaused: Boolean,
        val lastUpdateMs: Long,
        val points: List<GpsPoint>,
        val hrSum: Double,
        val hrCount: Int,
        val hrMax: Double,
    )

    fun save(context: Context, checkpoint: Checkpoint) {
        val arr = JSONArray()
        checkpoint.points.forEach { p ->
            arr.put(JSONObject().apply {
                put("lat", p.lat)
                put("lng", p.lng)
                put("alt", p.altitude)
                put("spd", p.speed)
                put("ts",  p.timestampMs)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_POINTS, arr.toString())
            .putString(KEY_TYPE, checkpoint.activityType)
            .putLong(KEY_STARTED, checkpoint.startedAt)
            .putBoolean(KEY_PAUSED, checkpoint.isPaused)
            .putLong(KEY_LAST_UPDATE, checkpoint.lastUpdateMs)
            .putFloat(KEY_HR_SUM, checkpoint.hrSum.toFloat())
            .putInt(KEY_HR_COUNT, checkpoint.hrCount)
            .putFloat(KEY_HR_MAX, checkpoint.hrMax.toFloat())
            .apply()
    }

    fun load(context: Context): Checkpoint? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // KEY_POINTS è sempre scritto (anche "[]" per sessioni indoor senza
        // GPS) — la sua assenza è l'unico segnale affidabile di "nessun
        // checkpoint".
        if (!prefs.contains(KEY_POINTS)) return null
        val raw = prefs.getString(KEY_POINTS, null) ?: return null
        return runCatching {
            val arr    = JSONArray(raw)
            val points = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                GpsPoint(
                    lat         = o.getDouble("lat"),
                    lng         = o.getDouble("lng"),
                    altitude    = o.getDouble("alt"),
                    speed       = o.getDouble("spd"),
                    timestampMs = o.getLong("ts"),
                )
            }
            Checkpoint(
                activityType = prefs.getString(KEY_TYPE, "run") ?: "run",
                startedAt    = prefs.getLong(KEY_STARTED, 0L),
                isPaused     = prefs.getBoolean(KEY_PAUSED, false),
                lastUpdateMs = prefs.getLong(KEY_LAST_UPDATE, 0L),
                points       = points,
                hrSum        = prefs.getFloat(KEY_HR_SUM, 0f).toDouble(),
                hrCount      = prefs.getInt(KEY_HR_COUNT, 0),
                hrMax        = prefs.getFloat(KEY_HR_MAX, 0f).toDouble(),
            )
        }.getOrNull()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
