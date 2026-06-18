package com.airi.assistant.billing

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * BillingHistoryStore — persistent local billing record store.
 *
 * Persists all billing events (credit purchases, subscription changes) to
 * SharedPreferences as a JSON array. This gives the user an always-available
 * offline record of their transactions, independent of payment provider
 * availability.
 *
 * DESIGN CHOICES:
 *  - SharedPreferences JSON is sufficient for < 1000 records. If the user
 *    has more than MAX_RECORDS purchases, oldest records are pruned.
 *  - Records are never deleted by the user — only pruned by age limit.
 *  - [historyFlow] emits whenever the store changes, driving Compose recompose.
 *  - Stripe PaymentIntents are stored with their `pi_...` IDs for dispute reference.
 */
class BillingHistoryStore(context: Context) {

    companion object {
        private const val TAG         = "BillingHistoryStore"
        private const val PREFS_NAME  = "airi_billing_history"
        private const val KEY_RECORDS = "records_v1"
        private const val MAX_RECORDS = 500
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _historyFlow = MutableStateFlow<List<BillingRecord>>(emptyList())
    val historyFlow: StateFlow<List<BillingRecord>> = _historyFlow.asStateFlow()

    init { _historyFlow.value = loadAll() }

    // ── Write ─────────────────────────────────────────────────────────────────

    fun record(
        type:            BillingRecord.RecordType,
        productId:       String,
        description:     String,
        amountUsdCents:  Int,
        credits:         Int          = 0,
        status:          BillingRecord.Status = BillingRecord.Status.SUCCEEDED,
        stripePaymentId: String?      = null
    ): BillingRecord {
        val record = BillingRecord(
            id              = UUID.randomUUID().toString(),
            timestampMs     = System.currentTimeMillis(),
            type            = type,
            productId       = productId,
            description     = description,
            amountUsdCents  = amountUsdCents,
            credits         = credits,
            status          = status,
            stripePaymentId = stripePaymentId
        )
        val records = loadAll().toMutableList().also { it.add(0, record) }
        saveAll(records.take(MAX_RECORDS))
        return record
    }

    fun updateStatus(id: String, status: BillingRecord.Status) {
        val records = loadAll().map { if (it.id == id) it.copy(status = status) else it }
        saveAll(records)
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    fun getAll():    List<BillingRecord> = _historyFlow.value
    fun getById(id: String): BillingRecord? = _historyFlow.value.firstOrNull { it.id == id }

    fun totalSpentCents():  Int  = _historyFlow.value.filter { it.isSuccess }.sumOf { it.amountUsdCents }
    fun totalCreditsBought(): Int = _historyFlow.value.filter { it.isSuccess && it.credits > 0 }.sumOf { it.credits }

    // ── Serialisation ─────────────────────────────────────────────────────────

    private fun loadAll(): List<BillingRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { arr.getJSONObject(i).toRecord() }.getOrNull()
            }
        }.getOrElse {
            Log.e(TAG, "Failed to parse billing history: ${it.message}")
            emptyList()
        }
    }

    private fun saveAll(records: List<BillingRecord>) {
        val arr = JSONArray()
        records.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_RECORDS, arr.toString()).apply()
        _historyFlow.value = records
    }

    private fun BillingRecord.toJson() = JSONObject().apply {
        put("id",               id)
        put("timestamp_ms",     timestampMs)
        put("type",             type.name)
        put("product_id",       productId)
        put("description",      description)
        put("amount_usd_cents", amountUsdCents)
        put("credits",          credits)
        put("status",           status.name)
        put("stripe_payment_id", stripePaymentId ?: JSONObject.NULL)
    }

    private fun JSONObject.toRecord() = BillingRecord(
        id              = getString("id"),
        timestampMs     = getLong("timestamp_ms"),
        type            = BillingRecord.RecordType.valueOf(getString("type")),
        productId       = getString("product_id"),
        description     = getString("description"),
        amountUsdCents  = getInt("amount_usd_cents"),
        credits         = optInt("credits", 0),
        status          = BillingRecord.Status.valueOf(optString("status", "SUCCEEDED")),
        stripePaymentId = optString("stripe_payment_id").takeIf { it.isNotBlank() && it != "null" }
    )
}
