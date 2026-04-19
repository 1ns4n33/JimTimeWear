package com.jimtime.wear.data

data class GpsPoint(
    val lat: Double,
    val lng: Double,
    val altitude: Double,
    val speed: Double,
    val timestampMs: Long,
)
