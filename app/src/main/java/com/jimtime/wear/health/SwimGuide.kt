package com.jimtime.wear.health

import com.jimtime.wear.data.SwimWorkoutSetWire
import com.jimtime.wear.data.SwimWorkoutWire

/// Un passo del countdown per il conduttore (§7, D4/D12) — quello che
/// [com.jimtime.wear.presentation.SwimSessionScreen] mostra "grande":
/// etichetta della serie, "rip. 3/10", secondi al prossimo via.
/// [countdownSec] è `null` quando la serie corrente non ha un intervallo
/// di partenza (`send`/`intervalSec` assente sul wire) — in quel caso la
/// UI mostra la serie ma SENZA countdown automatico, il passaggio alla
/// successiva resta solo manuale (D4: "avanzamento SOLO a tempo", una
/// serie senza intervallo non inventa un tempo).
data class SwimGuideRep(
    val setIndex: Int,   // 0-based
    val setCount: Int,
    val repIndex: Int,   // 1-based
    val repCount: Int,
    val label: String,
    val countdownSec: Int?,
    val finished: Boolean = false,
)

/// Evento del [SwimGuide.update] a cui la UI/service reagisce con
/// un'aptica (§7): normale a inizio ripetizione, più forte a cambio serie.
enum class SwimGuideEvent { NONE, REP_START, SET_CHANGE }

/**
 * F5b (Nuoto, §7/D4) — motore PURO (nessun Android/coroutine) che avanza
 * un [SwimWorkoutWire] SOLO A TEMPO: ogni serie con un intervallo di
 * partenza (`sendOffSec`, mirror del campo wire `send`) genera un via ogni
 * `sendOffSec` secondi per `reps` ripetizioni, poi passa alla serie
 * successiva. Una serie senza `sendOffSec` non avanza da sola — l'atleta
 * la salta a mano ([manualNextSet], rotella o tap sull'attiva).
 *
 * Deliberatamente senza `System.currentTimeMillis()` al suo interno:
 * ogni chiamata riceve `nowMs` dal chiamante, così è interamente
 * testabile con JUnit puro iniettando timestamp finti (vedi
 * `SwimGuideTest`).
 */
class SwimGuide(private val workout: SwimWorkoutWire, startAtMs: Long) {

    private var setIndex = 0
    private var repIndex = 0 // 0-based all'interno della serie corrente
    private var sendOffAtMs: Long? = null

    init {
        armSendOff(startAtMs)
    }

    private fun currentSet(): SwimWorkoutSetWire? = workout.sets.getOrNull(setIndex)

    private fun armSendOff(nowMs: Long) {
        val interval = currentSet()?.sendOffSec
        sendOffAtMs = if (interval != null && interval > 0) nowMs + interval * 1000L else null
    }

    /// Vero quando tutte le serie sono state completate (auto o a mano).
    fun isFinished(): Boolean = currentSet() == null

    /// Da chiamare ~1 volta al secondo con l'ora corrente. Avanza rip./
    /// serie quando il countdown corrente è scaduto — mai più di un passo
    /// per chiamata anche se `nowMs` salta più di un intervallo (un salto
    /// di orologio o un tick perso non deve far bruciare più serie in un
    /// colpo): il prossimo `update` recupera il passo successivo.
    fun update(nowMs: Long): SwimGuideEvent {
        val due = sendOffAtMs ?: return SwimGuideEvent.NONE
        if (nowMs < due) return SwimGuideEvent.NONE
        return advance(nowMs)
    }

    private fun advance(nowMs: Long): SwimGuideEvent {
        val set = currentSet() ?: return SwimGuideEvent.NONE
        repIndex++
        return if (repIndex >= set.reps) {
            setIndex++
            repIndex = 0
            armSendOff(nowMs)
            SwimGuideEvent.SET_CHANGE
        } else {
            armSendOff(nowMs)
            SwimGuideEvent.REP_START
        }
    }

    /// Salta a mano alla serie successiva (rotella/tap, §7) — usata sia
    /// quando la serie corrente non ha `sendOffSec` sia quando l'atleta
    /// vuole semplicemente andare avanti prima del via.
    fun manualNextSet(nowMs: Long) {
        if (currentSet() == null) return
        setIndex++
        repIndex = 0
        armSendOff(nowMs)
    }

    fun snapshot(nowMs: Long): SwimGuideRep? {
        val set = currentSet() ?: return null
        val countdownSec = sendOffAtMs?.let { due ->
            ((due - nowMs) / 1000.0).let { s -> if (s <= 0) 0 else Math.ceil(s).toInt() }
        }
        return SwimGuideRep(
            setIndex = setIndex,
            setCount = workout.sets.size,
            repIndex = repIndex + 1,
            repCount = set.reps,
            label = set.label,
            countdownSec = countdownSec,
        )
    }
}
