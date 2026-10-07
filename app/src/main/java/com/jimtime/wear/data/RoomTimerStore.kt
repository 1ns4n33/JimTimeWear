package com.jimtime.wear.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/// M1 (Arti marziali, timer di sala §7.1) — mirror del timer di sala del
/// coach: il TELEFONO è l'autorità, il polso è un telecomando che mostra
/// fase/round/conto alla rovescia dal messaggio `roomTimer`.
data class RoomTimerState(
    /// `live` | `ended` | `clear` (`clear` non resta mai nello store).
    val state: String,
    val lessonName: String,
    val kind: String,
    val phase: String,
    val label: String,
    val roundIndex: Int,
    val roundTotal: Int,
    /// Epoch ms; assente in pausa.
    val endsAt: Long?,
    /// «1:47» già pronto, usato in pausa o senza `endsAt`.
    val remaining: String,
    val paused: Boolean,
    val next: String,
    val finished: Boolean,
    val seq: Long?,
    val receivedAtMs: Long,
    /// Ordinale dello step corrente (unico per step, ripetizioni incluse);
    /// `null` = telefono vecchio: l'aptica ricade su round-fase-endsAt.
    val step: Int? = null,
    /// Numero di «lezione» nello store: cresce quando arriva un live dopo
    /// null/ended. Chiave dell'effetto di navigazione: un nuovo live dopo un
    /// dismiss o una lezione finita riapre il telecomando, i battiti no.
    val episode: Int = 0,
) {
    val isEnded: Boolean get() = state == RoomTimerStore.STATE_ENDED || finished
}

object RoomTimerStore {
    const val STATE_LIVE = "live"
    const val STATE_ENDED = "ended"
    const val STATE_CLEAR = "clear"

    /// Senza messaggi da 60 s il polso non si fida più: «Telefono?».
    const val EXPIRY_MS = 60_000L

    /// Dopo altri 60 s di silenzio (120 s dall'ultimo messaggio) lo store si
    /// dimentica da solo: il telecomando si chiude.
    const val FORGET_MS = 120_000L

    /// «Fine lezione» resta 3 s poi lo store si azzera.
    const val ENDED_CLEAR_MS = 3_000L

    // Azzeramenti nello store (non nel composable): sopravvivono a un
    // telecomando chiuso con lo swipe. Durate sovrascrivibili dai test.
    internal var forgetMs = FORGET_MS
    internal var endedClearMs = ENDED_CLEAR_MS
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timerJob: Job? = null
    private var episode = 0

    private val _state = MutableStateFlow<RoomTimerState?>(null)
    val state: StateFlow<RoomTimerState?> = _state

    private var lastSeq: Long? = null
    private var lastAtMs: Long = 0L

    /// Applica un messaggio `roomTimer`. `false` = scartato (seq vecchio).
    /// Parser tollerante: ogni campo ha un default; un telefono che riparte
    /// (seq che torna indietro) è accettato quando l'ultimo messaggio è già
    /// scaduto, così il polso non resta sordo per sempre.
    @Synchronized
    fun apply(json: JSONObject, nowMs: Long = System.currentTimeMillis()): Boolean {
        val seq = if (json.has("seq") && !json.isNull("seq")) json.optLong("seq") else null
        val prevSeq = lastSeq
        val fresh = nowMs - lastAtMs <= EXPIRY_MS
        if (seq != null && prevSeq != null && seq < prevSeq && fresh) return false
        lastSeq = seq ?: prevSeq
        lastAtMs = nowMs

        val st = json.optString("state", STATE_LIVE).ifBlank { STATE_LIVE }
        if (st == STATE_CLEAR) {
            clearLocked()
            return true
        }
        val endsAt = if (json.has("endsAt") && !json.isNull("endsAt")) json.optLong("endsAt") else null
        val prev = _state.value
        if (prev == null || prev.isEnded) episode++
        val newState = RoomTimerState(
            state = if (st == STATE_ENDED) STATE_ENDED else STATE_LIVE,
            lessonName = json.optString("lessonName", ""),
            kind = json.optString("kind", ""),
            phase = json.optString("phase", ""),
            label = json.optString("label", ""),
            roundIndex = json.optInt("roundIndex", 0),
            roundTotal = json.optInt("roundTotal", 0),
            endsAt = endsAt,
            remaining = json.optString("remaining", ""),
            paused = json.optBoolean("paused", false),
            next = json.optString("next", ""),
            finished = json.optBoolean("finished", false),
            seq = seq,
            receivedAtMs = nowMs,
            step = if (json.has("step") && !json.isNull("step")) json.optInt("step") else null,
            episode = episode,
        )
        _state.value = newState
        // Un secondo `ended` (battito ripetuto) non rimanda lo zero.
        if (!(newState.isEnded && prev?.isEnded == true)) scheduleAutoClear(newState.isEnded)
        return true
    }

    /// `ended` ⇒ null dopo 3 s; altrimenti dopo 120 s senza messaggi (scaduto).
    private fun scheduleAutoClear(ended: Boolean) {
        timerJob?.cancel()
        val token = _state.value
        val wait = if (ended) endedClearMs else forgetMs
        timerJob = scope.launch {
            delay(wait)
            expire(token)
        }
    }

    @Synchronized
    private fun expire(token: RoomTimerState?) {
        // Un messaggio più nuovo ha già sostituito lo stato: niente da fare.
        if (_state.value === token) clearLocked()
    }

    private fun clearLocked() {
        timerJob?.cancel()
        timerJob = null
        _state.value = null
    }

    @Synchronized
    fun clear() = clearLocked()

    fun isStale(s: RoomTimerState, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - s.receivedAtMs > EXPIRY_MS

    /// Solo test.
    @Synchronized
    internal fun resetForTest() {
        clearLocked()
        lastSeq = null
        lastAtMs = 0L
        episode = 0
        forgetMs = FORGET_MS
        endedClearMs = ENDED_CLEAR_MS
    }
}
