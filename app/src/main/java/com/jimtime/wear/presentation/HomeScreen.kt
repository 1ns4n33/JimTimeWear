package com.jimtime.wear.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import com.jimtime.wear.data.DailySummary
import com.jimtime.wear.data.DailySummaryStore
import com.jimtime.wear.data.PlanDay
import com.jimtime.wear.presentation.theme.JTColors
import com.jimtime.wear.presentation.theme.JimTimeWearTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Coordinate di una scheda avviabile — o dal nextPlanDay del riepilogo, o dal primo giorno cache. */
private data class HeroPlanDay(
    val week: Int,
    val day: Int,
    val label: String,
    val exercises: Int,
)

@Composable
fun HomeScreen(
    dailySummary: DailySummary?,
    summaryReceivedAt: Long,
    pendingWaterMl: Int,
    pendingWaterCount: Int = 0,
    lastUsedType: String?,
    planName: String,
    planDays: List<PlanDay>,
    showPhoneNeeded: Boolean = false,
    onStartPlanDay: (week: Int, day: Int) -> Unit = { _, _ -> },
    onQuickStart: (String) -> Unit = {},
    onOpenPicker: () -> Unit = {},
    onOpenIntervals: () -> Unit = {},
    onAddWater: (Int) -> Unit = {},
    onRefresh: () -> Unit = {},
) {
    val listState: ScalingLazyListState = rememberScalingLazyListState()
    val today = DailySummaryStore.todayLocal()
    val isFresh = dailySummary != null && dailySummary.date == today

    val hero: HeroPlanDay? = dailySummary?.nextPlanDay?.let {
        HeroPlanDay(it.week, it.day, it.label, it.exercises)
    } ?: planDays.firstOrNull()?.let {
        HeroPlanDay(it.week, it.day, it.label, it.exercises)
    }

    // Sui quadranti piccoli (< 200.dp, es. 384 px @320) quattro colonne
    // lasciano ~36.dp l'una e le etichette non ci stanno: tre.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val quickTypes = ActivityCatalog
        .quickTypes(lastUsedType, dailySummary?.quickTypes ?: emptyList())
        .take(if (screenWidthDp < 200) 3 else 4)

    JimTimeWearTheme {
        AppScaffold {
            ScreenScaffold(
                scrollState = listState,
                timeText = { TimeText() },
            ) {
                ScalingLazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item { BrandHeader() }

                    if (hero != null) {
                        item {
                            HeroPlanCard(
                                planName = planName,
                                hero = hero,
                                onClick = { onStartPlanDay(hero.week, hero.day) },
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
                    }

                    item {
                        QuickActivityRow(
                            types = quickTypes,
                            onTap = onQuickStart,
                        )
                    }

                    item {
                        NavRow(
                            glyph = "📂",
                            title = "Tutte le attività",
                            onClick = onOpenPicker,
                        )
                    }
                    item {
                        NavRow(
                            glyph = "⏱",
                            title = "Intervalli",
                            onClick = onOpenIntervals,
                        )
                    }

                    item {
                        WaterCard(
                            summary = dailySummary,
                            isFresh = isFresh,
                            pendingMl = pendingWaterMl,
                            pendingCount = pendingWaterCount,
                            onAddWater = onAddWater,
                        )
                    }

                    item {
                        NutritionCard(
                            summary = dailySummary,
                            isFresh = isFresh,
                        )
                    }

                    // Solo con un riepilogo di OGGI: uno di ieri mostrerebbe
                    // "Oggi ✓" e il conteggio settimanale sbagliati (contract
                    // rule 4 — il gemello iOS fa lo stesso).
                    val training = dailySummary?.training
                    if (isFresh && training != null) {
                        item { TrainingCard(training = training) }
                    }

                    item {
                        FooterRow(
                            receivedAt = summaryReceivedAt,
                            onRefresh = onRefresh,
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

/** Rounded card container shared by every "Oggi" card (14dp corner, white 8% fill). */
@Composable
private fun CardContainer(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(10.dp),
    ) {
        Column { content() }
    }
}

/** Title row shared by every card: tinted glyph circle + semibold title + optional trailing caption. */
@Composable
private fun CardTitleRow(glyph: String, tint: Color, title: String, trailing: String? = null, trailingColor: Color = Color.Unspecified) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, fontSize = 12.sp)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = if (trailingColor != Color.Unspecified) trailingColor
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HeroPlanCard(planName: String, hero: HeroPlanDay, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(JTColors.brand, JTColors.brandBright)))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = planName.ifEmpty { "PROSSIMA SCHEDA" }.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.6.sp,
                    ),
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = hero.label.ifEmpty { "Giorno ${hero.day + 1}" },
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Sett. ${hero.week} · ${hero.exercises} esercizi",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.24f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("▶", fontSize = 13.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun QuickActivityRow(types: List<String>, onTap: (String) -> Unit) {
    // Quattro colonne a peso uguale: il cerchio prende la larghezza della
    // colonna (aspectRatio 1), MAI una misura fissa. Con 52.dp × 4 la riga
    // superava i ~176.dp utili di un quadrante da 192.dp e veniva tagliata.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        types.forEach { id ->
            val info = ActivityCatalog.info(id)
            val accent = JTColors.activity(id)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.22f))
                        .clickable { onTap(id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(info.emoji, fontSize = 16.sp)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = info.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** "Tutte le attività" / "Intervalli" nav row — glyph + title + chevron. */
@Composable
private fun NavRow(glyph: String, title: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(glyph, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "›",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WaterCard(
    summary: DailySummary?,
    isFresh: Boolean,
    pendingMl: Int,
    pendingCount: Int,
    onAddWater: (Int) -> Unit,
) {
    val water = summary?.water
    val goalMl = water?.goalMl?.takeIf { it > 0 } ?: 2500
    val stepMl = water?.stepMl?.takeIf { it > 0 } ?: 250
    val cachedMl = if (isFresh) water?.ml ?: 0 else 0
    val displayed = (cachedMl + pendingMl).coerceAtLeast(0)
    val overGoal = displayed > goalMl
    val fraction = if (goalMl > 0) (displayed.toFloat() / goalMl).coerceIn(0f, 1f) else 0f

    // Niente caption nel titolo: "Acqua" + "obiettivo 2,5 L" non stanno in
    // una riga su un quadrante piccolo. L'obiettivo va sotto la barra.
    CardContainer {
        CardTitleRow(
            glyph = "💧",
            tint = JTColors.water,
            title = "Acqua",
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RoundIconButton(
                text = "−",
                size = 34.dp,
                enabled = displayed > 0,
                containerColor = Color.White.copy(alpha = 0.10f),
                contentColor = Color.White,
                onClick = { onAddWater(-stepMl) },
            )
            // Il numero è l'unico elemento elastico della riga.
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = displayed.toString(),
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = "ml",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RoundIconButton(
                text = "+",
                size = 34.dp,
                enabled = true,
                containerColor = JTColors.water,
                contentColor = Color.Black,
                onClick = { onAddWater(stepMl) },
            )
        }
        Spacer(Modifier.height(8.dp))
        CapsuleProgressBar(
            fraction = fraction,
            fillColor = if (overGoal) JTColors.success else JTColors.water,
        )
        Spacer(Modifier.height(3.dp))
        // Centrata, non a destra: sullo schermo tondo l'angolo della card
        // finisce sotto la maschera e il testo lì viene mangiato.
        Text(
            text = "obiettivo ${formatWaterVolume(goalMl)}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
        // Sulla CODA, non sulla somma: un "+" e un "−" entrambi in attesa
        // fanno 0 ml ma sono ancora due messaggi non confermati.
        if (pendingCount > 0) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "in attesa del telefono",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = JTColors.warning,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RoundIconButton(
    text: String,
    size: Dp,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) containerColor else containerColor.copy(alpha = containerColor.alpha * 0.4f))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) contentColor else contentColor.copy(alpha = 0.4f),
        )
    }
}

/** Capsule progress bar — track white 10%, animated fill. */
@Composable
private fun CapsuleProgressBar(fraction: Float, fillColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(CircleShape)
                .background(fillColor),
        )
    }
}

@Composable
private fun NutritionCard(summary: DailySummary?, isFresh: Boolean) {
    val nutrition = if (isFresh) summary?.nutrition else null

    CardContainer {
        // Niente caption nel titolo (non ci sta su un quadrante piccolo):
        // i pasti stanno nel corpo, accanto all'anello.
        CardTitleRow(
            glyph = "🍽",
            tint = JTColors.food,
            title = "Nutrizione",
        )
        Spacer(Modifier.height(8.dp))
        when {
            !isFresh -> EmptyCardBody(
                headline = "In attesa del telefono…",
                caption = "Aggiorna con il pulsante in basso",
            )
            nutrition == null -> EmptyCardBody(
                headline = "Nessun dato per oggi",
                caption = "Registra un pasto nell'app",
            )
            else -> NutritionBody(nutrition)
        }
    }
}

@Composable
private fun NutritionBody(nutrition: com.jimtime.wear.data.DailySummaryNutrition) {
    val kcalGoal = nutrition.kcalGoal
    val overKcal = kcalGoal > 0 && nutrition.kcal > kcalGoal
    val kcalFraction = if (kcalGoal > 0) (nutrition.kcal.toFloat() / kcalGoal).coerceIn(0f, 1f) else 0f

    // Due righe, non una: anello + testo a fianco, POI le barre macro a
    // tutta larghezza. Anello (56) + etichette fisse + valori in un'unica
    // riga non entravano nei ~156.dp interni di un quadrante da 192.dp.
    val remaining = kcalGoal - nutrition.kcal
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
            CircularProgressIndicator(
                progress = { kcalFraction },
                modifier = Modifier.size(48.dp),
                strokeWidth = 5.dp,
                colors = ProgressIndicatorDefaults.colors(
                    indicatorColor = if (overKcal) JTColors.danger else JTColors.food,
                    trackColor = Color.White.copy(alpha = 0.10f),
                ),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = nutrition.kcal.toString(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = "kcal",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (kcalGoal > 0) {
                Text(
                    text = if (remaining >= 0) "$remaining rimaste"
                           else "${-remaining} oltre l'obiettivo",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = if (remaining >= 0) Color.Unspecified else JTColors.danger,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "di $kcalGoal kcal",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = "Nessun obiettivo kcal",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (nutrition.meals > 0) {
                Text(
                    text = if (nutrition.meals == 1) "1 pasto" else "${nutrition.meals} pasti",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MacroBar("Proteine", nutrition.proteinG, nutrition.proteinGoalG, JTColors.protein)
        MacroBar("Carbo", nutrition.carbsG, nutrition.carbsGoalG, JTColors.carbs)
        MacroBar("Grassi", nutrition.fatG, nutrition.fatGoalG, JTColors.fat)
    }
}

@Composable
private fun MacroBar(label: String, valueG: Int, goalG: Int, tint: Color) {
    val fraction = if (goalG > 0) (valueG.toFloat() / goalG).coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        // Etichetta e valore a larghezza fissa ma calibrata (40 + 46.dp),
        // la barra è l'unico elemento elastico della riga.
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(5.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(tint),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = if (goalG > 0) "$valueG/$goalG g" else "$valueG g",
            // Niente monospace: lo spazio largo staccava la "g" dal numero.
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.End,
            modifier = Modifier.width(46.dp),
        )
    }
}

@Composable
private fun TrainingCard(training: com.jimtime.wear.data.DailySummaryTraining) {
    CardContainer {
        // Niente caption nel titolo (larghezza): lo streak va nel corpo.
        CardTitleRow(
            glyph = "🏋️",
            tint = JTColors.brand,
            title = "Allenamenti",
        )
        Spacer(Modifier.height(8.dp))
        if (training.weekGoal > 0) {
            SegmentedBar(done = training.weekDone, goal = training.weekGoal)
            Spacer(Modifier.height(4.dp))
        }
        // Riga settimana da sola (non ci sta accanto a "Oggi ✓" su un
        // quadrante piccolo); streak e "Oggi ✓" sulla seconda riga.
        Text(
            text = if (training.weekGoal > 0) "${training.weekDone} di ${training.weekGoal} questa settimana"
                   else "${training.weekDone} questa settimana",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (training.streakDays > 0 || training.todayDone) {
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (training.streakDays > 0) "🔥 ${training.streakDays} giorni di fila" else "",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (training.todayDone) {
                    Text(
                        text = "Oggi ✓",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = JTColors.success,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentedBar(done: Int, goal: Int) {
    // "extra done beyond goal → success" (spec-ui.md): il bar ha `goal`
    // segmenti fissi, quindi l'extra si esprime colorando l'intera barra
    // di success una volta superato l'obiettivo, non aggiungendo segmenti.
    val overGoal = done > goal
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (i in 0 until goal) {
            val filled = i < done
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            !filled -> Color.White.copy(alpha = 0.10f)
                            overGoal -> JTColors.success
                            else -> JTColors.brand
                        }
                    ),
            )
        }
    }
}

@Composable
private fun EmptyCardBody(headline: String, caption: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = headline,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            textAlign = TextAlign.Center,
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FooterRow(receivedAt: Long, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (receivedAt > 0) "Aggiornato alle ${formatTime(receivedAt)}" else "Non ancora sincronizzato",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .clickable(onClick = onRefresh),
            contentAlignment = Alignment.Center,
        ) {
            Text("↻", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatTime(epochMillis: Long): String =
    runCatching {
        DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMillis))
    }.getOrDefault("--:--")

/** ≥1000 → "x,y L" (virgola, una cifra decimale), altrimenti "N ml" (regola spec-ui.md). */
private fun formatWaterVolume(ml: Int): String {
    if (ml < 1000) return "$ml ml"
    val liters = ml / 1000.0
    val rounded = Math.round(liters * 10) / 10.0
    val text = if (rounded == rounded.toLong().toDouble()) {
        "${rounded.toLong()},0"
    } else {
        String.format("%.1f", rounded).replace('.', ',')
    }
    return "$text L"
}

@WearPreviewDevices
@Composable
private fun HomeScreenPreview() {
    HomeScreen(
        dailySummary = null,
        summaryReceivedAt = 0L,
        pendingWaterMl = 0,
        lastUsedType = null,
        planName = "",
        planDays = emptyList(),
    )
}
