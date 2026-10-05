package com.airi.assistant.ai.skills.impl

import android.content.Context
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.core.ServiceLocator

class DriveSearchSkill(private val context: Context) : AiriSkill {

    override val name = "drive_search"
    override val description = "Search and locate files in Google Drive"
    override val parameters: Map<String, String> = mapOf(
        "query" to "file name or search keywords"
    )

    private val secureStorage = SecureStorage(context)

    private val exactPhrases = listOf(
        "google drive", "search in drive", "find file in drive",
        "my files on drive", "files in drive", "search drive"
    )
    private val singleWords = listOf("drive", "workspace", "document")

    override fun score(input: String, context: SkillContext): Int {
        if (!secureStorage.isGoogleConnected()) return 0
        val lower = input.lowercase()
        var score = 0

        exactPhrases.forEach { if (lower.contains(it)) score += 25 }
        singleWords.forEach  { if (lower.contains(it)) score += 15 }

        if (context.lastUsedSkill == name) score += 20
        if (context.lastAssistantMessage?.lowercase()?.let {
            it.contains("drive") || it.contains("file") || it.contains("document")
        } == true) score += 10

        return score.coerceAtMost(100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        if (!secureStorage.isGoogleConnected()) {
            return SkillResult(false, "", "Google is not connected. Please connect it in Integrations.")
        }

        val input   = params["input"] as? String ?: ""
        val context = params["context"] as? SkillContext
        val query   = resolveQuery(input, context)

        val output = ServiceLocator.connectorRuntimeManager.execute(
            connectorId = "google",
            input = ConnectorInput(action = "drive_search", params = mapOf("query" to query))
        )
        return when (output) {
            is ConnectorOutput.Success -> SkillResult(true, output.text)
            is ConnectorOutput.Failure -> SkillResult(false, "", output.message)
            is ConnectorOutput.Streaming -> SkillResult(false, "", "Streaming output is not supported for Drive.")
        }
    }

    private fun resolveQuery(input: String, context: SkillContext?): String {
        val cleaned = input.lowercase()
            .replace("google drive", "")
            .replace("search in drive", "")
            .replace("find file in drive", "")
            .replace("my files on drive", "")
            .replace("search drive", "")
            .replace("find file", "")
            .replace("search file", "")
            .replace("workspace", "")
            .replace("document", "")
            .trim()

        if (cleaned.isNotBlank()) return cleaned

        val lastMsg = context?.lastAssistantMessage ?: return ""
        val keywords = lastMsg.split(" ")
            .filter { it.length > 3 && !it.contains("http") }
            .take(3)
            .joinToString(" ")
        return keywords
    }
}
