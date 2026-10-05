package com.jimtime.wear.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.jimtime.wear.data.PendingRouteStore
import com.jimtime.wear.presentation.theme.JTColors
import com.jimtime.wear.presentation.theme.JimTimeWearTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Da sincronizzare" — mirror di PendingSyncView.swift lato iOS. Registrazioni
 * ancora in coda per il telefono, col motivo per cui ognuna è ferma (invece
 * della sola riga "N allenamenti da inviare" dell'home, che non dice QUALE
 * sia bloccata o PERCHÉ). Nessuna azione di cancellazione: un item lascia il
 * polso SOLO quando il telefono conferma il salvataggio (syncAck).
 */
@Composable
fun PendingSyncScreen(
    items: List<SessionViewModel.PendingSyncItem>,
    onSendOne: (String) -> Unit = {},
    onSendAll: () -> Unit = {},
) {
    val listState = rememberScalingLazyListState()
    // "Sincronizza tutti" non ha senso mentre ogni item è già in volo, o
    // quando la coda si è svuotata sotto ai piedi dell'atleta (arrivato un
    // syncAck mentre la pagina era aperta).
    val allSyncDisabled = items.isEmpty() ||
        items.all { it.status is SessionViewModel.PendingSyncStatus.Sending }

    JimTimeWearTheme {
        AppScaffold {
            ScreenScaffold(
                scrollState = listState,
                timeText = { TimeText() },
            ) {
                ScalingLazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item {
                        Text(
                            text = "Da sincronizzare",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }

                    if (items.isEmpty()) {
                        item { EmptySyncState() }
                    } else {
                        item {
                            Button(
                                onClick = onSendAll,
                                enabled = !allSyncDisabled,
                                colors = ButtonDefaults.buttonColors(containerColor = JTColors.brand),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = "Sincronizza tutti",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        items.forEach { entry ->
                            item {
                                PendingSyncRow(
                                    item = entry,
                                    onClick = { entry.route.syncId?.let(onSendOne) },
                                )
                            }
                        }
                    }

                    item { Spacer(Modifier.height(4.dp)) }
                }
            }
        }
    }
}

@Composable
private fun EmptySyncState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(JTColors.success.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", fontSize = 18.sp, color = JTColors.success, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Tutto sincronizzato",
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PendingSyncRow(item: SessionViewModel.PendingSyncItem, onClick: () -> Unit) {
    val route = item.route
    val info = ActivityCatalog.info(route.activityType)
    val tint = JTColors.activity(route.activityType)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(info.emoji, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = info.label,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitleLines(route).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusLine(item.status)
            }
        }
    }
}

/// "28 set · 14:05" + "52 min" su righe separate — su un quadrante piccolo
/// data+ora+durata insieme non ci stanno mai, nemmeno con l'ellissi (regola
/// mirror di PendingSyncRow.subtitleLines lato iOS).
private fun subtitleLines(route: PendingRouteStore.PendingRoute): List<String> {
    val dateLine = if (route.startedAt > 0) {
        val instant = Instant.ofEpochMilli(route.startedAt).atZone(ZoneId.systemDefault())
        "${DAY_FORMATTER.format(instant)} · ${TIME_FORMATTER.format(instant)}"
    } else null
    val durationSec = ((route.endedAt - route.startedAt) / 1000L).coerceAtLeast(0)
    val durationLine = if (durationSec > 0) "${(durationSec / 60).coerceAtLeast(1)} min" else null
    val lines = listOfNotNull(dateLine, durationLine)
    return lines.ifEmpty { listOf("Registrata sul polso") }
}

@Composable
private fun StatusLine(status: SessionViewModel.PendingSyncStatus) {
    when (status) {
        is SessionViewModel.PendingSyncStatus.Sending -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(3.dp))
            Text(
                text = "Invio…",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is SessionViewModel.PendingSyncStatus.None -> Text(
            text = "In attesa",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is SessionViewModel.PendingSyncStatus.Reason -> Text(
            text = statusText(status.reason),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Medium),
            color = statusColor(status.reason),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/// Testo per ciascun motivo noto dal contratto phone→watch; un motivo
/// sconosciuto (futuro, o typo lato telefono) resta comunque leggibile
/// invece di sparire — mirror di PendingSyncRow.statusText lato iOS.
internal fun statusText(reason: String): String = when (reason) {
    "notLoggedIn" -> "Accedi a JimTime sul telefono"
    "openOnPhone" -> "Chiudila sul telefono"
    "saveFailed" -> "Salvataggio non riuscito, riprova"
    "unreadable" -> "Il telefono non riesce a leggerla"
    "phoneUnreachable" -> "Telefono non raggiungibile"
    "noResponse" -> "Nessuna risposta dal telefono"
    else -> "Non ancora salvata sul telefono"
}

private fun statusColor(reason: String): Color = when (reason) {
    "notLoggedIn" -> JTColors.warning
    else -> Color.Unspecified
}

// Italiano fisso come il resto dell'interfaccia (testi scritti in italiano):
// con la lingua del dispositivo usciva "27 Sep" in mezzo a "Da sincronizzare".
private val DAY_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN)
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
