package com.airi.assistant.agent.loop.verification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeTaskGateTest {

    private val goodProgram = "def main():\n    print(\"hi\")\n\nmain()\n"
    private val brokenProgram = "def main()\n    print(\"hi\")\n"

    private fun write(name: String, content: String) = mapOf("filename" to name, "content" to content)

    @Test
    fun textWithoutAnyFileIsCorrectedThenRejected() {
        val gate = CodeTaskGate(maxCorrections = 2)
        val first = gate.onFinalText(canContinue = true)
        assertTrue(first is CodeTaskGate.Verdict.Correct)
        assertEquals(CodeTaskGate.Reason.TOOL_REQUIRED_NOT_CALLED, (first as CodeTaskGate.Verdict.Correct).reason)
        assertTrue(gate.onFinalText(canContinue = true) is CodeTaskGate.Verdict.Correct)
        val third = gate.onFinalText(canContinue = true)
        assertTrue(third is CodeTaskGate.Verdict.Reject)
        assertEquals(CodeTaskGate.Reason.TOOL_REQUIRED_NOT_CALLED, (third as CodeTaskGate.Verdict.Reject).reason)
    }

    @Test
    fun savedAndCheckedFileIsAccepted() {
        val gate = CodeTaskGate()
        val feedback = gate.onWriteFile(write("calc.py", goodProgram), succeeded = true)
        assertNotNull(feedback)
        assertTrue(feedback!!.contains("passed"))
        assertTrue(feedback.contains("was not run"))
        assertEquals(CodeTaskGate.Verdict.Accept, gate.onFinalText(canContinue = true))
    }

    @Test
    fun failedSyntaxCheckBlocksFinalAnswerUntilFixed() {
        val gate = CodeTaskGate()
        val feedback = gate.onWriteFile(write("calc.py", brokenProgram), succeeded = true)
        assertTrue(feedback!!.contains("FAILED"))
        assertTrue(feedback.contains("overwrite"))
        val verdict = gate.onFinalText(canContinue = true)
        assertTrue(verdict is CodeTaskGate.Verdict.Correct)
        assertEquals(CodeTaskGate.Reason.SYNTAX_CHECK_FAILED, (verdict as CodeTaskGate.Verdict.Correct).reason)
        assertTrue(verdict.prompt.contains("calc.py"))
        assertTrue(verdict.prompt.contains("overwrite"))

        gate.onWriteFile(write("calc.py", goodProgram), succeeded = true)
        assertEquals(CodeTaskGate.Verdict.Accept, gate.onFinalText(canContinue = true))
    }

    @Test
    fun rejectionCarriesNoticeInTheRightLanguage() {
        val gate = CodeTaskGate(maxCorrections = 0)
        val arabic = gate.onFinalText(canContinue = true, preferArabic = true) as CodeTaskGate.Verdict.Reject
        assertTrue(arabic.notice.contains("تنبيه"))
        val english = gate.onFinalText(canContinue = true, preferArabic = false) as CodeTaskGate.Verdict.Reject
        assertTrue(english.notice.startsWith("Note:"))
    }

    @Test
    fun noStepsLeftRejectsInsteadOfCorrecting() {
        val gate = CodeTaskGate()
        assertTrue(gate.onFinalText(canContinue = false) is CodeTaskGate.Verdict.Reject)
    }

    @Test
    fun failedWriteDoesNotCountAsSaved() {
        val gate = CodeTaskGate()
        assertNull(gate.onWriteFile(write("calc.py", goodProgram), succeeded = false))
        assertTrue(gate.onFinalText(canContinue = true) is CodeTaskGate.Verdict.Correct)
    }

    @Test
    fun pendingApprovalIsNotPushedToRetry() {
        val gate = CodeTaskGate()
        gate.onWriteFile(write("calc.py", goodProgram), succeeded = false, approvalPending = true)
        assertEquals(CodeTaskGate.Verdict.Accept, gate.onFinalText(canContinue = true))
    }

    @Test
    fun nonPythonFileIsSavedButNotChecked() {
        val gate = CodeTaskGate()
        assertNull(gate.onWriteFile(write("notes.txt", "hello"), succeeded = true))
        assertEquals(CodeTaskGate.Verdict.Accept, gate.onFinalText(canContinue = true))
    }

    @Test
    fun laterNonPythonWriteDoesNotHideAFailingPythonFile() {
        val gate = CodeTaskGate()
        gate.onWriteFile(write("calc.py", brokenProgram), succeeded = true)
        gate.onWriteFile(write("notes.txt", "hello"), succeeded = true)
        assertTrue(gate.onFinalText(canContinue = true) is CodeTaskGate.Verdict.Correct)
    }

    @Test
    fun argumentNamesAreMatchedCaseInsensitively() {
        val gate = CodeTaskGate()
        val feedback = gate.onWriteFile(mapOf("File_Path" to "a.py", "Content" to brokenProgram), succeeded = true)
        assertTrue(feedback!!.contains("FAILED"))
    }

    @Test
    fun arabicDetection() {
        assertTrue(CodeTaskGate.looksArabic("قم بإنشاء كود"))
        assertTrue(!CodeTaskGate.looksArabic("build a calculator"))
    }

    // ── Phase 2: requirements and phases ────────────────────────────────────

    private val calcRequirements = com.airi.assistant.agent.loop.plan.RequirementExtractor.extract(
        "قم بإنشاء كود لآلة حاسبة بلغة python مع 3 ثيمات ووضع علمي"
    )

    private val oneThemeProgram =
        "import math\nTHEMES = {\"dark\": {\"bg\": \"#111111\"}}\nprint(math.sin(1), math.cos(1), math.sqrt(4))\n"
    private val fullProgram =
        "import math\nTHEMES = {\"dark\": {\"bg\": \"#111111\"}, \"light\": {\"bg\": \"#ffffff\"}, \"blue\": {\"bg\": \"#0000ff\"}}\n" +
            "print(math.sin(1), math.cos(1), math.sqrt(4))\n"

    @Test
    fun missingRequirementBlocksFinalAnswerUntilTheFileShowsIt() {
        val gate = CodeTaskGate(requirements = calcRequirements)
        val feedback = gate.onWriteFile(write("calc.py", oneThemeProgram), succeeded = true)
        assertTrue(feedback!!.contains("Still missing"))
        val verdict = gate.onFinalText(canContinue = true)
        assertTrue(verdict is CodeTaskGate.Verdict.Correct)
        assertEquals(CodeTaskGate.Reason.REQUIREMENTS_NOT_MET, (verdict as CodeTaskGate.Verdict.Correct).reason)
        assertTrue(verdict.prompt.contains("themes"))

        gate.onWriteFile(write("calc.py", fullProgram), succeeded = true)
        assertEquals(CodeTaskGate.Verdict.Accept, gate.onFinalText(canContinue = true))
        assertTrue(gate.unmetRequirements().isEmpty())
    }

    @Test
    fun unmetRequirementIsRejectedWithAVisibleNoticeWhenCorrectionsRunOut() {
        val gate = CodeTaskGate(maxCorrections = 0, requirements = calcRequirements)
        gate.onWriteFile(write("calc.py", oneThemeProgram), succeeded = true)
        val verdict = gate.onFinalText(canContinue = true, preferArabic = true)
        assertTrue(verdict is CodeTaskGate.Verdict.Reject)
        assertEquals(CodeTaskGate.Reason.REQUIREMENTS_NOT_MET, (verdict as CodeTaskGate.Verdict.Reject).reason)
        assertTrue(verdict.notice.contains("ثيمات"))
    }

    @Test
    fun phasesFollowPlanActVerifyFinalWithACorrectionLoop() {
        val gate = CodeTaskGate(requirements = calcRequirements)
        assertEquals(com.airi.assistant.agent.loop.plan.AgentPhase.PLAN, gate.phase.current)
        gate.onWriteFile(write("calc.py", oneThemeProgram), succeeded = true)
        gate.onFinalText(canContinue = true)
        assertEquals(com.airi.assistant.agent.loop.plan.AgentPhase.ACT, gate.phase.current)
        gate.onWriteFile(write("calc.py", fullProgram), succeeded = true)
        gate.onFinalText(canContinue = true)
        assertEquals(com.airi.assistant.agent.loop.plan.AgentPhase.FINAL, gate.phase.current)
        assertEquals(
            listOf(
                com.airi.assistant.agent.loop.plan.AgentPhase.PLAN,
                com.airi.assistant.agent.loop.plan.AgentPhase.ACT,
                com.airi.assistant.agent.loop.plan.AgentPhase.VERIFY,
                com.airi.assistant.agent.loop.plan.AgentPhase.ACT,
                com.airi.assistant.agent.loop.plan.AgentPhase.VERIFY,
                com.airi.assistant.agent.loop.plan.AgentPhase.FINAL,
            ),
            gate.phase.history,
        )
    }

    @Test
    fun noRequirementsKeepsPhaseOneBehaviour() {
        val gate = CodeTaskGate()
        assertEquals(null, gate.requirementStatus())
        gate.onWriteFile(write("calc.py", goodProgram), succeeded = true)
        assertEquals(CodeTaskGate.Verdict.Accept, gate.onFinalText(canContinue = true))
    }
}
