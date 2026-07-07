package com.airi.assistant.connector.api

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * T31 — Certificate-pinning infrastructure for all LLM API endpoints.
 *
 * Pins are SHA-256 of the SubjectPublicKeyInfo DER (SPKI).
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ IMPORTANT — Certificate rotation procedure documented in docs/RUNBOOK.md│
 * │ Re-verify each pin against a live connection before releasing:          │
 * │   openssl s_client -connect <host>:443 </dev/null \                    │
 * │     | openssl x509 -pubkey -noout \                                    │
 * │     | openssl pkey -pubin -outform der \                               │
 * │     | openssl dgst -sha256 -binary | base64                            │
 * │                                                                         │
 * │ Each host has two pins: one for the current intermediate cert and       │
 * │ one backup so that certificate rotation does NOT lock users out.        │
 * │                                                                         │
 * │ Pin expiry (from Q2 2026 chain audit):                                  │
 * │   api.openai.com                  — 2027-02 (DigiCert SHA2 chain)      │
 * │   api.anthropic.com               — 2027-06 (Amazon RSA 2048 chain)    │
 * │   generativelanguage.googleapis.com — 2026-12 (GTS CA 1C3 chain)      │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
object LlmCertPins {

    /**
     * Master switch for certificate pinning.
     * Enabled: real SPKI hashes verified against live connections (Q2 2026 audit).
     */
    const val PINNING_ENABLED = true

    // ── OpenAI (api.openai.com) ───────────────────────────────────────────────
    // Primary:  DigiCert SHA-2 Secure Server CA intermediate (expires 2027-02)
    // Backup:   DigiCert Global Root CA (long-lived root backup)
    private const val OPENAI_PIN_PRIMARY = "sha256/DiQYz/e/WEFVBW+LBMaLyoChJcO/5DAkE7m3S3D1h8k="
    private const val OPENAI_PIN_BACKUP  = "sha256/r/mIkG3eEpVdm+u/ko/cwxzOMo1bk4TyHIlByibiA5E="

    // ── Anthropic (api.anthropic.com) ─────────────────────────────────────────
    // Primary:  Amazon RSA 2048 M01 intermediate (expires 2027-06)
    // Backup:   Amazon Root CA 1 (long-lived root backup)
    private const val ANTHROPIC_PIN_PRIMARY = "sha256/++MBgDH5WGvL9Bcn5Be30cRcL0f5O+NyoXuWtQdX1aI="
    private const val ANTHROPIC_PIN_BACKUP  = "sha256/f0KW/FtqTjs108NpYj42SrGvOB2PpxIVM8nWxjPqJGE="

    // ── Google Gemini (generativelanguage.googleapis.com) ─────────────────────
    // Primary:  GTS CA 1C3 intermediate (expires 2026-12)
    // Backup:   GTS Root R1 (long-lived root backup)
    private const val GEMINI_PIN_PRIMARY = "sha256/zCTnfLwLKbS9S2sbp+uFz4KZOocFvXxkV06Ce9O5M2w="
    private const val GEMINI_PIN_BACKUP  = "sha256/hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Vg="

    /**
     * Pinner covering all three LLM API domains.
     * Only referenced when [PINNING_ENABLED] is true.
     */
    private val pinner: CertificatePinner by lazy {
        CertificatePinner.Builder()
            .add("api.openai.com",                    OPENAI_PIN_PRIMARY,    OPENAI_PIN_BACKUP)
            .add("api.anthropic.com",                 ANTHROPIC_PIN_PRIMARY, ANTHROPIC_PIN_BACKUP)
            .add("generativelanguage.googleapis.com", GEMINI_PIN_PRIMARY,    GEMINI_PIN_BACKUP)
            .build()
    }

    /**
     * Build an [OkHttpClient] with optional certificate pinning and the given timeouts.
     *
     * Pinning is applied only when [PINNING_ENABLED] is true. While [PINNING_ENABLED]
     * is false the returned client uses the system trust store (normal TLS verification).
     *
     * Callers may customize further via the [customize] block (e.g. to override timeouts).
     */
    fun pinnedClient(customize: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)

        if (PINNING_ENABLED) {
            builder.certificatePinner(pinner)
        }

        return builder.apply(customize).build()
    }
}
