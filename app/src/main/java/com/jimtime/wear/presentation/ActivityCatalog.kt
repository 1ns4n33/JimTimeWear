package com.jimtime.wear.presentation

import com.jimtime.wear.data.SportsCatalog

/// Catalogo attività condiviso da home/picker/countdown (vedi
/// spec-contract.md "Activity type catalogue"). Unica fonte di verità —
/// prima duplicata/disallineata tra IdleScreen e SessionScreen.
///
/// F0: outdoor/indoor ora derivano da `data.SportsCatalog` (generato da
/// tool/sports_catalog.json nel repo Flutter) invece di essere la TERZA
/// copia a mano dello stesso elenco (Flutter, Apple Watch, Wear OS).
data class ActivityTypeInfo(val id: String, val label: String, val emoji: String)

object ActivityCatalog {
    val outdoor = SportsCatalog.watchOutdoorIds.mapNotNull { id ->
        SportsCatalog.entry(id)?.let { ActivityTypeInfo(it.id, it.labelIt, it.emoji) }
    }

    val indoor = SportsCatalog.watchIndoorIds.mapNotNull { id ->
        SportsCatalog.entry(id)?.let { ActivityTypeInfo(it.id, it.labelIt, it.emoji) }
    }

    /// F5b (Nuoto, D11) — swim_pool/swim_open_water non sono né
    /// `outdoorGps` né `indoor` nel catalogo condiviso (hanno un flusso
    /// proprio: SwimStartScreen prima del countdown, non il generico
    /// SessionScreen) — filtrate qui per id invece che tramite
    /// `watchOutdoorIds`/`watchIndoorIds`, che le escluderebbero sempre.
    val swim = listOf("swim_pool", "swim_open_water").mapNotNull { id ->
        SportsCatalog.entry(id)?.takeIf { it.watchWear }?.let { ActivityTypeInfo(it.id, it.labelIt, it.emoji) }
    }

    val all = outdoor + indoor + swim

    /// Default quando phone/quickTypes non offrono nulla di utilizzabile.
    val defaultQuickTypes = listOf("run", "walk", "bike", "hike")

    fun info(id: String): ActivityTypeInfo =
        all.find { it.id == id } ?: ActivityTypeInfo(id, "Attività", "🏃")

    fun label(id: String): String = info(id).label

    fun emoji(id: String): String = info(id).emoji

    /// dedup([lastUsedLocal] + summary.quickTypes + defaults).take(4) —
    /// regola 6 del contratto.
    fun quickTypes(lastUsed: String?, summaryTypes: List<String>): List<String> {
        val ordered = LinkedHashSet<String>()
        lastUsed?.let { ordered.add(it) }
        ordered.addAll(summaryTypes)
        ordered.addAll(defaultQuickTypes)
        return ordered.toList().take(4)
    }
}
