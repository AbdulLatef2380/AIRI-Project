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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        object PaymentIdle                              : PaymentState()
        object Processing                                : PaymentState()
        data class CheckoutReady(val url: String)        : PaymentState()
        data class Success(val record: BillingRecord)    : PaymentState()
        data class Failed(val message: String)           : PaymentState()
        data class Pending(val message: String)          : PaymentState()
    }

    /** B3: serialises validate→dedupe→grant so a re-delivered deep link cannot double-grant. */
    private val grantMutex = Mutex()

    private val _paymentState = MutableStateFlow<PaymentState>(PaymentState.PaymentIdle)
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
     *
     * B3 — the deep link is UNTRUSTED: any app on the device can fire `airi://stripe/success`.
     * Therefore only [sessionId] is taken from it. What was bought (type, product, plan,
     * period end, owner) comes from the SERVER's answer for that session, and the same session
     * can be granted only once. [type] and [productId] are accepted for source compatibility
     * but are ignored (only logged when they disagree with the server).
     *
     * Required backend contract for GET /validate-session?session_id=… (paid sessions):
     *   payment_status, amount_total, payment_intent, customer_id,
     *   mode ("payment" | "subscription"), product_id (credit packs),
     *   annual (subscriptions), current_period_end (subscriptions, epoch SECONDS).
     * Missing fields ⇒ nothing is granted (fail closed).
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun validateCheckoutSession(
        sessionId: String,
        type:      String,
        productId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (sessionId.isBlank() || sessionId.length > 255 || !sessionId.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            _paymentState.value = PaymentState.Failed("Invalid payment reference.")
            return@withContext false
        }
        _paymentState.value = PaymentState.Processing
        try {
            val request = Request.Builder()
                .url("$BACKEND_URL/validate-session?session_id=$sessionId")
                .get()
                .build()

            val json = client.newCall(request).execute().use { JSONObject(it.body?.string() ?: "{}") }
            val status = json.optString("payment_status")

            return@withContext when (status) {
                "paid"   -> grantMutex.withLock { grantPaidSession(sessionId, json, type, productId) }
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

    /** Runs under [grantMutex]. Returns true when the session is (now or already) granted. */
    private suspend fun grantPaidSession(sessionId: String, json: JSONObject, claimedType: String, claimedProduct: String?): Boolean {
        val amount = json.optInt("amount_total", 0)
        val piId   = json.optString("payment_intent")
        // Idempotency key: the payment intent, or the session id when the server sends none.
        val paymentKey = piId.ifBlank { sessionId }

        // 1) Same session already granted → report success, grant nothing again.
        if (billingHistory.getAll().any { it.isSuccess && it.stripePaymentId == paymentKey }) {
            Log.i(TAG, "Session already granted — ignoring duplicate delivery")
            return true
        }

        // 2) The session must belong to THIS user's Stripe customer.
        val myCustomer = authManager.getCredential("stripe", CUSTOMER_ID_KEY)
        val sessionCustomer = json.optString("customer_id")
        if (myCustomer.isNullOrBlank() || sessionCustomer.isBlank() || myCustomer != sessionCustomer) {
            _paymentState.value = PaymentState.Failed("This payment does not belong to this account.")
            return false
        }

        // 3) What was bought is decided by the server's record, never by the link.
        val serverMode = json.optString("mode")
        return when (serverMode) {
            "payment" -> {
                val pack = CreditPackage.fromProductId(json.optString("product_id"))
                if (pack == null) {
                    _paymentState.value = PaymentState.Failed("Payment could not be matched to a product.")
                    return false
                }
                if (amount < pack.priceUsdCents) {
                    Log.w(TAG, "Paid amount below pack price — refusing grant")
                    _paymentState.value = PaymentState.Failed("Paid amount does not match the selected pack.")
                    return false
                }
                if (claimedProduct != null && claimedProduct != pack.stripeProductId) {
                    Log.w(TAG, "Deep link product disagreed with server — using server value")
                }
                grantCredits(pack, amount, paymentKey)
                true
            }
            "subscription" -> {
                val periodEndSec = json.optLong("current_period_end", 0L)
                val expiresAtMs = periodEndSec * 1000L
                if (periodEndSec <= 0L || expiresAtMs <= System.currentTimeMillis()) {
                    _paymentState.value = PaymentState.Failed("Subscription period could not be confirmed.")
                    return false
                }
                if (claimedType != "subscription") {
                    Log.w(TAG, "Deep link type disagreed with server — using server value")
                }
                grantSubscription(amount, paymentKey, json.optBoolean("annual", false), expiresAtMs)
                true
            }
            else -> {
                _paymentState.value = PaymentState.Failed("Payment type could not be confirmed.")
                false
            }
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

    private fun grantSubscription(amountCents: Int, stripeId: String, annual: Boolean, serverExpiresAtMillis: Long) {
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
        subscriptionManager.applyVerifiedEntitlement(SubscriptionTier.PREMIUM, serverExpiresAtMillis)
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

    fun resetState() { _paymentState.value = PaymentState.PaymentIdle }

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
