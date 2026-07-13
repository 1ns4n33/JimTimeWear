package com.jimtime.wear.data

import android.content.Context
import org.json.JSONObject

/// Configurazione di una sessione a intervalli avviata DAL POLSO, senza
/// telefono. I preset quick-start non hanno esercizi: il sync logga un
/// movimento generico col nome della modalità (durata/FC/round tracciati).
data class IntervalSpec(
    val mode: String,        // tabata|emom|amrap|forTime|hiit|stations
    val modeLabel: String,
    val workSeconds: Int,
    val restSeconds: Int,
    val rounds: Int,
    val totalSeconds: Int?,  // amrap: durata; forTime: time cap
) {
    val isAutoTimed: Boolean
        get() = mode in listOf("tabata", "emom", "hiit", "stations")

    val compactLabel: String
        get() = when (mode) {
            "emom" -> "${rounds}′"
            "amrap" -> "${(totalSeconds ?: 0) / 60}′"
            "forTime" -> "$rounds round"
            else -> "${workSeconds}″/${restSeconds}″ × $rounds"
        }

    companion object {
        val presets = listOf(
            IntervalSpec("tabata", "Tabata", 20, 10, 8, null),
            IntervalSpec("emom", "EMOM", 60, 0, 10, null),
            IntervalSpec("hiit", "HIIT", 30, 30, 10, null),
            IntervalSpec("amrap", "AMRAP", 0, 0, 1, 12 * 60),
        )
    }
}

/// Outbox minimale per il gymSessionSync di una sessione intervalli chiusa
/// col telefono irraggiungibile: un solo slot (l'ultima vince), riprovato
/// all'avvio successivo dell'app watch. Specchio ridotto di PendingRouteStore.
object PendingGymStore {
    private const val PREFS = "pending_gym_sync"
    private const val KEY = "payload"

    fun save(context: Context, payload: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, payload.toString()).apply()
    }

    fun load(context: Context): JSONObject? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
