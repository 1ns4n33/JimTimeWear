package com.jimtime.wear.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class PlanDay(
    val week: Int,      // 1-based, as pushed by the phone
    val day: Int,       // 0-based index within the week
    val label: String,
    val exercises: Int,
)

/// F7 (§2.2, = F2 §2.1 Apple) — un giorno di piano con attività sportive
/// avviabili dal polso. `week` 1-based / `day` 0-based ORIGINALI della scheda.
data class SportDay(
    val week: Int,
    val day: Int,
    val label: String,
    val activities: List<SportActivity>,
)

/// Una attività di [SportDay]. `structureJson`/`profileJson` = wire grezzo
/// (`ResolvedStructureWire` con `ui`, `ThresholdsProfile` numerico), presenti
/// solo se `guided`. `week`/`day`/`dayLabel` ripetono il giorno contenitore
/// così che l'attività basti da sola a costruire il `plan` (PlanActivityRef).
data class SportActivity(
    val id: String,
    val sport: String,
    val title: String?,
    val summary: String,
    val guided: Boolean,
    val structureJson: String?,
    val profileJson: String?,
    val week: Int = 1,
    val day: Int = 0,
    val dayLabel: String = "",
) {
    /// `plan` del wire = `PlanActivityRef.toUploadJson` del telefono. Senza
    /// `planId` il telefono scarta il ref (parseWirePlanRef): l'attività
    /// resta comunque salvata, solo non agganciata al piano.
    fun planRef(planId: String?): JSONObject {
        val o = JSONObject()
        if (!planId.isNullOrEmpty()) o.put("planId", planId)
        o.put("weekNumber", week)
        o.put("dayIndex", day)
        o.put("dayLabel", dayLabel)
        o.put("activityId", id)
        return o
    }
}

/**
 * Caches the active plan's day list (pushed by the phone via
 * CMD_PLAN_DAYS) in SharedPreferences so the wrist can browse it even
 * while the phone is away. Starting a GYM day still requires the phone —
 * the session engine lives there. F7: the same payload carries
 * `sportDays` (Premium only) whose activities start ON THE WRIST.
 */
object PlanDaysStore {

    private const val PREFS_NAME = "jimtime_plan_days"
    private const val KEY_NAME   = "planName"
    private const val KEY_DAYS   = "days"
    // F7 (§2.2) — assenti nei payload di telefoni vecchi o free: in quel
    // caso le attività sport spariscono (mai una cache stantia di Premium).
    private const val KEY_SPORT_DAYS = "sportDays"
    private const val KEY_LANG       = "lang"
    private const val KEY_PLAN_ID    = "planId"

    private val _planName = MutableStateFlow("")
    val planName: StateFlow<String> = _planName.asStateFlow()

    private val _days = MutableStateFlow<List<PlanDay>>(emptyList())
    val days: StateFlow<List<PlanDay>> = _days.asStateFlow()

    private val _sportDays = MutableStateFlow<List<SportDay>>(emptyList())
    val sportDays: StateFlow<List<SportDay>> = _sportDays.asStateFlow()

    /// `lang` del payload (`it|en|es|fr|de`), "" se mai ricevuto —
    /// GuidedStrings ricade sulla lingua del device.
    private val _lang = MutableStateFlow("")
    val lang: StateFlow<String> = _lang.asStateFlow()

    private val _planId = MutableStateFlow<String?>(null)
    val planId: StateFlow<String?> = _planId.asStateFlow()

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _planName.value = prefs.getString(KEY_NAME, "") ?: ""
        _days.value = parse(prefs.getString(KEY_DAYS, null))
        _sportDays.value = parseSportDays(prefs.getString(KEY_SPORT_DAYS, null))
        _lang.value = prefs.getString(KEY_LANG, "") ?: ""
        _planId.value = prefs.getString(KEY_PLAN_ID, null)
    }

    fun apply(context: Context, planName: String, daysJson: JSONArray) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAME, planName)
            .putString(KEY_DAYS, daysJson.toString())
            .apply()
        _planName.value = planName
        _days.value = parse(daysJson.toString())
    }

    /// F7 — payload `planDays` v2 intero (§2.2). `days` come [apply];
    /// `sportDays` assente ⇒ lista vuota (free o telefono vecchio); `lang`
    /// assente ⇒ si tiene l'ultimo noto.
    fun applyPayload(context: Context, json: JSONObject) {
        val days = json.optJSONArray("days") ?: JSONArray()
        val sportRaw = json.optJSONArray("sportDays")?.toString()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Dalle prefs, non dal flow: a processo appena nato (listener) il
        // flow non è ancora stato caricato.
        val lang = (json.opt("lang") as? String)?.takeIf { it.isNotEmpty() }
            ?: prefs.getString(KEY_LANG, "") ?: ""
        val planId = (json.opt("planId") as? String)?.takeIf { it.isNotEmpty() }
        val editor = prefs.edit()
            .putString(KEY_LANG, lang)
        if (sportRaw != null) editor.putString(KEY_SPORT_DAYS, sportRaw) else editor.remove(KEY_SPORT_DAYS)
        if (planId != null) editor.putString(KEY_PLAN_ID, planId) else editor.remove(KEY_PLAN_ID)
        editor.apply()
        _sportDays.value = parseSportDays(sportRaw)
        _lang.value = lang
        _planId.value = planId
        apply(context, json.optString("planName"), days)
    }

    /// Cerca un'attività per (week, day, activityId) — hero della home
    /// (nextSportDay, J13) e ogni punto che ha solo il riferimento.
    fun findActivity(week: Int, day: Int, activityId: String): SportActivity? =
        _sportDays.value.firstOrNull { it.week == week && it.day == day }
            ?.activities?.firstOrNull { it.id == activityId }

    internal fun parse(raw: String?): List<PlanDay> {
        if (raw == null) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PlanDay(
                    week      = o.optInt("week", 1),
                    day       = o.optInt("day", 0),
                    label     = o.optString("label")
                        .ifEmpty { "Giorno ${o.optInt("day", 0) + 1}" },
                    exercises = o.optInt("exercises", 0),
                )
            }
        }.getOrDefault(emptyList())
    }

    /// Parse tollerante di `sportDays` (§2.2): voci malformate scartate,
    /// attività di sport non avviabili sul Wear (`SportsCatalog.watchWear`)
    /// scartate (il telefono filtra già su `kWatchSportIds`, qui si
    /// rifiltra sul catalogo Wear, D21), giorni rimasti vuoti scartati.
    /// `structure`/`profile` tenuti solo per le attività `guided`.
    internal fun parseSportDays(raw: String?): List<SportDay> {
        if (raw == null) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<SportDay>()
        for (i in 0 until arr.length()) {
            val o = arr.opt(i) as? JSONObject ?: continue
            val week = (o.opt("week") as? Number)?.toInt() ?: continue
            val day = (o.opt("day") as? Number)?.toInt() ?: continue
            val label = (o.opt("label") as? String)?.takeIf { it.isNotEmpty() } ?: "Giorno ${day + 1}"
            val rawActivities = o.opt("activities") as? JSONArray ?: continue
            val activities = (0 until rawActivities.length()).mapNotNull { j ->
                (rawActivities.opt(j) as? JSONObject)?.let { parseActivity(it, week, day, label) }
            }
            if (activities.isNotEmpty()) out.add(SportDay(week, day, label, activities))
        }
        return out
    }

    private fun parseActivity(o: JSONObject, week: Int, day: Int, dayLabel: String): SportActivity? {
        val id = (o.opt("id") as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val sport = (o.opt("sport") as? String)?.takeIf { it.isNotEmpty() } ?: return null
        if (SportsCatalog.entry(sport)?.watchWear != true) return null
        val guided = o.opt("guided") == true
        val structure = if (guided) o.opt("structure") as? JSONObject else null
        val profile = if (guided) o.opt("profile") as? JSONObject else null
        return SportActivity(
            id = id,
            sport = sport,
            title = (o.opt("title") as? String)?.takeIf { it.isNotBlank() },
            summary = o.opt("summary") as? String ?: "",
            guided = guided,
            structureJson = structure?.toString(),
            profileJson = profile?.toString(),
            week = week,
            day = day,
            dayLabel = dayLabel,
        )
    }
}
