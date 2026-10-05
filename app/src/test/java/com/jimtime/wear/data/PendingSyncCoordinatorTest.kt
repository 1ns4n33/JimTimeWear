package com.jimtime.wear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stato "Invio…" della pagina Da sincronizzare (29/9). Il coordinatore è
 * toccato da più thread: "Sincronizza tutti" lancia un invio per riga su
 * Dispatchers.Default e le risposte arrivano dal WearableListenerService.
 * Con controllo e scrittura separati due invii paralleli perdevano un
 * aggiornamento e una riga poteva restare su "Invio…" per sempre.
 */
class PendingSyncCoordinatorTest {

    @Test
    fun `una riga si prende una volta sola e si rilascia una volta sola`() {
        assertTrue(PendingSyncCoordinator.claimSending("a"))
        assertFalse(PendingSyncCoordinator.claimSending("a"))
        assertTrue(PendingSyncCoordinator.releaseSending("a"))
        assertFalse(PendingSyncCoordinator.releaseSending("a"))
    }

    @Test
    fun `stesso id preso da molti thread insieme - vince uno solo`() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(64)
        val winners = AtomicInteger(0)
        repeat(64) {
            pool.execute {
                start.await()
                if (PendingSyncCoordinator.claimSending("race")) winners.incrementAndGet()
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(1, winners.get())
        assertTrue(PendingSyncCoordinator.releaseSending("race"))
    }

    @Test
    fun `tanti id diversi in parallelo - nessun aggiornamento perso`() {
        val ids = (1..200).map { "id-$it" }
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(ids.size)
        ids.forEach { id ->
            pool.execute {
                PendingSyncCoordinator.claimSending(id)
                done.countDown()
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(PendingSyncCoordinator.sendingIds.value.containsAll(ids))

        val released = CountDownLatch(ids.size)
        ids.forEach { id ->
            pool.execute {
                PendingSyncCoordinator.releaseSending(id)
                released.countDown()
            }
        }
        assertTrue(released.await(5, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue(PendingSyncCoordinator.sendingIds.value.none { it in ids })
    }
}
