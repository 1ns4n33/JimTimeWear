package com.jimtime.wear.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WearWorkoutManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val hrSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

    private val _heartRate = MutableStateFlow(0.0)
    val heartRate: StateFlow<Double> = _heartRate.asStateFlow()

    // Session-long accumulation so standalone sessions can deliver a
    // summary (avg/max) to the phone at reconnection. Reset manually at
    // session start — stopMonitoring() intentionally leaves it intact so
    // the summary survives until it is read.
    private var hrSum = 0.0
    private var hrCount = 0
    private var hrMax = 0.0

    val hrAverage: Double? get() = if (hrCount > 0) hrSum / hrCount else null
    val hrMaxOrNull: Double? get() = if (hrMax > 0) hrMax else null

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
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_HEART_RATE && event.values.isNotEmpty()) {
            val bpm = event.values[0].toDouble()
            _heartRate.value = bpm
            if (bpm > 0) {
                hrSum += bpm
                hrCount++
                if (bpm > hrMax) hrMax = bpm
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}
