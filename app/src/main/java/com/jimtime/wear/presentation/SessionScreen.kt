package com.jimtime.wear.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import com.jimtime.wear.data.GuidedStepMirror
import com.jimtime.wear.data.SessionState
import com.jimtime.wear.presentation.theme.JTColors
import com.jimtime.wear.presentation.theme.JimTimeWearTheme

// F7 (WP-WU/WP-WD, §2.4) — `GuidedStepMirror` (`data/SessionState.kt`):
// eco di `guidedStep` con `receivedAtMs` aggiunto dal service alla
// ricezione; `isFresh(nowMs)` (< `VALID_MS` = 60 s) decide se la striscia
// companion resta visibile — usata direttamente, nessun calcolo locale.

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
    // F7 (D24) — con la striscia companion visibile il budget del 192 dp
    // non basta più per tutto invariato (misurato allo screenshot: i
    // bottoni finivano tagliati sotto il bordo anche dopo aver tolto
    // "Durata" e ridotto numero/bottoni): si stringe anche il padding
    // esterno e quello della riga chip.
    val freshGuidedStep = sessionState.guidedStep
        ?.takeIf { it.isFresh(System.currentTimeMillis()) }

    JimTimeWearTheme {
        AppScaffold {
            // F7 (conduttore) — stesso fix di `GuidedSessionScreen`:
            // schermo statico (nessuno scrollState), l'orologio di sistema
            // di default di `ScreenScaffold` resta fisso in alto e si
            // sovrappone al chip attività (visto allo screenshot).
            ScreenScaffold(timeText = {}) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = 12.dp,
                            vertical = if (freshGuidedStep != null) 4.dp else 8.dp,
                        ),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // ── Top row: activity chip + optional standalone badge ─
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = if (freshGuidedStep != null) 0.dp else 4.dp),
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

                    // ── Companion strip (F7, D24/§2.8) ──────────────────────
                    // Il telefono guida (F1): 2 righe dal mirror
                    // `sessionState.guidedStep`, valide 60 s dalla
                    // ricezione (oltre, il companion sparisce — niente
                    // "fase 3/14" congelata per minuti se il telefono
                    // smette di mandare). Mai durante una sessione
                    // wrist-authoritative (`guided == true`): quella usa
                    // `GuidedSessionScreen`, non questa schermata.
                    freshGuidedStep?.let { GuidedCompanionStrip(it) }

                    // ── Elapsed ────────────────────────────────────────────
                    // Con la striscia companion visibile il budget verticale
                    // del 192 dp non basta più per TUTTO invariato (chip +
                    // 2 righe + "Durata" + 40 sp + stats + bottoni finiva
                    // tagliato sotto il bordo, visto allo screenshot F7):
                    // si toglie la caption "Durata" (ridondante quando la
                    // fase è già sopra) e il numero scende a 32 sp.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (freshGuidedStep == null) TinyLabel("Durata")
                        Text(
                            text = sessionState.formattedElapsed(),
                            style = MaterialTheme.typography.displayMedium.copy(
                                fontSize = if (freshGuidedStep != null) 26.sp else 40.sp,
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
                    // Con la striscia companion visibile il budget verticale
                    // del 192 dp non basta più per tutto invariato (misurato
                    // allo screenshot F7): oltre a rimpicciolire i tasti
                    // (32 dp, spaziatura 8 dp invece di 48/14), la riga va
                    // alzata di 6 dp — verifica geometrica (entrambi i tasti
                    // sono "esterni" qui, 2 soli): centro-raggio ≤ R−4 dp,
                    // R=96 dp sul 192 dp piccolo (margine reale ~7 dp dopo
                    // l'alzata; senza, i due cerchi uscivano dal tondo,
                    // "tacca" tagliata dalla maschera, visto allo screenshot).
                    val controlSize = if (freshGuidedStep != null) 32.dp else 48.dp
                    val controlSpacing = if (freshGuidedStep != null) 8.dp else 14.dp
                    val controlsRowOffset = if (freshGuidedStep != null) (-6).dp else 0.dp
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(controlSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.offset(y = controlsRowOffset),
                    ) {
                        // Stop — red tinted circle
                        CircleControl(
                            glyph = "■",
                            tint = JTColors.danger,
                            solid = false,
                            size = controlSize,
                            onClick = onStop,
                        )

                        // Pause / Resume
                        if (sessionState.isPaused) {
                            CircleControl(
                                glyph = "▶",
                                tint = JTColors.success,
                                solid = true,
                                size = controlSize,
                                onClick = onResume,
                            )
                        } else {
                            CircleControl(
                                glyph = "⏸",
                                tint = JTColors.warning,
                                solid = false,
                                size = controlSize,
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
internal fun StatCell(icon: String, tint: Color, label: String, value: String) {
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
        // Correzioni verificatore — il content padding di default di
        // `Button` (pensato per testo/icone rettangolari) schiacciava il
        // glifo ■ orizzontalmente sui tasti piccoli (32–40 dp): diventava
        // una barra stretta invece di un quadrato. A zero, il glifo usa
        // l'intero cerchio (controllato anche su ▶/⏸/› qui e in
        // `GuidedSessionScreen`: nessuna regressione, erano già centrati).
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(
            glyph,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * F7 (D24) — striscia companion "il telefono guida" (F1): 2 righe dal
 * mirror `guidedStep` (`SessionState.guidedStep: GuidedStepMirror?`,
 * WP-WD). Riga 1 = fase + rimanente, riga 2 = target (omesso se vuoto).
 * Niente banda/marcatore qui — questa resta la sessione "libera" mirror
 * del phone, non la guida wrist-authoritative (`GuidedSessionScreen`).
 */
@Composable
private fun GuidedCompanionStrip(step: GuidedStepMirror) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 0.dp, bottom = 0.dp),
    ) {
        Text(
            // Correzioni verificatore — il telefono manda già `index` in
            // base 1 (`_engine.index + 1`, §2.4): sommare un altro +1 qui
            // faceva leggere «2/14» sulla primissima fase.
            text = "${step.index}/${step.total} · ${step.label} · ${step.remaining}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Medium),
            color = JTColors.brand,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (step.target.isNotEmpty()) {
            Text(
                text = step.target,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
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
