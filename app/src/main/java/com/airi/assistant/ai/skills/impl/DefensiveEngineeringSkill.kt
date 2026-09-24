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
 * Evidence-first programming and defensive-security analysis.
 *
 * This executor is intentionally analysis-only: it does not run commands,
 * scan targets, exploit vulnerabilities, contact hosts, or modify repositories.
 * Security tasks produce remediation and safe validation guidance only.
 */
class DefensiveEngineeringSkill(private val spec: Spec) : AiriSkill {
    override val skillId = spec.id
    override val name = spec.name
    override val displayName = spec.name
    override val description = spec.description
    override val version = "1.0.0"
    override val author = "AIRI Official"
    override val category = spec.category
    override val isOfficial = true
    override val memoryAccess = SkillMemoryAccess.NONE
    override val modelAccess = SkillModelAccess.CHAT
    override val parameters = mapOf("input" to "string")
    override val inputSchema = parameters
    override val outputSchema = mapOf("findings" to "string", "safe_actions" to "string", "verification" to "string")
    override val instructions = spec.stages.joinToString(" ") { it.instruction }
    override val examples = spec.examples
    override val limitations = spec.limitations
    override val riskLevel = if (spec.defensive) SkillRiskLevel.MEDIUM else SkillRiskLevel.LOW
    override val requiresConfirmation = false
    override val supportsStreaming = false
    override val supportsAttachments = false
    override val toolDefinitions = listOf(
        SkillToolDefinition(
            name = spec.id,
            description = spec.description,
            parameters = mapOf("input" to SkillParamDef("string", "Source, logs, configuration, or report", required = true)),
            dangerous = false
        )
    )

    override fun score(input: String, context: SkillContext): Int {
        val lower = input.lowercase()
        val hits = spec.keywords.count { lower.contains(it) }
        return (hits * 28 + if (context.lastUsedSkill == skillId) 10 else 0).coerceIn(0, 100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val startedAt = System.currentTimeMillis()
        val context = params["context"] as? SkillContext
            ?: return failure("Skill context is required for this analysis.", startedAt)
        val input = params["input"] as? String
            ?: return failure("No source, log, configuration, or report was provided.", startedAt)
        if (input.isBlank()) return failure("Input cannot be blank.", startedAt)
        val bridge = context.modelBridge
            ?: return failure("An active AI model is required for this analysis.", startedAt)

        var evidence = input.trim()
        return try {
            spec.stages.forEachIndexed { index, stage ->
                val prompt = buildString {
                    append(stage.instruction)
                    append("\n\nSupplied evidence:\n")
                    append(evidence)
                    append("\n\nRequired output contract:\n")
                    append(stage.outputContract)
                    append("\nSeparate observed evidence, inference, and unknowns.")
                    append("\nNever invent file locations, versions, indicators, logs, or test results.")
                    if (spec.defensive) {
                        append("\nDefensive boundary: do not probe, scan, exploit, persist, evade, exfiltrate, or target any system.")
                        append("\nDo not output exploit payloads, malware, credential theft steps, or operational attack instructions.")
                        append("\nOnly provide remediation, detection, containment, lab-safe validation, or authorized test planning.")
                    }
                    if (index == spec.stages.lastIndex) append("\nReturn the final report only.")
                }
                val result = try {
                    bridge.complete(prompt, spec.systemPrompt, spec.maxTokens)
                } catch (error: Exception) {
                    throw IllegalStateException(
                        "Analysis stage ${index + 1} failed: ${error.message ?: "unknown model error"}",
                        error
                    )
                }
                if (result.isBlank()) error("Analysis stage ${index + 1} returned an empty result")
                evidence = result.trim()
            }
            SkillResult(
                success = true,
                data = evidence,
                skillName = skillId,
                executionMs = System.currentTimeMillis() - startedAt,
                metadata = mapOf(
                    "category" to spec.category,
                    "defensive_only" to spec.defensive.toString(),
                    "model_execution" to "active_bridge",
                    "stages" to spec.stages.size.toString()
                )
            )
        } catch (error: Exception) {
            failure("Engineering skill failed: ${error.message ?: "unknown error"}", startedAt)
        }
    }

    private fun failure(message: String, startedAt: Long) = SkillResult(
        success = false,
        data = "",
        error = message,
        skillName = skillId,
        executionMs = System.currentTimeMillis() - startedAt
    )

    data class Stage(val instruction: String, val outputContract: String)

    data class Spec(
        val id: String,
        val name: String,
        val description: String,
        val category: String,
        val keywords: List<String>,
        val stages: List<Stage>,
        val systemPrompt: String,
        val examples: List<String>,
        val limitations: List<String>,
        val defensive: Boolean,
        val maxTokens: Int = 1200
    )

    companion object {
        private fun programmingStages(subject: String, finding: String, action: String) = listOf(
            Stage("Inventory the supplied $subject and identify contracts, dependencies, and missing context.", "A concise inventory separating observed facts from assumptions."),
            Stage("Analyze the inventory for $finding. Rank issues by impact, confidence, and reproducibility.", "Findings with evidence, severity, affected area, and confidence."),
            Stage("Produce $action. Prefer incremental, reviewable changes and include a deterministic verification test.", "Prioritized actions, concrete implementation guidance, risks, and verification tests.")
        )

        private fun securityStages(subject: String, finding: String, action: String) = listOf(
            Stage("Inventory the supplied $subject, assets, trust boundaries, actors, and observable indicators.", "A defensive asset and evidence inventory with unknowns explicitly marked."),
            Stage("Analyze the inventory for $finding using least privilege, secure defaults, and evidence-based severity.", "Defensive findings with evidence, impact, confidence, and affected control."),
            Stage("Produce $action without interacting with any target. Include containment, remediation, detection, and safe authorized validation.", "A defensive action plan with owner-neutral steps, safe verification, residual risk, and escalation criteria.")
        )

        private fun p(id: String, name: String, description: String, keywords: List<String>, stages: List<Stage>, examples: List<String>, limitations: List<String>) = Spec(id, name, description, "DEVELOPER", keywords, stages, "You are a careful software engineer. Do not claim execution, compilation, repository access, or test results that were not supplied.", examples, limitations, defensive = false)
        private fun s(id: String, name: String, description: String, keywords: List<String>, stages: List<Stage>, examples: List<String>, limitations: List<String>) = Spec(id, name, description, "SECURITY", keywords, stages, "You are a defensive cybersecurity analyst. Work only on supplied evidence or explicitly authorized lab context. Never provide operational attack instructions.", examples, limitations, defensive = true)

        val SPECS = listOf(
            p("debugging", "Debugging Assistant", "Localize software defects from symptoms, logs, and a minimal reproduction", listOf("debug", "bug", "exception", "stack trace", "تصحيح", "خطأ"), programmingStages("bug report or reproduction", "root-cause candidates, broken invariants, and data-flow mismatches", "a minimal fix hypothesis, reproduction test, and regression guard"), listOf("Debug this crash from the stack trace"), listOf("Does not execute the program or guarantee the root cause without a reproduction.")),
            p("test_generation", "Test Generation", "Generate focused tests from behavior and contracts", listOf("generate tests", "test cases", "test generation", "توليد اختبارات"), programmingStages("requirements and code behavior", "untested branches, boundary values, error paths, and observable contracts", "unit or integration test cases with fixtures and expected outcomes"), listOf("Generate tests for this parser"), listOf("Generated tests require review and project-specific dependencies.")),
            p("unit_test_analysis", "Unit Test Analysis", "Evaluate unit-test quality, coverage of behavior, and brittleness", listOf("unit test", "test quality", "coverage", "اختبار وحدة"), programmingStages("unit tests and the code they exercise", "missing behavior, weak assertions, hidden coupling, flaky setup, and over-mocking", "a prioritized test-quality improvement plan with focused additions"), listOf("Review these unit tests"), listOf("Coverage percentage alone is not a proof of correctness.")),
            p("dependency_analysis", "Dependency Analysis", "Analyze dependency roles, coupling, lifecycle, and upgrade risk", listOf("dependency", "library", "version", "coupling", "اعتمادية", "مكتبة"), programmingStages("dependency declarations and usage evidence", "unused or duplicated libraries, version conflicts, license unknowns, and unsafe coupling", "a conservative dependency cleanup or upgrade plan with compatibility checks"), listOf("Analyze this Gradle dependency list"), listOf("Does not resolve packages or verify licenses without supplied metadata.")),
            p("api_integration", "API Integration Planner", "Design safe API integration contracts and adapters", listOf("api integration", "http api", "rest", "oauth", "تكامل api"), programmingStages("API documentation and client requirements", "contract mismatches, auth handling, retries, idempotency, timeouts, and error mapping", "an adapter plan with request/response schemas, failure handling, and contract tests"), listOf("Plan integration with this REST API"), listOf("Does not call external APIs or store credentials.")),
            p("git_workflow", "Git Workflow Assistant", "Plan safe branching, commits, review, and rollback workflows", listOf("git", "branch", "commit", "pull request", "merge", "git workflow"), programmingStages("repository state and change intent", "unsafe history edits, mixed concerns, missing review gates, and rollback gaps", "a sequence of small commits and safe commands with preconditions"), listOf("Plan commits for this change"), listOf("It does not execute Git commands or push changes.")),
            p("repository_analysis", "Repository Analysis", "Map a repository's architecture, ownership boundaries, and change hotspots", listOf("repository", "codebase", "repo analysis", "تحليل مستودع"), programmingStages("repository tree, entry points, modules, and supplied metadata", "architectural boundaries, dependency flow, duplication, and risk hotspots", "an evidence-linked repository map and prioritized investigation plan"), listOf("Analyze this repository structure"), listOf("Requires repository files or an exported tree; it cannot inspect an unavailable repository.")),
            p("error_log_analysis", "Error Log Analysis", "Normalize logs into error signatures, probable causes, and next evidence", listOf("error log", "logs", "exception log", "تحليل السجل", "سجل الأخطاء"), programmingStages("error logs and timestamps", "recurring signatures, causal ordering, noise, and missing correlation identifiers", "a triage table and safe next diagnostic collection"), listOf("Analyze these production logs"), listOf("Never expose secrets from logs; redact them before sharing.")),
            p("documentation_generation", "Documentation Generation", "Generate maintainable technical documentation from code and contracts", listOf("documentation", "readme", "technical docs", "توثيق", "مستندات تقنية"), programmingStages("source contracts and intended audience", "missing prerequisites, stale claims, undocumented errors, and ambiguous examples", "structured documentation with examples, limitations, and update triggers"), listOf("Generate API documentation"), listOf("Documentation cannot prove behavior that is absent from the supplied evidence.")),
            s("security_code_review", "Security Code Review", "Review source code for defensive security weaknesses and safer alternatives", listOf("security code review", "secure code", "appsec code", "مراجعة كود أمني"), securityStages("source code and security-sensitive data flows", "trust-boundary violations, injection hazards, access-control gaps, secret handling, and unsafe defaults", "secure code changes, regression tests, and authorized static or unit validation"), listOf("Review this authentication code securely"), listOf("No exploit payloads, bypass instructions, or active testing.")),
            s("vulnerability_analysis", "Vulnerability Analysis", "Classify defensive vulnerability evidence and remediation priority", listOf("vulnerability", "weakness", "cve", "ثغرة", "تحليل ثغرات"), securityStages("reported vulnerability or code evidence", "exploitability claims, affected assets, impact, and evidence quality", "risk treatment, remediation, detection, and authorized validation"), listOf("Assess this vulnerability report"), listOf("No exploitation, scanning, or payload generation.")),
            s("dependency_vulnerability_check", "Dependency Vulnerability Check", "Review supplied dependency versions against known-risk evidence", listOf("dependency vulnerability", "cve dependency", "supply chain", "ثغرات الاعتماديات"), securityStages("dependency manifest and advisory evidence", "affected versions, transitive exposure, reachability assumptions, and false positives", "upgrade, pinning, mitigation, and evidence-preserving verification"), listOf("Review this dependency advisory"), listOf("Does not query registries or claim current CVE status without supplied sources.")),
            s("secure_configuration_review", "Secure Configuration Review", "Review application and infrastructure configuration for secure defaults", listOf("secure configuration", "hardening", "configuration review", "إعدادات آمنة"), securityStages("configuration files and deployment context", "exposed secrets, insecure defaults, excessive permissions, and unsafe network settings", "least-privilege remediation and a non-invasive configuration test"), listOf("Review this production configuration"), listOf("Secrets must be redacted; it does not connect to infrastructure.")),
            s("owasp_analysis", "OWASP Analysis", "Map supplied application behavior to OWASP-style defensive risks", listOf("owasp", "web security", "appsec", "أمان التطبيقات"), securityStages("application flow and evidence", "injection, access control, authentication, data exposure, and integrity risks", "defensive controls, secure coding changes, and authorized test cases"), listOf("Map this API to OWASP risks"), listOf("It is a review aid, not a penetration test or current OWASP compliance certification.")),
            s("incident_triage", "Incident Triage", "Prioritize a reported security incident and define safe first response", listOf("incident triage", "security incident", "containment", "فرز حادث أمني", "احتواء"), securityStages("incident report, timeline, and affected assets", "urgency, blast-radius evidence, containment needs, and missing facts", "a severity-ranked first-response plan with stop conditions, escalation, and evidence preservation"), listOf("Triage this security incident"), listOf("Does not contact systems, isolate hosts, or execute response actions.")),
            s("security_log_analysis", "Security Log Analysis", "Analyze security-relevant logs for defensive signals and gaps", listOf("security logs", "audit log", "authentication log", "تحليل سجلات الأمن"), securityStages("security logs and event context", "suspicious sequences, failed controls, missing telemetry, and confidence limits", "detection queries, containment suggestions, and privacy-preserving evidence handling"), listOf("Analyze these authentication logs"), listOf("Do not include credentials or personal data in supplied logs.")),
            s("ioc_analysis", "IOC Analysis", "Normalize and assess indicators of compromise defensively", listOf("ioc", "indicator of compromise", "hash", "domain", "مؤشر اختراق"), securityStages("indicators and provenance", "indicator type, confidence, age, context, and likely false-positive conditions", "defensive enrichment requests, containment, and safe retrospective checks"), listOf("Analyze these IOCs"), listOf("Does not scan, beacon, detonate, or contact indicators.")),
            s("malware_report_analysis", "Malware Report Analysis", "Summarize malware reports into defensive behaviors and mitigations", listOf("malware report", "malware analysis", "ransomware", "تقرير برمجية خبيثة"), securityStages("malware report and supplied observations", "behaviors, persistence, impact, evidence quality, and defensive controls", "containment, recovery, detection, and communication recommendations"), listOf("Summarize this malware report for defenders"), listOf("Does not generate malware, reverse-engineer an unknown sample, or provide evasion steps.")),
            s("security_documentation", "Security Documentation", "Generate threat-aware security runbooks and control documentation", listOf("security documentation", "security runbook", "control document", "توثيق أمني"), securityStages("security design, controls, and audience", "missing ownership, assumptions, control gaps, and unsafe operational ambiguity", "a defensive runbook with prerequisites, escalation, evidence handling, and rollback"), listOf("Create a security runbook"), listOf("It documents approved procedures; it does not authorize new access.")),
            s("ctf_lab_assistant", "CTF and Lab Assistant", "Support authorized educational labs with safe explanations and remediation", listOf("ctf", "lab", "sandbox", "capture the flag", "مختبر أمني"), securityStages("authorized lab prompt and scope", "concepts, defensive learning objectives, and scope boundaries", "a lab-safe learning plan, hints, and remediation explanation without real-target instructions"), listOf("Explain this authorized lab concept"), listOf("Only sandbox or explicitly authorized lab context; no real-target exploitation or persistence.")),
            s("network_security_analysis", "Network Security Analysis", "Review supplied network designs, flows, and alerts defensively", listOf("network security", "firewall", "network flow", "شبكة", "أمن الشبكات"), securityStages("network diagram, flow, or alert evidence", "trust boundaries, exposed services, segmentation gaps, and detection blind spots", "hardening, segmentation, monitoring, and safe validation steps"), listOf("Review this network flow diagram"), listOf("Does not scan hosts, enumerate targets, or generate attack traffic.")),
            s("security_testing_planner", "Security Testing Planner", "Plan authorized, bounded security tests with safety gates", listOf("security testing", "security test plan", "authorized test", "اختبار أمني"), securityStages("system scope, authorization, and risk constraints", "test objectives, out-of-scope assets, safety gates, and evidence requirements", "a non-destructive test plan with stop conditions and remediation outputs"), listOf("Plan an authorized security test"), listOf("Planning only; authorization must be verified outside the model.")),
            s("privacy_security_audit", "Privacy and Security Audit", "Audit data flows, minimization, access, retention, and security controls", listOf("privacy audit", "security audit", "data flow", "خصوصية", "تدقيق أمني"), securityStages("data flow, storage, permissions, and policy evidence", "collection excess, retention gaps, access boundaries, and control mismatches", "privacy-preserving remediation, evidence requests, and audit-ready verification"), listOf("Audit this data flow"), listOf("Not legal advice and not a certification; jurisdiction and policy evidence are required."))
        )
    }
}
