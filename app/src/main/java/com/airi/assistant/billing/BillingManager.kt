package com.airi.assistant.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.domain.monetization.SubscriptionManager
import com.airi.assistant.domain.monetization.SubscriptionTier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class BillingManager(
    private val context: Context,
    private val subscriptionManager: SubscriptionManager
) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingManager"
        const val PRODUCT_PREMIUM_MONTHLY = "premium_monthly"
    }

    sealed class BillingState {
        object Idle : BillingState()
        object Connecting : BillingState()
        object Connected : BillingState()
        data class PurchaseSuccess(val tier: SubscriptionTier) : BillingState()
        data class PurchasePending(val message: String) : BillingState()
        data class Error(val message: String) : BillingState()
    }

    private val _billingState = MutableStateFlow<BillingState>(BillingState.Idle)
    val billingState: StateFlow<BillingState> = _billingState

    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    // ── Connect ───────────────────────────────────────────────────────────────

    fun connect() {
        if (billingClient.isReady) {
            scope.launch { queryProductDetails() }
            return
        }
        _billingState.value = BillingState.Connecting
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "BillingClient connected")
                    _billingState.value = BillingState.Connected
                    scope.launch {
                        queryProductDetails()
                        restorePurchases()
                    }
                } else {
                    Log.w(TAG, "BillingClient setup failed: ${result.debugMessage}")
                    _billingState.value = BillingState.Error("Billing unavailable: ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "BillingClient disconnected — will retry on next launch")
                _billingState.value = BillingState.Idle
            }
        })
    }

    // ── Query product details ─────────────────────────────────────────────────

    private suspend fun queryProductDetails() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_PREMIUM_MONTHLY)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()

        val result = billingClient.queryProductDetails(params)
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            val details = result.productDetailsList?.firstOrNull()
            _productDetails.value = details
            Log.d(TAG, "Product details loaded: ${details?.name}")
        } else {
            Log.w(TAG, "queryProductDetails failed: ${result.billingResult.debugMessage}")
        }
    }

    // ── Launch purchase flow ──────────────────────────────────────────────────

    fun launchPurchaseFlow(activity: Activity) {
        val details = _productDetails.value
        if (details == null) {
            _billingState.value = BillingState.Error("Product not available. Check your connection.")
            return
        }

        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: run {
            _billingState.value = BillingState.Error("No subscription offer available.")
            return
        }

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .setOfferToken(offerToken)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        val result = billingClient.launchBillingFlow(activity, billingFlowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _billingState.value = BillingState.Error("Could not open billing: ${result.debugMessage}")
        }
    }

    // ── Purchase updates ──────────────────────────────────────────────────────

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase -> processPurchase(purchase) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "Purchase cancelled by user")
                _billingState.value = BillingState.Idle
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.d(TAG, "Item already owned — restoring")
                scope.launch { restorePurchases() }
            }
            else -> {
                Log.w(TAG, "Purchase error: ${result.responseCode} ${result.debugMessage}")
                AnalyticsService.purchaseFailed("code=${result.responseCode}")
                _billingState.value = BillingState.Error("Purchase failed: ${result.debugMessage}")
            }
        }
    }

    // ── Process & acknowledge purchase ────────────────────────────────────────

    private fun processPurchase(purchase: Purchase) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                if (!purchase.isAcknowledged) {
                    scope.launch { acknowledgePurchase(purchase) }
                } else {
                    grantPremium()
                }
            }
            Purchase.PurchaseState.PENDING -> {
                _billingState.value = BillingState.PurchasePending(
                    "Payment is pending. Premium will activate once payment is confirmed."
                )
            }
            else -> {
                Log.d(TAG, "Purchase state: ${purchase.purchaseState}")
            }
        }
    }

    private suspend fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        val result = billingClient.acknowledgePurchase(params)
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            Log.d(TAG, "Purchase acknowledged")
            AnalyticsService.purchaseSuccess(PRODUCT_PREMIUM_MONTHLY)
            grantPremium()
        } else {
            Log.w(TAG, "Acknowledge failed: ${result.debugMessage}")
            AnalyticsService.purchaseFailed("ack_failed: ${result.debugMessage}")
            _billingState.value = BillingState.Error("Purchase acknowledgement failed. Try restoring purchases.")
        }
    }

    private fun grantPremium() {
        subscriptionManager.setTier(SubscriptionTier.PREMIUM)
        _billingState.value = BillingState.PurchaseSuccess(SubscriptionTier.PREMIUM)
        Log.d(TAG, "Premium granted")
    }

    // ── Restore purchases ─────────────────────────────────────────────────────

    suspend fun restorePurchases() {
        if (!billingClient.isReady) {
            connect()
            return
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        val result = billingClient.queryPurchasesAsync(params)
        val activePurchase = result.purchasesList.firstOrNull { purchase ->
            purchase.products.contains(PRODUCT_PREMIUM_MONTHLY) &&
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED
        }

        if (activePurchase != null) {
            if (!activePurchase.isAcknowledged) {
                acknowledgePurchase(activePurchase)
            } else {
                grantPremium()
                AnalyticsService.restorePurchase(true)
            }
            Log.d(TAG, "Purchase restored successfully")
        } else {
            // No active subscription — ensure tier is free
            AnalyticsService.restorePurchase(false)
            if (subscriptionManager.isPremium()) {
                subscriptionManager.setTier(SubscriptionTier.FREE)
                Log.d(TAG, "Subscription expired — reverted to FREE")
            }
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun destroy() {
        scope.cancel()
        if (billingClient.isReady) {
            billingClient.endConnection()
        }
    }
}
