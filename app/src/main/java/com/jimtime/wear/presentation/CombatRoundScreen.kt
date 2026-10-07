package com.jimtime.wear.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.jimtime.wear.data.CombatMeta
import com.jimtime.wear.data.GuidedStepMirror
import com.jimtime.wear.data.SessionRepository
import com.jimtime.wear.data.SessionState
import com.jimtime.wear.data.SportsCatalog
import com.jimtime.wear.health.TrackingEngine
import com.jimtime.wear.health.WristEvent
import com.jimtime.wear.presentation.theme.JTColors
import com.jimtime.wear.presentation.theme.JimTimeWearTheme
import kotlinx.coroutines.delay

// WP4 (Arti marziali M0, D6) — schermata round guidati dal TELEFONO
// (autorità): il polso mostra fase/round/conto alla rovescia dal mirror
// `guidedStep{kind:"combat"}`, manda HR e comandi. Colonna unica, nessuno
// scroll, pensata per il tondo piccolo da 192 dp (3 righe + 4 controlli).

/// Secondi rimanenti della fase. Con il polso in pausa (o senza `endsAt`)
/// vale il `remaining` "mm:ss" già pronto del telefono, altrimenti
/// `endsAt` (epoch ms) − adesso, arrotondato per eccesso. `null` = ignoto.
internal fun combatRemainingSec(step: GuidedStepMirror?, nowMs: Long): Int? {
    if (step == null || step.finished) return null
    val endsAt = step.endsAt
    if (!step.paused && endsAt != null) {
        return (((endsAt - nowMs) + 999L) / 1000L).toInt().coerceAtLeast(0)
    }
    return parseClock(step.remaining)
}

/// Vista risolta della fase: il mirror del telefono prevale; se è scaduto
/// (nessun mirror da un po') o fermo oltre `endsAt`, il polso avanza da solo
/// le fasi da `CombatMeta` + ultimo `endsAt` noto finché ne arriva uno nuovo.
internal data class CombatView(
    val phase: String?,
    val roundIndex: Int,
    val roundTotal: Int,
    val roundType: String?,
    val remainingSec: Int?,
    val finished: Boolean,
    /// M2 — etichetta del telefono (titolo di `timed`/`reps`), secondi pianificati, ripetizioni.
    val label: String = "",
    val phaseSec: Int = 0,
    val reps: Int = 0,
)

internal fun resolveCombatView(step: GuidedStepMirror?, meta: CombatMeta?, nowMs: Long): CombatView {
    val v = resolveCombatPhase(step, meta, nowMs)
    return if (step == null) v else v.copy(label = step.label, phaseSec = step.phaseSec, reps = step.reps)
}

private fun resolveCombatPhase(step: GuidedStepMirror?, meta: CombatMeta?, nowMs: Long): CombatView {
    val total = step?.roundTotal?.takeIf { it > 0 } ?: meta?.rounds ?: 0
    if (step == null) return CombatView(null, 0, total, null, null, false)
    if (step.finished) return CombatView(step.phase, step.roundIndex, total, null, null, true)
    val endsAt = step.endsAt
    if (step.paused || endsAt == null) {
        return CombatView(step.phase, step.roundIndex, total, step.roundType, parseClock(step.remaining), false)
    }
    var phase = step.phase
    var round = step.roundIndex
    var type = step.roundType
    var end = endsAt
    val work = (meta?.workSec ?: 0).toLong() * 1000L
    val rest = (meta?.restSec ?: 0).toLong() * 1000L
    var guard = 0
    // M2 — scheda: la sequenza è del telefono, mai «FINE» né fasi dedotte qui.
    while (meta?.plan != true && nowMs >= end && guard++ < 2000) {
        // Prossima fase: prep→work 1 · work→rest (o work+1 se niente riposo) · rest→work+1.
        val next: Triple<String, Int, Long>? = when (phase) {
            "prep" -> Triple("work", 1, work)
            "work" -> when {
                total > 0 && round >= total -> null
                rest > 0 -> Triple("rest", round, rest)
                else -> Triple("work", round + 1, work)
            }
            "rest" -> if (total > 0 && round >= total) null else Triple("work", round + 1, work)
            else -> return CombatView(phase, round, total, type, 0, false)
        }
        if (next == null) return CombatView(phase, round, total, null, null, true)
        if (next.third <= 0L) break // durate ignote (CombatMeta dal mirror): fermo a 0:00
        phase = next.first; round = next.second; type = null; end += next.third
    }
    val rem = (((end - nowMs) + 999L) / 1000L).toInt().coerceAtLeast(0)
    return CombatView(phase, round, total, type, rem, false)
}

internal fun parseClock(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.size !in 2..3) return null
    val nums = parts.map { it.toIntOrNull() ?: return null }
    return if (nums.size == 2) nums[0] * 60 + nums[1] else nums[0] * 3600 + nums[1] * 60 + nums[2]
}

internal fun formatCombatClock(sec: Int?): String {
    if (sec == null) return "--:--"
    return "%d:%02d".format(sec / 60, sec % 60)
}

private fun phaseColor(phase: String?): Color = when (phase) {
    "work" -> JTColors.warning
    "rest" -> JTColors.success
    else -> JTColors.brandBright
}

private fun phaseLabel(v: CombatView): String {
    val total = v.roundTotal
    return when (v.phase) {
        // M2 — il telefono compone il titolo («Riscaldamento», «Tecnica · 20 rip.»).
        "timed", "reps" -> v.label.ifBlank { if (v.phase == "reps") "RIPETIZIONI" else "BLOCCO" }
        "work" -> if (total > 0) "ROUND ${v.roundIndex}/$total" else "ROUND"
        "rest" -> "RIPOSO"
        "prep" -> "PREPARATI"
        else -> if (total > 0) "$total ROUND" else "ROUND"
    }
}

@Composable
fun CombatRoundScreen(
    sessionState: SessionState,
    heartRate: Double,
    offBody: Boolean,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: () -> Unit,
    onExtend: () -> Unit,
) {
    val meta = sessionState.combat
    // Ultimo mirror noto, anche se vecchio: il fallback locale lo prosegue.
    val last = sessionState.guidedStep?.takeIf { it.isCombat }
    val discipline = meta?.discipline ?: sessionState.activityType

    // Ticker locale (250 ms): il mirror arriva ogni ~5 s, il conto è locale.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(250)
        }
    }
    val view = resolveCombatView(last, meta, nowMs)
    val color = if (view.finished) JTColors.brandBright else phaseColor(view.phase)
    val paused = sessionState.isPaused || (last?.paused == true)
    // Mai "--:--" a metà seduta: senza fase nota si mostra il tempo trascorso.
    val isReps = view.phase == "reps"
    // M2 — un tocco al polso quando si entra o si esce da un blocco a ripetizioni.
    val context = LocalContext.current
    var prevRepsKey by remember { mutableStateOf<String?>(null) }
    val repsKey = last?.takeIf { it.phase == "reps" }?.let { "s${it.index}" }
    LaunchedEffect(repsKey, last?.index) {
        val prev = prevRepsKey
        if (repsKey != prev && (repsKey != null || prev != null)) {
            GuidedHaptics.repsTap(context)
        }
        prevRepsKey = repsKey
    }
    val clockText = view.remainingSec?.let { formatCombatClock(it) } ?: sessionState.formattedElapsed()

    JimTimeWearTheme {
        AppScaffold {
            ScreenScaffold(timeText = {}) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 26.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (view.finished) {
                        CombatFinishedBody(discipline, sessionState.formattedElapsed(), color, onStop)
                        return@Column
                    }
                    // ── (1) Disciplina ───────────────────────────────────
                    Box0 {
                        CapsuleChip(
                            text = "${SportsCatalog.emoji(discipline)} ${SportsCatalog.label(discipline)}",
                            tint = color,
                        )
                    }

                    // ── (2) Fase + conto alla rovescia (hero) ────────────
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = phaseLabel(view) +
                                (view.roundType?.takeIf { view.phase == "work" }
                                    ?.let { " · " + it.replace('_', ' ').uppercase() } ?: ""),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                            ),
                            color = color,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = if (isReps) "${view.reps}×" else clockText,
                            style = MaterialTheme.typography.displayMedium.copy(
                                fontSize = 42.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = if (paused) Color.White.copy(alpha = 0.5f) else Color.White,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                        )
                        // M2 — barra della fase da `phaseSec` (non per `reps`/ignoto).
                        val rem = view.remainingSec
                        if (!isReps && view.phaseSec > 0 && rem != null) {
                            val frac = (1f - rem.toFloat() / view.phaseSec).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .width(96.dp)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.White.copy(alpha = 0.18f)),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(frac)
                                        .fillMaxHeight()
                                        .background(color),
                                )
                            }
                        }
                    }

                    // ── (3) FC oppure avviso «orologio tolto» ────────────
                    if (offBody) {
                        Text(
                            text = "⌚ Orologio tolto",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = JTColors.warning,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(
                            text = (if (heartRate > 0) "♥ ${heartRate.toInt()}" else "♥ --") +
                                "  ·  ${sessionState.formattedElapsed()}",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = if (heartRate > 0) JTColors.hr else Color.White.copy(alpha = 0.6f),
                            maxLines = 1,
                        )
                    }

                    // ── (4) Controlli: stop · pausa · +30″ · salta ───────
                    // 4 cerchi da 30 dp (riga 132 dp): il bordo basso sta a ≤69 dp
                    // sotto il centro del tondo da 192 dp, quindi gli angoli
                    // esterni restano dentro il cerchio (r=96) — padding basso
                    // 26 dp, verificato allo screenshot.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircleControl("■", JTColors.danger, solid = false, size = 30.dp, onClick = onStop)
                        if (sessionState.isPaused) {
                            CircleControl("▶", JTColors.success, solid = true, size = 30.dp, onClick = onResume)
                        } else {
                            CircleControl("⏸", JTColors.warning, solid = false, size = 30.dp, onClick = onPause)
                        }
                        // M2 — su `reps` il «+30″» non ha senso; «›» = «Fatto».
                        if (!isReps) {
                            CircleControl("+30", color, solid = false, size = 30.dp, onClick = onExtend)
                        }
                        CircleControl("›", color, solid = false, size = 30.dp, onClick = onSkip)
                    }
                }
            }
        }
    }
}

/// Stato finito: solo «FINE» + Stop (passa dallo stop esistente del polso).
@Composable
private fun CombatFinishedBody(discipline: String, elapsed: String, color: Color, onStop: () -> Unit) {
    Box0 {
        CapsuleChip(
            text = "${SportsCatalog.emoji(discipline)} ${SportsCatalog.label(discipline)}",
            tint = color,
        )
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "FINE",
            style = MaterialTheme.typography.displayMedium.copy(
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = color,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Text(
            text = elapsed,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            ),
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 1,
        )
    }
    CircleControl("■", JTColors.danger, solid = true, size = 40.dp, onClick = onStop)
}

@Composable
private fun Box0(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(top = 2.dp)) { content() }
}

/// Solo debug: `adb shell am start -n … --es JT_FAKE_COMBAT work|rest|offbody|finished|stale`
/// riempie lo stato come farebbe il telefono, per gli screenshot (mai input
/// sintetico). No-op nelle release (MainActivity lo chiama solo con `BuildConfig.DEBUG`).
object CombatDebugHook {
    fun apply(phase: String) {
        val now = System.currentTimeMillis()
        val plan = phase == "timed" || phase == "reps"
        val meta = if (plan) CombatMeta("boxing", 7, 0, 0, plan = true)
        else CombatMeta(discipline = "boxing", rounds = 5, workSec = 180, restSec = 60)
        SessionRepository.startSession("boxing", now - 754_000L, null, meta)
        if (plan) {
            // M2 — schede: `timed` (Riscaldamento 12:00, 4:30 trascorsi) · `reps` (Tecnica · 20 rip.).
            val timed = phase == "timed"
            SessionRepository.updateGuidedStep(
                GuidedStepMirror(
                    index = 1, total = 6, label = if (timed) "Riscaldamento" else "Tecnica · 20 rip.",
                    remaining = "", target = "", next = null,
                    endsAt = if (timed) now + 450_000L else null,
                    finished = false, receivedAtMs = now,
                    kind = "combat", phase = phase, roundIndex = 0, roundTotal = 7,
                    phaseSec = if (timed) 720 else 0, reps = if (timed) 0 else 20,
                ),
            )
            return
        }
        val rest = phase == "rest"
        val finished = phase == "finished"
        // `stale`: mirror ricevuto 5 min fa, round 2 finito da 100 s ⇒ il polso
        // avanza da solo (riposo 60 s concluso → round 3, ~2:20 rimasti).
        val stale = phase == "stale"
        SessionRepository.updateGuidedStep(
            GuidedStepMirror(
                index = if (rest) 2 else 3, total = 10, label = if (rest) "Riposo" else "Round 2",
                remaining = "", target = "", next = null,
                endsAt = if (stale) now - 100_000L else now + (if (rest) 41_000L else 97_000L),
                finished = finished,
                receivedAtMs = if (stale) now - 300_000L else now,
                kind = "combat", phase = if (rest) "rest" else "work",
                roundIndex = if (finished) 5 else 2, roundTotal = 5,
                roundType = if (rest || stale) null else "sparring",
            ),
        )
        // Dopo che la VM si è agganciata ai wristEvents (un SharedFlow non ha replay).
        if (phase == "offbody") {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { TrackingEngine.swimSource.emitWrist(WristEvent.OFF_BODY) }, 2_500L,
            )
        }
    }
}
