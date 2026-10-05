package com.jimtime.wear.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F7 (correzioni WP-WD) — tempo attivo da orologio monotono e ripresa da
 * checkpoint: le pause non contano, il ritmo del ticker nemmeno, e la
 * ripresa non scende mai sotto quanto il motore guidato aveva già visto.
 */
class ActiveClockTest {

    @Test
    fun `accumula solo fuori pausa`() {
        val c = ActiveClock()
        c.reset(0L, running = true, nowMono = 1_000L)
        assertEquals(30_000L, c.activeMs(31_000L))
        c.pause(31_000L)
        // 70 s di pausa: fermo.
        assertEquals(30_000L, c.activeMs(101_000L))
        c.pause(101_000L) // idempotente
        c.resume(101_000L)
        c.resume(105_000L) // idempotente: non sposta l'inizio
        assertEquals(40_000L, c.activeMs(111_000L))
    }

    @Test
    fun `il numero di letture non cambia il tempo`() {
        val c = ActiveClock()
        c.reset(5_000L, running = true, nowMono = 0L)
        // Un ticker in ritardo legge di rado: conta il tempo, non i giri.
        assertEquals(65_000L, c.activeMs(60_000L))
        repeat(100) { c.activeMs(60_000L) }
        assertEquals(65_000L, c.activeMs(60_000L))
        // Orologio che torna indietro: mai meno della base.
        assertEquals(5_000L, c.activeMs(-10L))
    }

    @Test
    fun `reset fermo e base negativa`() {
        val c = ActiveClock()
        c.reset(-50L, running = false, nowMono = 0L)
        assertEquals(0L, c.activeMs(99_000L))
    }

    @Test
    fun `ripresa - in corso aggiunge il tempo reale trascorso`() {
        assertEquals(
            150_000L,
            ActiveClock.resumeActiveMs(savedActiveMs = 120_000L, savedAtWallMs = 1_000L, wasPaused = false, nowWallMs = 31_000L),
        )
    }

    @Test
    fun `ripresa - in pausa non aggiunge nulla (pausa 70 s e kill)`() {
        assertEquals(
            120_000L,
            ActiveClock.resumeActiveMs(savedActiveMs = 120_000L, savedAtWallMs = 1_000L, wasPaused = true, nowWallMs = 71_000L),
        )
    }

    @Test
    fun `ripresa - mai sotto il motore, mai negativa`() {
        assertEquals(
            130_000L,
            ActiveClock.resumeActiveMs(120_000L, 1_000L, wasPaused = true, nowWallMs = 2_000L, floorActiveMs = 130_000L),
        )
        // Orologio wall tornato indietro: nessun delta negativo.
        assertEquals(
            120_000L,
            ActiveClock.resumeActiveMs(120_000L, 50_000L, wasPaused = false, nowWallMs = 10_000L),
        )
    }

    @Test
    fun `SessionRepository - elapsed dall'orologio, pausa ferma, guidedStep clear`() {
        var t = 0L
        SessionRepository.monoNow = { t }
        try {
            SessionRepository.startStandaloneSession("run")
            t = 30_500L
            SessionRepository.tick()
            SessionRepository.tick() // due chiamanti: nessun doppio conteggio
            assertEquals(30L, SessionRepository.state.value.elapsedSeconds)
            SessionRepository.pauseSession()
            t = 100_500L
            SessionRepository.tick()
            assertEquals(30_500L, SessionRepository.activeMs())
            SessionRepository.resumeSession()
            t = 110_500L
            SessionRepository.tick()
            assertEquals(40L, SessionRepository.state.value.elapsedSeconds)

            // Ripresa da checkpoint in pausa: tempo e distanza subito.
            SessionRepository.restoreStandaloneSession("run", 0L, isPaused = true, activeMs = 30_500L, distanceMeters = 550.0)
            t = 200_000L
            assertEquals(30_500L, SessionRepository.activeMs())
            assertEquals(550.0, SessionRepository.state.value.distanceMeters, 0.0)

            SessionRepository.stopSession()
            SessionRepository.startSession("run", System.currentTimeMillis())
            SessionRepository.updateGuidedStep(GuidedStepMirror(0, 3, "A", "1:00", "", null, null, false, 0L))
            assertEquals("A", SessionRepository.state.value.guidedStep?.label)
            SessionRepository.clearGuidedStep()
            assertEquals(null, SessionRepository.state.value.guidedStep)
        } finally {
            SessionRepository.stopSession()
            SessionRepository.monoNow = { android.os.SystemClock.elapsedRealtime() }
        }
    }
}

