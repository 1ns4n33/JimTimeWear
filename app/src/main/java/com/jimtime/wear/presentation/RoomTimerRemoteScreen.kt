package com.jimtime.wear.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.jimtime.wear.data.RoomTimerState
import com.jimtime.wear.data.RoomTimerStore
import com.jimtime.wear.data.SessionRepository
import com.jimtime.wear.presentation.theme.JTColors
import com.jimtime.wear.presentation.theme.JimTimeWearTheme
import kotlinx.coroutines.delay

// M1 (Arti marziali, timer di sala §7.5) — telecomando del timer del coach.
// Il TELEFONO è l'autorità: qui solo specchio (fase/round/conto alla rovescia
// da `endsAt`) e 4 comandi. Colonna unica, nessuno scroll, tondo piccolo 192 dp.

/// Secondi rimanenti: da `endsAt` se in corso, altrimenti `remaining` del telefono.
internal fun roomRemainingSec(s: RoomTimerState, nowMs: Long): Int? {
    val endsAt = s.endsAt
    if (!s.paused && endsAt != null) {
        return (((endsAt - nowMs) + 999L) / 1000L).toInt().coerceAtLeast(0)
    }
    return parseClock(s.remaining)
}

/// Aptica −10 s / 0 derivata da `endsAt`: `warn`/`end` oppure `null`.
/// Mai in pausa né a stato scaduto.
internal fun roomHapticFor(s: RoomTimerState, nowMs: Long): String? {
    if (s.paused || s.endsAt == null || s.isEnded) return null
    val rem = roomRemainingSec(s, nowMs) ?: return null
    return when {
        rem == 0 -> "end"
        rem in 1..10 -> "warn"
        else -> null
    }
}

private fun roomColor(s: RoomTimerState): Color = when {
    s.isEnded -> JTColors.brandBright
    s.phase == "work" || s.phase == "timed" -> JTColors.warning
    s.phase == "rest" || s.phase == "gap" -> JTColors.success
    else -> JTColors.brandBright
}

@Composable
fun RoomTimerRemoteScreen(
    state: RoomTimerState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: () -> Unit,
    onExtend: () -> Unit,
    onRepeat: () -> Unit,
    onRefresh: () -> Unit,
) {
    val ctx = LocalContext.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(250)
        }
    }
    val stale = RoomTimerStore.isStale(state, nowMs)
    val color = roomColor(state)

    // Aptica −10 s (una volta) e 0 (una volta) per fase, derivate da endsAt.
    val warnKey = state.step?.let { "s$it-${state.endsAt}" } ?: "${state.roundIndex}-${state.phase}-${state.endsAt}"
    var warned by remember(warnKey) { mutableLongStateOf(0L) } // bit0 warn, bit1 end
    if (!stale) {
        when (roomHapticFor(state, nowMs)) {
            "warn" -> if (warned and 1L == 0L) { warned = warned or 1L; GuidedHaptics.roomWarn(ctx) }
            "end" -> if (warned and 2L == 0L) { warned = warned or 2L; GuidedHaptics.roomEnd(ctx) }
        }
    }

    // «Fine lezione» → null dopo 3 s: lo azzera lo store, non questo composable.

    JimTimeWearTheme {
        AppScaffold {
            ScreenScaffold(timeText = {}) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 14.dp, end = 14.dp, top = 20.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when {
                        state.isEnded -> EndedBody(state.lessonName)
                        stale -> StaleBody(onRefresh)
                        else -> LiveBody(state, nowMs, color, onPause, onResume, onSkip, onExtend, onRepeat)
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveBody(
    s: RoomTimerState,
    nowMs: Long,
    color: Color,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: () -> Unit,
    onExtend: () -> Unit,
    onRepeat: () -> Unit,
) {
    val label = s.label.ifBlank { s.lessonName }.uppercase()
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.2.sp,
        ),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = formatCombatClock(roomRemainingSec(s, nowMs)),
            style = MaterialTheme.typography.displayMedium.copy(
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            ),
            color = if (s.paused) Color.White.copy(alpha = 0.5f) else Color.White,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        if (s.paused) {
            Text(
                text = "PAUSA",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                ),
                color = JTColors.warning,
                maxLines = 1,
            )
        }
    }
    Text(
        text = s.next,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
        color = Color.White.copy(alpha = 0.65f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
    // 4 cerchi da 30 dp su due righe: [pausa · salta] / [+30″ · ripeti].
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.paused) {
                CircleControl("▶", JTColors.success, solid = true, size = 30.dp, onClick = onResume)
            } else {
                CircleControl("⏸", JTColors.warning, solid = false, size = 30.dp, onClick = onPause)
            }
            CircleControl("›", color, solid = false, size = 30.dp, onClick = onSkip)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleControl("+30", color, solid = false, size = 30.dp, onClick = onExtend)
            CircleControl("↻", color, solid = false, size = 30.dp, onClick = onRepeat)
        }
    }
}

@Composable
private fun EndedBody(lessonName: String) {
    Text(
        text = lessonName.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
        color = JTColors.brandBright,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = "Fine lezione",
        style = MaterialTheme.typography.displayMedium.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold),
        color = Color.White,
        maxLines = 2,
        textAlign = TextAlign.Center,
    )
    Text(text = "", maxLines = 1)
}

@Composable
private fun StaleBody(onRefresh: () -> Unit) {
    Text(text = "", maxLines = 1)
    Text(
        text = "Telefono?",
        style = MaterialTheme.typography.displayMedium.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold),
        color = Color.White,
        maxLines = 1,
        textAlign = TextAlign.Center,
    )
    androidx.wear.compose.material3.Button(onClick = onRefresh) {
        Text("Aggiorna", maxLines = 1)
    }
}

/// Solo debug: `adb shell am start -n … --es JT_FAKE_ROOM_TIMER work|rest|paused|stale|ended`
/// riempie lo store come farebbe il telefono, per gli screenshot (mai input
/// sintetico). MainActivity lo chiama solo con `BuildConfig.DEBUG`.
object RoomTimerDebugHook {
    fun apply(mode: String) {
        val now = System.currentTimeMillis()
        val rest = mode == "rest"
        val paused = mode == "paused"
        val json = org.json.JSONObject()
            .put("cmd", "roomTimer")
            .put("state", if (mode == "ended") "ended" else "live")
            .put("lessonName", "Sparring del martedì")
            .put("kind", "rounds")
            .put("phase", if (rest) "rest" else "work")
            .put("label", if (rest) "Riposo" else "Round 3 · Sparring")
            .put("roundIndex", 3).put("roundTotal", 6)
            .put("remaining", if (paused) "1:47" else if (rest) "0:41" else "1:37")
            .put("paused", paused)
            .put("next", if (rest) "Round 4 · Sparring" else "Pausa 1:00 · poi round 4")
            .put("finished", mode == "ended")
            .put("seq", 7).put("step", if (rest) 6 else 5)
        if (!paused) json.put("endsAt", now + if (rest) 41_000L else 97_000L)
        // `stale`: ricevuto 2 minuti fa ⇒ «Telefono?».
        RoomTimerStore.apply(json, if (mode == "stale") now - 70_000L else now)
    }
}
