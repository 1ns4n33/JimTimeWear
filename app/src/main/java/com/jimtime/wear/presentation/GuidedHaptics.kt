package com.jimtime.wear.presentation

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import com.jimtime.wear.guided.OffTargetDirection
import com.jimtime.wear.guided.StepEvent

/*
 * F7 (Schede multisport: al polso, WP-WU, §2.7) — aptica dei cambi fase:
 * canale GARANTITO (il TTS di GuidedVoice è best-effort). Un pattern
 * `VibrationEffect.createWaveform` distinto per evento, stessa tabella
 * della Garmin (Attention) e dello Swift (`HapticEngine`/`WKInterfaceDevice`
 * lato Apple Watch — qui però con `createWaveform`, non un one-shot come
 * [vibrate] in `Haptics.kt`).
 *
 * Sostituisce il segnaposto vuoto del conduttore con la STESSA firma:
 * `GuidedHaptics.play(context: Context, event: StepEvent)` — lo chiama
 * TrackingService (WP-WD) ad ogni evento del motore, mai la UI.
 *
 * **Thread (nota WP-WD, fase B):** chiamato dal ticker del service su
 * `Dispatchers.Default`. Nessun problema qui: l'oggetto non ha stato
 * mutabile e `Vibrator.vibrate` è una chiamata Binder thread-safe di per
 * sé (a differenza di [GuidedVoice], che invece serializza tutto sul
 * main thread per il `TextToSpeech`).
 */
object GuidedHaptics {

    fun play(context: Context, event: StepEvent) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        val pattern = patternFor(event) ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    /**
     * F7 (correzioni WP-WD) — un click del countdown 3-2-1 di una fase a
     * TEMPO. Lo chiama TrackingService a ogni soglia attraversata (3, 2,
     * 1 s dalla fine, `GuidedRunRepository.takeCountdownClicks`), porting di
     * `scheduleLastPartClicks` (Swift): mai per le fasi a distanza.
     */
    fun countdownClick(context: Context) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(COUNTDOWN_CLICK, -1))
    }

    private val COUNTDOWN_CLICK = longArrayOf(0, 30)

    /**
     * Timing/amplitude `[0, onMs, offMs, onMs, ...]` di `createWaveform`
     * (il primo valore è un delay iniziale, qui sempre 0). `-1` = nessuna
     * repetizione (il chiamante di `play` passa `repeat = -1`).
     *
     * Nota `stepLastPart`: nessuna aptica qui. L'evento del motore arriva
     * a 10 s (o a metri) dalla fine ed è solo per la voce; i tre click
     * 3-2-1 delle fasi a tempo li temporizza il service ([countdownClick]).
     */
    private fun patternFor(event: StepEvent): LongArray? = when (event) {
        is StepEvent.StepStart -> longArrayOf(0, 60, 60, 60, 60, 60)
        is StepEvent.StepHalfway -> longArrayOf(0, 40)
        is StepEvent.StepLastPart -> null // solo voce; i click 3-2-1 via countdownClick.
        is StepEvent.StepOffTarget -> if (isFastOrHighDirection(event.direction)) {
            longArrayOf(0, 250)
        } else {
            longArrayOf(0, 80, 80, 80)
        }
        is StepEvent.StepBackOnTarget -> longArrayOf(0, 40, 40, 40)
        is StepEvent.WorkoutEnd -> longArrayOf(0, 120, 80, 120, 80, 300)
        is StepEvent.StepEnd -> null // nessuna aptica dedicata: stepStart del prossimo step la copre.
    }

    /** `tooFast`/`hrHigh`/`cadenceHigh`/`powerHigh` ⇒ un impulso lungo; gli opposti ⇒ due corti. */
    private fun isFastOrHighDirection(direction: OffTargetDirection): Boolean = when (direction) {
        OffTargetDirection.TOO_FAST,
        OffTargetDirection.HR_HIGH,
        OffTargetDirection.CADENCE_HIGH,
        OffTargetDirection.POWER_HIGH,
        -> true
        OffTargetDirection.TOO_SLOW,
        OffTargetDirection.HR_LOW,
        OffTargetDirection.CADENCE_LOW,
        OffTargetDirection.POWER_LOW,
        -> false
    }
}
