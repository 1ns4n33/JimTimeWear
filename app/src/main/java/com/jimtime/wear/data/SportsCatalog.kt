package com.jimtime.wear.data

// GENERATED — do not edit by hand.
// Source: tool/sports_catalog.json
// Regenerate with: dart run tool/gen_sports_catalog.dart

/// One row of the shared sports catalogue (tool/sports_catalog.json,
/// Flutter repo).
data class SportsCatalogEntry(
    val id: String,
    val labelIt: String,
    val emoji: String,
    val enabled: Boolean,
    val watch: Boolean,
    /// F5 (Nuoto, D11) — effective Wear OS availability (`watchWear ??
    /// watch` from tool/sports_catalog.json); use this, not [watch], to
    /// decide what the Wear picker shows. `false` for the two swim ids
    /// today — Wear pool support is a separate sensor stack, rinviato a
    /// F5b.
    val watchWear: Boolean,
    val outdoorGps: Boolean,
    val indoor: Boolean,
)

object SportsCatalog {
    val all: List<SportsCatalogEntry> = listOf(
        SportsCatalogEntry("run", "Corsa", "🏃", enabled = true, watch = true, watchWear = true, outdoorGps = true, indoor = false),
        SportsCatalogEntry("walk", "Camminata", "🚶", enabled = true, watch = true, watchWear = true, outdoorGps = true, indoor = false),
        SportsCatalogEntry("bike", "Bici", "🚴", enabled = true, watch = true, watchWear = true, outdoorGps = true, indoor = false),
        SportsCatalogEntry("hike", "Escursione", "🥾", enabled = true, watch = true, watchWear = true, outdoorGps = true, indoor = false),
        SportsCatalogEntry("trail", "Trail", "🌲", enabled = true, watch = true, watchWear = true, outdoorGps = true, indoor = false),
        SportsCatalogEntry("skate", "Pattinaggio", "⛸", enabled = true, watch = true, watchWear = true, outdoorGps = true, indoor = false),
        SportsCatalogEntry("mtb", "MTB", "🚵", enabled = true, watch = true, watchWear = true, outdoorGps = true, indoor = false),
        SportsCatalogEntry("climbing", "Arrampicata", "🧗", enabled = true, watch = true, watchWear = true, outdoorGps = false, indoor = false),
        SportsCatalogEntry("treadmill_walk", "Tapis roulant cammino", "🚶", enabled = true, watch = true, watchWear = true, outdoorGps = false, indoor = true),
        SportsCatalogEntry("treadmill_run", "Tapis roulant corsa", "🏃", enabled = true, watch = true, watchWear = true, outdoorGps = false, indoor = true),
        SportsCatalogEntry("indoor_cycling", "Bici indoor", "🚴", enabled = true, watch = true, watchWear = true, outdoorGps = false, indoor = true),
        SportsCatalogEntry("meditation", "Meditazione", "🧘", enabled = true, watch = true, watchWear = true, outdoorGps = false, indoor = true),
        SportsCatalogEntry("pilates", "Pilates", "🤸", enabled = true, watch = true, watchWear = true, outdoorGps = false, indoor = true),
        SportsCatalogEntry("yoga", "Yoga", "🤸", enabled = true, watch = true, watchWear = true, outdoorGps = false, indoor = true),
        SportsCatalogEntry("stretching", "Stretching", "🙆", enabled = true, watch = true, watchWear = true, outdoorGps = false, indoor = true),
        SportsCatalogEntry("workout", "Allenamento", "🏋️", enabled = true, watch = false, watchWear = false, outdoorGps = false, indoor = false),
        SportsCatalogEntry("boulder", "Boulder", "🧗", enabled = true, watch = true, watchWear = true, outdoorGps = false, indoor = false),
        SportsCatalogEntry("climbing_indoor", "Arrampicata indoor", "🧗", enabled = true, watch = true, watchWear = true, outdoorGps = false, indoor = false),
        SportsCatalogEntry("swim_pool", "Nuoto in vasca", "🏊", enabled = true, watch = true, watchWear = true, outdoorGps = false, indoor = false),
        SportsCatalogEntry("swim_open_water", "Nuoto in acque libere", "🏊", enabled = true, watch = true, watchWear = true, outdoorGps = false, indoor = false),
        SportsCatalogEntry("padel", "Padel", "🎾", enabled = true, watch = true, watchWear = true, outdoorGps = false, indoor = false),
        SportsCatalogEntry("tennis", "Tennis", "🎾", enabled = true, watch = true, watchWear = true, outdoorGps = false, indoor = false),
        SportsCatalogEntry("squash", "Squash", "🎾", enabled = true, watch = true, watchWear = true, outdoorGps = false, indoor = false),
        SportsCatalogEntry("beach_volley", "Beach volley", "🏐", enabled = true, watch = true, watchWear = true, outdoorGps = false, indoor = false),
        SportsCatalogEntry("calcio5", "Calcio a 5", "⚽", enabled = true, watch = true, watchWear = true, outdoorGps = false, indoor = false),
        SportsCatalogEntry("rowing_machine", "Vogatore", "🚣", enabled = false, watch = false, watchWear = false, outdoorGps = false, indoor = false),
        SportsCatalogEntry("boxing", "Boxe", "🥊", enabled = false, watch = false, watchWear = false, outdoorGps = false, indoor = false),
    )

    val watchOutdoorIds: List<String> = all.filter { it.watchWear && it.outdoorGps }.map { it.id }
    val watchIndoorIds: List<String> = all.filter { it.watchWear && it.indoor }.map { it.id }

    fun entry(id: String): SportsCatalogEntry? = all.find { it.id == id }

    fun label(id: String): String = entry(id)?.labelIt ?: "Attività"

    fun emoji(id: String): String = entry(id)?.emoji ?: "🏃"

    fun isIndoor(id: String): Boolean = entry(id)?.indoor ?: false
}

