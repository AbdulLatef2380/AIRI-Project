package com.airi.assistant.ai.skills

import android.Manifest
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

    init {
        // Register all known skills with the AiriSkillOrchestrator so it can
        // automatically discover and match them without manual configuration.
        registerOrchestrationDescriptors()
    }

    private fun registerOrchestrationDescriptors() {
        val descriptors = listOf(
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "github_guardian",
                displayName = "GitHub Guardian",
                description = "Read GitHub repos, profile, stars, issues, activity",
                keywords    = listOf("github", "repo", "repository", "commit", "code", "stars", "pull", "issue"),
                intents     = listOf("check github", "show repos", "read code", "list issues"),
                offlineOk   = false,
                connectorIds = listOf("github"),
                priorityBias = 0.75f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "gmail_assistant",
                displayName = "Gmail Assistant",
                description = "Read, summarize, and manage Gmail emails",
                keywords    = listOf("email", "gmail", "inbox", "mail", "message", "unread", "draft"),
                intents     = listOf("read email", "check inbox", "summarize email", "send mail"),
                offlineOk   = false,
                connectorIds = listOf("google"),
                priorityBias = 0.80f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "drive_search",
                displayName = "Drive Search",
                description = "Search and retrieve Google Drive files",
                keywords    = listOf("drive", "file", "document", "spreadsheet", "folder", "cloud storage"),
                intents     = listOf("find file", "search drive", "open document", "list files"),
                offlineOk   = false,
                connectorIds = listOf("google"),
                priorityBias = 0.70f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "calendar_events",
                displayName = "Calendar Events",
                description = "Check and create Google Calendar events and schedule",
                keywords    = listOf("calendar", "event", "meeting", "schedule", "appointment", "tomorrow", "today"),
                intents     = listOf("check calendar", "schedule meeting", "what do I have", "upcoming events"),
                offlineOk   = false,
                connectorIds = listOf("google"),
                permissions  = listOf(Manifest.permission.READ_CALENDAR),
                priorityBias = 0.85f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "telegram_messenger",
                displayName = "Telegram Messenger",
                description = "Send Telegram messages and notifications",
                keywords    = listOf("telegram", "message", "notify", "send", "chat"),
                intents     = listOf("send telegram", "message someone", "notify via telegram"),
                offlineOk   = false,
                connectorIds = listOf("telegram"),
                priorityBias = 0.65f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "alarm_tool",
                displayName = "Alarm & Reminder",
                description = "Set alarms, timers, and reminders",
                keywords    = listOf("alarm", "reminder", "timer", "wake", "alert", "notify"),
                intents     = listOf("set alarm", "remind me", "wake me up", "set timer"),
                offlineOk   = true,
                priorityBias = 0.90f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "search_tool",
                displayName = "Web Search",
                description = "Search the web for current information",
                keywords    = listOf("search", "find", "look up", "google", "web", "internet", "news", "latest"),
                intents     = listOf("search for", "look up", "find information", "what is"),
                offlineOk   = false,
                priorityBias = 0.70f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "notes_tool",
                displayName = "Notes",
                description = "Create and manage notes",
                keywords    = listOf("note", "write down", "save", "remember", "jot"),
                intents     = listOf("take note", "save note", "write note", "remember this"),
                offlineOk   = true,
                priorityBias = 0.75f
            )
        )
        descriptors.forEach { AiriSkillOrchestrator.register(it) }
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
