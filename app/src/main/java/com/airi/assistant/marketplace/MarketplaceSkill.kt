package com.airi.assistant.marketplace

import org.json.JSONArray
import org.json.JSONObject

/**
 * MarketplaceSkill — the canonical data model for a skill in the AIRI
 * Developer Marketplace.
 *
 * A marketplace skill is a superset of [com.airi.assistant.domain.customskill.CustomSkill]:
 * it adds publisher metadata, version history, trust signals (review count,
 * verification badge, sandbox pass rate), and install tracking.
 */
data class MarketplaceSkill(
    val id:               String,
    val name:             String,
    val description:      String,
    val publisher:        Publisher,
    val category:         Category,
    val version:          String,
    val tags:             List<String>    = emptyList(),
    val iconUrl:          String?         = null,
    val repositoryUrl:    String?         = null,
    val documentationUrl: String?         = null,
    val skillJsonUrl:     String,          // raw URL to skill.json definition
    val stats:            Stats           = Stats(),
    val trust:            TrustSignals    = TrustSignals(),
    val publishedAtMs:    Long            = 0L,
    val updatedAtMs:      Long            = 0L,
    val isVerified:       Boolean         = false,
    val isFeatured:       Boolean         = false,
    val isInstalled:      Boolean         = false,
    val installedVersion: String?         = null,
    val hasUpdate:        Boolean         = false
) {
    data class Publisher(
        val id:          String,
        val displayName: String,
        val avatarUrl:   String?  = null,
        val isVerified:  Boolean  = false,
        val website:     String?  = null
    )

    data class Stats(
        val installCount:    Int   = 0,
        val reviewCount:     Int   = 0,
        val averageRating:   Float = 0f,   // 0.0 – 5.0
        val weeklyInstalls:  Int   = 0
    )

    data class TrustSignals(
        val sandboxPassRate:    Float  = 0f,   // 0.0 – 1.0
        val securityScanPassed: Boolean = false,
        val isOpenSource:       Boolean = false,
        val trustScore:         Int    = 0     // 0 – 100
    )

    enum class Category(val label: String, val emoji: String) {
        PRODUCTIVITY ("Productivity", ""),
        COMMUNICATION("Communication", ""),
        DEVELOPER    ("Developer",     ""),
        DATA         ("Data & Analytics", ""),
        CREATIVITY   ("Creativity",    ""),
        FINANCE      ("Finance",       ""),
        AUTOMATION   ("Automation",    ""),
        AI           ("AI & ML",       ""),
        UTILITY      ("Utilities",     ""),
        OTHER        ("Other",         "")
    }

    val ratingStars: String get() = "".repeat(averageRatingInt) + "".repeat(5 - averageRatingInt)
    private val averageRatingInt: Int get() = stats.averageRating.toInt().coerceIn(0, 5)
    val isOutdated: Boolean get() = isInstalled && hasUpdate
    val displayVersion: String get() = "v$version"

    companion object {
        fun fromJson(json: JSONObject): MarketplaceSkill = MarketplaceSkill(
            id               = json.getString("id"),
            name             = json.getString("name"),
            description      = json.optString("description"),
            publisher        = json.getJSONObject("publisher").let { p ->
                Publisher(
                    id          = p.getString("id"),
                    displayName = p.getString("display_name"),
                    avatarUrl   = p.optString("avatar_url").ifBlank { null },
                    isVerified  = p.optBoolean("is_verified"),
                    website     = p.optString("website").ifBlank { null }
                )
            },
            category         = runCatching {
                Category.valueOf(json.optString("category", "OTHER").uppercase())
            }.getOrDefault(Category.OTHER),
            version          = json.optString("version", "1.0.0"),
            tags             = json.optJSONArray("tags")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
            iconUrl          = json.optString("icon_url").ifBlank { null },
            repositoryUrl    = json.optString("repository_url").ifBlank { null },
            documentationUrl = json.optString("documentation_url").ifBlank { null },
            skillJsonUrl     = json.getString("skill_json_url"),
            stats            = json.optJSONObject("stats")?.let { s ->
                Stats(
                    installCount   = s.optInt("install_count"),
                    reviewCount    = s.optInt("review_count"),
                    averageRating  = s.optDouble("average_rating", 0.0).toFloat(),
                    weeklyInstalls = s.optInt("weekly_installs")
                )
            } ?: Stats(),
            trust            = json.optJSONObject("trust")?.let { t ->
                TrustSignals(
                    sandboxPassRate    = t.optDouble("sandbox_pass_rate", 0.0).toFloat(),
                    securityScanPassed = t.optBoolean("security_scan_passed"),
                    isOpenSource       = t.optBoolean("is_open_source"),
                    trustScore         = t.optInt("trust_score")
                )
            } ?: TrustSignals(),
            publishedAtMs    = json.optLong("published_at_ms"),
            updatedAtMs      = json.optLong("updated_at_ms"),
            isVerified       = json.optBoolean("is_verified"),
            isFeatured       = json.optBoolean("is_featured")
        )
    }
}

/** A user review of a marketplace skill. */
data class SkillReview(
    val id:            String,
    val skillId:       String,
    val reviewerName:  String,
    val rating:        Int,         // 1 – 5
    val body:          String,
    val timestampMs:   Long,
    val helpfulCount:  Int  = 0,
    val isVerifiedPurchase: Boolean = false
) {
    val ratingStars: String get() = "".repeat(rating.coerceIn(1,5)) + "".repeat(5 - rating.coerceIn(1,5))
}
