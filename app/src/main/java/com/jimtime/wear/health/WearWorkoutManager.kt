package com.jimtime.wear.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// WP4 (D7) — quanto vecchio può essere un campione per essere rimandato al
/// telefono come «fresco»: oltre, il bpm è considerato stantio.
const val HR_FRESH_MS = 5_000L

/// Decide cosa spedire come `hrUpdate`: il bpm solo se positivo, arrivato da
/// `onSensorChanged` negli ultimi [HR_FRESH_MS] e con l'orologio al polso.
/// Mai un valore vecchio ri-inviato come fresco, mai nulla in off-body.
internal fun hrUpdateToSend(
    bpm: Double,
    sampleAtMs: Long,
    nowMs: Long,
    offBody: Boolean,
): Double? {
    if (offBody || bpm <= 0.0 || sampleAtMs <= 0L) return null
    if (nowMs - sampleAtMs > HR_FRESH_MS) return null
    return bpm
}

class WearWorkoutManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val hrSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

    private val _heartRate = MutableStateFlow(0.0)
    val heartRate: StateFlow<Double> = _heartRate.asStateFlow()

    /// Istante (epoch ms) dell'ultimo `onSensorChanged` valido; 0 = nessuno.
    @Volatile var lastSampleAtMs: Long = 0L
        private set

    /// `true` quando il sensore segnala `SENSOR_STATUS_NO_CONTACT` (cinturino aperto).
    private val _noContact = MutableStateFlow(false)
    val noContact: StateFlow<Boolean> = _noContact.asStateFlow()

    // Session-long accumulation so standalone sessions can deliver a
    // summary (avg/max) to the phone at reconnection. Reset manually at
    // session start — stopMonitoring() intentionally leaves it intact so
    // the summary survives until it is read.
    private var hrSum = 0.0
    private var hrCount = 0
    private var hrMax = 0.0

    val hrAverage: Double? get() = if (hrCount > 0) hrSum / hrCount else null
    val hrMaxOrNull: Double? get() = if (hrMax > 0) hrMax else null

    /// Stato grezzo degli accumulatori HR — usato dal checkpoint periodico
    /// di TrackingService (serve sum/count, non solo la media, per poter
    /// continuare ad accumulare dopo un restore()).
    data class HrSnapshot(val hrSum: Double, val hrCount: Int, val hrMax: Double)

    fun snapshot(): HrSnapshot = HrSnapshot(hrSum, hrCount, hrMax)

    /// Crash recovery: ripristina gli accumulatori da un checkpoint invece
    /// di ripartire da zero (altrimenti avg/max di ore di allenamento
    /// andrebbero persi ad ogni restart del processo).
    fun restore(snapshot: HrSnapshot) {
        hrSum = snapshot.hrSum
        hrCount = snapshot.hrCount
        hrMax = snapshot.hrMax
    }

    fun resetHrAccumulation() {
        hrSum = 0.0
        hrCount = 0
        hrMax = 0.0
    }

    fun startMonitoring() {
        hrSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stopMonitoring() {
        sensorManager.unregisterListener(this)
        _heartRate.value = 0.0
        lastSampleAtMs = 0L
        _noContact.value = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_HEART_RATE && event.values.isNotEmpty()) {
            val bpm = event.values[0].toDouble()
            _heartRate.value = bpm
            lastSampleAtMs = System.currentTimeMillis()
            if (bpm > 0) {
                hrSum += bpm
                hrCount++
                if (bpm > hrMax) hrMax = bpm
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type != Sensor.TYPE_HEART_RATE) return
        val off = accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT
        _noContact.value = off
        if (off) {
            // Il bpm precedente non vale più: niente valore stantio a schermo né sul filo.
            _heartRate.value = 0.0
            lastSampleAtMs = 0L
        }
    }
}
