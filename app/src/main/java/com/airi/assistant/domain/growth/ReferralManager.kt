package com.airi.assistant.domain.growth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.domain.logging.LoggingService
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * ReferralManager — SHA-256 referral code generation and bonus-credit accounting.
 *
 * ── Phase 2, Task 13: SecureStorage Migration ─────────────────────────────────
 * Previously used plaintext [SharedPreferences] (`airi_referrals`). On rooted
 * devices any process can read and modify this file, enabling trivial bonus-message
 * fraud (self-grant or replay).
 *
 * All preferences are now stored in [EncryptedSharedPreferences] backed by the
 * Android Keystore. On first launch after this migration, any existing plaintext
 * values are read from the legacy file, written to the encrypted store, and the
 * legacy file is cleared — matching the migration pattern in [ExecModePreferences].
 *
 * If the Keystore is unavailable (broken TEE, simulator without Keystore emulation),
 * [buildPrefs] logs a warning and falls back to plaintext so the app remains
 * functional. The fallback is intentional: a broken audit trail must never block
 * the main execution path (same philosophy as [AuditRepository]).
 *
 * ── Key contents ─────────────────────────────────────────────────────────────
 * referral_code              — user's own SHA-256-derived 8-char code
 * pending_referral_code      — code captured from deep link, pending redemption
 * joined_codes               — StringSet of codes already redeemed (de-duplication)
 * bonus_messages             — Int count of bonus turns available
 * share_bonus_granted        — Boolean, one-time share bonus guard
 * first_launch_bonus_granted — Boolean, one-time welcome bonus guard
 */
object ReferralManager {
    private const val TAG = "ReferralManager"

    private const val LEGACY_PREFS_NAME  = "airi_referrals"
    private const val SECURE_PREFS_NAME  = "airi_referrals_secure"
    private const val MIGRATION_DONE_KEY = "migrated_to_secure_v1"

    private const val KEY_CODE                      = "referral_code"
    private const val KEY_PENDING_CODE              = "pending_referral_code"
    private const val KEY_JOINED_CODES              = "joined_codes"
    private const val KEY_BONUS_MESSAGES            = "bonus_messages"
    private const val KEY_SHARE_BONUS_GRANTED       = "share_bonus_granted"
    private const val KEY_FIRST_LAUNCH_BONUS_GRANTED = "first_launch_bonus_granted"

    private const val FIRST_LAUNCH_BONUS = 20
    private const val BONUS_MESSAGES     = 20

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        migrateIfNeeded(context.applicationContext)
    }

    // ── Migration: plaintext → EncryptedSharedPreferences ────────────────────

    /**
     * One-time migration from the legacy plaintext `airi_referrals` file to the
     * encrypted `airi_referrals_secure` store. Idempotent — guarded by
     * [MIGRATION_DONE_KEY] in the encrypted store.
     */
    private fun migrateIfNeeded(context: Context) {
        val secure = buildEncryptedPrefs(context) ?: return
        if (secure.getBoolean(MIGRATION_DONE_KEY, false)) return

        val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val editor = secure.edit()
        var migrated = 0

        legacy.getString(KEY_CODE, null)?.let {
            editor.putString(KEY_CODE, it); migrated++
        }
        legacy.getString(KEY_PENDING_CODE, null)?.let {
            editor.putString(KEY_PENDING_CODE, it); migrated++
        }
        legacy.getStringSet(KEY_JOINED_CODES, null)?.let {
            editor.putStringSet(KEY_JOINED_CODES, it); migrated++
        }
        if (legacy.contains(KEY_BONUS_MESSAGES)) {
            editor.putInt(KEY_BONUS_MESSAGES, legacy.getInt(KEY_BONUS_MESSAGES, 0)); migrated++
        }
        if (legacy.contains(KEY_SHARE_BONUS_GRANTED)) {
            editor.putBoolean(KEY_SHARE_BONUS_GRANTED,
                legacy.getBoolean(KEY_SHARE_BONUS_GRANTED, false)); migrated++
        }
        if (legacy.contains(KEY_FIRST_LAUNCH_BONUS_GRANTED)) {
            editor.putBoolean(KEY_FIRST_LAUNCH_BONUS_GRANTED,
                legacy.getBoolean(KEY_FIRST_LAUNCH_BONUS_GRANTED, false)); migrated++
        }

        editor.putBoolean(MIGRATION_DONE_KEY, true).apply()

        // Clear the plaintext file after successful migration.
        if (migrated > 0) {
            legacy.edit().clear().apply()
            LoggingService.info(TAG, "AIRI_RUNTIME REFERRAL_PREFS_MIGRATED keys=$migrated")
        }
    }

    // ── Bonus accounting ──────────────────────────────────────────────────────

    /**
     * B-10 FIX: Grant a one-time welcome bonus on first login so new users
     * never see "Bonus messages available: 0" when they first open ReferralScreen.
     * Safe to call multiple times — idempotent via [KEY_FIRST_LAUNCH_BONUS_GRANTED].
     */
    fun grantFirstLaunchBonus() {
        val prefs = prefs() ?: return
        if (!prefs.getBoolean(KEY_FIRST_LAUNCH_BONUS_GRANTED, false)) {
            addBonusMessages(FIRST_LAUNCH_BONUS)
            prefs.edit().putBoolean(KEY_FIRST_LAUNCH_BONUS_GRANTED, true).apply()
            LoggingService.info(TAG, "First-launch bonus granted: $FIRST_LAUNCH_BONUS messages")
        }
    }

    // ── Deep-link capture ─────────────────────────────────────────────────────

    fun captureReferralIntent(intent: Intent?) {
        val data = intent?.data ?: return
        val code = parseCode(data) ?: return
        val prefs = prefs() ?: return
        prefs.edit().putString(KEY_PENDING_CODE, code).apply()
        LoggingService.info(TAG, "Captured referral code $code")
    }

    // ── Code generation ───────────────────────────────────────────────────────

    fun getOrCreateCode(userId: String?): String {
        val prefs = prefs() ?: return generateCode(userId ?: UUID.randomUUID().toString())
        val existing = prefs.getString(KEY_CODE, null)
        if (!existing.isNullOrBlank()) return existing
        val code = generateCode(userId ?: UUID.randomUUID().toString())
        prefs.edit().putString(KEY_CODE, code).apply()
        return code
    }

    fun createShareText(userId: String?): String {
        val code = getOrCreateCode(userId)
        return "Try AIRI, the on-device AI assistant that can chat, use voice, and run smart agents.\n\nUse my referral code: $code\nOpen: ${createReferralLink(code)}\n\nairi://referral?code=$code"
    }

    fun createReferralLink(code: String): String = "https://airi.app/referral?code=$code"

    // ── Share bonus ───────────────────────────────────────────────────────────

    fun onReferralSent(channel: String, userId: String?) {
        val code = getOrCreateCode(userId)
        val prefs = prefs() ?: return
        if (!prefs.getBoolean(KEY_SHARE_BONUS_GRANTED, false)) {
            addBonusMessages(BONUS_MESSAGES)
            prefs.edit().putBoolean(KEY_SHARE_BONUS_GRANTED, true).apply()
        }
        AnalyticsService.referralSent(channel, code)
    }

    // ── Referral redemption ───────────────────────────────────────────────────

    fun completePendingReferral(userId: String?) {
        val prefs = prefs() ?: return
        val pendingCode = prefs.getString(KEY_PENDING_CODE, null)?.trim()?.uppercase(Locale.US) ?: return
        if (pendingCode.isBlank()) return
        val ownCode = getOrCreateCode(userId)
        val joinedCodes = prefs.getStringSet(KEY_JOINED_CODES, emptySet()) ?: emptySet()
        if (pendingCode == ownCode || joinedCodes.contains(pendingCode)) {
            prefs.edit().remove(KEY_PENDING_CODE).apply()
            return
        }
        val updated = joinedCodes.toMutableSet().apply { add(pendingCode) }
        prefs.edit()
            .putStringSet(KEY_JOINED_CODES, updated)
            .remove(KEY_PENDING_CODE)
            .apply()
        addBonusMessages(BONUS_MESSAGES)
        AnalyticsService.referralJoined(pendingCode)
    }

    fun redeemCode(code: String, userId: String?): Boolean {
        val normalized = code.trim().uppercase(Locale.US)
        if (!isValidCode(normalized)) return false
        val prefs = prefs() ?: return false
        prefs.edit().putString(KEY_PENDING_CODE, normalized).apply()
        completePendingReferral(userId)
        return true
    }

    // ── Consumption ───────────────────────────────────────────────────────────

    fun consumeBonusUsage(): Boolean {
        val prefs = prefs() ?: return false
        val current = prefs.getInt(KEY_BONUS_MESSAGES, 0)
        if (current <= 0) return false
        prefs.edit().putInt(KEY_BONUS_MESSAGES, current - 1).apply()
        return true
    }

    fun getBonusMessages(): Int = prefs()?.getInt(KEY_BONUS_MESSAGES, 0) ?: 0

    fun isValidCode(code: String): Boolean = code.matches(Regex("^[A-Z0-9]{8}$"))

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun addBonusMessages(amount: Int) {
        val prefs = prefs() ?: return
        val current = prefs.getInt(KEY_BONUS_MESSAGES, 0)
        prefs.edit().putInt(KEY_BONUS_MESSAGES, current + amount).apply()
    }

    private fun parseCode(uri: Uri): String? {
        val code = uri.getQueryParameter("code") ?: uri.lastPathSegment
        return code?.trim()?.uppercase(Locale.US)?.takeIf { isValidCode(it) }
    }

    private fun generateCode(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return digest.take(6).fold(StringBuilder()) { builder, byte ->
            val value = byte.toInt() and 0xFF
            builder.append(alphabet[value % alphabet.length])
            builder.append(alphabet[(value / alphabet.length) % alphabet.length])
        }.toString().take(8)
    }

    /**
     * Returns the active [SharedPreferences] — encrypted when the Keystore is
     * available, plaintext otherwise (logged as a security warning).
     */
    private fun prefs(): SharedPreferences? {
        val ctx = appContext ?: return null
        return buildEncryptedPrefs(ctx)
            ?: ctx.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Attempt to build [EncryptedSharedPreferences]. Returns null on any
     * Keystore failure so callers can fall back to the plaintext store.
     */
    private fun buildEncryptedPrefs(context: Context): SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.onFailure { e ->
        Log.w(TAG, "AIRI_RUNTIME REFERRAL_PREFS_ENCRYPT_FAILED — falling back to plaintext: ${e.message}")
    }.getOrNull()
}
