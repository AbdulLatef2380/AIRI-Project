package com.airi.assistant.domain.monetization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PricingConfigTest {
    @Test
    fun targetPricesAreStableWhileBillingIsPaused() {
        assertEquals(7.99, PricingConfig.PRO_MONTHLY_PRICE_USD, 0.0)
        assertEquals(39.99, PricingConfig.PRO_ANNUAL_PRICE_USD, 0.0)
        assertFalse(PricingConfig.BILLING_ENABLED)
    }

    @Test
    fun advertisingIsPermanentlyDisabledByPolicy() {
        assertFalse(PricingConfig.ADS_ENABLED)
        assertFalse(PricingConfig.SHOW_LAUNCH_AD_ONCE)
        assertFalse(AdPolicy.shouldShowLaunchAd(hasShownThisLaunch = false))
    }
}
