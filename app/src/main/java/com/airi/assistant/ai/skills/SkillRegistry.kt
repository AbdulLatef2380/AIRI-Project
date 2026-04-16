package com.airi.assistant.ai.skills

import android.content.Context
import com.airi.assistant.ai.skills.impl.CalendarEventsSkill
import com.airi.assistant.ai.skills.impl.DriveSearchSkill
import com.airi.assistant.ai.skills.impl.GithubGuardianSkill
import com.airi.assistant.ai.skills.impl.GmailAssistantSkill
import com.airi.assistant.ai.skills.impl.TelegramMessengerSkill
import com.airi.assistant.auth.SecureStorage

class SkillRegistry(private val context: Context) {

    private val secureStorage = SecureStorage(context)

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
    )

    fun buildSkillDescriptionBlock(): String {
        val available = getAvailableSkills()
        if (available.isEmpty()) return ""
        return buildString {
            append("\n\nYou also have access to the following Skills for high-level tasks:")
            for (skill in available) {
                append("\n- Skill[${skill.name}]: ${skill.description}")
            }
            append(
                "\n\nUse these skills intelligently when the user's intent matches them. " +
                        "Skills are separate from Tools — prefer skills for known integration tasks."
            )
        }
    }
}
