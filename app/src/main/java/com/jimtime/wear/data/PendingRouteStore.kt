package com.jimtime.wear.data

import android.content.SharedPreferences
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists standalone GPS routes to SharedPreferences so they survive app
 * kills. An ORDERED QUEUE keyed by syncId — not a single overwritable slot
 * — because a second standalone session can be finalized
 * (SessionViewModel.stopFromWatch / TrackingEngine.recoverIfNeeded) before
 * the phone acks the first one; a single slot would silently drop whichever
 * route wasn't acked yet. Entries are removed individually: normally via
 * the phone's syncAck (PhoneMessageService.handleSyncAck), or — for legacy
 * pre-syncId entries that can never receive one — once successfully sent
 * (SessionViewModel.startPendingRouteRetry).
 */
object PendingRouteStore {

    private const val PREFS_NAME = "jimtime_pending_route"
    private const val KEY_ROUTES = "routes"

    // Vecchie chiavi a slot singolo (pre-queue) — lette una sola volta per
    // migrare dati già scritti su device reali, mai più scritte.
    private const val LEGACY_KEY_POINTS  = "points"
    private const val LEGACY_KEY_TYPE    = "activityType"
    private const val LEGACY_KEY_STARTED = "startedAt"
    private const val LEGACY_KEY_ENDED   = "endedAt"
    private const val LEGACY_KEY_AVG_HR  = "avgHr"
    private const val LEGACY_KEY_MAX_HR  = "maxHr"
    private const val LEGACY_KEY_SYNC_ID = "syncId"

    data class PendingRoute(
        val points: List<GpsPoint>,
        val activityType: String,
        val startedAt: Long,
        val endedAt: Long,
        val avgHr: Double? = null,
        val maxHr: Double? = null,
        // Correla l'ack del phone (CMD_SYNC_ACK) con questa entry — null
        // solo per dati scritti prima di questo campo (mai popolato quindi
        // il phone non manderà mai un ack per questa entry, vedi
        // SessionViewModel.startPendingRouteRetry).
        val syncId: String? = null,
    )

    /// Accoda una nuova route in attesa di sync — MAI overwrite: due
    /// sessioni standalone possono concludersi prima che il phone confermi
    /// la prima, e uno slot singolo perderebbe silenziosamente quella non
    /// ancora ackata.
    fun save(context: Context, route: PendingRoute) {
        val routes = loadAll(context).toMutableList()
        routes.add(route)
        writeAll(context, routes)
    }

    /// Tutte le route ancora in attesa di sync, in ordine di inserimento.
    fun loadAll(context: Context): List<PendingRoute> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_ROUTES)) {
            // Migrazione one-shot dal vecchio slot singolo, se presente su
            // questo device (build precedente all'introduzione della coda).
            val legacy = loadLegacySlot(prefs) ?: return emptyList()
            writeAll(context, listOf(legacy))
            return listOf(legacy)
        }
        val raw = prefs.getString(KEY_ROUTES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> routeFromJson(arr.optJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    /// La route più vecchia ancora in coda, se presente — usata dove serve
    /// solo sapere "c'è qualcosa in attesa" (TrackingService.finishAndStop).
    fun load(context: Context): PendingRoute? = loadAll(context).firstOrNull()

    /// Rimuove SOLO la entry corrispondente a `syncId`; per le entry legacy
    /// (syncId nullo, scritte prima dell'introduzione del campo) usa
    /// `startedAt` per individuare quella giusta invece di svuotare l'intera
    /// coda — vedi PhoneMessageService.handleSyncAck e il retry loop.
    fun remove(context: Context, syncId: String?, startedAt: Long? = null) {
        val remaining = loadAll(context).filterNot { route ->
            if (syncId != null) route.syncId == syncId
            else route.syncId == null && (startedAt == null || route.startedAt == startedAt)
        }
        writeAll(context, remaining)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun writeAll(context: Context, routes: List<PendingRoute>) {
        val arr = JSONArray()
        routes.forEach { arr.put(routeToJson(it)) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ROUTES, arr.toString())
            .apply()
    }

    private fun routeToJson(route: PendingRoute): JSONObject {
        val points = JSONArray()
        route.points.forEach { p ->
            points.put(JSONObject().apply {
                put("lat", p.lat)
                put("lng", p.lng)
                put("alt", p.altitude)
                put("spd", p.speed)
                put("ts",  p.timestampMs)
            })
        }
        return JSONObject().apply {
            put("points", points)
            put("activityType", route.activityType)
            put("startedAt", route.startedAt)
            put("endedAt", route.endedAt)
            if (route.avgHr != null) put("avgHr", route.avgHr)
            if (route.maxHr != null) put("maxHr", route.maxHr)
            if (route.syncId != null) put("syncId", route.syncId)
        }
    }

    private fun routeFromJson(o: JSONObject?): PendingRoute? {
        if (o == null) return null
        return runCatching {
            val arr    = o.optJSONArray("points") ?: JSONArray()
            val points = (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                GpsPoint(
                    lat         = p.getDouble("lat"),
                    lng         = p.getDouble("lng"),
                    altitude    = p.getDouble("alt"),
                    speed       = p.getDouble("spd"),
                    timestampMs = p.getLong("ts"),
                )
            }
            PendingRoute(
                points       = points,
                activityType = o.optString("activityType", "run"),
                startedAt    = o.optLong("startedAt", 0L),
                endedAt      = o.optLong("endedAt", 0L),
                avgHr        = if (o.has("avgHr")) o.optDouble("avgHr") else null,
                maxHr        = if (o.has("maxHr")) o.optDouble("maxHr") else null,
                syncId       = if (o.has("syncId")) o.optString("syncId") else null,
            )
        }.getOrNull()
    }

    private fun loadLegacySlot(prefs: SharedPreferences): PendingRoute? {
        val raw = prefs.getString(LEGACY_KEY_POINTS, null) ?: return null
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
                activityType = prefs.getString(LEGACY_KEY_TYPE, "run") ?: "run",
                startedAt    = prefs.getLong(LEGACY_KEY_STARTED, 0L),
                endedAt      = prefs.getLong(LEGACY_KEY_ENDED, 0L),
                avgHr        = if (prefs.contains(LEGACY_KEY_AVG_HR))
                                   prefs.getFloat(LEGACY_KEY_AVG_HR, 0f).toDouble() else null,
                maxHr        = if (prefs.contains(LEGACY_KEY_MAX_HR))
                                   prefs.getFloat(LEGACY_KEY_MAX_HR, 0f).toDouble() else null,
                syncId       = prefs.getString(LEGACY_KEY_SYNC_ID, null),
            )
        }.getOrNull()
    }
}
