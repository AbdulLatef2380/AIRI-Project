package com.airi.assistant.billing

import android.content.Context
import android.util.Log
import com.airi.assistant.connector.ConnectorAuthManager
import com.airi.assistant.domain.monetization.SubscriptionManager
import com.airi.assistant.domain.monetization.SubscriptionTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * StripeManager — Stripe payment processing for AIRI.
 *
 * Handles:
 *  - One-time credit pack purchases via Stripe Checkout Sessions
 *  - Monthly/annual Premium subscriptions via Stripe Subscriptions
 *  - Payment validation via Stripe Payment Intent status checks
 *  - Local billing history via [BillingHistoryStore]
 *
 * ── ARCHITECTURE ─────────────────────────────────────────────────────────────
 * On Android, Stripe payments are completed via Stripe Checkout (hosted page)
 * opened in a Custom Chrome Tab. AIRI does NOT collect raw card numbers — all
 * PCI-sensitive data is handled by Stripe's hosted UI.
 *
 * Flow:
 *  1. [createCheckoutSession] → POST to AIRI backend → returns Stripe session URL
 *  2. Host Activity opens the URL in a Chrome Custom Tab
 *  3. On completion, Stripe redirects to `airi://stripe/success?session_id=...`
 *  4. [validateCheckoutSession] confirms payment status via backend
 *  5. On success: credits are added, billing history is recorded, tier is upgraded
 *
 * ── SECURITY ─────────────────────────────────────────────────────────────────
 *  - Stripe publishable key is stored in BuildConfig (non-secret).
 *  - Stripe secret key NEVER appears in client code — all Payment Intent creation
 *    goes through the AIRI backend (see BACKEND_URL).
 *  - Payment validation is server-side: the client only confirms an already-verified
 *    PaymentIntent, preventing client-side manipulation of payment status.
 *  - [SecureStorage] stores the Stripe Customer ID (not a payment method).
 */
class StripeManager(
    private val context:             Context,
    private val subscriptionManager: SubscriptionManager,
    private val billingHistory:      BillingHistoryStore,
    private val authManager:         ConnectorAuthManager
) {

    companion object {
        private const val TAG             = "StripeManager"
        // Replace with your deployed AIRI backend URL. On Android, all Stripe secret-key
        // operations (create PaymentIntent, create CheckoutSession) go through this server.
        private const val BACKEND_URL     = "https://api.airi-assistant.app/v1/billing"
        private const val STRIPE_REDIRECT = "airi://stripe/success"
        private const val CUSTOMER_ID_KEY = "stripe_customer_id"
    }

    sealed class PaymentState {
        object Idle                                      : PaymentState()
        object Processing                                : PaymentState()
        data class CheckoutReady(val url: String)        : PaymentState()
        data class Success(val record: BillingRecord)    : PaymentState()
        data class Failed(val message: String)           : PaymentState()
        data class Pending(val message: String)          : PaymentState()
    }

    private val _paymentState = MutableStateFlow<PaymentState>(PaymentState.Idle)
    val paymentState: StateFlow<PaymentState> = _paymentState.asStateFlow()

    private val _subscriptionState = MutableStateFlow<SubscriptionStatus>(SubscriptionStatus.Unknown)
    val subscriptionState: StateFlow<SubscriptionStatus> = _subscriptionState.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── Credit Purchase ───────────────────────────────────────────────────────

    /**
     * Create a Stripe Checkout Session for [pack] and open it in the browser.
     * Returns the Checkout URL, or emits [PaymentState.Failed] on error.
     */
    suspend fun purchaseCredits(pack: CreditPackage): String? = withContext(Dispatchers.IO) {
        _paymentState.value = PaymentState.Processing
        try {
            val customerId = getOrCreateCustomerId()
            val body = JSONObject().apply {
                put("product_id",   pack.stripeProductId)
                put("customer_id",  customerId)
                put("success_url",  "$STRIPE_REDIRECT?type=credits&product=${pack.stripeProductId}")
                put("cancel_url",   "airi://stripe/cancel")
                put("metadata", JSONObject().apply {
                    put("credits",      pack.totalCredits)
                    put("package_name", pack.displayName)
                })
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$BACKEND_URL/checkout-session")
                .post(body)
                .addHeader("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val json     = JSONObject(response.body?.string() ?: "{}")
            val url      = json.optString("checkout_url")

            if (url.isBlank()) {
                _paymentState.value = PaymentState.Failed("Could not create checkout session. Try again.")
                return@withContext null
            }

            _paymentState.value = PaymentState.CheckoutReady(url)
            Log.d(TAG, "Checkout session created for ${pack.displayName}")
            url
        } catch (e: Exception) {
            Log.e(TAG, "purchaseCredits failed: ${e.message}")
            _paymentState.value = PaymentState.Failed("Payment error: ${e.message}")
            null
        }
    }

    /**
     * Create a Stripe Checkout Session for a Premium subscription.
     * [annual] = true for the annual plan (20% savings vs monthly).
     */
    suspend fun purchaseSubscription(annual: Boolean = false): String? = withContext(Dispatchers.IO) {
        _paymentState.value = PaymentState.Processing
        try {
            val priceId    = if (annual) CreditPackage.STRIPE_PRICE_PREMIUM_ANNUAL
                             else         CreditPackage.STRIPE_PRICE_PREMIUM_MONTHLY
            val customerId = getOrCreateCustomerId()
            val body = JSONObject().apply {
                put("price_id",    priceId)
                put("customer_id", customerId)
                put("success_url", "$STRIPE_REDIRECT?type=subscription&annual=$annual")
                put("cancel_url",  "airi://stripe/cancel")
                put("mode",        "subscription")
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$BACKEND_URL/checkout-session")
                .post(body)
                .addHeader("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val json     = JSONObject(response.body?.string() ?: "{}")
            val url      = json.optString("checkout_url")

            if (url.isBlank()) {
                _paymentState.value = PaymentState.Failed("Could not create subscription checkout.")
                return@withContext null
            }
            _paymentState.value = PaymentState.CheckoutReady(url)
            url
        } catch (e: Exception) {
            Log.e(TAG, "purchaseSubscription failed: ${e.message}")
            _paymentState.value = PaymentState.Failed("Subscription error: ${e.message}")
            null
        }
    }

    // ── Payment Validation ────────────────────────────────────────────────────

    /**
     * Validate a completed Stripe Checkout Session.
     * Called from the deep-link handler after `airi://stripe/success?session_id=...`
     */
    suspend fun validateCheckoutSession(
        sessionId: String,
        type:      String,
        productId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        _paymentState.value = PaymentState.Processing
        try {
            val request = Request.Builder()
                .url("$BACKEND_URL/validate-session?session_id=$sessionId")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val json     = JSONObject(response.body?.string() ?: "{}")
            val status   = json.optString("payment_status")
            val amount   = json.optInt("amount_total", 0)
            val piId     = json.optString("payment_intent")

            return@withContext when (status) {
                "paid" -> {
                    when (type) {
                        "credits" -> {
                            val pack = productId?.let { CreditPackage.fromProductId(it) }
                            if (pack != null) {
                                grantCredits(pack, amount, piId)
                            }
                        }
                        "subscription" -> {
                            grantSubscription(amount, piId, json.optBoolean("annual", false))
                        }
                    }
                    Log.d(TAG, "Payment validated: type=$type sessionId=$sessionId")
                    true
                }
                "unpaid" -> {
                    _paymentState.value = PaymentState.Pending("Payment is being processed. Credits will appear shortly.")
                    false
                }
                else -> {
                    _paymentState.value = PaymentState.Failed("Payment validation failed. Contact support with ID: $sessionId")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "validateCheckoutSession failed: ${e.message}")
            _paymentState.value = PaymentState.Failed("Validation error: ${e.message}")
            false
        }
    }

    // ── Grant entitlements ────────────────────────────────────────────────────

    private fun grantCredits(pack: CreditPackage, amountCents: Int, stripeId: String) {
        val record = billingHistory.record(
            type            = BillingRecord.RecordType.CREDIT_PURCHASE,
            productId       = pack.stripeProductId,
            description     = "${pack.displayName} — ${pack.totalCredits} credits",
            amountUsdCents  = amountCents,
            credits         = pack.totalCredits,
            status          = BillingRecord.Status.SUCCEEDED,
            stripePaymentId = stripeId
        )
        // Credits are tracked by CreditMeteringEngine. Signal via GenericInfo event.
        com.airi.assistant.domain.event.EventBus.emitSync(
            com.airi.assistant.domain.event.AppEvent.GenericInfo("credits_top_up:${pack.totalCredits}")
        )
        _paymentState.value = PaymentState.Success(record)
        Log.d(TAG, "Granted ${pack.totalCredits} credits via ${pack.displayName}")
    }

    private fun grantSubscription(amountCents: Int, stripeId: String, annual: Boolean) {
        val record = billingHistory.record(
            type            = BillingRecord.RecordType.SUBSCRIPTION_START,
            productId       = if (annual) CreditPackage.STRIPE_PRODUCT_PREMIUM_ANNUAL
                              else CreditPackage.STRIPE_PRODUCT_PREMIUM_MONTHLY,
            description     = "AIRI Premium — ${if (annual) "Annual" else "Monthly"}",
            amountUsdCents  = amountCents,
            credits         = 0,
            status          = BillingRecord.Status.SUCCEEDED,
            stripePaymentId = stripeId
        )
        subscriptionManager.setTier(SubscriptionTier.PREMIUM)
        _paymentState.value = PaymentState.Success(record)
        Log.d(TAG, "Premium subscription activated (annual=$annual)")
    }

    // ── Customer management ───────────────────────────────────────────────────

    private suspend fun getOrCreateCustomerId(): String {
        val stored = authManager.getCredential("stripe", CUSTOMER_ID_KEY)
        if (!stored.isNullOrBlank()) return stored

        // Create a new Stripe Customer via backend
        val body = JSONObject().apply {
            put("description", "AIRI Android user")
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$BACKEND_URL/customers")
            .post(body)
            .build()
        return try {
            val response = client.newCall(request).execute()
            val json     = JSONObject(response.body?.string() ?: "{}")
            val id       = json.optString("customer_id")
            if (id.isNotBlank()) {
                authManager.storeCredential("stripe", CUSTOMER_ID_KEY, id)
            }
            id.ifBlank { "anonymous" }
        } catch (e: Exception) {
            Log.w(TAG, "Customer creation failed: ${e.message}")
            "anonymous"
        }
    }

    fun resetState() { _paymentState.value = PaymentState.Idle }

    // ── Subscription status ───────────────────────────────────────────────────

    enum class SubscriptionStatus { Unknown, Active, Cancelled, PastDue, Inactive }

    suspend fun fetchSubscriptionStatus(): SubscriptionStatus = withContext(Dispatchers.IO) {
        val customerId = authManager.getCredential("stripe", CUSTOMER_ID_KEY) ?: return@withContext SubscriptionStatus.Unknown
        try {
            val request = Request.Builder()
                .url("$BACKEND_URL/subscriptions?customer_id=$customerId")
                .get()
                .build()
            val json   = JSONObject(client.newCall(request).execute().body?.string() ?: "{}")
            val status = when (json.optString("status")) {
                "active"   -> SubscriptionStatus.Active
                "canceled" -> SubscriptionStatus.Cancelled
                "past_due" -> SubscriptionStatus.PastDue
                else       -> SubscriptionStatus.Inactive
            }
            _subscriptionState.value = status
            status
        } catch (e: Exception) {
            SubscriptionStatus.Unknown
        }
    }
}
