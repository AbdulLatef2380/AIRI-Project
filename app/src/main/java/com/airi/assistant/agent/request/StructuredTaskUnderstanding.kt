package com.airi.assistant.agent.request

import com.airi.assistant.ai.CapabilityIntentDetector
import com.airi.assistant.ai.CodingTaskDetector

/**
 * Request-bound semantic projection. It describes what the user wants without
 * granting permission or executing anything. Rules are deliberately cheap and
 * deterministic; richer model understanding can replace this projection later
 * without changing routing or authorization contracts.
 */
data class StructuredTaskUnderstanding(
    val userGoal: String,
    val taskType: TaskType,
    val requestedOutcome: RequestedOutcome,
    val entities: Map<String, String> = emptyMap(),
    val requiredCapabilities: Set<CapabilityIntentDetector.Capability> = emptySet(),
    val constraints: List<String> = emptyList(),
    val missingInformation: List<String> = emptyList(),
    val confidence: Double,
    val needsClarification: Boolean,
    val subtasks: List<Subtask> = emptyList(),
) {
    enum class TaskType {
        CONVERSATION, QUESTION, CODE_GENERATION, FILE_CREATION, FILE_EDITING,
        CODE_ANALYSIS, CODE_EXECUTION, WEB_RESEARCH, CONNECTOR_OPERATION,
        MULTI_STEP, UNKNOWN,
    }
    enum class RequestedOutcome {
        ANSWER, CREATE_AND_RETURN_CODE, CREATE_FILE, CREATE_FILE_AND_VERIFY,
        ANALYZE_AND_REPORT, RESEARCH_AND_REPORT, EXECUTE_OPERATION, CLARIFY,
    }
    data class Subtask(val ordinal: Int, val goal: String, val capabilities: Set<String>)

    companion object {
        private val languageAliases = linkedMapOf(
            "python" to "Python", "بايثون" to "Python", "بايثن" to "Python",
            "kotlin" to "Kotlin", "كوتلن" to "Kotlin", "java" to "Java", "جافا" to "Java",
            "javascript" to "JavaScript", "جافاسكربت" to "JavaScript", "typescript" to "TypeScript",
            "rust" to "Rust", "swift" to "Swift", "dart" to "Dart", "flutter" to "Flutter",
            "c++" to "C++", "cpp" to "C++", "c#" to "C#", "html" to "HTML", "css" to "CSS",
            "sql" to "SQL", "bash" to "Bash", "kivy" to "Kivy", "tkinter" to "Tkinter",
        )
        private val codeAnalysisTerms = listOf(
            "review", "analyze", "analyse", "debug", "find bugs", "errors", "explain this code",
            "راجع الكود", "حلل الكود", "حلل هذا الكود", "اكتشف الاخطاء", "اكتشف الأخطاء",
            "صحح الكود", "افحص الكود", "اشرح هذا الكود",
            "ابحث عن أخطاء", "ابحث عن اخطاء", "أخطاء في هذا البرنامج", "اخطاء في هذا البرنامج",
        )
        private val guiTerms = listOf("gui", "graphical", "واجهة رسومية", "واجهة", "tkinter", "kivy")
        private val calculatorTerms = listOf("calculator", "حاسبة", "اله حاسبة", "آلة حاسبة", "arithmetic", "العمليات الحسابية")
        private val fileNameRegex = Regex(
            "(?:file|ملف|في|into|to)\\s+([A-Za-z0-9_.-]+\\.[A-Za-z0-9]+)|\\b([A-Za-z0-9_-]+\\.(?:py|kt|java|js|ts|html|css|json|txt))\\b",
            RegexOption.IGNORE_CASE,
        )

        fun understand(input: String): StructuredTaskUnderstanding {
            val text = CapabilityIntentDetector.normalize(input)
            val runtime = CapabilityIntentDetector.detect(text)
            val coding = CodingTaskDetector.detect(text)
            val analysis = codeAnalysisTerms.any { text.contains(CapabilityIntentDetector.normalize(it)) }
            val hasFile = runtime.requires(CapabilityIntentDetector.Capability.FILE_WRITE)
            val asksToTest = listOf("test", "اختبر", "اختبار", "شغل الاختبارات", "run tests")
                .any { text.contains(CapabilityIntentDetector.normalize(it)) }
            val hasExecute = runtime.requires(CapabilityIntentDetector.Capability.TERMINAL_EXECUTE) ||
                (coding != null && asksToTest)
            val hasWeb = runtime.requires(CapabilityIntentDetector.Capability.WEB_SEARCH)
            val hasConnector = runtime.requires(CapabilityIntentDetector.Capability.CONNECTOR_READ) ||
                runtime.requires(CapabilityIntentDetector.Capability.CONNECTOR_WRITE)
            val entities = linkedMapOf<String, String>()
            coding?.languages?.firstOrNull()?.let { language ->
                entities["programming_language"] = languageAliases[language] ?: language
            }
            if (calculatorTerms.any { text.contains(CapabilityIntentDetector.normalize(it)) }) entities["application_type"] = "Calculator"
            if (guiTerms.any { text.contains(CapabilityIntentDetector.normalize(it)) }) entities["interface"] = "GUI"
            fileNameRegex.find(text)?.let { match ->
                (match.groupValues.getOrNull(1)?.takeIf(String::isNotBlank)
                    ?: match.groupValues.getOrNull(2)?.takeIf(String::isNotBlank))
                    ?.let { entities["file_name"] = it }
            }

            val capabilities = linkedSetOf<CapabilityIntentDetector.Capability>().apply {
                addAll(runtime.capabilities)
                if (coding != null) add(CapabilityIntentDetector.Capability.CODE_TASK)
                if (analysis && coding != null) add(CapabilityIntentDetector.Capability.CODE_TASK)
            }
            val type = when {
                coding != null && analysis -> TaskType.CODE_ANALYSIS
                coding != null && hasExecute -> TaskType.CODE_EXECUTION
                coding != null && hasFile -> TaskType.FILE_CREATION
                coding != null -> TaskType.CODE_GENERATION
                hasFile -> TaskType.FILE_CREATION
                hasExecute -> TaskType.CODE_EXECUTION
                hasConnector -> TaskType.CONNECTOR_OPERATION
                hasWeb -> TaskType.WEB_RESEARCH
                text.isBlank() -> TaskType.UNKNOWN
                else -> TaskType.QUESTION
            }
            val outcome = when {
                coding != null && hasExecute -> RequestedOutcome.CREATE_FILE_AND_VERIFY
                coding != null && hasFile -> RequestedOutcome.CREATE_FILE
                coding != null && analysis -> RequestedOutcome.ANALYZE_AND_REPORT
                coding != null -> RequestedOutcome.CREATE_AND_RETURN_CODE
                hasExecute || hasConnector -> RequestedOutcome.EXECUTE_OPERATION
                hasWeb -> RequestedOutcome.RESEARCH_AND_REPORT
                type == TaskType.UNKNOWN -> RequestedOutcome.CLARIFY
                else -> RequestedOutcome.ANSWER
            }
            val parts = splitSubtasks(text)
            val multiStep = parts.size > 1
            val finalType = if (multiStep) TaskType.MULTI_STEP else type
            val score = when {
                coding != null -> 0.94
                hasConnector || hasExecute || hasFile || hasWeb -> 0.86
                analysis -> 0.78
                type == TaskType.QUESTION -> 0.72
                text.length < 3 -> 0.20
                else -> 0.55
            }
            val missing = buildList {
                if (coding != null && entities["programming_language"] == null) add("programming_language")
            }
            return StructuredTaskUnderstanding(
                userGoal = when {
                    entities["application_type"] == "Calculator" && coding != null -> "create_calculator"
                    coding != null -> "create_program"
                    hasConnector -> "operate_connector"
                    hasWeb -> "research_web"
                    analysis -> "analyze_code"
                    else -> "answer_question"
                },
                taskType = finalType,
                requestedOutcome = if (multiStep) RequestedOutcome.CREATE_FILE_AND_VERIFY else outcome,
                entities = entities,
                requiredCapabilities = capabilities,
                constraints = buildList {
                    if (entities["interface"] == "GUI") add("graphical_interface")
                    if (asksToTest) add("verify_with_tests")
                },
                missingInformation = missing,
                confidence = score,
                needsClarification = score < 0.45 || (type == TaskType.UNKNOWN && text.isNotBlank()),
                subtasks = parts.mapIndexed { index, part -> Subtask(index + 1, part, capabilities.map { it.name }.toSet()) },
            )
        }

        private fun splitSubtasks(text: String): List<String> =
            text.split(Regex("\\s+(?:ثم|and then|then)\\s+|[,؛;]\\s*"))
                .map(String::trim).filter { it.isNotBlank() }.takeIf { it.size > 1 }.orEmpty()
    }
}
