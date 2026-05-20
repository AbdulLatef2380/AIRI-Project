package com.airi.assistant.ui.input

sealed class PlusMenuAction(val label: String, val emoji: String) {
    object UploadImage      : PlusMenuAction("Upload image",       "🖼")
    object TakePhoto        : PlusMenuAction("Take photo",         "📷")
    object UploadFile       : PlusMenuAction("Upload file",        "📄")
    object CreateWebsite    : PlusMenuAction("Create website",     "🌐")
    object DevelopApp       : PlusMenuAction("Develop app",        "⚙")
    object GenerateSlides   : PlusMenuAction("Generate slides",    "📊")
    object LaunchResearch   : PlusMenuAction("Research mode",      "🔍")
    object CreateAutomation : PlusMenuAction("Create automation",  "🤖")
    object CodeWorkspace    : PlusMenuAction("Code workspace",     "💻")
    object AnalyzeRepo      : PlusMenuAction("Analyse repository", "📦")
    object OpenSandbox      : PlusMenuAction("Open sandbox",       "🔬")
    object OpenWorkspace    : PlusMenuAction("Workspace",          "🗂")
    object OpenTerminal     : PlusMenuAction("Terminal",           "💻")
    object AddSkill         : PlusMenuAction("Add skill",          "✨")

    companion object {
        val sections: List<Pair<String, List<PlusMenuAction>>> = listOf(
            "Media & Files"    to listOf(UploadImage, TakePhoto, UploadFile),
            "Agent Workflows"  to listOf(CreateWebsite, DevelopApp, GenerateSlides,
                                          LaunchResearch, CreateAutomation, CodeWorkspace,
                                          AnalyzeRepo, OpenSandbox, OpenWorkspace, OpenTerminal),
            "Skills"           to listOf(AddSkill)
        )
    }
}
