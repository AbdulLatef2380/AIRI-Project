package com.airi.assistant.agent.request

import com.airi.assistant.ai.QueryType
import com.airi.assistant.execution.ExecutionRequest.ToolChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRequestPlannerTest {
    private val tools = setOf("write_file", "check_syntax", "terminal_execute", "web_search", "current_time", "memory_recall")

    @Test
    fun arabicCalculatorRequestIsACodeTaskThatForcesWriteFile() {
        val plan = AgentRequestPlanner.plan(
            "قم بإنشاء كود لآلة حاسبة متقدمة بلغة python", ModelToolSupport.NATIVE, tools,
        )
        assertEquals(RequestPlan.Kind.CODE_TASK, plan.kind)
        assertEquals(QueryType.ACTION, plan.queryType)
        assertEquals(setOf("write_file"), plan.requiredTools)
        assertTrue("write_file" in plan.exposedTools)
        assertEquals(ToolChoice.REQUIRED, plan.toolChoice)
        assertNull(plan.unfulfillable)
    }

    @Test
    fun greetingGetsNoToolsAndSaysWhy() {
        val plan = AgentRequestPlanner.plan("مرحبا", ModelToolSupport.NATIVE, tools)
        assertEquals(RequestPlan.Kind.CHAT, plan.kind)
        assertTrue(plan.exposedTools.isEmpty())
        assertEquals(ToolChoice.NONE, plan.toolChoice)
        assertTrue(plan.blockedTools.values.all { it == RequestPlan.BlockReason.CONVERSATION_ONLY })
        assertEquals(tools, plan.blockedTools.keys)
    }

    @Test
    fun modelWithoutToolsIsReportedNotSilentlyAnswered() {
        val plan = AgentRequestPlanner.plan("write a python script for a calculator", ModelToolSupport.NONE, tools)
        assertEquals(RequestPlan.Kind.CODE_TASK, plan.kind)
        assertEquals(RequestPlan.Unfulfillable.MODEL_HAS_NO_TOOLS, plan.unfulfillable)
        assertTrue(plan.exposedTools.isEmpty())
        assertTrue(plan.blockedTools.values.all { it == RequestPlan.BlockReason.MODEL_HAS_NO_TOOLS })
    }

    @Test
    fun textProtocolModelIsNotForcedButStillRequiresTheTool() {
        val plan = AgentRequestPlanner.plan("write a python script for a calculator", ModelToolSupport.TEXT_PROTOCOL, tools)
        assertEquals(ToolChoice.AUTO, plan.toolChoice)
        assertEquals(setOf("write_file"), plan.requiredTools)
    }

    @Test
    fun missingRequiredToolIsRecorded() {
        val plan = AgentRequestPlanner.plan(
            "write a python script for a calculator", ModelToolSupport.NATIVE, setOf("web_search"),
        )
        assertEquals(RequestPlan.Unfulfillable.REQUIRED_TOOL_MISSING, plan.unfulfillable)
        assertEquals(RequestPlan.BlockReason.NOT_AVAILABLE, plan.blockedTools["write_file"])
    }

    @Test
    fun requestedGithubConnectorToolsAreRetainedByThePlanner() {
        val plan = AgentRequestPlanner.plan(
            "اقرأ مستودعاتي في GitHub",
            ModelToolSupport.NATIVE,
            setOf("connector_github_list_repos", "connector_google_gmail_list"),
        )

        assertEquals(RequestPlan.Kind.ACTION, plan.kind)
        assertTrue("connector_github_list_repos" in plan.exposedTools)
        assertTrue("connector_google_gmail_list" !in plan.exposedTools)
        assertNull(plan.unfulfillable)
    }

    @Test
    fun requestedGmailConnectorToolsAreRetainedForWriteRequestsWithoutOtherConnectors() {
        val plan = AgentRequestPlanner.plan(
            "أرسل رسالة عبر Gmail",
            ModelToolSupport.NATIVE,
            setOf("connector_google_gmail_send", "connector_github_create_issue"),
        )

        assertTrue("connector_google_gmail_send" in plan.exposedTools)
        assertTrue("connector_github_create_issue" !in plan.exposedTools)
        assertNull(plan.unfulfillable)
    }

    @Test
    fun requestedConnectorWithNoMatchingRuntimeToolFailsClosed() {
        val plan = AgentRequestPlanner.plan(
            "اقرأ بريدي في Gmail",
            ModelToolSupport.NATIVE,
            setOf("connector_github_list_repos"),
        )

        assertEquals(RequestPlan.Unfulfillable.REQUIRED_TOOL_MISSING, plan.unfulfillable)
        assertTrue(plan.exposedTools.isEmpty())
    }

    @Test
    fun explanationQuestionAboutCodeStaysChat() {
        val plan = AgentRequestPlanner.plan("كيف اكتب كود بايثون؟", ModelToolSupport.NATIVE, tools)
        assertEquals(RequestPlan.Kind.CHAT, plan.kind)
        assertEquals(ToolChoice.NONE, plan.toolChoice)
    }
}
