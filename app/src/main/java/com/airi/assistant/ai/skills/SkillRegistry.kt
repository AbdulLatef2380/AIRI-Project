package com.airi.assistant.ai.skills

import android.content.Context
import com.airi.assistant.ai.skills.impl.CalendarEventsSkill
import com.airi.assistant.ai.skills.impl.DriveSearchSkill
import com.airi.assistant.ai.skills.impl.GithubGuardianSkill
import com.airi.assistant.ai.skills.impl.GmailAssistantSkill
import com.airi.assistant.ai.skills.impl.TelegramMessengerSkill
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.domain.customskill.CustomSkillRepository

class SkillRegistry(private val context: Context) {

    private val secureStorage = SecureStorage(context)
    private val customSkillRepository = CustomSkillRepository(context)

    private val disabledSkillsPrefs by lazy {
        context.getSharedPreferences("airi_skill_toggles", Context.MODE_PRIVATE)
    }

    fun isSkillEnabled(skillName: String): Boolean =
        disabledSkillsPrefs.getBoolean(skillName, true)

    fun setSkillEnabled(skillName: String, enabled: Boolean) {
        disabledSkillsPrefs.edit().putBoolean(skillName, enabled).apply()
    }

    fun getAvailableSkills(): List<AiriSkill> {
        val skills = mutableListOf<AiriSkill>()

        if (secureStorage.isGithubConnected() && isSkillEnabled("github_guardian")) {
            skills.add(GithubGuardianSkill(context))
        }
        if (secureStorage.isTelegramConnected() && isSkillEnabled("telegram_messenger")) {
            skills.add(TelegramMessengerSkill(context))
        }
        if (secureStorage.isGoogleConnected()) {
            if (isSkillEnabled("gmail_assistant")) skills.add(GmailAssistantSkill(context))
            if (isSkillEnabled("drive_search")) skills.add(DriveSearchSkill(context))
            if (isSkillEnabled("calendar_events")) skills.add(CalendarEventsSkill(context))
        }

        return skills
    }

    data class SkillInfo(
        val name: String,
        val description: String,
        val isConnected: Boolean,
        val isEnabled: Boolean
    )

    fun getAllSkillInfos(): List<SkillInfo> = listOf(
        SkillInfo(
            name = "github_guardian",
            description = "Check GitHub repositories and profile",
            isConnected = secureStorage.isGithubConnected(),
            isEnabled = isSkillEnabled("github_guardian")
        ),
        SkillInfo(
            name = "telegram_messenger",
            description = "Send messages via Telegram bot",
            isConnected = secureStorage.isTelegramConnected(),
            isEnabled = isSkillEnabled("telegram_messenger")
        ),
        SkillInfo(
            name = "gmail_assistant",
            description = "Read and summarize Gmail emails",
            isConnected = secureStorage.isGoogleConnected(),
            isEnabled = isSkillEnabled("gmail_assistant")
        ),
        SkillInfo(
            name = "drive_search",
            description = "Search files in Google Drive",
            isConnected = secureStorage.isGoogleConnected(),
            isEnabled = isSkillEnabled("drive_search")
        ),
        SkillInfo(
            name = "calendar_events",
            description = "Get upcoming Google Calendar events",
            isConnected = secureStorage.isGoogleConnected(),
            isEnabled = isSkillEnabled("calendar_events")
        )
    ) + customSkillRepository.getAllSkills().map { skill ->
        SkillInfo(
            name = skill.name,
            description = skill.description,
            isConnected = true,
            isEnabled = true
        )
    }

    fun buildSkillDescriptionBlock(): String {
        val available = getAvailableSkills()
        val customSkills = customSkillRepository.getAllSkills()
        if (available.isEmpty() && customSkills.isEmpty()) return ""
        return buildString {
            append("\n\nYou have access to the following Skills for high-level tasks:")
            for (skill in available) {
                val meta = SKILL_METADATA[skill.name]
                append("\n\n- Skill: ${skill.name}")
                append("\n  Description: ${skill.description}")
                if (meta != null) {
                    append("\n  When to use: ${meta.whenToUse}")
                    append("\n  Expected input: ${meta.expectedInput}")
                }
            }
            for (skill in customSkills) {
                append("\n\n- CustomSkill: ${skill.name}")
                append("\n  Description: ${skill.description}")
                append("\n  Type: ${skill.type.name}")
                append("\n  When to use: Use this skill when the user's request matches '${skill.name}' or its description.")
                append("\n  Expected input: user_input (the user's request or message)")
            }
            append(
                "\n\nUse these skills intelligently when the user's intent matches them. " +
                        "Skills are separate from Tools — prefer skills for known integration tasks."
            )
        }
    }

    private data class SkillMeta(val whenToUse: String, val expectedInput: String)

    private companion object {
        private val SKILL_METADATA = mapOf(
            "github_guardian" to SkillMeta(
                whenToUse = "When user asks about their GitHub repos, profile, stars, or code activity",
                expectedInput = "Natural language query about GitHub (e.g. 'show my repos', 'how many stars do I have')"
            ),
            "telegram_messenger" to SkillMeta(
                whenToUse = "When user asks to send a Telegram message or notification",
                expectedInput = "chat_id and message text (e.g. 'send hello to @mychat')"
            ),
            "gmail_assistant" to SkillMeta(
                whenToUse = "When user asks to read, summarize, or check their emails",
                expectedInput = "Natural language query about email (e.g. 'show my latest emails')"
            ),
            "drive_search" to SkillMeta(
                whenToUse = "When user wants to find or search files in their Google Drive",
                expectedInput = "File name or search query (e.g. 'find my resume')"
            ),
            "calendar_events" to SkillMeta(
                whenToUse = "When user asks about upcoming meetings, events, or schedule",
                expectedInput = "Natural language request for calendar info (e.g. 'what do I have tomorrow')"
            )
        )
    }
}
