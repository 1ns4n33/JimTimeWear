package com.jimtime.wear.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

data class PlanDay(
    val week: Int,      // 1-based, as pushed by the phone
    val day: Int,       // 0-based index within the week
    val label: String,
    val exercises: Int,
)

/**
 * Caches the active plan's day list (pushed by the phone via
 * CMD_PLAN_DAYS) in SharedPreferences so the wrist can browse it even
 * while the phone is away. Starting a day still requires the phone —
 * the session engine lives there.
 */
object PlanDaysStore {

    private const val PREFS_NAME = "jimtime_plan_days"
    private const val KEY_NAME   = "planName"
    private const val KEY_DAYS   = "days"

    private val _planName = MutableStateFlow("")
    val planName: StateFlow<String> = _planName.asStateFlow()

    private val _days = MutableStateFlow<List<PlanDay>>(emptyList())
    val days: StateFlow<List<PlanDay>> = _days.asStateFlow()

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _planName.value = prefs.getString(KEY_NAME, "") ?: ""
        _days.value = parse(prefs.getString(KEY_DAYS, null))
    }

    fun apply(context: Context, planName: String, daysJson: JSONArray) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAME, planName)
            .putString(KEY_DAYS, daysJson.toString())
            .apply()
        _planName.value = planName
        _days.value = parse(daysJson.toString())
    }

    private fun parse(raw: String?): List<PlanDay> {
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
}
