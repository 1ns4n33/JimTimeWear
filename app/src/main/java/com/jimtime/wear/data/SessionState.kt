package com.jimtime.wear.data

data class SessionState(
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val isStandalone: Boolean = false,
    val activityType: String = "run",
    val startedAt: Long = 0L,
    val elapsedSeconds: Long = 0L,
    val distanceMeters: Double = 0.0,
) {
    val paceSecondsPerKm: Double
        get() = if (distanceMeters > 100 && elapsedSeconds > 0)
            elapsedSeconds / (distanceMeters / 1000.0) else 0.0

    fun formattedElapsed(): String {
        val h = elapsedSeconds / 3600
        val m = (elapsedSeconds % 3600) / 60
        val s = elapsedSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%02d:%02d".format(m, s)
    }

    fun formattedDistance(): String {
        val km = distanceMeters / 1000.0
        return if (km >= 10) "%.1f km".format(km) else "%.2f km".format(km)
    }

    fun formattedPace(): String {
        if (paceSecondsPerKm <= 0) return "--:--"
        val m = (paceSecondsPerKm / 60).toInt()
        val s = (paceSecondsPerKm % 60).toInt()
        return "%d:%02d/km".format(m, s)
    }

    fun activityIcon(): String = when (activityType) {
        "run", "treadmill_run"   -> "🏃"
        "walk", "treadmill_walk" -> "🚶"
        "bike", "indoor_cycling" -> "🚴"
        "hike"                   -> "🥾"
        "trail"                  -> "🌲"
        "skate"                  -> "🛼"
        "mtb"                    -> "🚵"
        "meditation"             -> "🧘"
        "pilates", "yoga"        -> "🤸"
        "stretching"             -> "🙆"
        else                     -> "🏋️"
    }

    fun isIndoor(): Boolean = activityType in setOf(
        "treadmill_walk", "treadmill_run", "indoor_cycling",
        "meditation", "pilates", "yoga", "stretching",
    )
}
