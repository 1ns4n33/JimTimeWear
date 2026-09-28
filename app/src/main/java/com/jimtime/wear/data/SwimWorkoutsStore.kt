package com.jimtime.wear.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/// Una serie pianificata (D3) — mirror ESATTO delle chiavi corte di
/// `SwimWorkoutSet.toWire()` (`lib/modules/swimming/models/swim_workout.dart`):
/// `kind`/`stroke`/`reps`/`dist`/`send`?/`rest`?/`label`. `sendOffSec` è
/// il nome Kotlin di `send` (l'intervallo di partenza, D4) — non
/// rinominato sul wire, solo qui per leggibilità.
data class SwimWorkoutSetWire(
    val kind: String,
    val stroke: String,
    val reps: Int,
    val distM: Double,
    val sendOffSec: Int?,
    val restSec: Int?,
    val label: String,
) {
    companion object {
        fun fromWire(o: JSONObject): SwimWorkoutSetWire = SwimWorkoutSetWire(
            kind = o.optString("kind", "MAIN"),
            stroke = o.optString("stroke", "unknown"),
            reps = o.optInt("reps", 1),
            distM = o.optDouble("dist", 0.0),
            sendOffSec = if (o.has("send")) o.optInt("send") else null,
            restSec = if (o.has("rest")) o.optInt("rest") else null,
            label = o.optString("label", ""),
        )
    }
}

/// Un allenamento del coach (D3) — mirror ESATTO di
/// `SwimWorkoutRecord.toWire()`: `id`/`title`/`poolLengthM`?/`sets[]`.
/// Stesso oggetto sia quando arriva dalla lista pushata dal telefono
/// (`cmd: swimWorkouts`, [SwimWorkoutsStore]) sia quando arriva incorporato
/// in `startSession.swim.workout` (avvio dal telefono con una serie già
/// scelta) — un solo parser, [fromWire].
data class SwimWorkoutWire(
    val id: String,
    val title: String,
    val poolLengthM: Double?,
    val sets: List<SwimWorkoutSetWire>,
) {
    companion object {
        fun fromWire(o: JSONObject): SwimWorkoutWire {
            val rawSets = o.optJSONArray("sets") ?: JSONArray()
            return SwimWorkoutWire(
                id = o.optString("id", ""),
                title = o.optString("title", ""),
                poolLengthM = if (o.has("poolLengthM")) o.optDouble("poolLengthM") else null,
                sets = (0 until rawSets.length()).mapNotNull { i ->
                    rawSets.optJSONObject(i)?.let { SwimWorkoutSetWire.fromWire(it) }
                },
            )
        }
    }
}

/**
 * F5b (Nuoto, §7) — cache locale delle serie del coach (proprie +
 * assegnate, D3) pushate dal telefono (`cmd: swimWorkouts`, forwarded
 * verbatim su `/jimtime/session` come `planDays`/`intervalConfig` — vedi
 * `WearableBridge.sendSwimWorkouts`/`PhoneMessageService`). Stesso pattern
 * di [PlanDaysStore]: cache in SharedPreferences, letta all'avvio per
 * offrire la scelta anche senza telefono raggiungibile in quel momento
 * (l'avvio VERO resta comunque locale — [com.jimtime.wear.health.HealthServicesExerciseSource]
 * — non serve il telefono per seguire una serie già sincronizzata).
 *
 * Tetti (≤10 allenamenti, ≤40 serie l'uno) già applicati lato phone
 * (`WatchSwimSyncService`) — qui si accetta quello che arriva senza
 * ri-troncare, un payload fuori tetto è un bug altrove, non qui.
 */
object SwimWorkoutsStore {

    private const val PREFS_NAME = "jimtime_swim_workouts"
    private const val KEY_ITEMS  = "items"

    private val _workouts = MutableStateFlow<List<SwimWorkoutWire>>(emptyList())
    val workouts: StateFlow<List<SwimWorkoutWire>> = _workouts.asStateFlow()

    fun load(context: Context) {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null)
        _workouts.value = parse(raw)
    }

    fun apply(context: Context, itemsJson: JSONArray) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ITEMS, itemsJson.toString())
            .apply()
        _workouts.value = parse(itemsJson.toString())
    }

    private fun parse(raw: String?): List<SwimWorkoutWire> {
        if (raw == null) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { SwimWorkoutWire.fromWire(it) }
            }
        }.getOrDefault(emptyList())
    }
}
