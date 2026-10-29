package com.airi.assistant.domain.customskill

import android.content.Context
import com.airi.assistant.ai.skills.SkillManifest
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Durable, user-decision-bound store for skills proposed by AIRI.
 * A proposal is never returned by the runtime registry until [accept] succeeds.
 */
class SkillProposalStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson: Gson = GsonBuilder().create()

    fun create(
        skill: CustomSkill,
        manifest: SkillManifest,
        source: ProposalSource,
        sourceUrl: String? = null,
        warnings: List<String> = emptyList(),
        risks: List<String> = defaultRisks(skill, manifest),
    ): SkillProposal {
        val proposal = SkillProposal(
            id = "skill-proposal-${UUID.randomUUID()}",
            status = ProposalStatus.AWAITING_USER_DECISION,
            skill = skill.copy(config = skill.config.copy(headers = CustomSkillCrypto.encryptSensitiveHeaders(skill.config.headers))),
            manifest = manifest,
            source = source,
            sourceUrl = sourceUrl,
            warnings = warnings,
            risks = risks,
            createdAt = System.currentTimeMillis(),
            validatedAt = System.currentTimeMillis(),
        )
        val updated = load().filterNot { it.id == proposal.id } + proposal
        save(updated)
        return proposal
    }

    fun get(id: String): SkillProposal? = load().firstOrNull { it.id == id }

    fun pending(): List<SkillProposal> = load().filter { it.status == ProposalStatus.AWAITING_USER_DECISION }

    /** Rejecting a proposal must leave no pending artifact that can be activated later. */
    fun reject(id: String): Boolean {
        val current = load()
        if (current.none { it.id == id }) return false
        save(current.filterNot { it.id == id })
        return true
    }

    /** Execute persistence only after the caller has received explicit user approval. */
    fun accept(id: String, persist: (SkillProposal) -> Boolean): Boolean {
        val proposal = get(id) ?: return false
        if (proposal.status != ProposalStatus.AWAITING_USER_DECISION) return false
        val persisted = runCatching { persist(proposal.copy(skill = proposal.skill.copy(config = proposal.skill.config.copy(headers = CustomSkillCrypto.decryptSensitiveHeaders(proposal.skill.config.headers))))) }.getOrDefault(false)
        return if (persisted) updateStatus(id, ProposalStatus.PERSISTED) else updateStatus(id, ProposalStatus.REJECTED)
    }

    fun history(): List<SkillProposal> = load().sortedByDescending { it.createdAt }

    private fun updateStatus(id: String, status: ProposalStatus): Boolean {
        val current = load()
        if (current.none { it.id == id }) return false
        save(current.map { if (it.id == id) it.copy(status = status, decidedAt = System.currentTimeMillis()) else it })
        return true
    }

    private fun load(): List<SkillProposal> {
        val raw = prefs.getString(KEY_PROPOSALS, null) ?: return emptyList()
        val type = object : TypeToken<List<SkillProposal>>() {}.type
        return runCatching { gson.fromJson<List<SkillProposal>>(raw, type).orEmpty() }.getOrDefault(emptyList())
    }

    private fun save(value: List<SkillProposal>) {
        prefs.edit().putString(KEY_PROPOSALS, gson.toJson(value)).apply()
    }

    private companion object {
        const val PREFS = "airi_skill_proposals"
        const val KEY_PROPOSALS = "proposals"

        fun defaultRisks(skill: CustomSkill, manifest: SkillManifest): List<String> = buildList {
            add("سيتم إرسال بيانات الطلب إلى endpoint خارجي عبر HTTPS.")
            if (skill.config.headers.keys.any { it.equals("authorization", true) || it.equals("x-api-key", true) }) {
                add("تحتوي المهارة على رؤوس مصادقة؛ ستُحفظ القيم مشفرة.")
            }
            if (manifest.permissions.isNotEmpty()) add("الصلاحيات المعلنة: ${manifest.permissions.joinToString()}")
        }
    }
}

enum class ProposalStatus { DRAFT, VALIDATED, AWAITING_USER_DECISION, ACCEPTED, REJECTED, PERSISTED }
enum class ProposalSource { AIRI_GENERATED, AIRI_IMPORTED_FROM_INTERNET }

data class SkillProposal(
    val id: String,
    val status: ProposalStatus,
    val skill: CustomSkill,
    val manifest: SkillManifest,
    val source: ProposalSource,
    val sourceUrl: String? = null,
    val warnings: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    val createdAt: Long,
    val validatedAt: Long? = null,
    val decidedAt: Long? = null,
)
