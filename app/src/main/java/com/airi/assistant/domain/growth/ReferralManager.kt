package com.airi.assistant.domain.growth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.domain.logging.LoggingService
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

object ReferralManager {
    private const val TAG = "ReferralManager"
    private const val PREFS_NAME = "airi_referrals"
    private const val KEY_CODE = "referral_code"
    private const val KEY_PENDING_CODE = "pending_referral_code"
    private const val KEY_JOINED_CODES = "joined_codes"
    private const val KEY_BONUS_MESSAGES = "bonus_messages"
    private const val KEY_SHARE_BONUS_GRANTED       = "share_bonus_granted"
    // B-10: one-time welcome grant so new users never see "Bonus messages: 0" on first open
    private const val KEY_FIRST_LAUNCH_BONUS_GRANTED = "first_launch_bonus_granted"
    private const val FIRST_LAUNCH_BONUS             = 20
    private const val BONUS_MESSAGES = 20

    private var context: Context? = null

    fun init(appContext: Context) {
        context = appContext.applicationContext
    }

    /**
     * B-10 FIX: Grant a one-time welcome bonus on first login so new users
     * never see "Bonus messages available: 0" when they first open ReferralScreen.
     * Safe to call multiple times — idempotent via KEY_FIRST_LAUNCH_BONUS_GRANTED flag.
     */
    fun grantFirstLaunchBonus() {
        val prefs = prefs() ?: return
        if (!prefs.getBoolean(KEY_FIRST_LAUNCH_BONUS_GRANTED, false)) {
            addBonusMessages(FIRST_LAUNCH_BONUS)
            prefs.edit().putBoolean(KEY_FIRST_LAUNCH_BONUS_GRANTED, true).apply()
            LoggingService.info(TAG, "First-launch bonus granted: $FIRST_LAUNCH_BONUS messages")
        }
    }

    fun captureReferralIntent(intent: Intent?) {
        val data = intent?.data ?: return
        val code = parseCode(data) ?: return
        val prefs = prefs() ?: return
        prefs.edit().putString(KEY_PENDING_CODE, code).apply()
        LoggingService.info(TAG, "Captured referral code $code")
    }

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

    fun onReferralSent(channel: String, userId: String?) {
        val code = getOrCreateCode(userId)
        val prefs = prefs() ?: return
        if (!prefs.getBoolean(KEY_SHARE_BONUS_GRANTED, false)) {
            addBonusMessages(BONUS_MESSAGES)
            prefs.edit().putBoolean(KEY_SHARE_BONUS_GRANTED, true).apply()
        }
        AnalyticsService.referralSent(channel, code)
    }

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

    fun consumeBonusUsage(): Boolean {
        val prefs = prefs() ?: return false
        val current = prefs.getInt(KEY_BONUS_MESSAGES, 0)
        if (current <= 0) return false
        prefs.edit().putInt(KEY_BONUS_MESSAGES, current - 1).apply()
        return true
    }

    fun getBonusMessages(): Int = prefs()?.getInt(KEY_BONUS_MESSAGES, 0) ?: 0

    fun isValidCode(code: String): Boolean = code.matches(Regex("^[A-Z0-9]{8}$"))

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

    private fun prefs() = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}