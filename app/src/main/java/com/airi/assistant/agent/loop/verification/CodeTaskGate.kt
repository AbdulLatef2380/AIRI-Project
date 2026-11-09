package com.airi.assistant.agent.loop.verification

import com.airi.assistant.agent.loop.plan.AgentPhase
import com.airi.assistant.agent.loop.plan.AgentPhaseTracker
import com.airi.assistant.agent.loop.plan.TaskRequirement

/**
 * Verification gate for code tasks. AgentLoop feeds it every write_file outcome and asks it
 * before accepting a plain-text reply as the final answer.
 *
 * A code task is accepted only when a write_file call succeeded, no saved Python file still
 * fails the syntax check, and every [requirements] item taken from the user's request shows up in
 * the saved sources. Otherwise the gate asks for a correction turn, at most [maxCorrections]
 * times, then rejects with a visible reason instead of passing the text off as a verified
 * result. [phase] records the PLAN -> ACT -> VERIFY -> FINAL path for the trace. Pure Kotlin so
 * it is unit-testable on the JVM.
 */
class CodeTaskGate(
    private val maxCorrections: Int = DEFAULT_MAX_CORRECTIONS,
    val requirements: List<TaskRequirement> = emptyList(),
) {

    enum class Reason { TOOL_REQUIRED_NOT_CALLED, SYNTAX_CHECK_FAILED, REQUIREMENTS_NOT_MET }

    sealed class Verdict {
        object Accept : Verdict()

        /** Add [prompt] to the history as a user turn and give the model another turn. */
        data class Correct(val reason: Reason, val prompt: String) : Verdict()

        /** Give up; show [notice] ahead of the model's text so the user is not misled. */
        data class Reject(val reason: Reason, val notice: String) : Verdict()
    }

    /** PLAN -> ACT -> VERIFY -> FINAL, for the trace. */
    val phase = AgentPhaseTracker()

    private var wroteFile = false
    private val sources = LinkedHashMap<String, String>()
    private var awaitingApproval = false
    private var corrections = 0
    private val failing = LinkedHashMap<String, List<PythonSyntaxChecker.Issue>>()

    /**
     * Records a write_file outcome. Returns text to append to the tool result so the model (and the
     * user's tool output) see the check result, or null when nothing was checked.
     */
    fun onWriteFile(args: Map<String, String>, succeeded: Boolean, approvalPending: Boolean = false): String? {
        if (approvalPending) {
            awaitingApproval = true
            return null
        }
        if (!succeeded) return null
        awaitingApproval = false
        wroteFile = true
        phase.moveTo(AgentPhase.ACT)
        val name = args.entries
            .firstOrNull { it.key.lowercase() in NAME_KEYS && it.value.isNotBlank() }?.value?.trim()
        val content = args.entries.firstOrNull { it.key.lowercase() in CONTENT_KEYS }?.value
        if (name != null && content != null) sources[name.lowercase()] = content
        val syntaxFeedback = checkSyntax(name, content)
        val requirementFeedback = requirementStatus()
        return when {
            syntaxFeedback != null && requirementFeedback != null -> "$syntaxFeedback\n$requirementFeedback"
            else -> syntaxFeedback ?: requirementFeedback
        }
    }

    private fun checkSyntax(name: String?, content: String?): String? {
        if (name == null) return null
        val key = name.lowercase()
        if (!key.endsWith(".py")) return null
        if (content == null) {
            failing.remove(key)
            return null
        }
        return when (val result = PythonSyntaxChecker.check(content)) {
            PythonSyntaxChecker.Result.Passed -> {
                failing.remove(key)
                "[AIRI check] Python syntax check passed for $name. This is a structural check only; " +
                    "the program was not run, so do not claim it was tested."
            }
            is PythonSyntaxChecker.Result.Failed -> {
                failing[key] = result.issues
                "[AIRI check] Python syntax check FAILED for $name:\n" +
                    result.issues.joinToString("\n") { "- $it" } +
                    "\nFix every problem and call write_file again with the same filename, overwrite set to true, " +
                    "and the complete corrected file."
            }
        }
    }

    /** Requirements from the request that the saved sources do not show yet. */
    fun unmetRequirements(): List<TaskRequirement> =
        if (sources.isEmpty()) requirements else requirements.filterNot { it.evidence(sources) }

    /** One line for the tool result and the trace: "[AIRI check] Requirements 3/5 ...", or null with no plan. */
    fun requirementStatus(): String? {
        if (requirements.isEmpty()) return null
        val unmet = unmetRequirements()
        val met = requirements.size - unmet.size
        return if (unmet.isEmpty()) {
            "[AIRI check] Requirements shown in the saved files: $met/${requirements.size}. " +
                "This is a structural check of the source text, not a test run."
        } else {
            "[AIRI check] Requirements shown in the saved files: $met/${requirements.size}. Still missing: " +
                unmet.joinToString("; ") { it.labelEn } + ". Save the complete updated file with overwrite set to true."
        }
    }

    /** The plan as shown in the trace: the requirement labels, in the user's language. */
    fun planSummary(arabic: Boolean): String =
        requirements.joinToString(if (arabic) "، " else "; ") { it.label(arabic) }

    /**
     * Decides what to do with a plain-text reply that contains no tool call.
     * [canContinue] is false when no steps are left; [preferArabic] picks the notice language.
     */
    fun onFinalText(canContinue: Boolean, preferArabic: Boolean = false): Verdict {
        phase.moveTo(AgentPhase.ACT)
        phase.moveTo(AgentPhase.VERIFY)
        if (awaitingApproval) {
            phase.moveTo(AgentPhase.FINAL)
            return Verdict.Accept
        }
        val reason = when {
            !wroteFile -> Reason.TOOL_REQUIRED_NOT_CALLED
            failing.isNotEmpty() -> Reason.SYNTAX_CHECK_FAILED
            unmetRequirements().isNotEmpty() -> Reason.REQUIREMENTS_NOT_MET
            else -> {
                phase.moveTo(AgentPhase.FINAL)
                return Verdict.Accept
            }
        }
        if (canContinue && corrections < maxCorrections) {
            corrections++
            phase.moveTo(AgentPhase.ACT)
            return Verdict.Correct(reason, correctionPrompt(reason))
        }
        phase.moveTo(AgentPhase.FINAL)
        return Verdict.Reject(reason, notice(reason, preferArabic))
    }

    private fun correctionPrompt(reason: Reason): String = when (reason) {
        Reason.TOOL_REQUIRED_NOT_CALLED ->
            "[SYSTEM] The user asked you to build a program, but no file has been saved yet. " +
                "Do not paste the code in chat. Call write_file now with a plain file name and " +
                "extension (for example calculator.py) and the complete source in one call."
        Reason.SYNTAX_CHECK_FAILED ->
            "[SYSTEM] A saved file failed the syntax check:\n" +
                failing.entries.joinToString("\n") { (file, issues) ->
                    "$file: " + issues.joinToString("; ") { it.toString() }
                } +
                "\nFix every problem and call write_file again with the same filename, overwrite set to true " +
                "(an existing file is not replaced otherwise), and the complete corrected program. " +
                "Do not describe the fix without calling the tool."
        Reason.REQUIREMENTS_NOT_MET ->
            "[SYSTEM] The saved file does not yet show these requirements from the user's request:\n" +
                unmetRequirements().joinToString("\n") { "- ${it.labelEn}" } +
                "\nCall write_file again with the same filename, overwrite set to true, and the complete " +
                "updated program that includes every item. Do not describe the change without calling the tool."
    }

    private fun notice(reason: Reason, arabic: Boolean): String = when (reason) {
        Reason.TOOL_REQUIRED_NOT_CALLED ->
            if (arabic) {
                "تنبيه: لم يُحفظ البرنامج كملف ولم يتحقق AIRI من الكود. الكود أدناه كما أنتجه النموذج."
            } else {
                "Note: the program was not saved as a file and AIRI did not verify the code. " +
                    "The code below is exactly what the model produced."
            }
        Reason.SYNTAX_CHECK_FAILED -> {
            val files = failing.keys.joinToString(", ")
            if (arabic) {
                "تنبيه: فشل فحص الصياغة للملف ($files) ولم يُصلَح. راجع الكود قبل استخدامه."
            } else {
                "Note: the syntax check failed for ($files) and was not fixed. Review the code before using it."
            }
        }
        Reason.REQUIREMENTS_NOT_MET -> {
            val missing = unmetRequirements().joinToString(if (arabic) "، " else "; ") { it.label(arabic) }
            if (arabic) {
                "تنبيه: لم يظهر في الملف المحفوظ ما يثبت هذه المتطلبات: $missing. الفحص بنيوي على نص الكود ولم يُشغَّل البرنامج."
            } else {
                "Note: the saved file does not show these requirements: $missing. The check is structural " +
                    "on the source text; the program was not run."
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_CORRECTIONS = 2

        /** Argument names write_file implementations commonly use; matched case-insensitively. */
        private val NAME_KEYS = setOf("path", "file_path", "filepath", "filename", "file_name", "name")
        private val CONTENT_KEYS = setOf("content", "text", "data", "code", "contents")

        /** True when [text] contains Arabic letters, used to pick the notice language. */
        fun looksArabic(text: String): Boolean = text.any { it in '\u0600'..'\u06FF' }
    }
}
