package com.airi.assistant.workspace

data class AirisProject(
    val id: String,
    val title: String,
    val description: String,
    val files: MutableList<String> = mutableListOf(),
    val knowledgeTags: MutableList<String> = mutableListOf()
)

object AirisProjectManager {
    private val projects = mutableMapOf<String, AirisProject>()

    fun createProject(id: String, title: String, description: String): AirisProject {
        val proj = AirisProject(id, title, description)
        projects[id] = proj
        return proj
    }

    fun getProject(id: String): AirisProject? = projects[id]

    fun addFileToProject(projectId: String, filePath: String) {
        projects[projectId]?.files?.add(filePath)
    }

    fun clear() {
        projects.clear()
    }
}
