package com.jimtime.wear.health

import com.jimtime.wear.data.SwimWorkoutSetWire
import com.jimtime.wear.data.SwimWorkoutWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F5b (Nuoto, §7/D4) — [SwimGuide] avanza SOLO A TEMPO: queste prove
 * iniettano `nowMs` a mano (nessun clock reale, nessuna coroutine) per
 * coprire il caso comune (countdown → via → via → cambio serie) e i bordi
 * (serie senza `sendOffSec`, avanzamento manuale, fine allenamento, un
 * salto di `nowMs` più lungo di un intervallo intero).
 */
class SwimGuideTest {

    private fun setWire(reps: Int, sendOffSec: Int?, label: String = "set") = SwimWorkoutSetWire(
        kind = "MAIN",
        stroke = "free",
        reps = reps,
        distM = 100.0,
        sendOffSec = sendOffSec,
        restSec = null,
        label = label,
    )

    private fun workout(vararg sets: SwimWorkoutSetWire) = SwimWorkoutWire(
        id = "w1",
        title = "Test workout",
        poolLengthM = 25.0,
        sets = sets.toList(),
    )

    @Test
    fun `starts on rep 1 of set 1 with the send-off armed`() {
        val guide = SwimGuide(workout(setWire(reps = 3, sendOffSec = 90)), startAtMs = 0L)
        val step = guide.snapshot(nowMs = 0L)!!

        assertEquals(0, step.setIndex)
        assertEquals(1, step.setCount)
        assertEquals(1, step.repIndex)
        assertEquals(3, step.repCount)
        assertEquals(90, step.countdownSec)
        assertFalse(guide.isFinished())
    }

    @Test
    fun `countdown ticks down toward zero as time advances`() {
        val guide = SwimGuide(workout(setWire(reps = 3, sendOffSec = 90)), startAtMs = 0L)

        assertEquals(90, guide.snapshot(nowMs = 0L)?.countdownSec)
        assertEquals(60, guide.snapshot(nowMs = 30_000L)?.countdownSec)
        assertEquals(1, guide.snapshot(nowMs = 89_000L)?.countdownSec)
    }

    @Test
    fun `update fires REP_START and advances to the next rep when the send-off elapses`() {
        val guide = SwimGuide(workout(setWire(reps = 3, sendOffSec = 90)), startAtMs = 0L)

        assertEquals(SwimGuideEvent.NONE, guide.update(nowMs = 89_999L))
        assertEquals(SwimGuideEvent.REP_START, guide.update(nowMs = 90_000L))

        val step = guide.snapshot(nowMs = 90_000L)!!
        assertEquals(2, step.repIndex)
        assertEquals(3, step.repCount)
        // Next send-off re-armed 90s from the advance instant, not from 0.
        assertEquals(90, step.countdownSec)
    }

    @Test
    fun `update fires SET_CHANGE — a stronger haptic — when the last rep of a set completes`() {
        val guide = SwimGuide(
            workout(setWire(reps = 2, sendOffSec = 60, label = "A"), setWire(reps = 4, sendOffSec = 30, label = "B")),
            startAtMs = 0L,
        )

        assertEquals(SwimGuideEvent.REP_START, guide.update(nowMs = 60_000L)) // A rep 2/2 starts
        assertEquals(SwimGuideEvent.SET_CHANGE, guide.update(nowMs = 120_000L)) // A finished -> B rep 1

        val step = guide.snapshot(nowMs = 120_000L)!!
        assertEquals(1, step.setIndex)
        assertEquals("B", step.label)
        assertEquals(1, step.repIndex)
        assertEquals(4, step.repCount)
        assertEquals(30, step.countdownSec) // B's own interval, not A's
    }

    @Test
    fun `a set without sendOffSec never advances on its own`() {
        val guide = SwimGuide(workout(setWire(reps = 5, sendOffSec = null)), startAtMs = 0L)

        assertNull(guide.snapshot(nowMs = 0L)?.countdownSec)
        // Even a huge time jump changes nothing without a manual advance.
        assertEquals(SwimGuideEvent.NONE, guide.update(nowMs = 10_000_000L))
        assertEquals(1, guide.snapshot(nowMs = 10_000_000L)?.repIndex)
    }

    @Test
    fun `manualNextSet advances regardless of the current countdown — rotary or tap`() {
        val guide = SwimGuide(
            workout(setWire(reps = 3, sendOffSec = 90, label = "A"), setWire(reps = 2, sendOffSec = 45, label = "B")),
            startAtMs = 0L,
        )

        guide.manualNextSet(nowMs = 5_000L) // long before the 90s send-off would fire
        val step = guide.snapshot(nowMs = 5_000L)!!
        assertEquals(1, step.setIndex)
        assertEquals("B", step.label)
        assertEquals(1, step.repIndex)
        assertEquals(45, step.countdownSec)
    }

    @Test
    fun `workout finishes after the last set's last rep — isFinished becomes true`() {
        val guide = SwimGuide(workout(setWire(reps = 1, sendOffSec = 10)), startAtMs = 0L)

        assertFalse(guide.isFinished())
        val event = guide.update(nowMs = 10_000L)
        assertEquals(SwimGuideEvent.SET_CHANGE, event)
        assertTrue(guide.isFinished())
        assertNull(guide.snapshot(nowMs = 10_000L))
    }

    @Test
    fun `a clock jump past multiple send-offs still advances only one step per update call`() {
        // Never "burn" several reps in one tick if the ticker was delayed
        // (e.g. process briefly frozen) — the next update() call recovers
        // the rest, one at a time.
        val guide = SwimGuide(workout(setWire(reps = 5, sendOffSec = 10)), startAtMs = 0L)

        guide.update(nowMs = 999_000L) // way past several 10s intervals
        val step = guide.snapshot(nowMs = 999_000L)!!
        assertEquals(2, step.repIndex)
    }
}
