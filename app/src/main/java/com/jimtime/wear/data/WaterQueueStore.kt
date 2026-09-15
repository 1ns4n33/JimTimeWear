package com.jimtime.wear.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class WaterDelta(
    val syncId: String,
    val deltaMl: Int,
    val date: String,
    val at: Long,
)

/**
 * Pending water taps not yet acknowledged by the phone. One entry per tap
 * (never coalesced — the contract requires a distinct syncId per delta).
 * Both the WearableListenerService (syncAck / appliedWaterSyncIds) and the
 * ViewModel (enqueue / flush) touch this store from potentially different
 * processes — always re-read prefs before mutating, same pattern as
 * PendingRouteStore.
 */
object WaterQueueStore {

    private const val PREFS_NAME = "jimtime_water_queue"
    private const val KEY_QUEUE = "queue"

    private val _queue = MutableStateFlow<List<WaterDelta>>(emptyList())
    val queue: StateFlow<List<WaterDelta>> = _queue.asStateFlow()

    fun load(context: Context) {
        _queue.value = readAll(context)
    }

    @Synchronized
    fun enqueue(context: Context, delta: WaterDelta) {
        val current = readAll(context).toMutableList()
        current.add(delta)
        writeAll(context, current)
    }

    @Synchronized
    fun remove(context: Context, syncId: String?) {
        if (syncId == null) return
        val remaining = readAll(context).filterNot { it.syncId == syncId }
        writeAll(context, remaining)
    }

    @Synchronized
    fun removeAll(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val remaining = readAll(context).filterNot { it.syncId in ids }
        writeAll(context, remaining)
    }

    private fun readAll(context: Context): List<WaterDelta> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_QUEUE, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> fromJson(arr.optJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(context: Context, deltas: List<WaterDelta>) {
        val arr = JSONArray()
        deltas.forEach { arr.put(toJson(it)) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_QUEUE, arr.toString())
            .apply()
        _queue.value = deltas
    }

    private fun toJson(delta: WaterDelta): JSONObject = JSONObject().apply {
        put("syncId", delta.syncId)
        put("deltaMl", delta.deltaMl)
        put("date", delta.date)
        put("at", delta.at)
    }

    private fun fromJson(o: JSONObject?): WaterDelta? {
        if (o == null) return null
        return runCatching {
            WaterDelta(
                syncId = o.getString("syncId"),
                deltaMl = o.optInt("deltaMl", 0),
                date = o.optString("date"),
                at = o.optLong("at", 0L),
            )
        }.getOrNull()
    }
}
