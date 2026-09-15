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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import com.jimtime.wear.presentation.theme.JTColors
import com.jimtime.wear.presentation.theme.JimTimeWearTheme
import kotlinx.coroutines.delay

/**
 * 3‑2‑1 countdown before an activity actually starts (spec-ui.md). The
 * single [onGo] call is guarded by a `fired` flag: `LaunchedEffect(type)`
 * already cancels its coroutine when this composable leaves composition
 * (Cancel / SwipeDismissable back), so a cancelled countdown can never
 * reach the tick after 0 — the flag is belt-and-suspenders against a
 * recomposition re-triggering the same key.
 */
@Composable
fun CountdownScreen(
    activityType: String,
    onGo: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val accent = JTColors.activity(activityType)
    val info = ActivityCatalog.info(activityType)
    var value by remember { mutableIntStateOf(3) }
    var fired by remember { mutableStateOf(false) }

    LaunchedEffect(activityType) {
        for (n in 3 downTo 1) {
            value = n
            context.vibrate(30)
            delay(1000)
        }
        if (!fired) {
            fired = true
            context.vibrate(80)
            onGo(activityType)
        }
    }

    JimTimeWearTheme {
        AppScaffold {
            ScreenScaffold {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        Text(info.emoji, fontSize = 14.sp)
                        Text(
                            text = info.label,
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                            color = Color.White,
                        )
                    }

                    Text(
                        text = value.toString(),
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = 72.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = accent,
                        textAlign = TextAlign.Center,
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable(onClick = onCancel),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Annulla",
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                            color = Color.White,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

@WearPreviewDevices
@Composable
private fun CountdownScreenPreview() {
    CountdownScreen(activityType = "run", onGo = {}, onCancel = {})
}
