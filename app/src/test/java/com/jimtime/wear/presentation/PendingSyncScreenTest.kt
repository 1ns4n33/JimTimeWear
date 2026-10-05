package com.jimtime.wear.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Copre il contratto testuale di [statusText]: ogni `reason` noto del wire
 * phone→watch (mirror di WatchConnectivity.swift lato iOS) deve avere un
 * testo italiano stabile — e un motivo IGNOTO (futuro, o typo lato
 * telefono) non deve mai sparire silenziosamente, solo cadere sul
 * fallback generico.
 */
class PendingSyncScreenTest {

    @Test
    fun `known reasons map to the Apple-mirrored Italian copy`() {
        assertEquals("Accedi a JimTime sul telefono", statusText("notLoggedIn"))
        assertEquals("Chiudila sul telefono", statusText("openOnPhone"))
        assertEquals("Salvataggio non riuscito, riprova", statusText("saveFailed"))
        assertEquals("Il telefono non riesce a leggerla", statusText("unreadable"))
        assertEquals("Telefono non raggiungibile", statusText("phoneUnreachable"))
        assertEquals("Nessuna risposta dal telefono", statusText("noResponse"))
    }

    @Test
    fun `unknown reason falls back instead of disappearing`() {
        assertEquals("Non ancora salvata sul telefono", statusText("somethingNewFromThePhone"))
        assertEquals("Non ancora salvata sul telefono", statusText(""))
    }
}
