package com.airi.assistant.ai.skills.impl

import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillMemoryAccess
import com.airi.assistant.ai.skills.SkillModelAccess
import com.airi.assistant.ai.skills.SkillParamDef
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.ai.skills.SkillRiskLevel
import com.airi.assistant.ai.skills.SkillToolDefinition

/**
 * A real first-party executor for focused model tasks. Each catalog entry has
 * its own routing keywords, input contract, system policy, and output contract;
 * execution always goes through AIRI's active SkillModelBridge.
 */
class ModelUtilitySkill(
    private val spec: Spec
) : AiriSkill {
    override val skillId: String = spec.id
    override val name: String = spec.name
    override val displayName: String = spec.name
    override val description: String = spec.description
    override val version: String = "1.0.0"
    override val author: String = "AIRI Official"
    override val category: String = spec.category
    override val isOfficial: Boolean = true
    override val memoryAccess: SkillMemoryAccess = SkillMemoryAccess.NONE
    override val modelAccess: SkillModelAccess = SkillModelAccess.CHAT
    override val parameters: Map<String, String> = spec.parameters
    override val inputSchema: Map<String, String> = spec.parameters
    override val outputSchema: Map<String, String> = mapOf("result" to "string")
    override val instructions: String = spec.instructions
    override val examples: List<String> = spec.examples
    override val limitations: List<String> = spec.limitations
    override val riskLevel: SkillRiskLevel = SkillRiskLevel.LOW
    override val supportsStreaming: Boolean = false
    override val supportsAttachments: Boolean = false
    override val toolDefinitions: List<SkillToolDefinition> = listOf(
        SkillToolDefinition(
            name = spec.id,
            description = spec.description,
            parameters = spec.parameters.mapValues { (name, type) ->
                SkillParamDef(type, "Input field: $name", required = name == "input")
            }
        )
    )

    override fun score(input: String, context: SkillContext): Int {
        val lower = input.lowercase()
        val keywordScore = spec.keywords.count { lower.contains(it) } * 24
        val continuationScore = if (context.lastUsedSkill == skillId) 12 else 0
        return (keywordScore + continuationScore).coerceIn(0, 100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val startedAt = System.currentTimeMillis()
        val context = params["context"] as? SkillContext
            ?: return failure("Skill context is required for model execution.", startedAt)
        val input = (params["input"] as? String)
            ?: params["text"] as? String
            ?: return failure("No input was provided.", startedAt)
        if (input.isBlank()) return failure("Input cannot be blank.", startedAt)
        val bridge = context.modelBridge
            ?: return failure("An active AI model is required for this skill.", startedAt)

        val prompt = buildString {
            append(spec.instructions)
            append("\n\nUser input:\n")
            append(input.trim())
            append("\n\nOutput requirements:\n")
            append(spec.outputContract)
        }
        return try {
            val result = bridge.complete(prompt, spec.systemPrompt, maxTokens = spec.maxTokens)
            SkillResult(
                success = true,
                data = result,
                skillName = skillId,
                executionMs = System.currentTimeMillis() - startedAt,
                metadata = mapOf("task" to skillId, "model_execution" to "active_bridge")
            )
        } catch (error: Exception) {
            failure("Model execution failed: ${error.message ?: "unknown error"}", startedAt)
        }
    }

    private fun failure(message: String, startedAt: Long) = SkillResult(
        success = false,
        data = "",
        error = message,
        skillName = skillId,
        executionMs = System.currentTimeMillis() - startedAt
    )

    data class Spec(
        val id: String,
        val name: String,
        val description: String,
        val category: String,
        val keywords: List<String>,
        val parameters: Map<String, String>,
        val instructions: String,
        val outputContract: String,
        val systemPrompt: String,
        val examples: List<String>,
        val limitations: List<String>,
        val maxTokens: Int = 768
    )

    companion object {
        val SPECS: List<Spec> = listOf(
            Spec("summarizer", "Summarizer", "Create a faithful concise summary of text", "PRODUCTIVITY", listOf("summarize", "summary", "تلخيص", "لخص"), mapOf("input" to "string"), "Summarize the input while preserving facts, decisions, and important qualifiers.", "Use headings and bullets only when they improve scanability.", "You are a precise summarization specialist. Do not invent facts.", listOf("Summarize this report"), listOf("May omit low-salience details.")),
            Spec("email_drafter", "Email Drafter", "Draft a professional email from intent and context", "COMMUNICATION", listOf("email", "reply", "رسالة", "بريد"), mapOf("input" to "string"), "Draft an email matching the requested audience, tone, and purpose.", "Return subject and body. Do not send the email.", "You draft clear professional emails. Never claim that an email was sent.", listOf("Draft a reply to this customer"), listOf("This skill drafts only; it never sends.")),
            Spec("text_rewriter", "Text Rewriter", "Rewrite text for a requested tone, audience, or clarity level", "PRODUCTIVITY", listOf("rewrite", "rephrase", "صياغة", "أعد كتابة"), mapOf("input" to "string"), "Rewrite the input according to the user's requested tone without changing its factual meaning.", "Return only the rewritten text unless explanation is explicitly requested.", "You are an exacting editor. Preserve meaning and named entities.", listOf("Rewrite this more formally"), listOf("It cannot verify factual correctness.")),
            Spec("sentiment_analyzer", "Sentiment Analyzer", "Analyze sentiment, tone, and uncertainty in text", "ANALYSIS", listOf("sentiment", "tone", "مشاعر", "نبرة"), mapOf("input" to "string"), "Analyze sentiment, emotional tone, confidence, and textual evidence.", "Return sentiment label, confidence as a qualitative word, and evidence quotes.", "You analyze text conservatively and distinguish emotion from fact.", listOf("Analyze the tone of this message"), listOf("Sentiment is an interpretation, not a diagnosis.")),
            Spec("json_extractor", "JSON Extractor", "Extract structured fields from unstructured text", "DEVELOPER", listOf("json", "extract fields", "استخراج", "بيانات منظمة"), mapOf("input" to "string"), "Extract only information explicitly present in the input and produce valid JSON.", "Return valid JSON only. Use null for missing fields and do not add commentary.", "You produce strict valid JSON and never fabricate missing values.", listOf("Extract name, date, and amount as JSON"), listOf("Schema is inferred from the request unless supplied.")),
            Spec("decision_matrix", "Decision Matrix", "Compare options against explicit criteria and trade-offs", "ANALYSIS", listOf("compare", "decision", "pros and cons", "مقارنة", "قرار"), mapOf("input" to "string"), "Build a transparent comparison using the criteria supplied by the user; state assumptions.", "Return criteria, option scores or rationale, trade-offs, and a recommendation with caveats.", "You are a decision analyst. Do not present uncertain assumptions as facts.", listOf("Compare these two approaches"), listOf("The recommendation depends on supplied criteria.")),
            Spec("study_tutor", "Study Tutor", "Explain a topic and create practice questions", "EDUCATION", listOf("study", "learn", "explain", "دراسة", "اشرح"), mapOf("input" to "string"), "Teach the topic step by step at the learner's level and check understanding.", "Return explanation, one example, and practice questions with answers hidden under a clear section.", "You are a patient tutor. Correct misconceptions and adapt to the learner.", listOf("Teach me this concept"), listOf("Educational guidance is not a substitute for a qualified instructor.")),
            Spec("interview_coach", "Interview Coach", "Prepare interview questions and actionable feedback", "PRODUCTIVITY", listOf("interview", "resume", "مقابلة", "سيرة ذاتية"), mapOf("input" to "string"), "Coach the user for the stated role or interview scenario using concrete, respectful feedback.", "Return likely questions, answer structure, and specific improvement actions.", "You are a constructive interview coach. Never guarantee hiring outcomes.", listOf("Prepare me for a software interview"), listOf("Advice is based only on supplied context.")),
            Spec("sql_assistant", "SQL Assistant", "Write or explain SQL queries with safety checks", "DEVELOPER", listOf("sql", "query", "database", "استعلام", "قاعدة بيانات"), mapOf("input" to "string"), "Write or explain SQL for the described schema and task; call out destructive operations.", "Return SQL in a code block plus assumptions and a safe preview note for mutations.", "You are a careful SQL engineer. Prefer parameterized queries and never imply execution.", listOf("Write a query to find duplicate users"), listOf("The query is not executed by this skill.")),
            Spec("meeting_agenda", "Meeting Agenda", "Create a focused meeting agenda with outcomes and owners", "PRODUCTIVITY", listOf("meeting", "agenda", "اجتماع", "جدول أعمال"), mapOf("input" to "string"), "Turn the supplied meeting context into an agenda with outcomes, timeboxes, and owners when known.", "Return objective, agenda items, timing, decisions needed, and follow-ups.", "You are a meeting facilitator. Do not invent attendees or commitments.", listOf("Create an agenda for this meeting"), listOf("Unknown owners remain explicitly unassigned.")),
            Spec("requirements_extractor", "Requirements Extractor", "Turn prose into testable functional requirements", "DEVELOPER", listOf("requirements", "acceptance criteria", "متطلبات", "معايير القبول"), mapOf("input" to "string"), "Extract atomic requirements, actors, constraints, and acceptance criteria from the input.", "Return numbered requirements with Given/When/Then acceptance criteria where possible.", "You are a requirements analyst. Mark ambiguities instead of silently resolving them.", listOf("Extract requirements from this brief"), listOf("Ambiguities require stakeholder confirmation.")),
            Spec("meeting_summarizer", "Meeting Summarizer", "Summarize a meeting transcript into decisions and action items", "PRODUCTIVITY", listOf("meeting summary", "transcript", "decisions", "action items", "ملخص اجتماع"), mapOf("input" to "string"), "Summarize only the supplied transcript. Separate decisions, unresolved questions, action items, owners, and due dates.", "Return summary, decisions, action items, owners, due dates, and explicit unknowns.", "You are a precise meeting secretary. Never invent an owner, date, or decision.", listOf("Summarize this meeting transcript"), listOf("Audio must be transcribed before this skill can run.")),
            Spec("checklist_generator", "Checklist Generator", "Turn a goal or procedure into a verifiable checklist", "PRODUCTIVITY", listOf("checklist", "steps", "procedure", "قائمة تحقق"), mapOf("input" to "string"), "Convert the supplied goal or procedure into ordered, checkable items with dependencies and completion criteria.", "Return a numbered checklist with acceptance criteria and optional owner fields.", "You are a careful operations planner. Do not add undocumented compliance requirements.", listOf("Create a launch checklist"), listOf("The checklist is a draft until reviewed by the user.")),
            Spec("daily_task_organizer", "Daily Task Organizer", "Organize supplied tasks into a realistic daily plan", "PRODUCTIVITY", listOf("daily tasks", "today", "prioritize", "يومي", "مهام اليوم"), mapOf("input" to "string"), "Group supplied tasks by priority, effort, and dependencies; propose time blocks only when enough timing information is provided.", "Return priorities, sequence, time blocks when supported, and unresolved scheduling constraints.", "You are a planning assistant. Do not create calendar events or reminders from this draft.", listOf("Organize my tasks for today"), listOf("This skill plans only; it does not mutate the calendar.")),
            Spec("final_answer_verification", "Final Answer Verification", "Check a draft answer against supplied evidence and execution results", "AI", listOf("verify answer", "fact check answer", "evidence", "final answer", "تحقق الإجابة"), mapOf("input" to "string"), "Audit the draft answer against the supplied evidence. Separate supported claims, unsupported claims, contradictions, missing caveats, and confidence limits.", "Return a verification verdict, claim-level findings, required corrections, and an uncertainty statement.", "You are a strict answer verifier. Never replace missing evidence with plausible guesses.", listOf("Verify this answer against the cited evidence"), listOf("This skill cannot verify claims without supplied evidence.")),
            Spec("replanning_strategy", "Replanning Strategy", "Generate a bounded recovery plan after a failed or low-confidence step", "AI", listOf("replan", "recovery plan", "failed step", "retry strategy", "إعادة التخطيط"), mapOf("input" to "string"), "Analyze the failed step, its dependencies, available alternatives, and retry budget. Produce a smaller typed recovery plan with explicit stop conditions.", "Return root cause hypotheses, alternative actions, dependency changes, retry budget, and stop conditions.", "You are a conservative planner. Do not repeat a failed action without a changed premise or bounded retry.", listOf("Replan after this tool failure"), listOf("The plan must still pass policy and permission gates."))
        )

        val ids: Set<String> get() = SPECS.map { it.id }.toSet()

        fun spec(id: String): Spec? = SPECS.firstOrNull { it.id == id }
    }
}
