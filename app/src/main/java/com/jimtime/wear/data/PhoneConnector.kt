package com.jimtime.wear.data

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

class PhoneConnector(private val context: Context) {

    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient    = Wearable.getNodeClient(context)

    suspend fun isPhoneReachable(): Boolean =
        runCatching { nodeClient.connectedNodes.await().isNotEmpty() }.getOrDefault(false)

    suspend fun sendToPhone(cmd: String) {
        sendToPhone(cmd, emptyMap())
    }

    /// Variant that carries extra payload keys — used by the workout
    /// flow to send `kind: "workout"` plus optional reps/weight on
    /// `completeSet`.
    suspend fun sendToPhone(cmd: String, extras: Map<String, Any?>) {
        try {
            val nodes = nodeClient.connectedNodes.await()
            if (nodes.isEmpty()) {
                Log.w("PhoneConnector", "No connected nodes")
                return
            }
            val merged = mutableMapOf<String, Any?>("cmd" to cmd)
            for ((k, v) in extras) if (v != null) merged[k] = v
            val payload = JSONObject(merged.toMap()).toString().toByteArray()
            nodes.forEach { node ->
                messageClient.sendMessage(node.id, MessagePaths.PATH_WATCH, payload).await()
            }
        } catch (e: Exception) {
            Log.e("PhoneConnector", "sendToPhone error: $e")
        }
    }

    /// Delivers a standalone session summary to the phone: `routeSync`
    /// when GPS points exist, `sessionSync` (routeless, e.g. indoor on
    /// the wrist) when they don't. Returns true only on confirmed send
    /// so callers can keep the payload pending otherwise.
    ///
    /// `syncId` rides along so the phone can ack it back (CMD_SYNC_ACK)
    /// once persisted — only that ack, not this transport-level success,
    /// authorizes clearing PendingRouteStore.
    suspend fun sendRouteToPhone(
        points: List<GpsPoint>,
        activityType: String,
        startedAt: Long,
        endedAt: Long,
        avgHr: Double? = null,
        maxHr: Double? = null,
        syncId: String? = null,
    ): Boolean {
        return try {
            val nodes = nodeClient.connectedNodes.await()
            if (nodes.isEmpty()) {
                Log.w("PhoneConnector", "No nodes — route not sent")
                return false
            }
            val arr = JSONArray()
            // Downsample SOLO qui, all'invio: PendingRouteStore e il
            // checkpoint conservano la risoluzione piena. Una rotta di
            // ore altrimenti supera facilmente il limite ~100KB del
            // MessageClient.
            downsample(points, MAX_SENT_POINTS).forEach { p ->
                arr.put(JSONObject().apply {
                    put("lat", round(p.lat, 6))
                    put("lng", round(p.lng, 6))
                    put("alt", round(p.altitude, 1))
                    put("spd", round(p.speed, 1))
                    put("ts",  p.timestampMs)
                })
            }
            val payload = JSONObject().apply {
                put(
                    "cmd",
                    if (points.isEmpty()) MessagePaths.CMD_SESSION_SYNC
                    else MessagePaths.CMD_ROUTE_SYNC,
                )
                put("type",      activityType)
                put("startedAt", startedAt)
                put("endedAt",   endedAt)
                put("points",    arr)
                if (avgHr != null) put("avgHr", avgHr)
                if (maxHr != null) put("maxHr", maxHr)
                if (syncId != null) put("syncId", syncId)
            }.toString().toByteArray()

            nodes.forEach { node ->
                messageClient.sendMessage(node.id, MessagePaths.PATH_WATCH, payload).await()
            }
            Log.d("PhoneConnector", "Session sent: ${points.size} points")
            true
        } catch (e: Exception) {
            Log.e("PhoneConnector", "sendRouteToPhone error: $e")
            false
        }
    }

    companion object {
        private const val MAX_SENT_POINTS = 600

        /// Sotto-campiona linearmente a `max` punti mantenendo SEMPRE
        /// l'ultimo (mirror della strategia compactedForContext lato iOS).
        private fun downsample(points: List<GpsPoint>, max: Int): List<GpsPoint> {
            if (points.size <= max) return points
            val step = points.size.toDouble() / max
            val result = ArrayList<GpsPoint>(max)
            for (i in 0 until max - 1) {
                result.add(points[(i * step).toInt()])
            }
            result.add(points.last())
            return result
        }

        /// org.json stampa i double con precisione piena (17 cifre
        /// significative per coordinata) — triplica il payload per niente:
        /// 6 decimali ≈ 11cm, abbondantemente sotto il rumore GPS.
        private fun round(value: Double, decimals: Int): Double {
            val factor = Math.pow(10.0, decimals.toDouble())
            return Math.round(value * factor) / factor
        }
    }
}
