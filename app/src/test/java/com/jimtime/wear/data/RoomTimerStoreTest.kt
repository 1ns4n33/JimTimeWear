package com.jimtime.wear.data

import com.jimtime.wear.presentation.roomHapticFor
import com.jimtime.wear.presentation.roomRemainingSec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** M1 (timer di sala §7.1/§7.5) — parser tollerante, ordine `seq`, scadenza 60 s. */
class RoomTimerStoreTest {

    @Before fun reset() = RoomTimerStore.resetForTest()

    private fun msg(seq: Int, extra: String = "") = JSONObject(
        """{"cmd":"roomTimer","state":"live","lessonName":"Sparring","kind":"rounds","phase":"work",
        "label":"Round 3/6","roundIndex":3,"roundTotal":6,"endsAt":1760000097000,"remaining":"1:37",
        "paused":false,"next":"Pausa","finished":false,"seq":$seq$extra}""",
    )

    @Test
    fun `payload completo`() {
        assertTrue(RoomTimerStore.apply(msg(5), 1_000L))
        val s = RoomTimerStore.state.value!!
        assertEquals("Round 3/6", s.label)
        assertEquals(3, s.roundIndex); assertEquals(6, s.roundTotal)
        assertEquals(1760000097000L, s.endsAt)
        assertEquals(5L, s.seq)
        assertFalse(s.paused); assertFalse(s.isEnded)
    }

    @Test
    fun `campi mancanti usano i default e non crashano`() {
        assertTrue(RoomTimerStore.apply(JSONObject("""{"cmd":"roomTimer"}"""), 1_000L))
        val s = RoomTimerStore.state.value!!
        assertEquals("live", s.state)
        assertEquals("", s.label); assertEquals("", s.next); assertEquals("", s.remaining)
        assertNull(s.endsAt); assertNull(s.seq)
        assertEquals(0, s.roundIndex); assertFalse(s.paused)
        // `endsAt: null` esplicito = assente (pausa).
        RoomTimerStore.apply(JSONObject("""{"endsAt":null,"seq":null,"remaining":"0:30","paused":true}"""), 2_000L)
        assertNull(RoomTimerStore.state.value!!.endsAt)
        assertEquals(30, roomRemainingSec(RoomTimerStore.state.value!!, 5_000L))
    }

    @Test
    fun `seq vecchio scartato, uguale o nuovo accettato`() {
        RoomTimerStore.apply(msg(10), 1_000L)
        assertFalse(RoomTimerStore.apply(msg(9, ""), 2_000L))
        assertEquals(10L, RoomTimerStore.state.value!!.seq)
        assertTrue(RoomTimerStore.apply(msg(10), 3_000L))
        assertTrue(RoomTimerStore.apply(msg(11), 4_000L))
        assertEquals(11L, RoomTimerStore.state.value!!.seq)
        // Anche un `clear` fuori ordine non cancella lo stato.
        assertFalse(RoomTimerStore.apply(JSONObject("""{"state":"clear","seq":3}"""), 5_000L))
        assertTrue(RoomTimerStore.state.value != null)
        assertTrue(RoomTimerStore.apply(JSONObject("""{"state":"clear","seq":12}"""), 6_000L))
        assertNull(RoomTimerStore.state.value)
    }

    @Test
    fun `scadenza 60 s e telefono riavviato con seq ripartito`() {
        RoomTimerStore.apply(msg(50), 0L)
        val s = RoomTimerStore.state.value!!
        assertFalse(RoomTimerStore.isStale(s, 60_000L))
        assertTrue(RoomTimerStore.isStale(s, 60_001L))
        // Ultimo messaggio scaduto: un seq più basso (telefono riavviato) passa.
        assertTrue(RoomTimerStore.apply(msg(1), 120_000L))
        assertEquals(1L, RoomTimerStore.state.value!!.seq)
    }

    @Test
    fun `ended e conto alla rovescia da endsAt`() {
        RoomTimerStore.apply(JSONObject("""{"state":"ended","lessonName":"X","seq":2}"""), 0L)
        assertTrue(RoomTimerStore.state.value!!.isEnded)
        RoomTimerStore.apply(msg(3), 0L)
        val s = RoomTimerStore.state.value!!
        assertEquals(97, roomRemainingSec(s, 1760000000000L))
        assertEquals(0, roomRemainingSec(s, 1760000100000L))
        assertNull(roomHapticFor(s, 1760000000000L))
        assertEquals("warn", roomHapticFor(s, 1760000090000L))
        assertEquals("end", roomHapticFor(s, 1760000097000L))
        // In pausa nessuna aptica e conto da `remaining`.
        RoomTimerStore.apply(msg(4).also { it.put("paused", true) }, 0L)
        val p = RoomTimerStore.state.value!!
        assertEquals(97, roomRemainingSec(p, 1760000099000L))
        assertNull(roomHapticFor(p, 1760000092000L))
    }

    private fun waitUntil(ms: Long = 3_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    @Test
    fun `ended si azzera da solo`() {
        RoomTimerStore.endedClearMs = 150L
        RoomTimerStore.apply(JSONObject("""{"state":"ended","lessonName":"X","seq":2}"""))
        assertTrue(RoomTimerStore.state.value!!.isEnded)
        assertTrue(waitUntil { RoomTimerStore.state.value == null })
    }

    @Test
    fun `un live dopo ended annulla l'azzeramento`() {
        RoomTimerStore.endedClearMs = 150L
        RoomTimerStore.apply(JSONObject("""{"state":"ended","seq":2}"""))
        RoomTimerStore.apply(msg(3))
        Thread.sleep(400)
        assertTrue(RoomTimerStore.state.value != null)
    }

    @Test
    fun `scaduto si azzera da solo, un messaggio rimanda la scadenza`() {
        RoomTimerStore.forgetMs = 250L
        RoomTimerStore.apply(msg(1))
        Thread.sleep(150)
        RoomTimerStore.apply(msg(2))
        Thread.sleep(150) // 300 ms dal primo, 150 dal secondo: ancora vivo
        assertTrue(RoomTimerStore.state.value != null)
        assertTrue(waitUntil { RoomTimerStore.state.value == null })
    }

    @Test
    fun `nuovo live dopo clear riapre (episode nuovo), i battiti no`() {
        RoomTimerStore.apply(msg(1))
        val e1 = RoomTimerStore.state.value!!.episode
        RoomTimerStore.apply(msg(2))
        assertEquals(e1, RoomTimerStore.state.value!!.episode)
        RoomTimerStore.clear()
        assertNull(RoomTimerStore.state.value)
        RoomTimerStore.apply(msg(3))
        val e2 = RoomTimerStore.state.value!!.episode
        assertTrue(e2 > e1)
        // Anche live dopo ended = nuova lezione.
        RoomTimerStore.apply(JSONObject("""{"state":"ended","seq":4}"""))
        RoomTimerStore.apply(msg(5))
        assertTrue(RoomTimerStore.state.value!!.episode > e2)
    }

    @Test
    fun `step opzionale`() {
        RoomTimerStore.apply(msg(1, ",\"step\":4"))
        assertEquals(4, RoomTimerStore.state.value!!.step)
        RoomTimerStore.apply(msg(2))
        assertNull(RoomTimerStore.state.value!!.step)
    }
}
