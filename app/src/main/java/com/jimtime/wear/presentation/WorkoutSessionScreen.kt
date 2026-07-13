package com.jimtime.wear.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import com.jimtime.wear.data.SessionKind
import com.jimtime.wear.data.SessionState
import com.jimtime.wear.data.WorkoutContext
import com.jimtime.wear.data.WorkoutCursor
import com.jimtime.wear.data.WorkoutTarget
import com.jimtime.wear.presentation.theme.JTColors
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
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = ctx.target.exerciseName,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (heartRate > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "♥ ${heartRate.toInt()}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = JTColors.hr,
                    maxLines = 1,
                )
            }
        }
        val c = ctx.cursor
        val groupPart = if (c.totalGroups > 1) " · Es. ${c.groupIndex + 1}/${c.totalGroups}" else ""
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (c.totalRoundsInGroup <= 8) {
                SetProgressRow(
                    total = c.totalRoundsInGroup,
                    currentIndex = c.roundIndex,
                )
            }
            Text(
                text = "Set ${c.roundIndex + 1} di ${c.totalRoundsInGroup}$groupPart",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * One small segment per round: completed = solid brand violet, current =
 * brand violet with an outer ring, remaining = white 15 %.
 */
@Composable
private fun SetProgressRow(total: Int, currentIndex: Int) {
    val shape = RoundedCornerShape(3.dp)
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { i ->
            val fill = when {
                i < currentIndex  -> JTColors.brand
                i == currentIndex -> JTColors.brand
                else              -> Color.White.copy(alpha = 0.15f)
            }
            Box(
                modifier = Modifier
                    .width(10.dp)
                    .height(5.dp)
                    .clip(shape)
                    .background(fill)
                    .then(
                        if (i == currentIndex)
                            Modifier.border(1.dp, JTColors.brandBright.copy(alpha = 0.9f), shape)
                        else Modifier
                    ),
            )
        }
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

        // Intervalli a tempo (Tabata/EMOM/HIIT/stazioni): countdown lavoro
        // al posto del target di carico. Ancorato all'arrivo del cursor —
        // il telefono avanza allo 0 e il push successivo fa ripartire.
        val workSeconds = ctx.target.durationSeconds
        if (workSeconds != null && workSeconds > 0 && !isPaused) {
            var remaining by remember(ctx.cursor) {
                mutableStateOf(workSeconds)
            }
            LaunchedEffect(ctx.cursor) {
                val endAt = System.currentTimeMillis() + workSeconds * 1000L
                while (true) {
                    val left =
                        ((endAt - System.currentTimeMillis()) / 1000L).toInt()
                    remaining = left.coerceAtLeast(0)
                    if (left <= 0) break
                    kotlinx.coroutines.delay(250)
                }
            }
            Text(
                text = "%d:%02d".format(remaining / 60, remaining % 60),
                style = MaterialTheme.typography.numeralMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

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
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = JTColors.success,
                contentColor = Color.Black,
                disabledContainerColor = JTColors.success.copy(alpha = 0.4f),
                disabledContentColor = Color.Black.copy(alpha = 0.4f),
            ),
            enabled = !isPaused,
        ) {
            Text(
                "✓ Fatto",
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
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
        StepperButton(glyph = "−", onClick = onMinus, enabled = enabled)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                ),
                maxLines = 1,
            )
            TinyLabel(label)
        }
        StepperButton(glyph = "+", onClick = onPlus, enabled = enabled)
    }
}

/** 32 pt circle, white 10 % fill, bold symbol. */
@Composable
private fun StepperButton(
    glyph: String,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.10f),
            contentColor = Color.White,
        ),
        enabled = enabled,
    ) {
        Text(
            glyph,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
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
    val totalRestSeconds = ctx.target.restSeconds
    val nearEnd = remainingMs <= 5_000L

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TinyLabel("Recupero")

        val countdownText = "%d:%02d".format(mm, ss)
        val countdownColor = if (remainingMs == 0L) JTColors.success else Color.White

        if (totalRestSeconds != null && totalRestSeconds > 0) {
            // Countdown ring: track white 10 %, arc brand violet turning
            // green in the last 5 s; progress = remaining / target.
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = {
                        (remainingMs / (totalRestSeconds * 1000f)).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.size(84.dp),
                    colors = ProgressIndicatorDefaults.colors(
                        indicatorColor = if (nearEnd) JTColors.success else JTColors.brand,
                        trackColor = Color.White.copy(alpha = 0.10f),
                    ),
                    strokeWidth = 7.dp,
                )
                Text(
                    text = countdownText,
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = countdownColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        } else {
            // No prescribed rest duration — text-only countdown.
            Text(
                text = countdownText,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                ),
                color = countdownColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }

        Text(
            text = "prossimo: ${nextSetSummary(ctx)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Button(
            onClick = onSkipRest,
            modifier = Modifier
                .fillMaxWidth()
                .size(width = 140.dp, height = 40.dp)
                .border(1.dp, JTColors.warning.copy(alpha = 0.8f), CircleShape),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = JTColors.warning,
            ),
        ) {
            Text(
                "⏭  Salta",
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
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
        CircleControl(
            glyph = "■",
            tint = JTColors.danger,
            solid = false,
            size = 40.dp,
            onClick = onStop,
        )

        if (isPaused) {
            CircleControl(
                glyph = "▶",
                tint = JTColors.success,
                solid = true,
                size = 40.dp,
                onClick = onResume,
            )
        } else {
            CircleControl(
                glyph = "⏸",
                tint = JTColors.warning,
                solid = false,
                size = 40.dp,
                onClick = onPause,
            )
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
