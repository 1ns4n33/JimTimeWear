package com.jimtime.wear.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * JimTime watch design-system tokens (dark-first, brand violet).
 * Shared across all watch screens — keep in sync with the iOS watch app.
 */
object JTColors {
    /** Brand violet — start CTAs, plan cards, set-progress, rest ring. */
    val brand = Color(0xFF7C3AED)

    /** Gradient partner of [brand]. */
    val brandBright = Color(0xFF9F67FF)

    /** "Fatto" / resume. */
    val success = Color(0xFF30D158)

    /** Pause / skip rest. */
    val warning = Color(0xFFFFB020)

    /** Stop. */
    val danger = Color(0xFFFF453A)

    /** Hearts / bpm. */
    val hr = Color(0xFFFF375F)

    private val activityColors = mapOf(
        "run"            to Color(0xFFFF8A3D),
        "walk"           to Color(0xFF18A957),
        "bike"           to Color(0xFF00B8D9),
        "hike"           to Color(0xFF8D6E63),
        "trail"          to Color(0xFF2E7D32),
        "skate"          to Color(0xFFE0559B),
        "mtb"            to Color(0xFF5C6BC0),
        "treadmill_run"  to Color(0xFFFF8A3D),
        "treadmill_walk" to Color(0xFF18A957),
        "indoor_cycling" to Color(0xFF00B8D9),
        "meditation"     to Color(0xFF9C6BFF),
        "pilates"        to Color(0xFFE0559B),
        "yoga"           to Color(0xFF9C6BFF),
        "stretching"     to Color(0xFF00B8D9),
    )

    /** Per-activity accent color; falls back to the brand violet. */
    fun activity(id: String): Color = activityColors[id] ?: brand
}

private val JimTimeColorScheme = ColorScheme(
    primary = JTColors.brand,
    onPrimary = Color.White,
    primaryContainer = JTColors.brand.copy(alpha = 0.20f),
    onPrimaryContainer = JTColors.brandBright,
    error = JTColors.danger,
    onError = Color.White,
)

@Composable
fun JimTimeWearTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = JimTimeColorScheme,
        content = content,
    )
}
