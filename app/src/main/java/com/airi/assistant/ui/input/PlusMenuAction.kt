package com.airi.assistant.ui.input

/**
 * Actions available from the chat "+" menu.
 *
 * Phase 1 stabilization: removed `OpenSandbox`, `OpenWorkspace`, `OpenTerminal`,
 * and `CodeWorkspace`. Their backing screens existed in the project but the
 * underlying runtimes (on-device shell / code-execution sandbox) cannot
 * actually run programs on stock Android. The UI entry points are removed
 * until a real on-device runtime (e.g. WebAssembly) is wired in. The screen
 * files themselves are kept for now and will be removed during Phase 7
 * UI consolidation.
 */
sealed class PlusMenuAction(val label: String, val emoji: String) {
    object UploadImage      : PlusMenuAction("Upload image",       "\uD83D\uDDBC")
    object TakePhoto        : PlusMenuAction("Take photo",         "\uD83D\uDCF7")
    object UploadFile       : PlusMenuAction("Upload file",        "\uD83D\uDCC4")
    object CreateWebsite    : PlusMenuAction("Create website",     "\uD83C\uDF10")
    object DevelopApp       : PlusMenuAction("Develop app",        "\u2699")
    object GenerateSlides   : PlusMenuAction("Generate slides",    "\uD83D\uDCCA")
    object LaunchResearch   : PlusMenuAction("Research mode",      "\uD83D\uDD0D")
    object CreateAutomation : PlusMenuAction("Create automation",  "\uD83E\uDD16")
    object AnalyzeRepo      : PlusMenuAction("Analyse repository", "\uD83D\uDCE6")
    object AddSkill         : PlusMenuAction("Add skill",          "\u2728")

    companion object {
        val sections: List<Pair<String, List<PlusMenuAction>>> = listOf(
            "Media & Files"    to listOf(UploadImage, TakePhoto, UploadFile),
            "Agent Workflows"  to listOf(CreateWebsite, DevelopApp, GenerateSlides,
                                          LaunchResearch, CreateAutomation, AnalyzeRepo),
            "Skills"           to listOf(AddSkill)
        )
    }
}
