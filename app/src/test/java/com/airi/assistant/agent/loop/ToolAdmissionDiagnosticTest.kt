package com.airi.assistant.agent.loop

import com.airi.assistant.agent.loop.skill.AgentSkillCatalog
import com.airi.assistant.agent.loop.skill.SkillLoadReadiness
import com.airi.assistant.agent.loop.tool.ToolAdmissionDiagnostic
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolAdmissionDiagnosticTest {
    @Test
    fun loadSkillReportsRuntimeReadinessAndNextAction() {
        val result = AgentSkillCatalog.load(
            "document_generator",
            SkillLoadReadiness(
                ready = false,
                blockedReason = "document generator is disabled",
                nextAction = "enable_skill",
            ),
        ).getOrThrow()

        assertTrue(result.contains("READINESS: not_ready"))
        assertTrue(result.contains("BLOCKED_REASON: document generator is disabled"))
        assertTrue(result.contains("NEXT_ACTION: enable_skill"))
        assertTrue(result.contains("## Skill-specific procedure"))
        assertTrue(result.contains("generate_document"))
    }

    @Test
    fun loadSkillWithoutRuntimeStateDoesNotPromiseExecution() {
        val result = AgentSkillCatalog.load("web_search").getOrThrow()
        assertTrue(result.contains("READINESS: not_checked"))
        assertTrue(result.contains("NEXT_ACTION: check_runtime_status"))
    }

    @Test
    fun diagnosticListsEveryRequiredToolInHumanAndMachineForms() {
        val diagnostics = listOf(
            ToolAdmissionDiagnostic("terminal_execute", true, false, false, "sandbox unavailable", "enable_terminal_capability"),
            ToolAdmissionDiagnostic("write_file", false, false, false, "required tool is not registered", "enable_write_file_capability"),
        )
        val machine = diagnostics.joinToString("\n") { it.machineLine() }
        val human = diagnostics.joinToString("\n") { it.humanLine(arabic = false) }

        assertTrue(machine.contains("terminal_execute: registered=true; ready=false; exposed=false"))
        assertTrue(machine.contains("write_file: registered=false; ready=false; exposed=false"))
        assertTrue(human.contains("Tool terminal_execute"))
        assertTrue(human.contains("next action: enable_write_file_capability"))
    }
}
