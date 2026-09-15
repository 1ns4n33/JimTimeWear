package com.jimtime.wear.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import com.jimtime.wear.data.IntervalSpec
import com.jimtime.wear.presentation.theme.JimTimeWearTheme

/** The 4 interval presets, moved here unchanged from the old IdleScreen — tap starts immediately. */
@Composable
fun IntervalsScreen(onStartInterval: (IntervalSpec) -> Unit) {
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
                            text = "⏱ Intervalli",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    IntervalSpec.presets.forEach { spec ->
                        item {
                            Button(
                                onClick = { onStartInterval(spec) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = "${spec.modeLabel} · ${spec.compactLabel}",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@WearPreviewDevices
@Composable
private fun IntervalsScreenPreview() {
    IntervalsScreen(onStartInterval = {})
}
