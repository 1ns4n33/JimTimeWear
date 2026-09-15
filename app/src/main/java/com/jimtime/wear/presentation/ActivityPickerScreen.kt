package com.jimtime.wear.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import com.jimtime.wear.data.PlanDay
import com.jimtime.wear.presentation.theme.JTColors
import com.jimtime.wear.presentation.theme.JimTimeWearTheme

/**
 * "Tutte le attività" — SCHEDA (plan days, starts exactly like the hero
 * card) + OUTDOOR/INDOOR (tap starts the countdown, then the session —
 * no more select-then-scroll-to-"Inizia").
 */
@Composable
fun ActivityPickerScreen(
    planName: String,
    planDays: List<PlanDay>,
    onStartPlanDay: (PlanDay) -> Unit,
    onTapActivity: (String) -> Unit,
    showPhoneNeeded: Boolean = false,
    onRequestPlanDays: () -> Unit = {},
) {
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
                        SectionLabel(
                            text = "📋 ${planName.ifEmpty { "Scheda" }}",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (showPhoneNeeded) {
                        item {
                            // Stesso hint della home: la scheda parte solo col
                            // telefono raggiungibile (il motore vive lì).
                            Text(
                                text = "📵 Serve il telefono per avviare la scheda",
                                style = MaterialTheme.typography.labelSmall,
                                color = JTColors.danger,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (planDays.isEmpty()) {
                        item {
                            // Il Wear non ha ancora un segnale di sincronizzazione
                            // scheda (planSyncStatus, solo lato iOS): il tap
                            // ri-chiede la lista al telefono, come su iOS.
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .clickable(onClick = onRequestPlanDays)
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                            ) {
                                Text(
                                    text = "↻ Nessuna scheda",
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                                    maxLines = 1,
                                )
                                Text(
                                    text = "Apri JimTime sul telefono e tocca qui",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        planDays.forEach { day ->
                            item {
                                PlanDayChip(day = day, onClick = { onStartPlanDay(day) })
                            }
                        }
                    }

                    item {
                        SectionLabel(text = "Outdoor", modifier = Modifier.padding(top = 8.dp))
                    }
                    ActivityCatalog.outdoor.forEach { type ->
                        item {
                            ActivityRow(type = type, onClick = { onTapActivity(type.id) })
                        }
                    }

                    item {
                        SectionLabel(text = "Indoor", modifier = Modifier.padding(top = 8.dp))
                    }
                    ActivityCatalog.indoor.forEach { type ->
                        item {
                            ActivityRow(type = type, onClick = { onTapActivity(type.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 6.dp),
    )
}

/** Plan-day row — brand gradient chip, tap starts the day directly (existing path). */
@Composable
private fun PlanDayChip(day: PlanDay, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        JTColors.brand.copy(alpha = 0.12f),
                        JTColors.brand.copy(alpha = 0.05f),
                    )
                )
            )
            .border(1.dp, JTColors.brand.copy(alpha = 0.25f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(27.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(JTColors.brand.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("🏋️", fontSize = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = day.label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Sett. ${day.week} · ${day.exercises} esercizi",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(JTColors.brand),
                contentAlignment = Alignment.Center,
            ) {
                Text("▶", fontSize = 9.sp, color = Color.White)
            }
        }
    }
}

/** Outdoor/indoor chip — WITHOUT the old selection checkmark, tap = countdown → start. */
@Composable
private fun ActivityRow(type: ActivityTypeInfo, onClick: () -> Unit) {
    val accent = JTColors.activity(type.id)
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(type.emoji, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = type.label,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@WearPreviewDevices
@Composable
private fun ActivityPickerScreenPreview() {
    ActivityPickerScreen(
        planName = "",
        planDays = emptyList(),
        onStartPlanDay = {},
        onTapActivity = {},
    )
}
