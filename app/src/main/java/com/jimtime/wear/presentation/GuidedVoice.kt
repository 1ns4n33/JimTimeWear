package com.jimtime.wear.presentation

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.jimtime.wear.guided.GuidedStrings
import com.jimtime.wear.guided.OffTargetDirection
import com.jimtime.wear.guided.ResolvedStep
import com.jimtime.wear.guided.StepEvent
import java.util.Locale

/*
 * F7 (Schede multisport: al polso, WP-WU, §2.7/D12) — voce best-effort
 * (canale GARANTITO = [GuidedHaptics]). Un solo `TextToSpeech`, init lazy
 * (creato alla prima [speak] utile, mai in `onCreate`: su molti orologi
 * il motore TTS non è nemmeno installato — `onInit` con stato diverso da
 * `SUCCESS` ⇒ resta muto, nessuna eccezione, nessun crash).
 *
 * Modalità `guidedVoiceMode` (pref, default `auto`):
 *  - `off`  → mai;
 *  - `on`   → sempre;
 *  - `auto` → solo con un'uscita audio Bluetooth collegata
 *             (`AudioManager.getDevices` — cuffie/altoparlante BT).
 *
 * `QUEUE_FLUSH` per gli eventi a priorità alta (inizio fase, fine seduta,
 * fuori target): interrompono qualunque frase in corso, non si accodano
 * dietro una frase vecchia di 10" fa. `QUEUE_ADD` per halfway/lastPart
 * (si accodano, mai sovrapposti). Sostituisce il segnaposto del
 * conduttore con le STESSE firme (`speak`, `shutdown`), chiamate da
 * TrackingService (WP-WD).
 *
 * **Thread (nota WP-WD, fase B):** `speak`/`shutdown` sono chiamati dal
 * ticker del service su `Dispatchers.Default`, MAI sul main thread.
 * `TextToSpeech` va creato e usato sul main thread: ogni accesso allo
 * stato condiviso (`tts`, `ready`, `currentLang`, `pendingUtterance`) e
 * ogni chiamata all'engine passano per [mainHandler], che le serializza
 * sul main — nessun lock aggiuntivo necessario perché un solo thread
 * (il main) le esegue mai in parallelo.
 */
object GuidedVoice {

    private const val PREFS_NAME = "jimtime_guided_voice"
    private const val KEY_MODE = "guidedVoiceMode"
    private const val MODE_AUTO = "auto"
    private const val MODE_ON = "on"
    private const val MODE_OFF = "off"

    private val mainHandler = Handler(Looper.getMainLooper())

    // Stato mutabile — toccato SOLO dal main thread (via [mainHandler]).
    private var tts: TextToSpeech? = null
    private var ready = false
    private var currentLang: String? = null

    /** Ultima frase non ancora pronunciata perché l'init TTS non era pronto — SOLO la più recente. */
    private var pendingUtterance: Pair<String, Int>? = null

    fun speak(context: Context, event: StepEvent, step: ResolvedStep?, lang: String) {
        // Lettura di prefs/AudioManager — innocua da qualunque thread, non
        // tocca lo stato del TTS: fatta qui per non svegliare il main
        // thread quando la modalità è `off` o non c'è nulla da dire.
        if (!shouldSpeak(context)) return
        val utterance = textFor(event, step, lang) ?: return
        val appContext = context.applicationContext
        mainHandler.post { speakOnMain(appContext, lang, utterance) }
    }

    fun shutdown() {
        mainHandler.post {
            tts?.let {
                runCatching { it.stop() }
                runCatching { it.shutdown() }
            }
            tts = null
            ready = false
            currentLang = null
            pendingUtterance = null
        }
    }

    // ── Main thread only ──────────────────────────────────────────────

    private fun speakOnMain(context: Context, lang: String, utterance: Pair<String, Int>) {
        val engine = tts
        if (engine == null) {
            pendingUtterance = utterance
            init(context, lang)
            return
        }
        if (currentLang != lang) {
            runCatching { engine.language = Locale.forLanguageTag(lang) }
            currentLang = lang
        }
        if (!ready) {
            pendingUtterance = utterance
            return
        }
        runCatching { engine.speak(utterance.first, utterance.second, null, "guided") }
    }

    private fun init(context: Context, lang: String) {
        if (tts != null) return
        var instance: TextToSpeech? = null
        instance = TextToSpeech(context) { status ->
            // Documentato come consegnato sul main thread da TextToSpeech;
            // si inoltra comunque via [mainHandler] per restare corretti
            // anche se una OEM lo richiamasse da un thread diverso.
            mainHandler.post {
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    val engine = instance ?: return@post
                    runCatching { engine.language = Locale.forLanguageTag(lang) }
                    currentLang = lang
                    val pending = pendingUtterance
                    pendingUtterance = null
                    if (pending != null) {
                        runCatching { engine.speak(pending.first, pending.second, null, "guided") }
                    }
                }
                // Stato diverso da SUCCESS: `ready` resta false, [speak] non
                // chiamerà mai più `engine.speak` — muto, nessun crash (D12).
            }
        }
        tts = instance
    }

    // ── Modalità / rilevamento Bluetooth ────────────────────────────────

    private fun shouldSpeak(context: Context): Boolean = when (voiceMode(context)) {
        MODE_OFF -> false
        MODE_ON -> true
        else -> hasBluetoothAudioOutput(context) // MODE_AUTO
    }

    /**
     * Pubblica (correzioni conduttore) — letta dal picker (`ActivityPickerScreen`,
     * riga «Voce: Auto/Sempre/Mai») per mostrare la modalità corrente e
     * ciclarla al tocco. `"auto"` di default, mai un valore fuori da
     * `MODE_AUTO`/`MODE_ON`/`MODE_OFF` (la pref è scritta solo da
     * [setVoiceMode], che valida).
     */
    fun voiceMode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO
    }

    /** Pref pubblica per lo schermo impostazioni — stesse chiavi di [voiceMode]. */
    fun setVoiceMode(context: Context, mode: String) {
        require(mode == MODE_AUTO || mode == MODE_ON || mode == MODE_OFF)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, mode)
            .apply()
    }

    /** `auto` ⇒ `on` ⇒ `off` ⇒ `auto` — ciclo usato dal tocco sulla riga del picker. */
    fun nextVoiceMode(current: String): String = when (current) {
        MODE_AUTO -> MODE_ON
        MODE_ON -> MODE_OFF
        else -> MODE_AUTO
    }

    private fun hasBluetoothAudioOutput(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        return runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    // Correzioni conduttore — cuffie Bluetooth LE Audio
                    // (API 31+; la costante non esiste prima, da qui il
                    // controllo di versione invece di un `when` unico).
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
        }.getOrDefault(false)
    }

    // ── Testo per evento (§2.7, tabella "Voce") ─────────────────────────

    /** `text` + `queueMode` (`TextToSpeech.QUEUE_FLUSH`/`QUEUE_ADD`), o `null` = nessuna voce per questo evento. */
    private fun textFor(event: StepEvent, step: ResolvedStep?, lang: String): Pair<String, Int>? = when (event) {
        is StepEvent.StepStart ->
            step?.uiSpoken()?.takeIf { it.isNotEmpty() }?.let { it to TextToSpeech.QUEUE_FLUSH }
        is StepEvent.StepHalfway ->
            GuidedStrings.t("halfway", lang) to TextToSpeech.QUEUE_ADD
        is StepEvent.StepLastPart ->
            lastPartText(event, lang)?.let { it to TextToSpeech.QUEUE_ADD }
        is StepEvent.StepOffTarget ->
            offTargetKey(event.direction)?.let { GuidedStrings.t(it, lang) to TextToSpeech.QUEUE_FLUSH }
        is StepEvent.StepBackOnTarget ->
            GuidedStrings.t("back_on_target", lang) to TextToSpeech.QUEUE_ADD
        is StepEvent.WorkoutEnd ->
            GuidedStrings.t("workout_end", lang) to TextToSpeech.QUEUE_FLUSH
        is StepEvent.StepEnd -> null // nessuna frase dedicata: la copre lo stepStart del prossimo step.
    }

    private fun lastPartText(event: StepEvent.StepLastPart, lang: String): String? {
        event.seconds?.let { return GuidedStrings.t("last_seconds", lang, mapOf("sec" to it.toString())) }
        event.meters?.let { return GuidedStrings.t("last_meters", lang, mapOf("m" to it.toString())) }
        return null
    }

    private fun offTargetKey(direction: OffTargetDirection): String = when (direction) {
        OffTargetDirection.TOO_FAST -> "too_fast"
        OffTargetDirection.TOO_SLOW -> "too_slow"
        OffTargetDirection.HR_HIGH -> "hr_high"
        OffTargetDirection.HR_LOW -> "hr_low"
        OffTargetDirection.CADENCE_HIGH -> "cadence_high"
        OffTargetDirection.CADENCE_LOW -> "cadence_low"
        OffTargetDirection.POWER_HIGH -> "power_high"
        OffTargetDirection.POWER_LOW -> "power_low"
    }
}
