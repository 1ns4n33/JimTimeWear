package com.jimtime.wear.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
import com.jimtime.wear.guided.GuidedRunView
import com.jimtime.wear.guided.GuidedStrings
import com.jimtime.wear.guided.ResolvedEndType
import com.jimtime.wear.guided.ResolvedTarget
import com.jimtime.wear.guided.ResolvedTargetKind
import com.jimtime.wear.presentation.theme.JTColors
import com.jimtime.wear.presentation.theme.JimTimeWearTheme
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * F7 (Schede multisport: al polso, WP-WU, §2.8/D13/J15) — schermata di
 * fase della corsa guidata: UNA colonna, NESSUNO scroll (regola 26/9),
 * progettata per il quadrante PIÙ PICCOLO (192 dp, AVD Wear_OS_Small_Round).
 *
 * Budget verticale (192 dp, padding verticale 8+8=16 ⇒ ~176 dp utili):
 *   (1) riga fase            ~14 dp (11 sp + line-height)
 *   (2) rimanente (hero)     ~40 dp (34 sp bold) + ~12 dp sottotitolo
 *   (3) banda target          8 dp + ~12 dp etichetta ui.target (omessa se finished)
 *   (4) riga "Poi: …"        ~12 dp (omessa se compatto o finished)
 *   (5) metriche inline      ~14 dp
 *   (6) controlli             40 dp
 *   ≈ 14+2+(40+12)+2+(8+12)+2+12+2+14+2+40 ≈ 174 dp ≤ 192 − 2×8 = 176 dp.
 * Nessun numero agli estremi della banda (l'etichetta `ui.target` sta
 * SOPRA, mai ai lati). Mirror diretto di `GuidedStepView.swift` con i
 * widget Wear esistenti (`CircleControl`, `StatCell`, `TinyLabel`,
 * `CapsuleChip` — `SessionScreen.kt`, stesso package, nessuna duplicazione).
 *
 * API reale di WP-WD (fase B — `com.jimtime.wear.guided.GuidedRunView`,
 * `SessionViewModel.guidedRun: StateFlow<GuidedRunView?>`): `remainingText`
 * e `label` sono GIÀ pronti per ogni caso, inclusa la fine struttura
 * (durata totale / "Corsa libera") — questa schermata non riformatta mai
 * `sessionState` per quei due campi, solo per le metriche (5) che restano
 * di competenza della sessione (distanza/passo/battito correnti).
 */

@Composable
fun GuidedSessionScreen(
    sessionState: SessionState,
    view: GuidedRunView?,
    heartRate: Double,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onNextStep: () -> Unit,
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val isCompact = screenWidthDp < 200
    val accent = JTColors.activity(sessionState.activityType)
    val finished = view?.finished ?: false
    // «Fase ›» (lap) in pausa resta in attesa fino alla ripresa (nota
    // WP-WD, `GuidedRun.tick`): disabilitarlo in UI evita solo la
    // confusione di un tap che non produce aptica/avanzamento immediato.
    val nextStepEnabled = !finished && !sessionState.isPaused

    JimTimeWearTheme {
        AppScaffold {
            // `timeText = {}`: schermo statico (nessuno scrollState, come
            // `SessionScreen`/`WorkoutSessionScreen`) — l'orologio di
            // sistema di default di `ScreenScaffold` resta fisso in alto e
            // si sovrappone alla riga fase sul tondo 192 dp (visto allo
            // screenshot). Il "rimanente" in grande è già l'informazione
            // di tempo che conta durante la guida.
            ScreenScaffold(timeText = {}) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // ── (1) Riga fase ────────────────────────────────────
                    PhaseRow(
                        view = view,
                        variant = sessionState.guidedVariant,
                    )

                    // ── (2) Rimanente (hero) — già pronto in GuidedRunView,
                    // a fine struttura è la durata totale ("Corsa libera").
                    RemainingRow(view = view, isCompact = isCompact, isPaused = sessionState.isPaused)

                    if (!finished) {
                        // ── (3) Banda target ──────────────────────────────
                        TargetBandRow(view = view)

                        // ── (4) Riga "Poi: …" ─────────────────────────────
                        // Correzioni verificatore — chiesto di mostrarla
                        // anche sul 192 dp se il budget lo regge senza
                        // rubare margine ai tasti. Misura: aggiungerla
                        // (~12 dp: 10 sp + line-height) sul compatto porta
                        // il totale a ~186 dp contro i ~176 dp utili (12
                        // dp di padding verticale, vedi intestazione file)
                        // — gli unici 8 dp di margine liberi sono già
                        // impegnati dall'alzata dei tasti laterali (verifica
                        // geometrica sopra, margine reale 6.8 dp su 4
                        // richiesti: quasi a zero). Mostrarla comunque
                        // spingerebbe la riga controlli oltre il bordo
                        // inferiore — si lascia quindi SOLO su `!isCompact`
                        // (XL, dove il budget regge, vedi screenshot).
                        val next = view?.nextLabel
                        if (!isCompact && next != null) {
                            Text(
                                text = GuidedStrings.t("next_label", view?.lang ?: "", mapOf("next" to next)),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // ── (5) Metriche inline ──────────────────────────────
                    MetricsRow(sessionState = sessionState, heartRate = heartRate, accent = accent)

                    // ── (6) Controlli ─────────────────────────────────────
                    // Verifica geometrica (conduttore): ogni tasto deve
                    // stare INTERAMENTE nel cerchio, con ≥4 dp di margine —
                    // `distanza_dal_centro(centro_tasto) + raggio ≤ R−4`.
                    // Coi 3 tasti affiancati (40/44 dp, 10 dp di spazio) i
                    // due laterali uscivano di ~12 dp dalla maschera
                    // circolare (visto allo screenshot: "tacca" tagliata).
                    // Fix: tasti più piccoli (36/40 dp), spaziatura 8 dp, e
                    // i due laterali alzati di 14 dp (arco leggero, "quelli
                    // laterali più in alto" — quello centrale resta fermo,
                    // il suo margine era già ampio).
                    //
                    // Calcolo (dp, centro schermo = metà esatta):
                    //  R = 96 (small) / 113.5 (XL); riga a filo del bordo
                    //  inferiore (padding 8 dp) ⇒ centro-riga a R−8−taglia/2
                    //  dal centro. Laterale: dx = taglia+spaziatura=44/48,
                    //  centrale: dx=0. Con l'alzata di 14 dp sui laterali:
                    //   small  centro: dy=70,  dist=70,      +18 = 88  (R−4=92 ✓ margine 8)
                    //   small  laterale: dy=56, dist=√(44²+56²)=71.2, +18 = 89.2 (margine 6.8 ✓)
                    //   XL     centro: dy=85.5, dist=85.5,   +20 = 105.5 (R−4=109.5, margine 8 ✓)
                    //   XL     laterale: dy=71.5, dist=√(48²+71.5²)=86.1, +20=106.1 (margine 7.4 ✓)
                    val controlSize = if (isCompact) 36.dp else 40.dp
                    val sideOffset = Modifier.offset(y = (-14).dp)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = sideOffset) {
                            CircleControl(
                                glyph = "■",
                                tint = JTColors.danger,
                                solid = false,
                                size = controlSize,
                                onClick = onStop,
                            )
                        }
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
                        // «Fase ›» — disabilitato a fine struttura E in
                        // pausa (il lap resterebbe solo "in attesa").
                        Box(modifier = sideOffset) {
                            CircleControl(
                                glyph = "›",
                                tint = if (nextStepEnabled) JTColors.brand else MaterialTheme.colorScheme.onSurfaceVariant,
                                solid = false,
                                size = controlSize,
                                onClick = { if (nextStepEnabled) onNextStep() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseRow(view: GuidedRunView?, variant: String?) {
    // Riga vicino al bordo SUPERIORE del tondo: su un quadrante rotondo la
    // larghezza utile lì è MOLTO meno della larghezza piena (la maschera
    // circolare del display taglia i pixel fuori dal cerchio) — vero sia
    // sul 192 dp piccolo sia sull'XL (misurato su entrambi: una chip
    // separata accanto al testo spingeva il gruppo oltre il bordo da un
    // lato o dall'altro, TAGLIATO dalla maschera, non da un'ellissi di
    // Compose). Fix: UN SOLO testo centrato, col suffisso RIDOTTA incluso
    // nell'ellissi anziché una chip a parte — "GUIDATA" non serve qui:
    // l'intera schermata lo è.
    //
    // Larghezza utile — MISURATA sui pixel dello screenshot (non più una
    // stima): al bordo di `SpaceBetween` dove cade questa riga (~15 dp dal
    // bordo superiore, ~81 dp dal centro su un tondo 96 dp di raggio), la
    // corda vera è solo ~103 dp — un tetto largo (125 dp) lì sarebbe
    // comunque tagliato dalla maschera, non dall'ellissi. Per arrivare
    // alla corda più larga che serve a "14/14 · Defaticamento" (~125–
    // 130 dp) si abbassa SOLO il disegno del testo di 8 dp con
    // `Modifier.offset` (sposta il rendering, non lo slot di layout: non
    // ruba spazio a `RemainingRow` sotto, c'è margine) portando il centro
    // del testo a ~73 dp dal centro ⇒ corda ≈125 dp.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val maxTextWidth = (screenWidthDp * 0.65f).dp
    Text(
        // `view.label` è già "Corsa libera" a `finished` (GuidedRun.view()):
        // niente indice/totale/suffisso in quel caso (§2.8: blocco (1)
        // diventa solo "Corsa libera").
        text = when {
            view == null -> "Corsa libera"
            view.finished -> view.label
            variant == "reduced" -> "${view.index + 1}/${view.total} · ${view.label} · Ridotta"
            else -> "${view.index + 1}/${view.total} · ${view.label}"
        },
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = maxTextWidth)
            .offset(y = 8.dp),
    )
}

@Composable
private fun RemainingRow(view: GuidedRunView?, isCompact: Boolean, isPaused: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = view?.remainingText ?: "—",
            style = MaterialTheme.typography.displayMedium.copy(
                fontSize = if (isCompact) 30.sp else 34.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            ),
            color = if (isPaused) JTColors.warning else Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        val subtitle = view?.remainingSubtitle
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TargetBandRow(view: GuidedRunView?) {
    val target = view?.target
    if (view == null || target == null || target.kind == ResolvedTargetKind.NONE || target.kind == ResolvedTargetKind.RPE) {
        // RPE: nessuna banda numerica — solo l'etichetta, se presente.
        if (view?.targetLabel?.isNotEmpty() == true && target?.kind == ResolvedTargetKind.RPE) {
            Text(
                text = view.targetLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        // §2.8: l'etichetta `ui.target` sta SOPRA la banda (mai ai lati,
        // mai sotto) — "nessun numero agli estremi della banda".
        Text(
            text = view.targetLabel,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TargetBandGauge(
            target = target,
            currentValue = view.currentValue,
            inBand = view.inBand,
            modifier = Modifier.fillMaxWidth().height(8.dp),
        )
    }
}

/**
 * Banda target con marcatore — J15 (corregge §2.8): traccia
 * `onSurfaceVariant` 30 %, banda centrale 25 %–75 % (LARGA il 50 %, non
 * 55 % — il 55 % di §2.8 era l'OPACITÀ del colore, non la larghezza)
 * colore `success` ad opacità 0.55, marcatore 3×12 dp bianco posizionato
 * con [markerFraction] (invertito per `pace`, J15), bordo rosso quando
 * `inBand == false`. Nessun numero agli estremi: l'etichetta `ui.target`
 * sta SOPRA la banda (chiamante).
 */
@Composable
internal fun TargetBandGauge(
    target: ResolvedTarget,
    currentValue: Double?,
    inBand: Boolean?,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
    val bandColor = JTColors.success.copy(alpha = 0.55f) // J15 — 0.55 è l'opacità, non la larghezza.
    val outOfBand = inBand == false
    val markerWidth = 3.dp
    val markerHeight = 12.dp

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(trackColor)
            .then(if (outOfBand) Modifier.border(1.dp, JTColors.danger, RoundedCornerShape(50)) else Modifier),
    ) {
        val widthDp = maxWidth
        // Banda centrale LARGA IL 50 % (25 %–75 %), centrata (J15).
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .fillMaxSize()
                .align(Alignment.Center)
                .background(bandColor),
        )
        if (currentValue != null) {
            val inverted = target.kind == ResolvedTargetKind.PACE
            val fraction = markerFraction(currentValue, target.low, target.high, inverted)
            val centerX = widthDp * fraction
            val offsetX = (centerX - markerWidth / 2).coerceIn(0.dp, widthDp - markerWidth)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = offsetX)
                    .size(width = markerWidth, height = markerHeight)
                    .background(Color.White, RoundedCornerShape(1.dp)),
            )
        }
    }
}

/**
 * Posizione del marcatore sulla banda — J15 (corregge §2.8, che riportava
 * IDENTICO il parametro `inverted` morto dello Swift). Formula letterale
 * dell'emendamento:
 *  - normale: `t = (value − low) / (high − low)`;
 *  - `inverted` (passo, `kind == pace`): `t = (high − value) / (high − low)`
 *    — un passo più veloce (sec/km più basso) sta a DESTRA della banda;
 *  - `t` clampato a `[-0.5, 1.5]`, poi `fraction = 0.25 + t × 0.5`
 *    (la banda occupa il 25 %–75 % della traccia, J15);
 *  - `high == low` ⇒ marcatore al centro (0.5), nessuna divisione per zero.
 *
 * Nota: non si ordinano `low`/`high` (a differenza della prima bozza di
 * questa funzione) — la formula dell'emendamento li usa come dati dal
 * motore, dove per `pace` `low` è SEMPRE il valore veloce e `high` SEMPRE
 * il lento (mai il contrario): ordinarli avrebbe rotto l'inversione.
 */
internal fun markerFraction(value: Double, low: Double, high: Double, inverted: Boolean = false): Float {
    if (high == low) return 0.5f
    val t = if (inverted) (high - value) / (high - low) else (value - low) / (high - low)
    val clamped = t.coerceIn(-0.5, 1.5)
    return (0.25 + clamped * 0.5).toFloat()
}

/**
 * Testo del rimanente — port di `GuidedStepView.remainingValue` (Swift):
 * `lap`/`hrBelow` ⇒ "—"; altrimenti mm:ss (`remainingSec`) o "N m"
 * (`remainingM`); nessuno dei due ⇒ "—". Funzione pura e testabile,
 * TENUTA come riferimento/parità anche se la UI usa direttamente
 * `GuidedRunView.remainingText` (già formattato da WP-WD con la stessa
 * regola, incluso il caso `lap`/`hrBelow` = tempo TRASCORSO nella fase,
 * non "—": quella divergenza è intenzionale — `GuidedRunView` porta più
 * informazione di questa funzione "Swift-fedele", che resta solo un test
 * di parità sul formato mm:ss / "N m").
 */
internal fun remainingText(remainingSec: Double?, remainingM: Double?, endType: ResolvedEndType): String {
    if (endType == ResolvedEndType.LAP || endType == ResolvedEndType.HR_BELOW) return "—"
    remainingSec?.let {
        val s = max(0, it.roundToInt())
        return "%d:%02d".format(s / 60, s % 60)
    }
    remainingM?.let {
        return "${max(0, it.roundToInt())} m"
    }
    return "—"
}

@Composable
private fun MetricsRow(sessionState: SessionState, heartRate: Double, accent: Color) {
    val parts = mutableListOf<String>()
    if (heartRate > 0) parts.add("♥ ${heartRate.toInt()}")
    if (!sessionState.isIndoor()) {
        parts.add(sessionState.formattedDistance())
        parts.add(sessionState.formattedPace())
    }
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@WearPreviewDevices
@Composable
private fun GuidedSessionScreenPreview() {
    GuidedSessionScreen(
        sessionState = SessionState(
            isActive = true,
            activityType = "run",
            elapsedSeconds = 812,
            distanceMeters = 2650.0,
        ),
        view = GuidedRunView(
            index = 2,
            total = 14,
            label = "Ripetuta 3/6",
            remainingText = "4:32",
            remainingSubtitle = null,
            target = ResolvedTarget(kind = ResolvedTargetKind.PACE, low = 235.0, high = 245.0, zoneLabel = "5k"),
            targetLabel = "3:55–4:05 /km",
            currentValue = 240.0,
            inBand = true,
            nextLabel = "Recupero 3/6 · 2:00",
            finished = false,
            variant = null,
            lang = "it",
        ),
        heartRate = 171.0,
        onStop = {},
        onPause = {},
        onResume = {},
        onNextStep = {},
    )
}
