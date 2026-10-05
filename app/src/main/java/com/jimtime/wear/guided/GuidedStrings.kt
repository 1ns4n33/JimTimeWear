package com.jimtime.wear.guided

import java.util.Locale

/**
 * F7 (Schede multisport: al polso, WP-I18N, D14) — il Wear OS non ha i18n
 * (memoria `watch-oggi-home`): si localizza solo il minimo indispensabile,
 * cioè le ~14 frasi dinamiche pronunciate/mostrate durante la corsa guidata
 * (fuori ritmo, metà fase, ultimi N, fine seduta, ...). Le frasi di INIZIO
 * fase e le etichette arrivano già tradotte dal telefono nel payload `ui`
 * (template `{i}/{n}`); queste stringhe restano come fallback quando il
 * wire non porta `ui` (es. checkpoint vecchio) o per il resto della UI del
 * Wear, che resta IT come oggi.
 *
 * Port letterale di `GuidedStrings.swift` (stesse 28 chiavi, stessi testi
 * nelle 5 lingue, stessi placeholder): NON ritradurre, copiare da lì. Kotlin
 * puro — nessun import Android, solo [java.util.Locale] per il fallback di
 * lingua quando `lang` non è supportata.
 */
object GuidedStrings {
    private val supportedLanguages = setOf("it", "en", "es", "fr", "de")

    private val table: Map<String, Map<String, String>> = mapOf(
        "halfway" to mapOf(
            "it" to "Metà fase",
            "en" to "Halfway",
            "es" to "Mitad de la fase",
            "fr" to "Mi-parcours",
            "de" to "Hälfte geschafft",
        ),
        "last_seconds" to mapOf(
            "it" to "Ultimi {sec} secondi",
            "en" to "Last {sec} seconds",
            "es" to "Últimos {sec} segundos",
            "fr" to "Dernières {sec} secondes",
            "de" to "Noch {sec} Sekunden",
        ),
        "last_meters" to mapOf(
            "it" to "Ultimi {m} metri",
            "en" to "Last {m} meters",
            "es" to "Últimos {m} metros",
            "fr" to "Derniers {m} mètres",
            "de" to "Noch {m} Meter",
        ),
        "too_fast" to mapOf(
            "it" to "Rallenta un po'",
            "en" to "Slow down a little",
            "es" to "Baja un poco el ritmo",
            "fr" to "Ralentis un peu",
            "de" to "Etwas langsamer",
        ),
        "too_slow" to mapOf(
            "it" to "Accelera un po'",
            "en" to "Pick up the pace a little",
            "es" to "Acelera un poco",
            "fr" to "Accélère un peu",
            "de" to "Etwas schneller",
        ),
        "hr_high" to mapOf(
            "it" to "Battito alto, rallenta",
            "en" to "Heart rate high, slow down",
            "es" to "Pulso alto, baja el ritmo",
            "fr" to "Fréquence élevée, ralentis",
            "de" to "Puls hoch, langsamer",
        ),
        "hr_low" to mapOf(
            "it" to "Battito basso, spingi",
            "en" to "Heart rate low, push",
            "es" to "Pulso bajo, aprieta",
            "fr" to "Fréquence basse, pousse",
            "de" to "Puls niedrig, mehr Druck",
        ),
        "cadence_high" to mapOf(
            "it" to "Cadenza alta, allunga il passo",
            "en" to "Cadence high, lengthen your stride",
            "es" to "Cadencia alta, alarga el paso",
            "fr" to "Cadence élevée, allonge la foulée",
            "de" to "Kadenz hoch, längere Schritte",
        ),
        "cadence_low" to mapOf(
            "it" to "Cadenza bassa, passi più corti",
            "en" to "Cadence low, shorter steps",
            "es" to "Cadencia baja, pasos más cortos",
            "fr" to "Cadence basse, raccourcis la foulée",
            "de" to "Kadenz niedrig, kürzere Schritte",
        ),
        "power_high" to mapOf(
            "it" to "Potenza alta, cala un po'",
            "en" to "Power high, ease up a bit",
            "es" to "Potencia alta, baja un poco",
            "fr" to "Puissance élevée, relâche un peu",
            "de" to "Leistung hoch, etwas lockerer",
        ),
        "power_low" to mapOf(
            "it" to "Potenza bassa, spingi",
            "en" to "Power low, push harder",
            "es" to "Potencia baja, aprieta",
            "fr" to "Puissance basse, pousse plus fort",
            "de" to "Leistung niedrig, mehr Druck",
        ),
        "back_on_target" to mapOf(
            "it" to "Bene, sei nel ritmo",
            "en" to "Good, you're on pace",
            "es" to "Bien, vas en el ritmo",
            "fr" to "Bien, tu es dans le rythme",
            "de" to "Gut, du bist im Takt",
        ),
        "workout_end" to mapOf(
            "it" to "Allenamento completato. Continua libero o ferma la sessione",
            "en" to "Workout complete. Keep going freely or stop the session",
            "es" to "Entrenamiento completado. Sigue libre o detén la sesión",
            "fr" to "Séance terminée. Continue librement ou arrête la session",
            "de" to "Training abgeschlossen. Lauf frei weiter oder beende die Einheit",
        ),
        "role_warmup" to mapOf(
            "it" to "Riscaldamento",
            "en" to "Warm-up",
            "es" to "Calentamiento",
            "fr" to "Échauffement",
            "de" to "Aufwärmen",
        ),
        "role_work" to mapOf(
            "it" to "Lavoro",
            "en" to "Work",
            "es" to "Trabajo",
            "fr" to "Effort",
            "de" to "Belastung",
        ),
        "role_recovery" to mapOf(
            "it" to "Recupero",
            "en" to "Recovery",
            "es" to "Recuperación",
            "fr" to "Récupération",
            "de" to "Erholung",
        ),
        "role_rest" to mapOf(
            "it" to "Riposo",
            "en" to "Rest",
            "es" to "Descanso",
            "fr" to "Repos",
            "de" to "Pause",
        ),
        "role_cooldown" to mapOf(
            "it" to "Defaticamento",
            "en" to "Cool-down",
            "es" to "Vuelta a la calma",
            "fr" to "Retour au calme",
            "de" to "Auslaufen",
        ),
        "free_run" to mapOf(
            "it" to "Corsa libera",
            "en" to "Free run",
            "es" to "Carrera libre",
            "fr" to "Course libre",
            "de" to "Freier Lauf",
        ),
        "next_label" to mapOf(
            "it" to "Poi: {next}",
            "en" to "Next: {next}",
            "es" to "Luego: {next}",
            "fr" to "Ensuite : {next}",
            "de" to "Dann: {next}",
        ),
        "until_lap" to mapOf(
            "it" to "fino al tasto",
            "en" to "until you tap",
            "es" to "hasta que pulses",
            "fr" to "jusqu'au bouton",
            "de" to "bis zum Tippen",
        ),
        "until_hr_below" to mapOf(
            "it" to "FC < {bpm}",
            "en" to "HR < {bpm}",
            "es" to "FC < {bpm}",
            "fr" to "FC < {bpm}",
            "de" to "HF < {bpm}",
        ),
        "phase_button" to mapOf(
            "it" to "Fase",
            "en" to "Phase",
            "es" to "Fase",
            "fr" to "Phase",
            "de" to "Phase",
        ),
        "voice_title" to mapOf(
            "it" to "Voce",
            "en" to "Voice",
            "es" to "Voz",
            "fr" to "Voix",
            "de" to "Stimme",
        ),
        "voice_auto" to mapOf(
            "it" to "Auto (solo con cuffie)",
            "en" to "Auto (headphones only)",
            "es" to "Auto (solo con auriculares)",
            "fr" to "Auto (écouteurs seulement)",
            "de" to "Auto (nur mit Kopfhörern)",
        ),
        "voice_on" to mapOf(
            "it" to "Sempre",
            "en" to "Always",
            "es" to "Siempre",
            "fr" to "Toujours",
            "de" to "Immer",
        ),
        "voice_off" to mapOf(
            "it" to "Mai",
            "en" to "Never",
            "es" to "Nunca",
            "fr" to "Jamais",
            "de" to "Nie",
        ),
        "guided_badge" to mapOf(
            "it" to "Guidata",
            "en" to "Guided",
            "es" to "Guiada",
            "fr" to "Guidée",
            "de" to "Geführt",
        ),
    )

    /**
     * `lang` accetta sia forme corte (`it`) sia stile `it-IT`/`it_IT` (ne
     * prende solo il prefisso). Se la lingua non è supportata si tenta
     * [Locale.getDefault] del dispositivo, poi `en`. Una chiave sconosciuta
     * ritorna la chiave stessa (nessuna eccezione, nessuna stringa vuota).
     */
    fun t(key: String, lang: String, args: Map<String, String> = emptyMap()): String {
        val translations = table[key] ?: return key
        val resolvedLang = resolveLanguage(lang)
        val template = translations[resolvedLang] ?: translations["en"] ?: return key
        return substitute(template, args)
    }

    private fun resolveLanguage(lang: String): String {
        val normalized = normalize(lang)
        if (normalized in supportedLanguages) return normalized

        val deviceLang = normalize(Locale.getDefault().language)
        if (deviceLang in supportedLanguages) return deviceLang

        return "en"
    }

    private fun normalize(lang: String): String {
        val prefix = lang.split("-", limit = 2)[0].split("_", limit = 2)[0]
        return prefix.lowercase(Locale.ROOT)
    }

    private fun substitute(template: String, args: Map<String, String>): String {
        if (args.isEmpty()) return template
        var result = template
        for ((key, value) in args) {
            result = result.replace("{$key}", value)
        }
        return result
    }
}
