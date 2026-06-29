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

    // ── OpenAI (api.openai.com) ───────────────────────────────────────────────
    // Primary:  DigiCert SHA-2 Secure Server CA intermediate
    // Backup:   DigiCert Global Root CA
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

    /** Pinner covering all three LLM API domains. */
    val pinner: CertificatePinner = CertificatePinner.Builder()
        .add("api.openai.com",                       OPENAI_PIN_PRIMARY,   OPENAI_PIN_BACKUP)
        .add("api.anthropic.com",                    ANTHROPIC_PIN_PRIMARY, ANTHROPIC_PIN_BACKUP)
        .add("generativelanguage.googleapis.com",    GEMINI_PIN_PRIMARY,   GEMINI_PIN_BACKUP)
        .build()

    /**
     * Build an [OkHttpClient] with:
     *  - certificate pinning for all LLM hosts
     *  - 30 s connect / 90 s read+write timeouts
     *
     * Callers may customize via [OkHttpClient.newBuilder] on the returned instance.
     *
     * NOTE: pins are currently placeholder values. Replace each `sha256/…` constant
     * above with the real SPKI hash before deploying to production. Incorrect pins
     * will block **all** requests to the pinned host with a [javax.net.ssl.SSLPeerUnverifiedException].
     */
    fun pinnedClient(customize: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder()
            .certificatePinner(pinner)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .apply(customize)
            .build()
}
