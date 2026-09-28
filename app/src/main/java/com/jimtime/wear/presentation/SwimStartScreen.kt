package com.jimtime.wear.presentation

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.jimtime.wear.data.SwimWorkoutWire
import com.jimtime.wear.presentation.theme.JTColors
import com.jimtime.wear.presentation.theme.JimTimeWearTheme

/**
 * F5b (Nuoto, §7) — schermata di avvio per `swim_pool`/`swim_open_water`,
 * intercalata tra il picker e il countdown. Per la vasca raccoglie la
 * lunghezza (D4: `ExerciseConfig.swimmingPoolLengthMeters`, richiesta agli
 * eventi vasca — 25/50/33 m sono le taglie da gara standard, "Altro" con
 * uno stepper ±1 m per le piscine fuori standard); per le acque libere non
 * c'è nulla da configurare, si passa dritti al countdown (il GPS lo
 * abilita da sé [com.jimtime.wear.health.HealthServicesExerciseSource]).
 * [workouts] (se non vuoto — pushate dal telefono, D3/§7) offre una serie
 * del coach opzionale da seguire con la guida a tempo di
 * [com.jimtime.wear.health.SwimGuide]: un chip che al tap CICLA fra
 * "Nessuno" e gli allenamenti disponibili — niente lista scorrevole, lo
 * schermo è troppo piccolo per una seconda ScalingLazyColumn qui.
 *
 * Schermo piccolo e rotondo (192 dp, SE-class): niente larghezze fisse
 * sommate — i chip lunghezza vanno su due righe con `Arrangement.Center` e
 * `FlowRow`-style wrap manuale (3 elementi ci stanno su una riga a 192 dp
 * con padding stretto, quindi una singola Row centrata basta). Header
 * accorciato a "Vasca"/"Acque libere" (D2 review) — il nome esteso
 * dell'attività usciva dal bordo rotondo/sotto l'ora di sistema.
 */
@Composable
fun SwimStartScreen(
    activityType: String,
    workouts: List<SwimWorkoutWire> = emptyList(),
    onConfirm: (poolLengthM: Double?, workout: SwimWorkoutWire?) -> Unit,
    onCancel: () -> Unit,
) {
    val isOpenWater = activityType == "swim_open_water"
    val accent = JTColors.activity(activityType)
    var selectedPreset by remember { mutableStateOf(25.0) }
    var customMode by remember { mutableStateOf(false) }
    var customLength by remember { mutableDoubleStateOf(25.0) }
    // 0 = "Nessuno", 1..N = workouts[i - 1].
    var workoutChoice by remember { mutableIntStateOf(0) }

    JimTimeWearTheme {
        AppScaffold {
            ScreenScaffold {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 20.dp),
                    ) {
                        Text(ActivityCatalog.info(activityType).emoji, fontSize = 13.sp)
                        Text(
                            text = if (isOpenWater) "Acque libere" else "Vasca",
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                            maxLines = 1,
                        )
                    }

                    if (isOpenWater) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🌊", fontSize = 26.sp)
                            Text(
                                text = "GPS attivo",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else if (!customMode) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            TinyLabel("Lunghezza vasca")
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(25.0, 50.0, 33.0).forEach { preset ->
                                    PoolLengthChip(
                                        label = "${preset.toInt()}m",
                                        selected = selectedPreset == preset,
                                        accent = accent,
                                        onClick = { selectedPreset = preset },
                                    )
                                }
                            }
                            Text(
                                text = "Altro",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.clickable {
                                    customLength = selectedPreset
                                    customMode = true
                                },
                            )
                        }
                    } else {
                        // Stepper ±1 m — telefono/orologio non hanno un
                        // ingresso tastiera comodo, il tap resta il modo più
                        // affidabile su un quadrante di questa dimensione.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            StepperButton(glyph = "–", onClick = {
                                if (customLength > 5.0) customLength -= 1.0
                            })
                            Text(
                                text = "%.0f m".format(customLength),
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = accent,
                            )
                            StepperButton(glyph = "+", onClick = {
                                if (customLength < 200.0) customLength += 1.0
                            })
                        }
                    }

                    // F5b (§7) — selettore allenamento opzionale, un chip
                    // ciclico invece di una lista: niente spazio verticale
                    // per una seconda colonna scrollabile su 192 dp.
                    if (workouts.isNotEmpty()) {
                        val label = if (workoutChoice == 0) "Nessuna serie" else workouts[workoutChoice - 1].title
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (workoutChoice == 0) Color.White.copy(alpha = 0.08f) else accent.copy(alpha = 0.24f))
                                .clickable { workoutChoice = (workoutChoice + 1) % (workouts.size + 1) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "🏋️ $label",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = if (workoutChoice == 0) Color.White.copy(alpha = 0.7f) else accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircleControl(
                            glyph = "✕",
                            tint = JTColors.danger,
                            solid = false,
                            size = 40.dp,
                            onClick = onCancel,
                        )
                        CircleControl(
                            glyph = "▶",
                            tint = JTColors.success,
                            solid = true,
                            size = 48.dp,
                            onClick = {
                                val poolLengthM = when {
                                    isOpenWater -> null
                                    customMode  -> customLength
                                    else        -> selectedPreset
                                }
                                val workout = if (workoutChoice == 0) null else workouts.getOrNull(workoutChoice - 1)
                                onConfirm(poolLengthM, workout)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PoolLengthChip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            ),
            color = if (selected) accent else Color.White,
        )
    }
}

@Composable
private fun StepperButton(glyph: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = Color.White,
        ),
    ) {
        Text(glyph, fontWeight = FontWeight.Bold)
    }
}

@WearPreviewDevices
@Composable
private fun SwimStartScreenPoolPreview() {
    SwimStartScreen(activityType = "swim_pool", onConfirm = { _, _ -> }, onCancel = {})
}

@WearPreviewDevices
@Composable
private fun SwimStartScreenOpenWaterPreview() {
    SwimStartScreen(activityType = "swim_open_water", onConfirm = { _, _ -> }, onCancel = {})
}
