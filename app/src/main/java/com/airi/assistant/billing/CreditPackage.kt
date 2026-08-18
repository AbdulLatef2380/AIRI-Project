package com.airi.assistant.billing

/**
 * CreditPackage — defines all purchasable credit bundles available in AIRI.
 *
 * Credit packages supplement the daily free/premium quota. They are
 * one-time purchases (consumable) processed via Stripe.
 *
 * DESIGN: Packages are defined in code (not fetched from server) so the UI
 * renders instantly without a network call. Prices are verified server-side
 * via Stripe before the PaymentIntent is created.
 */
enum class CreditPackage(
    val stripeProductId: String,
    val displayName:     String,
    val credits:         Int,
    val priceUsdCents:   Int,
    val emoji:           String,
    val highlight:       String?,
    val bonusPercent:    Int = 0
) {
    STARTER(
        stripeProductId = "prod_airi_credits_starter",
        displayName     = "Starter Pack",
        credits         = 500,
        priceUsdCents   = 199,
        emoji           = "",
        highlight       = null,
        bonusPercent    = 0
    ),
    BASIC(
        stripeProductId = "prod_airi_credits_basic",
        displayName     = "Basic Pack",
        credits         = 1_500,
        priceUsdCents   = 499,
        emoji           = "",
        highlight       = null,
        bonusPercent    = 0
    ),
    PRO(
        stripeProductId = "prod_airi_credits_pro",
        displayName     = "Pro Pack",
        credits         = 5_000,
        priceUsdCents   = 1_499,
        emoji           = "",
        highlight       = "Best Value",
        bonusPercent    = 25
    ),
    POWER(
        stripeProductId = "prod_airi_credits_power",
        displayName     = "Power Pack",
        credits         = 15_000,
        priceUsdCents   = 3_999,
        emoji           = "",
        highlight       = "Most Popular",
        bonusPercent    = 50
    ),
    ENTERPRISE(
        stripeProductId = "prod_airi_credits_enterprise",
        displayName     = "Enterprise Pack",
        credits         = 50_000,
        priceUsdCents   = 9_999,
        emoji           = "",
        highlight       = "Maximum",
        bonusPercent    = 100
    );

    /** Effective credits after bonus. */
    val totalCredits: Int get() = credits + (credits * bonusPercent / 100)

    /** Display price string (e.g. "$4.99"). */
    val priceString: String get() = "$${priceUsdCents / 100}.${"%02d".format(priceUsdCents % 100)}"

    /** Cost per 1000 credits in cents. */
    val costPer1kCents: Float get() = priceUsdCents.toFloat() / (totalCredits.toFloat() / 1000f)

    companion object {
        /** Monthly Stripe subscription product IDs. */
        const val STRIPE_PRODUCT_PREMIUM_MONTHLY  = "prod_airi_premium_monthly"
        const val STRIPE_PRODUCT_PREMIUM_ANNUAL   = "prod_airi_premium_annual"
        const val STRIPE_PRICE_PREMIUM_MONTHLY    = "price_airi_premium_monthly"
        const val STRIPE_PRICE_PREMIUM_ANNUAL     = "price_airi_premium_annual"

        fun fromProductId(id: String): CreditPackage? = entries.firstOrNull { it.stripeProductId == id }
    }
}

/** A single record in the billing history. */
data class BillingRecord(
    val id:              String,
    val timestampMs:     Long,
    val type:            RecordType,
    val productId:       String,
    val description:     String,
    val amountUsdCents:  Int,
    val credits:         Int         = 0,
    val status:          Status      = Status.SUCCEEDED,
    val stripePaymentId: String?     = null
) {
    enum class RecordType { CREDIT_PURCHASE, SUBSCRIPTION_START, SUBSCRIPTION_RENEWAL, SUBSCRIPTION_CANCEL, REFUND }
    enum class Status     { SUCCEEDED, PENDING, FAILED, REFUNDED }

    val amountString: String get() = "$${amountUsdCents / 100}.${"%02d".format(amountUsdCents % 100)}"
    val isSuccess:    Boolean get() = status == Status.SUCCEEDED
}
