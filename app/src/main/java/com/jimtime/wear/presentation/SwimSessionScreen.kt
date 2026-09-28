package com.jimtime.wear.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
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
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import com.jimtime.wear.data.SessionState
import com.jimtime.wear.data.SwimData
import com.jimtime.wear.health.SwimGuideRep
import com.jimtime.wear.presentation.theme.JTColors
import com.jimtime.wear.presentation.theme.JimTimeWearTheme

/**
 * F5b (Nuoto, §7) — schermata attiva swim_pool/swim_open_water. STRUTTURA
 * gemella di [SessionScreen] (stessi [CapsuleChip]/[StatCell]/[TinyLabel]/
 * [CircleControl], stesso padding di Column, stesso `SpaceBetween`) — solo
 * il chip header è più corto ("Vasca"/"Acque libere" invece del nome
 * attività per esteso, D2 review: la label lunga usciva dal bordo
 * rotondo) e il blocco centrale mostra il countdown della guida a tempo
 * ([SwimGuideRep]) quando l'atleta segue una serie del coach (§7/D4),
 * altrimenti la durata come le altre attività.
 *
 * `padding(top = 20.dp)` sul chip header (contro i 4.dp di SessionScreen,
 * stesso valore di [SwimStartScreen] — verificato lì che basta a schiodare
 * il chip dall'overlay dell'ora di sistema) è l'unica differenza voluta
 * rispetto al gemello — non tocca SessionScreen, che resta quello che era.
 *
 * Con una serie seguita (D2/D3 review) NON c'è un terzo pulsante
 * "prossima serie": tre cerchi (stop/pausa/avanti) non ci stanno affiancati
 * vicino al bordo inferiore curvo di un quadrante rotondo da 192dp — stop e
 * avanti uscivano dalla curvatura. Il tap target "prossima serie" è
 * l'intero blocco della guida (etichetta/countdown), esattamente come sulla
 * versione Apple Watch — più la rotella già presente. Entrambi disabilitati
 * in pausa.
 */
@Composable
fun SwimSessionScreen(
    sessionState: SessionState,
    heartRate: Double,
    guide: SwimGuideRep?,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onNextSet: () -> Unit = {},
) {
    val accent = JTColors.activity(sessionState.activityType)
    val swim = sessionState.swim ?: SwimData()
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    var rotaryAccum by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    JimTimeWearTheme {
        AppScaffold {
            ScreenScaffold {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        // Rotella = "prossima serie" manuale (§7): soglia
                        // alta apposta — un tick di rotella minimo (come
                        // quello di uno scroll accidentale) non deve
                        // saltare una serie che l'atleta non voleva
                        // ancora lasciare.
                        .focusRequester(focusRequester)
                        .focusable()
                        .onRotaryScrollEvent { event ->
                            // Ignorata in pausa — un tick di rotella accidentale
                            // mentre l'atleta si è fermato non deve bruciare la
                            // serie che sta ancora leggendo/riposando.
                            if (guide == null || sessionState.isPaused) return@onRotaryScrollEvent false
                            rotaryAccum += event.verticalScrollPixels
                            if (kotlin.math.abs(rotaryAccum) > 180f) {
                                rotaryAccum = 0f
                                context.vibrate(40)
                                onNextSet()
                            }
                            true
                        },
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // ── Top row: chip corto, mai il nome attività per esteso ─
                    // 20dp, come SwimStartScreen — 8dp non bastava, il chip
                    // restava sotto l'ora di sistema (D3 review).
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 20.dp),
                    ) {
                        CapsuleChip(
                            text = "${sessionState.activityIcon()} ${swimShortLabel(swim)}",
                            tint = accent,
                        )
                    }

                    // ── Centro: guida a tempo se una serie è seguita, altrimenti durata ─
                    if (guide != null) {
                        SwimGuideBlock(
                            guide = guide,
                            accent = accent,
                            onTap = onNextSet,
                            enabled = !sessionState.isPaused,
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            TinyLabel("Durata")
                            Text(
                                text = sessionState.formattedElapsed(),
                                style = MaterialTheme.typography.displayMedium.copy(
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                            )
                        }
                    }

                    // ── Stats row: distanza, vasche, FC (come le altre sessioni) ─
                    // Variante compatta (senza didascalia sotto ogni icona,
                    // stesso pattern icona+valore di [StatCell]/SessionScreen
                    // ma più bassa): questa schermata ha un blocco centrale
                    // in più da far stare (guida o durata) rispetto a
                    // SessionScreen — con lo StatCell pieno stop/pausa
                    // uscivano dal bordo inferiore (D2 review).
                    SwimStatsRow(sessionState = sessionState, swim = swim, heartRate = heartRate, accent = accent)

                    // ── Buttons ────────────────────────────────────────────
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircleControl(
                            glyph = "■",
                            tint = JTColors.danger,
                            solid = false,
                            size = 48.dp,
                            onClick = onStop,
                        )
                        if (sessionState.isPaused) {
                            CircleControl(
                                glyph = "▶",
                                tint = JTColors.success,
                                solid = true,
                                size = 48.dp,
                                onClick = onResume,
                            )
                        } else {
                            CircleControl(
                                glyph = "⏸",
                                tint = JTColors.warning,
                                solid = false,
                                size = 48.dp,
                                onClick = onPause,
                            )
                        }
                        // Niente terzo pulsante qui (D3 review): con una
                        // serie seguita "prossima serie" è il tap sul
                        // blocco guida stesso (vedi SwimGuideBlock) + la
                        // rotella — tre cerchi non ci stanno vicino alla
                        // curvatura del bordo inferiore.
                    }
                }
            }
        }
    }
}

/// "Vasca"/"Acque libere" — MAI il nome esteso dell'attività (D2 review:
/// "Nuoto in vasca" usciva dal bordo rotondo sullo chip header).
private fun swimShortLabel(swim: SwimData): String =
    if (swim.location == "OPEN_WATER") "Acque libere" else "Vasca"

/// Blocco centrale della guida a tempo (§7/D4) — COMPATTO apposta (due
/// righe, non tre: su un quadrante di 192dp con l'header spostato a 20dp
/// per non toccare l'ora di sistema, una terza riga rispingeva stop/pausa
/// fuori dal bordo inferiore, D2/D3 review) — E TAP TARGET per "prossima
/// serie" (`onTap`, ignorato in pausa, `enabled`): stesso posto della
/// versione Apple Watch, niente terzo pulsante in fondo che non ci sta
/// vicino alla curvatura del bordo.
@Composable
private fun SwimGuideBlock(guide: SwimGuideRep, accent: Color, onTap: () -> Unit, enabled: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(enabled = enabled, onClick = onTap),
    ) {
        Text(
            // Una riga sola (D2/D3 review, spazio verticale scarso):
            // "rip. {rip}/{totRip} · S{serie}/{totale} · {etichetta}" — la ripetizione
            // per prima: è il dato che serve in vasca, l'etichetta può troncarsi —
            // ellissi se non ci sta tutta, mai una seconda riga.
            text = "rip. ${guide.repIndex}/${guide.repCount} · S${guide.setIndex + 1}/${guide.setCount} · ${guide.label}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (guide.countdownSec != null) {
            Text(
                text = formatMmSs(guide.countdownSec),
                style = MaterialTheme.typography.displayMedium.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold),
                color = JTColors.warning,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/// m:ss — stesso formato dell'etichetta della serie ("@1:45", D4/§7): 103s
/// → "1:43", 45s → "0:45". Mai secondi grezzi da soli sull'attiva.
private fun formatMmSs(totalSec: Int): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

/// Riga statistiche compatta (senza didascalia sotto ogni icona) — usata
/// SEMPRE usata (con o senza guida): questa schermata ha un blocco
/// centrale in più rispetto a [SessionScreen] (durata O guida), quindi le
/// statistiche restano un'icona + un valore su una riga sola — niente
/// didascalia sotto (quella di [StatCell]/SessionScreen) — per lasciare
/// lo spazio verticale che serve a stop/pausa/prossima-serie (D2 review).
@Composable
private fun SwimStatsRow(
    sessionState: SessionState,
    swim: SwimData,
    heartRate: Double,
    accent: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SwimStatCompact(icon = "📏", tint = accent, value = sessionState.formattedSwimDistance())
        SwimStatCompact(icon = "🔁", tint = Color.White.copy(alpha = 0.7f), value = swim.laps.toString())
        if (heartRate > 0) {
            SwimStatCompact(icon = "♥", tint = JTColors.hr, value = heartRate.toInt().toString())
        }
    }
}

@Composable
private fun SwimStatCompact(icon: String, tint: Color, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(icon, fontSize = 12.sp, color = tint)
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace),
            maxLines = 1,
        )
    }
}

@WearPreviewDevices
@Composable
private fun SwimSessionScreenPreview() {
    SwimSessionScreen(
        sessionState = SessionState(
            isActive = true,
            isStandalone = true,
            activityType = "swim_pool",
            elapsedSeconds = 1024,
            swim = SwimData(location = "POOL", poolLengthM = 25.0, distanceMeters = 850.0, laps = 34, strokes = 480),
        ),
        heartRate = 128.0,
        guide = null,
        onStop = {},
        onPause = {},
        onResume = {},
    )
}

@WearPreviewDevices
@Composable
private fun SwimSessionScreenGuidePreview() {
    SwimSessionScreen(
        sessionState = SessionState(
            isActive = true,
            isStandalone = true,
            activityType = "swim_pool",
            elapsedSeconds = 320,
            swim = SwimData(location = "POOL", poolLengthM = 25.0, distanceMeters = 250.0, laps = 10, strokes = 140),
        ),
        heartRate = 138.0,
        guide = SwimGuideRep(
            setIndex = 1,
            setCount = 4,
            repIndex = 3,
            repCount = 10,
            label = "10×100 SL @1:45",
            countdownSec = 12,
        ),
        onStop = {},
        onPause = {},
        onResume = {},
    )
}
