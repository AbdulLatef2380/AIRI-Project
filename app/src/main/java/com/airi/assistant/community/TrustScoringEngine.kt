package com.airi.assistant.community

import com.airi.assistant.marketplace.MarketplaceSkill
import kotlin.math.ln
import kotlin.math.min

/**
 * TrustScoringEngine — calculates a 0–100 trust score for community skills.
 *
 * The trust score is a weighted composite of several independent signals:
 *
 * ── SCORING SIGNALS ──────────────────────────────────────────────────────────
 *
 *   Signal                     | Max  | Weight | Description
 *  ─────────────────────────────────────────────────────────────────────────
 *   Verified publisher         |  25  |  high  | AIRI team has validated publisher identity
 *   Security scan passed       |  20  |  high  | Static analysis: no dangerous patterns found
 *   Sandbox pass rate          |  20  |  high  | % of sandbox test runs that succeeded
 *   Open source                |  10  |  med   | Source code is publicly auditable
 *   User reviews               |  10  |  med   | Logarithmic scale: 100+ reviews = max
 *   Average rating             |  10  |  med   | 5-star average, weighted by review count
 *   Install count              |   5  |  low   | Logarithmic scale: 10K+ installs = max
 *  ─────────────────────────────────────────────────────────────────────────
 *   Total                      | 100  |
 *
 * ── TIER THRESHOLDS ──────────────────────────────────────────────────────────
 *   UNVERIFIED  (0–39):   Runs in restricted sandbox with limited capabilities.
 *   BASIC       (40–59):  Standard sandbox. Access to network and file read.
 *   TRUSTED     (60–79):  Relaxed sandbox. Most capabilities available.
 *   VERIFIED    (80–100): Full capabilities. Direct SkillRegistry access.
 */
object TrustScoringEngine {

    enum class TrustTier(
        val label:         String,
        val emoji:         String,
        val minScore:      Int,
        val description:   String,
        val sandboxLevel:  SandboxLevel
    ) {
        UNVERIFIED("Unverified",  "⚠️",  0,  "New or unreviewed skill. Runs in restricted sandbox.", SandboxLevel.RESTRICTED),
        BASIC     ("Basic",       "🔵", 40,  "Community-reviewed. Standard sandbox access.",         SandboxLevel.STANDARD),
        TRUSTED   ("Trusted",     "✅", 60,  "Well-established skill with strong track record.",     SandboxLevel.RELAXED),
        VERIFIED  ("Verified",    "🏅", 80,  "Publisher verified by AIRI. Full capabilities.",       SandboxLevel.FULL);

        companion object {
            fun forScore(score: Int): TrustTier =
                entries.sortedByDescending { it.minScore }.firstOrNull { score >= it.minScore }
                    ?: UNVERIFIED
        }
    }

    enum class SandboxLevel { RESTRICTED, STANDARD, RELAXED, FULL }

    data class TrustBreakdown(
        val totalScore:          Int,
        val tier:                TrustTier,
        val verifiedPublisher:   Int,
        val securityScan:        Int,
        val sandboxPassRate:     Int,
        val openSource:          Int,
        val reviewScore:         Int,
        val ratingScore:         Int,
        val installScore:        Int,
        val signals:             List<SignalDetail>
    ) {
        data class SignalDetail(val label: String, val score: Int, val maxScore: Int, val passed: Boolean)
    }

    /**
     * Calculate the trust score for a [CommunitySkill].
     */
    fun score(skill: CommunitySkill): TrustBreakdown {
        val verifiedPub  = if (skill.publisher.isVerified) 25 else 0
        val secScan      = if (skill.trust.securityScanPassed) 20 else 0
        val sandboxScore = (skill.trust.sandboxPassRate * 20).toInt().coerceIn(0, 20)
        val openSrcScore = if (skill.trust.isOpenSource) 10 else 0
        val reviewScore  = logScore(skill.stats.reviewCount.toDouble(), 100.0, 10)
        val ratingScore  = ratingWeighted(skill.stats.averageRating, skill.stats.reviewCount)
        val installScore = logScore(skill.stats.installCount.toDouble(), 10_000.0, 5)

        val total = verifiedPub + secScan + sandboxScore + openSrcScore + reviewScore + ratingScore + installScore

        return TrustBreakdown(
            totalScore        = total.coerceIn(0, 100),
            tier              = TrustTier.forScore(total),
            verifiedPublisher = verifiedPub,
            securityScan      = secScan,
            sandboxPassRate   = sandboxScore,
            openSource        = openSrcScore,
            reviewScore       = reviewScore,
            ratingScore       = ratingScore,
            installScore      = installScore,
            signals           = listOf(
                TrustBreakdown.SignalDetail("Verified publisher",  verifiedPub,  25, verifiedPub > 0),
                TrustBreakdown.SignalDetail("Security scan",       secScan,      20, secScan > 0),
                TrustBreakdown.SignalDetail("Sandbox pass rate",   sandboxScore, 20, sandboxScore >= 10),
                TrustBreakdown.SignalDetail("Open source",         openSrcScore, 10, openSrcScore > 0),
                TrustBreakdown.SignalDetail("Review count",        reviewScore,  10, reviewScore > 0),
                TrustBreakdown.SignalDetail("Average rating",      ratingScore,  10, ratingScore >= 7),
                TrustBreakdown.SignalDetail("Install count",       installScore, 5,  installScore > 0)
            )
        )
    }

    /** Convenience overload for marketplace skills. */
    fun score(skill: MarketplaceSkill): TrustBreakdown {
        return score(CommunitySkill(
            id          = skill.id,
            name        = skill.name,
            description = skill.description,
            version     = skill.version,
            publisher   = CommunitySkill.Publisher(
                id          = skill.publisher.id,
                displayName = skill.publisher.displayName,
                isVerified  = skill.publisher.isVerified
            ),
            stats       = CommunitySkill.Stats(
                installCount  = skill.stats.installCount,
                reviewCount   = skill.stats.reviewCount,
                averageRating = skill.stats.averageRating
            ),
            trust       = CommunitySkill.TrustSignals(
                sandboxPassRate    = skill.trust.sandboxPassRate,
                securityScanPassed = skill.trust.securityScanPassed,
                isOpenSource       = skill.trust.isOpenSource
            )
        ))
    }

    // ── Scoring math ──────────────────────────────────────────────────────────

    /** Logarithmic scale: value=0→0, value=saturation→max. */
    private fun logScore(value: Double, saturation: Double, max: Int): Int {
        if (value <= 0) return 0
        val ratio = ln(value + 1.0) / ln(saturation + 1.0)
        return (min(ratio, 1.0) * max).toInt().coerceIn(0, max)
    }

    /** Rating score weighted by review count (low count → uncertain → lower score). */
    private fun ratingWeighted(rating: Float, reviewCount: Int): Int {
        if (reviewCount == 0) return 0
        val confidence = min(reviewCount.toDouble() / 10.0, 1.0)  // 10 reviews = full confidence
        return (rating / 5.0 * 10.0 * confidence).toInt().coerceIn(0, 10)
    }
}
