package com.airi.assistant.execution

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedExecutionLockTest {
    @Test
    fun uncontendedCallRunsWithoutReportingAWait() = runBlocking {
        val lock = BoundedExecutionLock()
        var waits = 0
        val result = lock.withLock(
            waitLimitMs = 100,
            onWaited = { _, _ -> waits++ },
            onBusy = { "busy" },
            block = { "ran" },
        )
        assertEquals("ran", result)
        assertEquals(0, waits)
        assertFalse(lock.isLocked)
    }

    @Test
    fun waiterRunsAfterTheHolderFinishesAndReportsTheWait() = runBlocking {
        val lock = BoundedExecutionLock()
        val release = CompletableDeferred<Unit>()
        val holderEntered = CompletableDeferred<Unit>()
        val holder = async {
            lock.withLock(1_000, onBusy = { "busy" }) {
                holderEntered.complete(Unit)
                release.await()
                "holder"
            }
        }
        holderEntered.await()
        var waitedAcquired: Boolean? = null
        val waiter = async {
            lock.withLock(
                waitLimitMs = 2_000,
                onWaited = { _, acquired -> waitedAcquired = acquired },
                onBusy = { "busy" },
                block = { "waiter" },
            )
        }
        delay(30)
        release.complete(Unit)
        assertEquals("holder", holder.await())
        assertEquals("waiter", waiter.await())
        assertEquals(true, waitedAcquired)
        assertFalse(lock.isLocked)
    }

    @Test
    fun stuckHolderMakesTheWaiterGiveUpWithoutCancellingTheHolder() = runBlocking {
        val lock = BoundedExecutionLock()
        val release = CompletableDeferred<Unit>()
        val holderEntered = CompletableDeferred<Unit>()
        val holder = async {
            lock.withLock(1_000, onBusy = { "busy" }) {
                holderEntered.complete(Unit)
                release.await()
                "holder"
            }
        }
        holderEntered.await()
        var acquiredFlag: Boolean? = null
        val outcome = lock.withLock(
            waitLimitMs = 50,
            onWaited = { _, acquired -> acquiredFlag = acquired },
            onBusy = { "busy" },
            block = { "waiter" },
        )
        assertEquals("busy", outcome)
        assertEquals(false, acquiredFlag)
        assertTrue("the holder must keep the lock", lock.isLocked)
        release.complete(Unit)
        assertEquals("holder", holder.await())
        assertFalse(lock.isLocked)
    }

    @Test
    fun lockIsReleasedWhenTheBlockThrows() = runBlocking {
        val lock = BoundedExecutionLock()
        try {
            lock.withLock<Unit>(100, onBusy = {}) { error("boom") }
        } catch (_: IllegalStateException) {
        }
        assertFalse(lock.isLocked)
    }
}
