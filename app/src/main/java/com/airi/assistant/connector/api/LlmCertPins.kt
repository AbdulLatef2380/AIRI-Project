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
 * │ IMPORTANT — Before shipping to production, re-verify each pin against  │
 * │ a live connection:                                                      │
 * │   openssl s_client -connect <host>:443 </dev/null \                    │
 * │     | openssl x509 -pubkey -noout \                                    │
 * │     | openssl pkey -pubin -outform der \                               │
 * │     | openssl dgst -sha256 -binary | base64                            │
 * │                                                                         │
 * │ Each host has two pins: one for the current leaf/intermediate cert and  │
 * │ one backup so that certificate rotation does NOT lock users out.        │
 * │                                                                         │
 * │ Pin expiry (approximate, from Q2 2025 chain audit):                    │
 * │   api.openai.com                  — 2026-03                            │
 * │   api.anthropic.com               — 2026-06                            │
 * │   generativelanguage.googleapis.com — 2025-12                          │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
object LlmCertPins {

    /**
     * Master switch for certificate pinning.
     *
     * ⚠️  MUST remain FALSE until ALL `sha256/…` constants below are replaced with
     * real SPKI hashes verified against live connections (see instructions in the
     * class-level KDoc). Activating pinning with placeholder hashes will throw
     * [javax.net.ssl.SSLPeerUnverifiedException] on every request to the pinned
     * hosts, making ALL LLM API traffic fail.
     *
     * Procedure to enable:
     *   1. Run `openssl s_client -connect <host>:443 </dev/null | …` for each host.
     *   2. Replace the placeholder constants with the real hashes.
     *   3. Set PINNING_ENABLED = true.
     *   4. Test on a real device before shipping.
     */
    const val PINNING_ENABLED = false

    // ── OpenAI (api.openai.com) ───────────────────────────────────────────────
    // Primary:  DigiCert SHA-2 Secure Server CA intermediate
    // Backup:   DigiCert Global Root CA
    // Replace these before enabling PINNING_ENABLED:
    private const val OPENAI_PIN_PRIMARY = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private const val OPENAI_PIN_BACKUP  = "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="

    // ── Anthropic (api.anthropic.com) ─────────────────────────────────────────
    // Primary:  Amazon RSA 2048 M01 intermediate
    // Backup:   Amazon Root CA 1
    private const val ANTHROPIC_PIN_PRIMARY = "sha256/CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC="
    private const val ANTHROPIC_PIN_BACKUP  = "sha256/DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD="

    // ── Google Gemini (generativelanguage.googleapis.com) ─────────────────────
    // Primary:  GTS CA 1C3 intermediate
    // Backup:   GTS Root R1
    private const val GEMINI_PIN_PRIMARY = "sha256/EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE="
    private const val GEMINI_PIN_BACKUP  = "sha256/FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF="

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
