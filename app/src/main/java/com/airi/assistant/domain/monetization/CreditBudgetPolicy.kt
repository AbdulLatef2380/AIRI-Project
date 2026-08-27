package com.airi.assistant.domain.monetization

/**
 * Pure budget rules shared by the persisted credit meter and unit tests.
 * Token volume belongs to TokenAccountant; it is not a second daily credit
 * charge and must never make the user-facing balance exceed its budget.
 */
internal object CreditBudgetPolicy {
    private val legacyTokenChargeKeys = setOf("LOCAL_TOKENS", "CLOUD_TOKENS")

    fun remaining(used: Int, budget: Int): Int =
        (budget - used).coerceIn(0, budget)

    fun canConsume(used: Int, budget: Int, charge: Int): Boolean =
        charge > 0 && used in 0..budget && charge <= remaining(used, budget)

    fun clampedUsed(used: Int, budget: Int): Int = used.coerceIn(0, budget)

    fun removeLegacyTokenCharges(
        dailyTotal: Int,
        lifetimeTotal: Long,
        perActionDay: Map<String, Int>,
    ): LegacyTokenChargeMigration {
        val removed = perActionDay
            .filterKeys { it in legacyTokenChargeKeys }
            .values
            .sum()
        if (removed == 0) {
            return LegacyTokenChargeMigration(dailyTotal, lifetimeTotal, perActionDay, migrated = false)
        }
        return LegacyTokenChargeMigration(
            dailyTotal = (dailyTotal - removed).coerceAtLeast(0),
            lifetimeTotal = (lifetimeTotal - removed).coerceAtLeast(0L),
            perActionDay = perActionDay.filterKeys { it !in legacyTokenChargeKeys },
            migrated = true,
        )
    }
}

internal data class LegacyTokenChargeMigration(
    val dailyTotal: Int,
    val lifetimeTotal: Long,
    val perActionDay: Map<String, Int>,
    val migrated: Boolean,
)
