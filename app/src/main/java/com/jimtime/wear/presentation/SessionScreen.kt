package com.jimtime.wear.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import com.jimtime.wear.data.SessionState
import com.jimtime.wear.presentation.theme.JTColors
import com.jimtime.wear.presentation.theme.JimTimeWearTheme

@Composable
fun SessionScreen(
    sessionState: SessionState,
    heartRate: Double,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    isStandalone: Boolean = false,
) {
    val accent = JTColors.activity(sessionState.activityType)

    JimTimeWearTheme {
        AppScaffold {
            ScreenScaffold {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // ── Top row: activity chip + optional standalone badge ─
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        CapsuleChip(
                            text = "${sessionState.activityIcon()} ${activityLabel(sessionState.activityType)}",
                            tint = accent,
                        )
                        if (isStandalone) {
                            CapsuleChip(
                                text = "📵 Standalone",
                                tint = JTColors.warning,
                            )
                        }
                    }

                    // ── Elapsed ────────────────────────────────────────────
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TinyLabel("Durata")
                        Text(
                            text = sessionState.formattedElapsed(),
                            style = MaterialTheme.typography.displayMedium.copy(
                                fontSize = 40.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }

                    // ── Stats row ──────────────────────────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        if (!sessionState.isIndoor()) {
                            StatCell(
                                icon = "📍",
                                tint = accent,
                                label = "km",
                                value = sessionState.formattedDistance().removeSuffix(" km"),
                            )
                            StatCell(
                                icon = "⚡",
                                tint = Color.White.copy(alpha = 0.7f),
                                label = "passo",
                                value = sessionState.formattedPace(),
                            )
                        }
                        if (heartRate > 0) {
                            StatCell(
                                icon = "♥",
                                tint = JTColors.hr,
                                label = "bpm",
                                value = heartRate.toInt().toString(),
                            )
                        }
                    }

                    // ── Buttons ────────────────────────────────────────────
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Stop — red tinted circle
                        CircleControl(
                            glyph = "■",
                            tint = JTColors.danger,
                            solid = false,
                            size = 48.dp,
                            onClick = onStop,
                        )

                        // Pause / Resume
                        if (sessionState.isPaused) {
                            CircleControl(
                                glyph = "▶",
                                tint = JTColors.success,
                                solid = true,
                                size = 48.dp,
                                onClick = onResume,
                            )
                        } else {
                            CircleControl(
                                glyph = "⏸",
                                tint = JTColors.warning,
                                solid = false,
                                size = 48.dp,
                                onClick = onPause,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun activityLabel(id: String): String = when (id) {
    "run"            -> "Corsa"
    "walk"           -> "Camminata"
    "bike"           -> "Bici"
    "hike"           -> "Escursione"
    "trail"          -> "Trail"
    "skate"          -> "Skate"
    "mtb"            -> "MTB"
    "treadmill_run"  -> "Tapis roulant corsa"
    "treadmill_walk" -> "Tapis roulant cammino"
    "indoor_cycling" -> "Bici indoor"
    "meditation"     -> "Meditazione"
    "pilates"        -> "Pilates"
    "yoga"           -> "Yoga"
    "stretching"     -> "Stretching"
    else             -> "Attività"
}

/** Small tinted capsule chip (activity / standalone badge). */
@Composable
internal fun CapsuleChip(text: String, tint: Color) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = tint,
            maxLines = 1,
        )
    }
}

/** 9pt uppercase secondary section micro-label. */
@Composable
internal fun TinyLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/** Icon-in-colored-circle + value + tiny unit label. */
@Composable
private fun StatCell(icon: String, tint: Color, label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, fontSize = 11.sp, color = tint)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            ),
            maxLines = 1,
        )
        TinyLabel(label)
    }
}

/**
 * Circular session control: tinted 22 %-alpha circle with the glyph in
 * the accent color, or a solid circle with a black glyph (resume).
 */
@Composable
internal fun CircleControl(
    glyph: String,
    tint: Color,
    solid: Boolean,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (solid) tint else tint.copy(alpha = 0.22f),
            contentColor = if (solid) Color.Black else tint,
        ),
    ) {
        Text(
            glyph,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@WearPreviewDevices
@Composable
private fun SessionScreenPreview() {
    SessionScreen(
        sessionState = SessionState(
            isActive = true,
            activityType = "run",
            elapsedSeconds = 754,
            distanceMeters = 2340.0,
        ),
        heartRate = 142.0,
        onStop = {},
        onPause = {},
        onResume = {},
    )
}
