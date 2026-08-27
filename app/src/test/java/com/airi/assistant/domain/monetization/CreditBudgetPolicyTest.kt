package com.airi.assistant.domain.monetization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreditBudgetPolicyTest {

    @Test
    fun budgetSequenceStopsAtTheDailyLimit() {
        var used = 0
        repeat(200) {
            assertTrue(CreditBudgetPolicy.canConsume(used, budget = 200, charge = 1))
            used += 1
        }

        assertEquals(200, used)
        assertEquals(0, CreditBudgetPolicy.remaining(used, budget = 200))
        assertFalse(CreditBudgetPolicy.canConsume(used, budget = 200, charge = 1))
    }

    @Test
    fun legacyTokenChargesAreRemovedFromTheCreditLedger() {
        val migration = CreditBudgetPolicy.removeLegacyTokenCharges(
            dailyTotal = 1_204,
            lifetimeTotal = 4_204L,
            perActionDay = mapOf(
                ActionType.MESSAGE.name to 4,
                "LOCAL_TOKENS" to 1_000,
                "CLOUD_TOKENS" to 200,
            ),
        )

        assertTrue(migration.migrated)
        assertEquals(4, migration.dailyTotal)
        assertEquals(3_004L, migration.lifetimeTotal)
        assertEquals(mapOf(ActionType.MESSAGE.name to 4), migration.perActionDay)
    }

    @Test
    fun visibleUsageIsAlwaysBoundedByTheBudget() {
        assertEquals(200, CreditBudgetPolicy.clampedUsed(1_204, budget = 200))
        assertEquals(0, CreditBudgetPolicy.remaining(1_204, budget = 200))
    }
}
