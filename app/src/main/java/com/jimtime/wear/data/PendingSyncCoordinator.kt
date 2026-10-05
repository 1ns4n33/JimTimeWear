package com.jimtime.wear.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Percorso UNICO di invio per un item della coda [PendingRouteStore] —
 * usato sia dal "Sincronizza tutti"/CMD_REQUEST_PENDING_SYNC (flush
 * automatico) sia dal tap manuale su una riga di PendingSyncScreen. Mirror
 * di `sendQueuedItem`/`sendingSyncIds` in WatchConnectivity.swift lato iOS:
 * stesso timeout di 10s ("noResponse" se il telefono non risponde),
 * "phoneUnreachable" se non c'è nemmeno un nodo connesso o l'invio fallisce
 * a livello di trasporto.
 *
 * `sendingIds` è VOLUTAMENTE in-memory (mai persistito): un riavvio del
 * processo watch mentre un invio è "in corso" lo riporta correttamente a
 * "In attesa" invece di restare bloccato per sempre su "Invio…".
 */
object PendingSyncCoordinator {

    private const val SEND_TIMEOUT_MS = 10_000L

    private val _sendingIds = MutableStateFlow<Set<String>>(emptySet())
    val sendingIds: StateFlow<Set<String>> = _sendingIds.asStateFlow()

    // Toccato da più thread (lo scope gira su Dispatchers.Default, e
    // onResponseReceived arriva dal thread del WearableListenerService):
    // mappa concorrente e aggiornamenti atomici di `_sendingIds` — con una
    // mappa semplice e `value = value + id` due invii paralleli di
    // "Sincronizza tutti" potevano perdere un aggiornamento e lasciare una
    // riga su "Invio…" per sempre (verifica 29/9).
    private val timeoutJobs = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob())

    /// Rimanda OGNI route ancora in coda — CMD_REQUEST_PENDING_SYNC e il tap
    /// "Sincronizza tutti" condividono questo stesso percorso. Le entry
    /// legacy (syncId nullo, pre-queue) restano fuori: non riceveranno mai
    /// un ack app-level, le copre solo il vecchio retry "clear on successful
    /// send" di SessionViewModel.startPendingRouteRetry.
    fun sendAll(context: Context, phoneConnector: PhoneConnector) {
        PendingRouteStore.loadAll(context).forEach { route -> sendOne(context, phoneConnector, route) }
    }

    fun sendOne(context: Context, phoneConnector: PhoneConnector, route: PendingRouteStore.PendingRoute) {
        val syncId = route.syncId ?: return
        // Presa atomica PRIMA di lanciare: due tap sulla stessa riga (o un tap
        // più un requestPendingSync) non devono partire entrambi. Controllo e
        // scrittura separati si scavalcavano fra thread diversi.
        if (!claimSending(syncId)) return
        scope.launch {
            if (!phoneConnector.isPhoneReachable()) {
                // Niente tentativo a vuoto: nessun nodo connesso, dillo
                // subito invece di aspettare un timeout che non scatterà
                // mai (nessun sendMessage è partito).
                clearSending(syncId)
                PendingSyncStatusStore.setStatus(context, syncId, "phoneUnreachable")
                return@launch
            }
            // Un nuovo tentativo azzera il motivo locale precedente (un
            // "telefono non raggiungibile" di 5 minuti fa non deve restare
            // appiccicato mentre il nuovo invio è in corso).
            PendingSyncStatusStore.clearStatus(context, syncId)
            scheduleTimeout(context, syncId)
            val sent = phoneConnector.sendRouteToPhone(
                points         = route.points,
                activityType   = route.activityType,
                startedAt      = route.startedAt,
                endedAt        = route.endedAt,
                avgHr          = route.avgHr,
                maxHr          = route.maxHr,
                syncId         = syncId,
                distanceMeters = route.distanceMeters,
                swim           = route.swimJson?.let { runCatching { JSONObject(it) }.getOrNull() },
            )
            if (!sent) {
                cancelTimeout(syncId)
                clearSending(syncId)
                PendingSyncStatusStore.setStatus(context, syncId, "phoneUnreachable")
            }
            // Invio riuscito a livello di trasporto: resta "Invio…" finché
            // non arriva syncAck/syncStatus (onResponseReceived) o scatta il
            // timeout (scheduleTimeout) — mai una clear ottimistica qui.
        }
    }

    /// Il telefono ha risposto (syncAck O syncStatus) — in entrambi i casi
    /// il "Invio…" locale non ha più senso, il motivo (se c'è) lo scrive il
    /// chiamante (PhoneMessageService).
    fun onResponseReceived(syncId: String) {
        cancelTimeout(syncId)
        clearSending(syncId)
    }

    private fun scheduleTimeout(context: Context, syncId: String) {
        lateinit var job: Job
        job = scope.launch {
            delay(SEND_TIMEOUT_MS)
            // Solo la PROPRIA voce: un reinvio può averne già registrata una
            // nuova per lo stesso id.
            timeoutJobs.remove(syncId, job)
            // Se nel frattempo è arrivata una risposta, sending è già stato
            // ripulito da onResponseReceived: non sovrascrivere.
            if (releaseSending(syncId)) {
                PendingSyncStatusStore.setStatus(context, syncId, "noResponse")
            }
        }
        timeoutJobs.put(syncId, job)?.cancel()
    }

    private fun cancelTimeout(syncId: String) {
        timeoutJobs.remove(syncId)?.cancel()
    }

    /// Solo per il seed finto da screenshot (MainActivity, dietro
    /// BuildConfig.DEBUG) — marca un syncId "in invio" senza che nessun
    /// invio reale sia partito, per fotografare lo stato "Invio…" di
    /// PendingSyncScreen senza un vero telefono connesso.
    fun debugMarkSending(id: String) {
        claimSending(id)
    }

    /// Aggiunge [id] agli invii in corso se non c'è già; `true` = preso ora.
    internal fun claimSending(id: String): Boolean {
        var claimed = false
        _sendingIds.update { current ->
            claimed = id !in current
            if (claimed) current + id else current
        }
        return claimed
    }

    /// Toglie [id] dagli invii in corso; `true` = c'era (e l'ha tolto questa
    /// chiamata, non un'altra in parallelo).
    internal fun releaseSending(id: String): Boolean {
        var released = false
        _sendingIds.update { current ->
            released = id in current
            if (released) current - id else current
        }
        return released
    }

    private fun clearSending(id: String) {
        releaseSending(id)
    }
}
