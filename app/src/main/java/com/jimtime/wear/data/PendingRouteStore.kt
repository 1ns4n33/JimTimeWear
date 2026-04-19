package com.jimtime.wear.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists a standalone GPS route to SharedPreferences so it survives app kills.
 * Cleared once successfully sent to the phone.
 */
object PendingRouteStore {

    private const val PREFS_NAME  = "jimtime_pending_route"
    private const val KEY_POINTS  = "points"
    private const val KEY_TYPE    = "activityType"
    private const val KEY_STARTED = "startedAt"
    private const val KEY_ENDED   = "endedAt"

    data class PendingRoute(
        val points: List<GpsPoint>,
        val activityType: String,
        val startedAt: Long,
        val endedAt: Long,
    )

    fun save(context: Context, route: PendingRoute) {
        val arr = JSONArray()
        route.points.forEach { p ->
            arr.put(JSONObject().apply {
                put("lat", p.lat)
                put("lng", p.lng)
                put("alt", p.altitude)
                put("spd", p.speed)
                put("ts",  p.timestampMs)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_POINTS,  arr.toString())
            .putString(KEY_TYPE,    route.activityType)
            .putLong(KEY_STARTED,   route.startedAt)
            .putLong(KEY_ENDED,     route.endedAt)
            .apply()
    }

    fun load(context: Context): PendingRoute? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw   = prefs.getString(KEY_POINTS, null) ?: return null
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
            PendingRoute(
                points       = points,
                activityType = prefs.getString(KEY_TYPE, "run") ?: "run",
                startedAt    = prefs.getLong(KEY_STARTED, 0L),
                endedAt      = prefs.getLong(KEY_ENDED, 0L),
            )
        }.getOrNull()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
