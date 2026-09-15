package com.jimtime.wear.presentation

/// Catalogo attività condiviso da home/picker/countdown (vedi
/// spec-contract.md "Activity type catalogue"). Unica fonte di verità —
/// prima duplicata/disallineata tra IdleScreen e SessionScreen.
data class ActivityTypeInfo(val id: String, val label: String, val emoji: String)

object ActivityCatalog {
    val outdoor = listOf(
        ActivityTypeInfo("run", "Corsa", "🏃"),
        ActivityTypeInfo("walk", "Camminata", "🚶"),
        ActivityTypeInfo("bike", "Bici", "🚴"),
        ActivityTypeInfo("hike", "Escursione", "🥾"),
        ActivityTypeInfo("trail", "Trail", "🌲"),
        ActivityTypeInfo("skate", "Pattinaggio", "⛸"),
        ActivityTypeInfo("mtb", "MTB", "🚵"),
    )

    val indoor = listOf(
        ActivityTypeInfo("treadmill_run", "Tapis roulant corsa", "🏃"),
        ActivityTypeInfo("treadmill_walk", "Tapis roulant cammino", "🚶"),
        ActivityTypeInfo("indoor_cycling", "Bici indoor", "🚴"),
        ActivityTypeInfo("meditation", "Meditazione", "🧘"),
        ActivityTypeInfo("pilates", "Pilates", "🤸"),
        ActivityTypeInfo("yoga", "Yoga", "🤸"),
        ActivityTypeInfo("stretching", "Stretching", "🙆"),
    )

    val all = outdoor + indoor

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
