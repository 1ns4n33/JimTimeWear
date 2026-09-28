package com.jimtime.wear.health

import android.content.Context
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseEvent
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.concurrent.futures.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * F5b (Nuoto, Wear OS) — sorgente sensori dedicata ESCLUSIVAMENTE alle
 * attività `swim_pool`/`swim_open_water` (D11), che usa Health Services
 * (`ExerciseClient`) invece dello stack GPS+SensorManager di [GpsTracker]/
 * [WearWorkoutManager] usato da tutte le altre attività: sott'acqua il GPS
 * non funziona (o solo a intermittenza tra una bracciata e l'altra in
 * acque libere) e il sensore HR grezzo non conta vasche/bracciate — solo
 * `ExerciseType.SWIMMING_POOL`/`SWIMMING_OPEN_WATER` lo fanno a bordo
 * dispositivo.
 *
 * Degrada in base a `ExerciseClient.getCapabilitiesAsync()`: un dispositivo
 * che non dichiara un certo data type per il tipo di esercizio (es. nessun
 * sensore ottico HR) semplicemente non lo richiede — mai un crash per un
 * tipo non supportato, mai un valore inventato.
 *
 * Auto-pause è sempre spenta (D4): un atleta che tocca il bordo vasca e
 * resta fermo per leggere il quadrante non deve vedere la sessione
 * fermarsi da sola.
 */
class HealthServicesExerciseSource(context: Context) {

    private val exerciseClient = HealthServices.getClient(context.applicationContext).exerciseClient

    /// Aggregati cumulativi correnti — laps/strokes sono TOTALI di sessione,
    /// non per-vasca: Health Services non espone un evento "fine vasca" con
    /// split individuale in questa API, solo il conteggio cumulativo (D9,
    /// stesso fallback "vasche/serie sconosciute" del wire contract:
    /// `sessionSync.swim.laps` resta `[]`).
    data class SwimMetrics(
        val distanceMeters: Double = 0.0,
        val laps: Int = 0,
        val strokes: Int = 0,
        val calories: Double = 0.0,
        val heartRate: Double = 0.0,
    )

    private val _metrics = MutableStateFlow(SwimMetrics())
    val metrics: StateFlow<SwimMetrics> = _metrics.asStateFlow()

    // Stesso pattern accumulatore di WearWorkoutManager, per coerenza col
    // resto del codice e per alimentare avgHr/maxHr al momento dello stop.
    private var hrSum = 0.0
    private var hrCount = 0
    private var hrMax = 0.0
    val hrAverage: Double? get() = if (hrCount > 0) hrSum / hrCount else null
    val hrMaxOrNull: Double? get() = if (hrMax > 0) hrMax else null

    private var callbackRegistered = false

    private val callback = object : ExerciseUpdateCallback {
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {}

        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val metrics = update.latestMetrics
            val distance = metrics.getData(DataType.DISTANCE_TOTAL)?.total ?: _metrics.value.distanceMeters
            val laps = metrics.getData(DataType.SWIMMING_LAP_COUNT_TOTAL)?.total ?: _metrics.value.laps.toLong()
            val strokes = metrics.getData(DataType.SWIMMING_STROKES_TOTAL)?.total ?: _metrics.value.strokes.toLong()
            val calories = metrics.getData(DataType.CALORIES_TOTAL)?.total ?: _metrics.value.calories

            var latestHr = _metrics.value.heartRate
            for (sample in metrics.getData(DataType.HEART_RATE_BPM)) {
                val bpm = sample.value
                if (bpm > 0) {
                    latestHr = bpm
                    hrSum += bpm
                    hrCount++
                    if (bpm > hrMax) hrMax = bpm
                }
            }

            _metrics.value = SwimMetrics(
                distanceMeters = distance,
                laps = laps.toInt(),
                strokes = strokes.toInt(),
                calories = calories,
                heartRate = latestHr,
            )
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
        override fun onExerciseEventReceived(event: ExerciseEvent) {}
    }

    /// Avvia la sessione swim. [poolLengthM] è ignorato per le acque libere
    /// (dove invece si abilita il GPS, D4) e per la vasca quando assente o
    /// non positivo — `ExerciseConfig` userà SWIMMING_POOL_LENGTH_UNSPECIFIED.
    suspend fun start(openWater: Boolean, poolLengthM: Double?) {
        hrSum = 0.0
        hrCount = 0
        hrMax = 0.0
        _metrics.value = SwimMetrics()

        val type = if (openWater) ExerciseType.SWIMMING_OPEN_WATER else ExerciseType.SWIMMING_POOL

        // D11: solo i data type che il dispositivo dichiara di supportare
        // per QUESTO tipo di esercizio — richiederne uno non supportato fa
        // fallire startExerciseAsync per l'intera sessione, non solo per
        // quel dato.
        val wanted = setOf(
            DataType.HEART_RATE_BPM,
            DataType.DISTANCE_TOTAL,
            DataType.SWIMMING_LAP_COUNT_TOTAL,
            DataType.SWIMMING_STROKES_TOTAL,
            DataType.CALORIES_TOTAL,
        )
        val supported = runCatching {
            exerciseClient.getCapabilitiesAsync().await()
                .getExerciseTypeCapabilities(type)?.supportedDataTypes
        }.getOrNull()
        val dataTypes = if (supported.isNullOrEmpty()) wanted else wanted.filterTo(mutableSetOf()) { it in supported }

        val builder = ExerciseConfig.builder(type)
            .setDataTypes(dataTypes)
            .setIsAutoPauseAndResumeEnabled(false)
            .setIsGpsEnabled(openWater)
        if (!openWater && poolLengthM != null && poolLengthM > 0) {
            builder.setSwimmingPoolLengthMeters(poolLengthM.toFloat())
        }

        if (!callbackRegistered) {
            exerciseClient.setUpdateCallback(callback)
            callbackRegistered = true
        }
        exerciseClient.startExerciseAsync(builder.build()).await()
    }

    suspend fun pause() {
        runCatching { exerciseClient.pauseExerciseAsync().await() }
    }

    suspend fun resume() {
        runCatching { exerciseClient.resumeExerciseAsync().await() }
    }

    /// Ferma la sessione e ritorna l'ultimo [SwimMetrics] noto — il
    /// chiamante (`TrackingService`) lo usa per costruire il blocco
    /// `sessionSync.swim` con cui il telefono scrive il satellite.
    suspend fun stop(): SwimMetrics {
        val last = _metrics.value
        runCatching { exerciseClient.endExerciseAsync().await() }
        if (callbackRegistered) {
            runCatching { exerciseClient.clearUpdateCallbackAsync(callback).await() }
            callbackRegistered = false
        }
        return last
    }
}
