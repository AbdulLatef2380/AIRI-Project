package com.airi.assistant.ai.skills

import java.security.MessageDigest

/**
 * SkillPackageVerifier — enforces integrity, version compatibility, and trust
 * checks on every skill manifest before install.
 *
 * Checks performed:
 *  1. SHA-256 checksum verification (when declared in manifest)
 *  2. airi_min_version compatibility against the running app version
 *  3. Signature field awareness (full Ed25519 verification is a future milestone)
 *
 * This verifier is called by [GitHubSkillImporter] and [MarketplaceRepository]
 * as a pre-install gate. Failures block installation; warnings are advisory.
 */
object SkillPackageVerifier {

    internal const val AIRI_APP_VERSION = "1.0.0"

    data class VerificationResult(
        val passed:   Boolean,
        val errors:   List<String> = emptyList(),
        val warnings: List<String> = emptyList()
    )

    sealed class VersionCompatResult {
        object Compatible                       : VersionCompatResult()
        data class Incompatible(val reason: String) : VersionCompatResult()
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Compute SHA-256 checksum of a raw JSON string.
     * @return Lowercase hex string (64 chars).
     */
    fun computeChecksum(jsonString: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes  = digest.digest(jsonString.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Verify a raw JSON string against an expected SHA-256 checksum.
     * Returns true when [expectedChecksum] is null/blank (no declared checksum = no failure).
     */
    fun verifyChecksum(jsonString: String, expectedChecksum: String?): Boolean {
        if (expectedChecksum.isNullOrBlank()) return true
        return computeChecksum(jsonString).equals(expectedChecksum.trim(), ignoreCase = true)
    }

    /**
     * Check whether [runningVersion] satisfies the skill's [airiMinVersion] requirement.
     */
    fun checkVersionCompatibility(
        airiMinVersion: String,
        runningVersion: String = AIRI_APP_VERSION
    ): VersionCompatResult {
        if (airiMinVersion.isBlank()) return VersionCompatResult.Compatible
        val required = parseVersion(airiMinVersion)
        val running  = parseVersion(runningVersion)
        return if (compareVersions(running, required) >= 0) {
            VersionCompatResult.Compatible
        } else {
            VersionCompatResult.Incompatible(
                "Skill requires AIRI v$airiMinVersion or later, but this build is v$runningVersion. " +
                "Update AIRI to install this skill."
            )
        }
    }

    /**
     * Run the full verification suite on a [manifest] parsed from [jsonString].
     *
     * @param jsonString  The raw skill.json text (used for checksum computation).
     * @param manifest    The already-parsed [SkillManifest].
     * @return            [VerificationResult] with all errors and warnings.
     */
    fun verify(jsonString: String, manifest: SkillManifest): VerificationResult {
        val errors   = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // 1. Checksum
        if (!manifest.checksum.isNullOrBlank()) {
            if (!verifyChecksum(jsonString, manifest.checksum)) {
                errors.add(
                    "Checksum mismatch — the manifest may have been tampered with. " +
                    "Declared: ${manifest.checksum.take(16)}…  " +
                    "Computed: ${computeChecksum(jsonString).take(16)}…"
                )
            }
        } else {
            warnings.add(
                "No 'checksum' field declared. Adding a SHA-256 checksum improves tamper detection."
            )
        }

        // 2. AIRI version compatibility
        if (manifest.airiMinVersion.isNotBlank()) {
            when (val compat = checkVersionCompatibility(manifest.airiMinVersion)) {
                is VersionCompatResult.Incompatible -> errors.add(compat.reason)
                VersionCompatResult.Compatible      -> Unit
            }
        }

        // 3. Signature awareness (full crypto verification = future milestone)
        if (!manifest.signature.isNullOrBlank()) {
            warnings.add(
                "Manifest declares a 'signature' field. Cryptographic signature verification " +
                "will be enforced in a future AIRI release. The field is recorded but not yet validated."
            )
        }

        // 4. Freshness warning (updatedAt > 2 years ago)
        if (manifest.updatedAt > 0L) {
            val ageMs = System.currentTimeMillis() - manifest.updatedAt
            val twoYearsMs = 2L * 365 * 24 * 3600 * 1000
            if (ageMs > twoYearsMs) {
                warnings.add("Skill has not been updated in over 2 years — consider checking for a maintained fork.")
            }
        }

        return VerificationResult(
            passed   = errors.isEmpty(),
            errors   = errors,
            warnings = warnings
        )
    }

    // ── Version helpers ────────────────────────────────────────────────────────

    private fun parseVersion(version: String): List<Int> =
        version.trim().substringBefore("-").substringBefore("+")
            .split(".")
            .map { it.trim().toIntOrNull() ?: 0 }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        val len = maxOf(a.size, b.size)
        for (i in 0 until len) {
            val diff = (a.getOrElse(i) { 0 }) - (b.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }
}
