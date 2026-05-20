package com.jimtime.wear.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.jimtime.wear.data.SessionKind
import com.jimtime.wear.data.SessionState
import com.jimtime.wear.data.WorkoutContext
import com.jimtime.wear.data.WorkoutCursor
import com.jimtime.wear.data.WorkoutTarget
import com.jimtime.wear.presentation.theme.JimTimeWearTheme
import kotlinx.coroutines.delay

/**
 * Wear OS counterpart to iOS [WorkoutSessionView]. Driven entirely by
 * [SessionState.workout] — the phone is the source of truth for cursor
 * and target; the watch only owns the local rest countdown timer and
 * the in-flight reps/weight overrides until "Fatto" is tapped.
 *
 * Three modes, swapped from a single composable:
 *   - **Active** — set N of M, target reps/weight stepper, "Fatto"
 *   - **Resting** — local countdown + "Salta riposo"
 *   - **Paused** — same data, controls swap to resume
 */
@Composable
fun WorkoutSessionScreen(
    sessionState: SessionState,
    heartRate: Double,
    onCompleteSet: (Int?, Double?) -> Unit,
    onSkipRest: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val ctx = sessionState.workout
    if (ctx == null) return

    // In-flight reps/weight overrides. Reset every time the cursor moves
    // (i.e. the athlete is now on a different set/exercise) so each new
    // "Fatto" starts from the prescribed plan target.
    var repsOverride by remember(ctx.cursor) {
        mutableStateOf<Int?>(null)
    }
    var weightOverride by remember(ctx.cursor) {
        mutableStateOf<Double?>(null)
    }

    JimTimeWearTheme {
        AppScaffold {
            ScreenScaffold {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // ── Header: exercise name + HR ────────────────────────
                    Header(ctx = ctx, heartRate = heartRate)

                    // ── Body: rest countdown OR active stepper + Fatto ────
                    if (ctx.restEndAtMs != null && !sessionState.isPaused) {
                        RestBody(
                            ctx = ctx,
                            onSkipRest = onSkipRest,
                        )
                    } else {
                        ActiveBody(
                            ctx = ctx,
                            isPaused = sessionState.isPaused,
                            repsOverride = repsOverride,
                            weightOverride = weightOverride,
                            onRepsChange = { repsOverride = it },
                            onWeightChange = { weightOverride = it },
                            onCompleteSet = {
                                onCompleteSet(repsOverride, weightOverride)
                            },
                        )
                    }

                    // ── Pause / Stop ──────────────────────────────────────
                    Controls(
                        isPaused = sessionState.isPaused,
                        onStop = onStop,
                        onPause = onPause,
                        onResume = onResume,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(ctx: WorkoutContext, heartRate: Double) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = ctx.target.exerciseName,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            if (heartRate > 0) {
                Text(
                    text = "♥ ${heartRate.toInt()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFEF5350),
                )
            }
        }
        val c = ctx.cursor
        val groupPart = if (c.totalGroups > 1) " · Es. ${c.groupIndex + 1}/${c.totalGroups}" else ""
        Text(
            text = "Set ${c.roundIndex + 1} di ${c.totalRoundsInGroup}$groupPart",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActiveBody(
    ctx: WorkoutContext,
    isPaused: Boolean,
    repsOverride: Int?,
    weightOverride: Double?,
    onRepsChange: (Int) -> Unit,
    onWeightChange: (Double) -> Unit,
    onCompleteSet: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val effectiveReps = repsOverride ?: ctx.target.reps
        val effectiveWeight = weightOverride ?: ctx.target.weight

        if (effectiveReps != null) {
            StepperRow(
                value = effectiveReps.toString(),
                label = "reps",
                onMinus = { onRepsChange((effectiveReps - 1).coerceAtLeast(0)) },
                onPlus  = { onRepsChange(effectiveReps + 1) },
                enabled = !isPaused,
            )
        }
        if (effectiveWeight != null) {
            StepperRow(
                value = formatWeight(effectiveWeight),
                label = "kg",
                onMinus = { onWeightChange((effectiveWeight - 0.5).coerceAtLeast(0.0)) },
                onPlus  = { onWeightChange(effectiveWeight + 0.5) },
                enabled = !isPaused,
            )
        }

        Button(
            onClick = onCompleteSet,
            modifier = Modifier
                .fillMaxWidth()
                .size(width = 140.dp, height = 44.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF43A047),
            ),
            enabled = !isPaused,
        ) {
            Text("✓ Fatto", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StepperRow(
    value: String,
    label: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Button(
            onClick = onMinus,
            modifier = Modifier.size(34.dp),
            shape = CircleShape,
            enabled = enabled,
        ) { Text("−") }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = onPlus,
            modifier = Modifier.size(34.dp),
            shape = CircleShape,
            enabled = enabled,
        ) { Text("+") }
    }
}

@Composable
private fun RestBody(
    ctx: WorkoutContext,
    onSkipRest: () -> Unit,
) {
    // Re-tick once per second so the countdown updates without a state
    // push from the phone.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ctx.restEndAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val endAt = ctx.restEndAtMs ?: return
    val remainingMs = (endAt - now).coerceAtLeast(0L)
    val mm = (remainingMs / 1000L) / 60
    val ss = (remainingMs / 1000L) % 60

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Recupero",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "%d:%02d".format(mm, ss),
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
            color = if (remainingMs == 0L) Color(0xFF66BB6A) else Color.White,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "prossimo: ${nextSetSummary(ctx)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onSkipRest,
            modifier = Modifier
                .fillMaxWidth()
                .size(width = 140.dp, height = 40.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFB8C00),
            ),
        ) {
            Text("⏭  Salta")
        }
    }
}

@Composable
private fun Controls(
    isPaused: Boolean,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onStop,
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
        ) { Text("■") }

        if (isPaused) {
            Button(
                onClick = onResume,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF43A047)),
            ) { Text("▶") }
        } else {
            Button(
                onClick = onPause,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFB8C00)),
            ) { Text("⏸") }
        }
    }
}

private fun nextSetSummary(ctx: WorkoutContext): String {
    val parts = mutableListOf<String>()
    ctx.target.reps?.let { parts += "$it reps" }
    ctx.target.weight?.let { parts += "${formatWeight(it)} kg" }
    return if (parts.isEmpty()) "—" else parts.joinToString(" · ")
}

private fun formatWeight(w: Double): String {
    return if (w == w.toLong().toDouble()) w.toLong().toString()
    else "%.1f".format(w)
}

@WearPreviewDevices
@Composable
private fun WorkoutSessionScreenPreview() {
    WorkoutSessionScreen(
        sessionState = SessionState(
            isActive = true,
            activityType = "workout",
            kind = SessionKind.WORKOUT,
            workout = WorkoutContext(
                planName = "Push/Pull",
                cursor = WorkoutCursor(
                    groupIndex = 1,
                    roundIndex = 1,
                    totalGroups = 6,
                    totalRoundsInGroup = 4,
                ),
                target = WorkoutTarget(
                    exerciseName = "Bench Press",
                    reps = 8,
                    weight = 60.0,
                    restSeconds = 90,
                ),
            ),
        ),
        heartRate = 124.0,
        onCompleteSet = { _, _ -> },
        onSkipRest = {},
        onStop = {},
        onPause = {},
        onResume = {},
    )
}
