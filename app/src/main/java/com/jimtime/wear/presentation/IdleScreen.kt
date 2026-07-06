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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
fun IdleScreen(
    onStart: (String) -> Unit = {},
    planName: String = "",
    planDays: List<PlanDay> = emptyList(),
    showPhoneNeeded: Boolean = false,
    onStartPlanDay: (PlanDay) -> Unit = {},
) {
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
                    item { BrandHeader() }

                    // ── Active plan days (pushed by the phone) ─────────
                    if (planDays.isNotEmpty()) {
                        item {
                            SectionLabel(
                                text = "📋 ${planName.ifEmpty { "Scheda attiva" }}",
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        if (showPhoneNeeded) {
                            item {
                                Text(
                                    text = "📵 Serve il telefono per avviare la scheda",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = JTColors.danger,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        planDays.forEach { day ->
                            item {
                                PlanDayChip(day = day, onClick = { onStartPlanDay(day) })
                            }
                        }
                        item {
                            SectionLabel(
                                text = "Attività",
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
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

                    item { StartCta(onClick = { onStart(selected) }) }

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

/** "JimTime" title with a small brand-violet dot suffix. */
@Composable
private fun BrandHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(
            text = "JimTime",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.width(3.dp))
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(JTColors.brand),
        )
    }
}

/** Tiny uppercase section label, aligned leading, letter-spaced. */
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

@Composable
private fun PlanDayChip(
    day: PlanDay,
    onClick: () -> Unit,
) {
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
            // Leading rounded-square dumbbell badge.
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
            // Trailing filled play glyph in brand violet.
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

@Composable
private fun ActivityChip(
    type: ActivityType,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = JTColors.activity(type.id)
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) accent.copy(alpha = 0.16f)
                else Color.White.copy(alpha = 0.06f)
            )
            .then(
                if (selected) Modifier.border(1.5.dp, accent, shape)
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leading circular activity badge.
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
            if (selected) {
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "✓",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                    )
                }
            }
        }
    }
}

/** Full-width capsule CTA with the brand violet gradient. */
@Composable
private fun StartCta(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(CircleShape)
            .background(
                Brush.horizontalGradient(listOf(JTColors.brand, JTColors.brandBright))
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "▶ Inizia",
            textAlign = TextAlign.Center,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

@WearPreviewDevices
@Composable
private fun IdleScreenPreview() {
    IdleScreen()
}
