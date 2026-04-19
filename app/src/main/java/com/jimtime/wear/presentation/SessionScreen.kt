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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import com.jimtime.wear.data.SessionState
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
                    // ── Standalone badge ───────────────────────────────────
                    if (isStandalone) {
                        Text(
                            text = "📵 Standalone",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFB8C00),
                            textAlign = TextAlign.Center,
                        )
                    }

                    // ── Timer ──────────────────────────────────────────────
                    Text(
                        text = sessionState.formattedElapsed(),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center,
                    )

                    // ── Stats row ──────────────────────────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        StatItem(label = "km", value = sessionState.formattedDistance())
                        StatItem(label = "passo", value = sessionState.formattedPace())
                        if (heartRate > 0) {
                            StatItem(label = "bpm", value = heartRate.toInt().toString())
                        }
                    }

                    // ── Buttons ────────────────────────────────────────────
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Stop — red
                        Button(
                            onClick = onStop,
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE53935),
                            ),
                        ) {
                            Text("■", textAlign = TextAlign.Center)
                        }

                        // Pause / Resume — amber
                        if (sessionState.isPaused) {
                            Button(
                                onClick = onResume,
                                modifier = Modifier.size(48.dp),
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF43A047),
                                ),
                            ) {
                                Text("▶", textAlign = TextAlign.Center)
                            }
                        } else {
                            Button(
                                onClick = onPause,
                                modifier = Modifier.size(48.dp),
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFB8C00),
                                ),
                            ) {
                                Text("⏸", textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
