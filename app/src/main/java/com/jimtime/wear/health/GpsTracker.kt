package com.jimtime.wear.health

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.jimtime.wear.data.GpsPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GpsTracker(context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    private val _points = MutableStateFlow<List<GpsPoint>>(emptyList())
    val points: StateFlow<List<GpsPoint>> = _points.asStateFlow()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val point = GpsPoint(
                lat         = loc.latitude,
                lng         = loc.longitude,
                altitude    = loc.altitude,
                speed       = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
                timestampMs = loc.time,
            )
            _points.value = _points.value + point
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        _points.value = emptyList()
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateDistanceMeters(5f)
            .build()
        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    fun stop() {
        fusedClient.removeLocationUpdates(locationCallback)
    }
}
