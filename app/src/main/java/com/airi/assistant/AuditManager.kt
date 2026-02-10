package com.airi.assistant

import java.security.MessageDigest
import java.time.OffsetDateTime

/**
 * AIRI Audit Manager - Implements an Immutable Audit Trail.
 * Each log entry is linked to the previous one via a SHA-256 hash.
 */
object AuditManager {

    data class AuditEntry(
        val timestamp: String,
        val intent: String,
        val action: String,
        val decision: String, // ALLOWED / DENIED
        val reason: String,
        val policyVersion: String,
        val previousHash: String,
        var currentHash: String = ""
    )

    private val auditLog = mutableListOf<AuditEntry>()
    private var lastHash = "0000000000000000000000000000000000000000000000000000000000000000"

    /**
     * Logs a decision and secures it with a hash chain.
     */
    fun logDecision(result: PolicyEngine.EvaluationResult, intent: String, action: String) {
        val entry = AuditEntry(
            timestamp = result.timestamp,
            intent = intent,
            action = action,
            decision = if (result.isAllowed) "ALLOWED" else "DENIED",
            reason = result.reason,
            policyVersion = result.policyVersion,
            previousHash = lastHash
        )

        entry.currentHash = calculateHash(entry)
        auditLog.add(entry)
        lastHash = entry.currentHash
        
        // In a real production system, this would be written to an append-only file or secure DB
        println("AUDIT LOG: [${entry.decision}] ${entry.intent}:${entry.action} | Hash: ${entry.currentHash}")
    }

    private fun calculateHash(entry: AuditEntry): String {
        val data = "${entry.timestamp}${entry.intent}${entry.action}${entry.decision}${entry.reason}${entry.policyVersion}${entry.previousHash}"
        val bytes = MessageDigest.getInstance("SHA-256").digest(data.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies the integrity of the entire audit trail.
     */
    fun verifyIntegrity(): Boolean {
        var expectedPreviousHash = "0000000000000000000000000000000000000000000000000000000000000000"
        for (entry in auditLog) {
            if (entry.previousHash != expectedPreviousHash) return false
            val recalculatedHash = calculateHash(entry)
            if (entry.currentHash != recalculatedHash) return false
            expectedPreviousHash = entry.currentHash
        }
        return true
    }
}
