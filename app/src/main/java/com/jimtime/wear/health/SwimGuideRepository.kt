package com.jimtime.wear.health

import com.jimtime.wear.data.SwimWorkoutWire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * F5b (Nuoto, §7) — ponte stateful fra il motore puro [SwimGuide] e il
 * resto dell'app (TrackingService lo pilota una volta al secondo dallo
 * stesso ticker della sessione swim, SwimSessionScreen ne osserva
 * [current]). Vive come singleton perché, come [TrackingEngine], deve
 * sopravvivere a schermo spento — non è legato al ciclo vita di una
 * Activity/ViewModel.
 */
object SwimGuideRepository {

    private var guide: SwimGuide? = null

    private val _current = MutableStateFlow<SwimGuideRep?>(null)
    val current: StateFlow<SwimGuideRep?> = _current.asStateFlow()

    /// Nessuna serie seguita = idle: [current] resta `null` per l'intera
    /// sessione, SwimSessionScreen mostra solo durata/distanza/vasche/FC.
    fun start(workout: SwimWorkoutWire?, nowMs: Long) {
        guide = workout?.takeIf { it.sets.isNotEmpty() }?.let { SwimGuide(it, nowMs) }
        _current.value = guide?.snapshot(nowMs)
    }

    fun stop() {
        guide = null
        _current.value = null
    }

    /// Chiamata ~1 volta al secondo — ritorna l'evento per cui il
    /// chiamante fa vibrare l'orologio (normale a inizio ripetizione, più
    /// forte a cambio serie), [SwimGuideEvent.NONE] altrimenti.
    fun tick(nowMs: Long): SwimGuideEvent {
        val g = guide ?: return SwimGuideEvent.NONE
        val event = g.update(nowMs)
        _current.value = g.snapshot(nowMs)
        return event
    }

    /// Avanzamento manuale (rotella/tap, §7) — sempre disponibile, non solo
    /// per le serie senza `sendOffSec`.
    fun manualNext(nowMs: Long) {
        val g = guide ?: return
        g.manualNextSet(nowMs)
        _current.value = g.snapshot(nowMs)
    }
}
