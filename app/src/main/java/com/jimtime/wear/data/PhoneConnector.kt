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
        try {
            val nodes = nodeClient.connectedNodes.await()
            if (nodes.isEmpty()) {
                Log.w("PhoneConnector", "No connected nodes")
                return
            }
            val payload = JSONObject(mapOf("cmd" to cmd)).toString().toByteArray()
            nodes.forEach { node ->
                messageClient.sendMessage(node.id, MessagePaths.PATH_WATCH, payload).await()
            }
        } catch (e: Exception) {
            Log.e("PhoneConnector", "sendToPhone error: $e")
        }
    }

    suspend fun sendRouteToPhone(
        points: List<GpsPoint>,
        activityType: String,
        startedAt: Long,
        endedAt: Long,
    ) {
        try {
            val nodes = nodeClient.connectedNodes.await()
            if (nodes.isEmpty()) {
                Log.w("PhoneConnector", "No nodes — route not sent")
                return
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
                put("cmd",       "routeSync")
                put("type",      activityType)
                put("startedAt", startedAt)
                put("endedAt",   endedAt)
                put("points",    arr)
            }.toString().toByteArray()

            nodes.forEach { node ->
                messageClient.sendMessage(node.id, MessagePaths.PATH_WATCH, payload).await()
            }
            Log.d("PhoneConnector", "Route sent: ${points.size} points")
        } catch (e: Exception) {
            Log.e("PhoneConnector", "sendRouteToPhone error: $e")
        }
    }
}
