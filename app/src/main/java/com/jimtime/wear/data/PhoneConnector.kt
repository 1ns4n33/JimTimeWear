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
    suspend fun sendRouteToPhone(
        points: List<GpsPoint>,
        activityType: String,
        startedAt: Long,
        endedAt: Long,
        avgHr: Double? = null,
        maxHr: Double? = null,
    ): Boolean {
        return try {
            val nodes = nodeClient.connectedNodes.await()
            if (nodes.isEmpty()) {
                Log.w("PhoneConnector", "No nodes — route not sent")
                return false
            }
            val arr = JSONArray()
            points.forEach { p ->
                arr.put(JSONObject().apply {
                    put("lat", p.lat)
                    put("lng", p.lng)
                    put("alt", p.altitude)
                    put("spd", p.speed)
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
}
