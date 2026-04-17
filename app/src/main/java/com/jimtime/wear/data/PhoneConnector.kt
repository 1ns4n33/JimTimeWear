package com.jimtime.wear.data

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class PhoneConnector(private val context: Context) {

    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient    = Wearable.getNodeClient(context)

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
}
