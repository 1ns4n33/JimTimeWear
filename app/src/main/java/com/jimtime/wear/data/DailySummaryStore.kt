package com.jimtime.wear.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.LocalDate

data class DailySummaryWater(
    val ml: Int,
    val goalMl: Int,
    val stepMl: Int,
)

data class DailySummaryNutrition(
    val kcal: Int,
    val kcalGoal: Int,
    val proteinG: Int,
    val proteinGoalG: Int,
    val carbsG: Int,
    val carbsGoalG: Int,
    val fatG: Int,
    val fatGoalG: Int,
    val meals: Int,
)

data class DailySummaryTraining(
    val weekDone: Int,
    val weekGoal: Int,
    val todayDone: Boolean,
    val streakDays: Int,
)

data class DailySummaryNextPlanDay(
    val week: Int,
    val day: Int,
    val label: String,
    val exercises: Int,
)

data class DailySummary(
    val date: String,
    val sentAt: Long,
    val water: DailySummaryWater,
    val nutrition: DailySummaryNutrition?,
    val training: DailySummaryTraining?,
    val nextPlanDay: DailySummaryNextPlanDay?,
    val quickTypes: List<String>,
    val appliedWaterSyncIds: List<String>,
)

/**
 * Caches the last `dailySummary` pushed by the phone (see
 * spec-contract.md). Message-only transport (never applicationContext),
 * so the watch pulls it on every foreground and keeps whatever it last
 * received in SharedPreferences in the meantime — must NOT depend on the
 * ViewModel: PhoneMessageService can apply a fresh one while MainActivity
 * is dead.
 */
object DailySummaryStore {

    private const val PREFS_NAME = "jimtime_daily_summary"
    private const val KEY_JSON = "json"
    private const val KEY_RECEIVED_AT = "receivedAt"

    private val _summary = MutableStateFlow<DailySummary?>(null)
    val summary: StateFlow<DailySummary?> = _summary.asStateFlow()

    private val _receivedAt = MutableStateFlow(0L)
    val receivedAt: StateFlow<Long> = _receivedAt.asStateFlow()

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _summary.value = parse(prefs.getString(KEY_JSON, null))
        _receivedAt.value = prefs.getLong(KEY_RECEIVED_AT, 0L)
    }

    /// Persiste il raw JSON così com'è ricevuto e aggiorna il clock watch
    /// (receivedAt), poi ricalcola i flow — chiamato dal WearableListenerService
    /// (processo potenzialmente diverso dall'Activity) tanto quanto dalla VM.
    fun apply(context: Context, json: JSONObject) {
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_JSON, json.toString())
            .putLong(KEY_RECEIVED_AT, now)
            .apply()
        _summary.value = parse(json.toString())
        _receivedAt.value = now
    }

    /// Data locale odierna nel formato usato dal contratto ("yyyy-MM-dd").
    fun todayLocal(): String = LocalDate.now().toString()

    private fun parse(raw: String?): DailySummary? {
        if (raw == null) return null
        return runCatching {
            val o = JSONObject(raw)
            val waterJson = o.getJSONObject("water")
            val water = DailySummaryWater(
                ml = waterJson.optIntTolerant("ml", 0),
                goalMl = waterJson.optIntTolerant("goalMl", 2500),
                stepMl = waterJson.optIntTolerant("stepMl", 250),
            )
            val nutrition = o.optJSONObject("nutrition")?.let { n ->
                DailySummaryNutrition(
                    kcal = n.optIntTolerant("kcal", 0),
                    kcalGoal = n.optIntTolerant("kcalGoal", 0),
                    proteinG = n.optIntTolerant("proteinG", 0),
                    proteinGoalG = n.optIntTolerant("proteinGoalG", 0),
                    carbsG = n.optIntTolerant("carbsG", 0),
                    carbsGoalG = n.optIntTolerant("carbsGoalG", 0),
                    fatG = n.optIntTolerant("fatG", 0),
                    fatGoalG = n.optIntTolerant("fatGoalG", 0),
                    meals = n.optIntTolerant("meals", 0),
                )
            }
            val training = o.optJSONObject("training")?.let { t ->
                DailySummaryTraining(
                    weekDone = t.optIntTolerant("weekDone", 0),
                    weekGoal = t.optIntTolerant("weekGoal", 0),
                    todayDone = t.optBoolean("todayDone", false),
                    streakDays = t.optIntTolerant("streakDays", 0),
                )
            }
            val nextPlanDay = o.optJSONObject("nextPlanDay")?.let { p ->
                DailySummaryNextPlanDay(
                    week = p.optIntTolerant("week", 1),
                    day = p.optIntTolerant("day", 0),
                    label = p.optString("label").ifEmpty { "Giorno ${p.optIntTolerant("day", 0) + 1}" },
                    exercises = p.optIntTolerant("exercises", 0),
                )
            }
            val quickTypesArr = o.optJSONArray("quickTypes")
            val quickTypes = if (quickTypesArr != null) {
                (0 until quickTypesArr.length()).map { quickTypesArr.optString(it) }
            } else emptyList()
            val appliedArr = o.optJSONArray("appliedWaterSyncIds")
            val applied = if (appliedArr != null) {
                (0 until appliedArr.length()).map { appliedArr.optString(it) }
            } else emptyList()

            DailySummary(
                date = o.optString("date"),
                sentAt = o.optLong("sentAt", 0L),
                water = water,
                nutrition = nutrition,
                training = training,
                nextPlanDay = nextPlanDay,
                quickTypes = quickTypes,
                appliedWaterSyncIds = applied,
            )
        }.getOrNull()
    }
}

/// I numeri sul wire possono arrivare come Int o Double (regola del
/// contratto) — org.json.optInt() su un Double troncato dà 0, quindi
/// proviamo optDouble prima e cadiamo sul default solo se manca la chiave.
private fun JSONObject.optIntTolerant(key: String, default: Int): Int {
    if (!has(key)) return default
    return optDouble(key, default.toDouble()).let {
        if (it.isNaN()) default else it.toInt()
    }
}
