package com.jimtime.wear.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Motivo per cui il telefono ha RICEVUTO ma non ancora SALVATO una
 * routeSync/sessionSync in coda (CMD_SYNC_STATUS) — mirror di
 * `pendingSyncStatus` in WatchConnectivity.swift lato iOS.
 *
 * Persistito: una entry sopravvive a un riavvio del processo watch finché
 * la route corrispondente resta in [PendingRouteStore]. Pulita SOLO da un
 * syncAck (route rimossa del tutto, vedi PhoneMessageService.handleSyncAck)
 * o dall'inizio di un nuovo tentativo di invio ([PendingSyncCoordinator]) —
 * mai da un timeout locale, che scrive semmai "noResponse".
 */
object PendingSyncStatusStore {

    private const val PREFS_NAME = "jimtime_pending_sync_status"
    private const val KEY_STATUSES = "statuses"

    private val _statuses = MutableStateFlow<Map<String, String>>(emptyMap())
    val statuses: StateFlow<Map<String, String>> = _statuses.asStateFlow()

    fun load(context: Context) {
        _statuses.value = readAll(context)
    }

    @Synchronized
    fun setStatus(context: Context, syncId: String, reason: String) {
        val current = readAll(context).toMutableMap()
        current[syncId] = reason
        writeAll(context, current)
    }

    @Synchronized
    fun clearStatus(context: Context, syncId: String) {
        val current = readAll(context).toMutableMap()
        if (current.remove(syncId) != null) writeAll(context, current)
    }

    private fun readAll(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_STATUSES, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        }.getOrDefault(emptyMap())
    }

    private fun writeAll(context: Context, statuses: Map<String, String>) {
        val obj = JSONObject()
        statuses.forEach { (k, v) -> obj.put(k, v) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_STATUSES, obj.toString())
            .apply()
        _statuses.value = statuses
    }
}
