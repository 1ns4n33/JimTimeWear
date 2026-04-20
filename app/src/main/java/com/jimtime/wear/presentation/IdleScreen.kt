package com.jimtime.wear.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import com.jimtime.wear.presentation.theme.JimTimeWearTheme

private data class ActivityType(val id: String, val label: String, val emoji: String)

private val activityTypes = listOf(
    ActivityType("run",            "Corsa",                 "🏃"),
    ActivityType("walk",           "Camminata",             "🚶"),
    ActivityType("bike",           "Bici",                  "🚴"),
    ActivityType("hike",           "Escursione",            "🥾"),
    ActivityType("trail",          "Trail",                 "🌲"),
    ActivityType("treadmill_run",  "Tapis roulant corsa",   "🏃"),
    ActivityType("treadmill_walk", "Tapis roulant cammino", "🚶"),
    ActivityType("indoor_cycling", "Bici indoor",           "🚴"),
    ActivityType("meditation",     "Meditazione",           "🧘"),
    ActivityType("pilates",        "Pilates",               "🤸"),
    ActivityType("yoga",           "Yoga",                  "🤸"),
    ActivityType("stretching",     "Stretching",            "🙆"),
)

@Composable
fun IdleScreen(onStart: (String) -> Unit = {}) {
    var selected by remember { mutableStateOf("run") }

    JimTimeWearTheme {
        AppScaffold {
            ScreenScaffold {
                ScalingLazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item {
                        Text(
                            text = "JimTime",
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    activityTypes.forEach { type ->
                        item {
                            ActivityChip(
                                type     = type,
                                selected = selected == type.id,
                                onClick  = { selected = type.id },
                            )
                        }
                    }

                    item { Spacer(Modifier.height(4.dp)) }

                    item {
                        Button(
                            onClick = { onStart(selected) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF43A047),
                            ),
                        ) {
                            Text("▶ Inizia", textAlign = TextAlign.Center)
                        }
                    }

                    item {
                        Text(
                            text = "o avvia dall'app",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityChip(
    type: ActivityType,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected)
                Color(0xFF43A047).copy(alpha = 0.25f)
            else
                MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${type.emoji}  ${type.label}")
            if (selected) Text("✓", color = Color(0xFF43A047))
        }
    }
}

@WearPreviewDevices
@Composable
private fun IdleScreenPreview() {
    IdleScreen()
}
