package com.jimtime.wear.guided

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * F7 (Schede multisport: al polso, WP-I18N, D14) — [GuidedStrings] è un
 * port letterale di `GuidedStrings.swift`: queste prove verificano che le
 * 28 chiavi esistano nelle 5 lingue con gli stessi placeholder, e che il
 * fallback di lingua funzioni (lingua ignota → `en`, `it-IT` → `it`).
 */
class GuidedStringsTest {

    private val keys = listOf(
        "halfway", "last_seconds", "last_meters", "too_fast", "too_slow",
        "hr_high", "hr_low", "cadence_high", "cadence_low", "power_high", "power_low",
        "back_on_target", "workout_end",
        "role_warmup", "role_work", "role_recovery", "role_rest", "role_cooldown",
        "free_run", "next_label", "until_lap", "until_hr_below",
        "phase_button", "voice_title", "voice_auto", "voice_on", "voice_off", "guided_badge",
    )

    private val languages = listOf("it", "en", "es", "fr", "de")

    @Test
    fun `there are exactly 28 keys`() {
        assertEquals(28, keys.size)
    }

    @Test
    fun `every key resolves to a non-empty, distinct-from-key string in all 5 languages`() {
        for (key in keys) {
            for (lang in languages) {
                val value = GuidedStrings.t(key, lang)
                assertTrue("$key/$lang should not be empty", value.isNotEmpty())
                assertTrue("$key/$lang should not fall back to the raw key", value != key)
            }
        }
    }

    @Test
    fun `placeholders are identical across all 5 languages for every key`() {
        for (key in keys) {
            val placeholderSets = languages.map { lang ->
                placeholdersOf(GuidedStrings.t(key, lang))
            }
            val reference = placeholderSets.first()
            for ((index, set) in placeholderSets.withIndex()) {
                assertEquals(
                    "placeholders of '$key' differ for language '${languages[index]}'",
                    reference,
                    set,
                )
            }
        }
    }

    @Test
    fun `placeholder substitution replaces every occurrence`() {
        assertEquals("Ultimi 5 secondi", GuidedStrings.t("last_seconds", "it", mapOf("sec" to "5")))
        assertEquals("Last 5 seconds", GuidedStrings.t("last_seconds", "en", mapOf("sec" to "5")))
        assertEquals("FC < 130", GuidedStrings.t("until_hr_below", "it", mapOf("bpm" to "130")))
        assertEquals("Poi: Recupero 2/6", GuidedStrings.t("next_label", "it", mapOf("next" to "Recupero 2/6")))
    }

    @Test
    fun `unsupported lang short code falls back to the device default, else English`() {
        // A short code outside the supported set (it/en/es/fr/de) is not in
        // the table, so GuidedStrings falls back to Locale.getDefault(), and
        // from there to English if the device locale is not supported either.
        val expected = if (Locale.getDefault().language.lowercase(Locale.ROOT) == "it") {
            GuidedStrings.t("halfway", "it")
        } else {
            GuidedStrings.t("halfway", "en")
        }
        assertEquals(expected, GuidedStrings.t("halfway", "zz"))
    }

    @Test
    fun `region-qualified lang codes are normalized to their language prefix`() {
        assertEquals(GuidedStrings.t("halfway", "it"), GuidedStrings.t("halfway", "it-IT"))
        assertEquals(GuidedStrings.t("halfway", "en"), GuidedStrings.t("halfway", "en-US"))
        assertEquals(GuidedStrings.t("halfway", "fr"), GuidedStrings.t("halfway", "fr_FR"))
    }

    @Test
    fun `unknown key returns the key itself`() {
        assertEquals("not_a_real_key", GuidedStrings.t("not_a_real_key", "it"))
    }

    /** Extracts the set of `{name}` placeholders used in a template string. */
    private fun placeholdersOf(text: String): Set<String> {
        val regex = Regex("\\{(\\w+)}")
        return regex.findAll(text).map { it.groupValues[1] }.toSet()
    }
}
