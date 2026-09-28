package com.jimtime.wear.health

/**
 * F5b (Nuoto, D2/§7) — costruisce il blocco `swim` di `sessionSync` (mirror
 * `SwimSessionDto`, letto lato phone da `SwimSummary.fromWire`). Estratto
 * come funzione pura (nessun `org.json`/Android) apposta per essere
 * testabile con JUnit puro, senza Robolectric — [SessionViewModel] lo
 * avvolge in un `org.json.JSONObject` solo al momento dell'invio.
 *
 * `laps`/`sets` sono SEMPRE vuoti (D9): Health Services espone solo il
 * conteggio cumulativo di vasche/bracciate, mai lo split per singola
 * vasca — mandare un array inventato sarebbe peggio che ometterlo, il
 * fallback "vasche/serie sconosciute" del parser lato phone è pensato
 * apposta per questo caso.
 */
object SwimPayloadBuilder {

    fun build(
        location: String,
        poolLengthM: Double?,
        distanceMeters: Double,
        laps: Int,
        strokes: Int,
    ): Map<String, Any?> = buildMap {
        put("location", location)
        put("source", "WATCH")
        if (poolLengthM != null && poolLengthM > 0) put("poolLengthM", poolLengthM)
        put("strokeCount", strokes)
        // Extra, non letto da SwimSummary.fromWire (che ignora le chiavi che
        // non conosce) — diagnostica utile, forward-compatible.
        put("lapCount", laps)
        put("distanceM", distanceMeters)
        put("laps", emptyList<Any>())
        put("sets", emptyList<Any>())
    }
}
