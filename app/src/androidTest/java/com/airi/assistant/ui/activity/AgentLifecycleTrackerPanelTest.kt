package com.airi.assistant.ui.activity

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.material3.MaterialTheme
import androidx.test.platform.app.InstrumentationRegistry
import com.airi.assistant.R
import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import com.airi.assistant.ui.trace.UnifiedExecutionTraceEvent
import com.airi.assistant.ui.trace.UnifiedExecutionTraceGroup
import com.airi.assistant.ui.trace.UnifiedExecutionTraceProjection
import com.airi.assistant.ui.trace.UnifiedExecutionTraceSummary
import com.airi.assistant.ui.trace.UnifiedExecutionTraceTaskNode
import com.airi.assistant.ui.trace.UnifiedTraceCategory
import com.airi.assistant.ui.trace.InlineExecutionTrace
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AgentLifecycleTrackerPanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersSummaryAndAllProjectionGroups() {
        setPanel(isWorking = false)

        composeRule.onNodeWithText(localized(R.string.agent_trace_summary_files, 1), substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(localized(R.string.agent_trace_summary_tasks, 2), substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("agent-trace-summary").performClick()
        assertGroupVisible(groupDescription(R.string.agent_trace_group_files, 1), index = 0)
        assertGroupVisible(groupDescription(R.string.agent_trace_group_commands, 1), index = 1)
        assertGroupVisible(groupDescription(R.string.agent_trace_group_tools, 1), index = 2)
        assertGroupVisible(groupDescription(R.string.agent_trace_group_search, 1), index = 3)
        assertGroupVisible(groupDescription(R.string.agent_trace_group_memory, 1), index = 4)
        // PLAN and TASK are distinct projector categories but share the Tasks
        // presentation title, so each has its own one-event header.
        composeRule.onNodeWithTag("agent-trace-list")
            .performScrollToIndex(5)
        composeRule.onAllNodesWithContentDescription(groupDescription(R.string.agent_trace_group_tasks, 1))
            .assertCountEquals(2)
    }

    @Test
    fun detailsAreCollapsedUntilTheSummaryIsOpened() {
        setPanel(isWorking = false)

        assertTrue(composeRule.onAllNodesWithTag("agent-trace-list").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("agent-trace-summary").performClick()
        composeRule.onNodeWithTag("agent-trace-list").assertIsDisplayed()
    }

    @Test
    fun rendersRealStepWithChildToolInsteadOfPlanAndTaskGroups() {
        composeRule.setContent {
            MaterialTheme {
                AgentLifecycleTrackerPanel(
                    projection = treeProjection(),
                    isWorking = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        assertTrue(composeRule.onAllNodesWithContentDescription(groupDescription(R.string.agent_trace_group_tasks, 1)).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("agent-trace-summary").performClick()
        composeRule.onNodeWithText("Read the inbox").assertIsDisplayed()
        composeRule.onNodeWithText("Tool action").assertIsDisplayed()
        composeRule.onNodeWithText("gmail_read", substring = true).assertIsDisplayed()
    }

    @Test
    fun groupExpansionShowsEventsAndCollapseHidesThem() {
        setPanel(isWorking = false)

        assertTrue(composeRule.onAllNodesWithText("Read file").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("agent-trace-summary").performClick()
        composeRule.onNodeWithContentDescription(groupDescription(R.string.agent_trace_group_files, 1)).performClick()
        composeRule.onNodeWithText("Read file").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(groupDescription(R.string.agent_trace_group_files, 1, expanded = true)).performClick()
        assertTrue(composeRule.onAllNodesWithText("Read file").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun technicalToolNameIsSeparateFromHumanTitleAndDetailsAreLabeled() {
        composeRule.setContent {
            MaterialTheme {
                AgentLifecycleTrackerPanel(
                    projection = technicalToolProjection(),
                    isWorking = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        composeRule.onNodeWithTag("agent-trace-summary").performClick()
        composeRule.onNodeWithContentDescription(groupDescription(R.string.agent_trace_group_tools, 1)).performClick()
        composeRule.onNodeWithText("Tool action").assertIsDisplayed()
        composeRule.onNodeWithText("Technical name").assertIsDisplayed()
        composeRule.onNodeWithText("skill_github_repository_read", substring = false).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Tool action. skill_github_repository_read. Failed. Show event details")
            .performClick()
        composeRule.onNodeWithText("Details").assertIsDisplayed()
        composeRule.onNodeWithText("Repository access was denied").assertIsDisplayed()
    }

    @Test
    fun statusSemanticsExposeWaitingRunningFailedAndCancelled() {
        setPanel(isWorking = true)

        composeRule.onNodeWithTag("agent-trace-summary").performClick()
        expandFixtureGroups()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertStatusVisible(context.getString(R.string.agent_lifecycle_status_waiting))
        assertStatusVisible(context.getString(R.string.agent_lifecycle_status_running))
        assertStatusVisible(context.getString(R.string.agent_lifecycle_status_failed))
        assertStatusVisible(context.getString(R.string.agent_lifecycle_status_cancelled))
    }

    @Test
    fun rtlLayoutKeepsGroupControlsAccessible() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    AgentLifecycleTrackerPanel(
                        projection = fixtureProjection(),
                        isWorking = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        composeRule.onNodeWithTag("agent-trace-summary").performClick()
        assertGroupVisible(groupDescription(R.string.agent_trace_group_files, 1), index = 0)
    }

    @Test
    fun rtlLayoutKeepsTechnicalIdentifierInSeparateLtrTextNode() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    AgentLifecycleTrackerPanel(
                        projection = technicalToolProjection(),
                        isWorking = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        composeRule.onNodeWithTag("agent-trace-summary").performClick()
        composeRule.onNodeWithContentDescription(groupDescription(R.string.agent_trace_group_tools, 1)).performClick()
        composeRule.onNodeWithTag("agent-trace-technical-token", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("skill_github_repository_read", substring = false).assertIsDisplayed()
    }

    @Test
    fun largeFontScaleKeepsSummaryAndGroupSemanticsVisible() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 2f),
            ) {
                MaterialTheme {
                    AgentLifecycleTrackerPanel(
                        projection = fixtureProjection(),
                        isWorking = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        composeRule.onNodeWithText(localized(R.string.agent_trace_summary_files, 1), substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("agent-trace-summary").performClick()
        assertGroupVisible(groupDescription(R.string.agent_trace_group_files, 1), index = 0)
    }

    @Test
    fun fiveThousandEventsRenderThroughBoundedLazyGroups() {
        composeRule.setContent {
            MaterialTheme {
                AgentLifecycleTrackerPanel(
                    projection = stressProjection(),
                    // Keep groups collapsed initially. Expanding all five
                    // groups at once would intentionally create 5,000 rows
                    // in one test process and does not verify lazy bounds.
                    isWorking = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        composeRule.onNodeWithText(localized(R.string.agent_trace_summary_files, 1_000)).assertIsDisplayed()
        composeRule.onNodeWithTag("agent-trace-summary").performClick()
        assertGroupVisible(groupDescription(R.string.agent_trace_group_files, 1_000), index = 0)
        assertGroupVisible(groupDescription(R.string.agent_trace_group_commands, 1_000), index = 1)
        assertGroupVisible(groupDescription(R.string.agent_trace_group_tools, 1_000), index = 2)
        assertGroupVisible(groupDescription(R.string.agent_trace_group_search, 1_000), index = 3)
        assertGroupVisible(groupDescription(R.string.agent_trace_group_memory, 1_000), index = 4)

        composeRule.onNodeWithContentDescription(groupDescription(R.string.agent_trace_group_files, 1_000)).performClick()
        composeRule.onNodeWithContentDescription(groupDescription(R.string.agent_trace_group_files, 1_000, expanded = true)).assertIsDisplayed()
        composeRule.onNodeWithTag("agent-trace-list").performScrollToIndex(1_000)
        composeRule.onNodeWithText("FILE event 999").assertIsDisplayed()
    }

    @Test
    fun streamingUpdateKeepsExpandedGroupState() {
        var updateProjection: ((UnifiedExecutionTraceProjection) -> Unit)? = null
        composeRule.setContent {
            var projection by remember { mutableStateOf(fixtureProjection()) }
            updateProjection = { projection = it }
            MaterialTheme {
                AgentLifecycleTrackerPanel(
                    projection = projection,
                    isWorking = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        composeRule.onNodeWithTag("agent-trace-summary").performClick()
        composeRule.onNodeWithContentDescription(groupDescription(R.string.agent_trace_group_files, 1)).performClick()
        composeRule.onNodeWithText("Read file").assertIsDisplayed()
        composeRule.runOnIdle {
            val current = fixtureProjection()
            val streamedEvent = event(
                id = "file-streamed",
                category = UnifiedTraceCategory.FILE,
                status = TaskStatus.COMPLETED,
                title = "Read streamed file",
            )
            val updatedGroups = current.groups.map { group ->
                if (group.category == UnifiedTraceCategory.FILE) {
                    group.copy(events = group.events + streamedEvent)
                } else {
                    group
                }
            }
            updateProjection?.invoke(
                current.copy(
                    events = current.events + streamedEvent,
                    groups = updatedGroups,
                    summary = current.summary.copy(filesRead = 2),
                ),
            )
        }
        composeRule.onNodeWithContentDescription(groupDescription(R.string.agent_trace_group_files, 2, expanded = true)).assertIsDisplayed()
        composeRule.onNodeWithText("Read streamed file").assertIsDisplayed()
    }

    @Test
    fun admittedExecutionShowsHumanProgressSummary() {
        val step = event(
            id = "admitted-step",
            category = UnifiedTraceCategory.TOOL,
            status = TaskStatus.RUNNING,
            title = "calendar_list",
        )
        composeRule.setContent {
            MaterialTheme {
                AgentLifecycleTrackerPanel(
                    projection = fixtureProjection().copy(admitted = true, lastActivity = step),
                    isWorking = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        val message = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.agent_lifecycle_understood)
        composeRule.onNodeWithText(message).assertIsDisplayed()
    }

    @Test
    fun runningFileActivityUsesHumanAttachmentMessage() {
        val fileEvent = event(
            id = "attachment-reading",
            category = UnifiedTraceCategory.FILE,
            status = TaskStatus.RUNNING,
            title = "report.pdf",
        )
        composeRule.setContent {
            MaterialTheme {
                AgentLifecycleTrackerPanel(
                    projection = fixtureProjection().copy(admitted = true, lastActivity = fileEvent),
                    isWorking = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        val message = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.agent_lifecycle_reading_attachments)
        composeRule.onNodeWithText(message).assertIsDisplayed()
    }

    @Test
    fun completedExecutionShowsResponseReadyMilestoneWithoutResponseContent() {
        val executionId = "response-ready-run"
        val entries = listOf(
            ExecutionTraceEvent(
                executionId = executionId,
                sequence = 1,
                timestampMs = 1_000L,
                kind = ExecutionTraceKind.TOOL_COMPLETED,
                summary = "Tool call completed: gmail_read",
                actionId = "gmail-read-action",
                machineTag = "tool_call",
            ),
            ExecutionTraceEvent(
                executionId = executionId,
                sequence = 2,
                timestampMs = 2_000L,
                kind = ExecutionTraceKind.COMPLETED,
                summary = "Execution completed",
                machineTag = "execution_completed",
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                InlineExecutionTrace(
                    executionId = executionId,
                    entries = entries,
                    isWorking = false,
                )
            }
        }

        val message = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.agent_lifecycle_response_ready)
        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.onAllNodesWithText("Execution completed").assertCountEquals(0)
    }

    @Test
    fun directResponseDoesNotShowTracePanelBeforeAnyActivityIsAdmitted() {
        composeRule.setContent {
            MaterialTheme {
                InlineExecutionTrace(
                    executionId = "direct-response-run",
                    entries = emptyList(),
                    isWorking = true,
                )
            }
        }

        assertTrue(composeRule.onAllNodesWithTag("agent-trace-summary").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("agent-trace-list").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun admissionPlanningAloneDoesNotShowExecutionPanel() {
        val executionId = "planning-only-run"
        composeRule.setContent {
            MaterialTheme {
                InlineExecutionTrace(
                    executionId = executionId,
                    entries = listOf(
                        ExecutionTraceEvent(
                            executionId = executionId,
                            sequence = 1,
                            timestampMs = 1_000L,
                            kind = ExecutionTraceKind.PLANNING,
                            summary = "Planning task",
                            machineTag = "planning",
                        ),
                    ),
                    isWorking = true,
                )
            }
        }

        assertTrue(composeRule.onAllNodesWithTag("agent-trace-summary").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun repeatedHeartbeatKeepsExpandedEventAndDoesNotDuplicateItsIdentity() {
        var updateProjection: ((UnifiedExecutionTraceProjection) -> Unit)? = null
        composeRule.setContent {
            var projection by remember { mutableStateOf(treeProjection()) }
            updateProjection = { projection = it }
            MaterialTheme {
                AgentLifecycleTrackerPanel(
                    projection = projection,
                    isWorking = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        composeRule.onNodeWithTag("agent-trace-summary").performClick()
        composeRule.onNodeWithText("gmail_read", substring = true).assertIsDisplayed()

        composeRule.runOnIdle {
            val original = treeProjection()
            repeat(3) { heartbeat ->
                val root = original.taskTree.single()
                val oldTool = root.children.single()
                val updatedTool = oldTool.event.copy(timestampMs = oldTool.event.timestampMs + heartbeat + 1)
                val updatedRoot = root.copy(children = listOf(oldTool.copy(event = updatedTool)))
                updateProjection?.invoke(
                    original.copy(
                        events = listOf(root.event, updatedTool),
                        taskTree = listOf(updatedRoot),
                    ),
                )
            }
        }

        composeRule.onAllNodesWithText("gmail_read", substring = true).assertCountEquals(1)
        composeRule.onNodeWithTag("agent-trace-list").assertIsDisplayed()
    }

    private fun setPanel(isWorking: Boolean) {
        composeRule.setContent {
            MaterialTheme {
                AgentLifecycleTrackerPanel(
                    projection = fixtureProjection(),
                    isWorking = isWorking,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    private fun assertGroupVisible(description: String, index: Int) {
        composeRule.onNodeWithTag("agent-trace-list")
            .performScrollToIndex(index)
        composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
    }

    private fun localized(resId: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resId, *args)

    private fun groupDescription(titleRes: Int, count: Int, expanded: Boolean = false): String {
        val actionRes = if (expanded) R.string.agent_trace_collapse_group else R.string.agent_trace_expand_group
        return localized(R.string.agent_trace_group_count, localized(titleRes), count) + ". " + localized(actionRes)
    }

    private fun assertStatusVisible(status: String) {
        composeRule.onNodeWithTag("agent-trace-list")
            .performScrollToNode(hasContentDescription(status, substring = true))
        composeRule.onNodeWithContentDescription(status, substring = true).assertIsDisplayed()
    }

    private fun expandFixtureGroups() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expand = context.getString(R.string.agent_trace_expand_group)
        listOf(
            R.string.agent_trace_group_files,
            R.string.agent_trace_group_commands,
            R.string.agent_trace_group_tools,
            R.string.agent_trace_group_search,
            R.string.agent_trace_group_memory,
            R.string.agent_trace_group_tasks,
            R.string.agent_trace_group_tasks,
        ).forEachIndexed { index, titleRes ->
            composeRule.onNodeWithTag("agent-trace-list").performScrollToIndex(index * 2)
            composeRule.onAllNodesWithContentDescription(
                "${context.getString(titleRes)} · 1. $expand",
            ).get(0).performClick()
        }
    }
    private fun fixtureProjection(): UnifiedExecutionTraceProjection {
        val events = listOf(
            event("file-1", UnifiedTraceCategory.FILE, TaskStatus.COMPLETED, "Read file"),
            event("command-1", UnifiedTraceCategory.COMMAND, TaskStatus.COMPLETED, "Run command"),
            event("tool-1", UnifiedTraceCategory.TOOL, TaskStatus.FAILED, "Invoke tool"),
            event("search-1", UnifiedTraceCategory.SEARCH, TaskStatus.COMPLETED, "Search web"),
            event("memory-1", UnifiedTraceCategory.MEMORY, TaskStatus.WAITING, "Read memory"),
            event("plan-1", UnifiedTraceCategory.PLAN, TaskStatus.RUNNING, "Planning task"),
            event("task-1", UnifiedTraceCategory.TASK, TaskStatus.CANCELLED, "Cancelled task"),
        )
        val groups = events.groupBy { it.category }.map { (category, groupEvents) ->
            UnifiedExecutionTraceGroup(
                id = category.name,
                title = category.name,
                category = category,
                events = groupEvents,
            )
        }
        return UnifiedExecutionTraceProjection(
            events = events,
            groups = groups,
            summary = UnifiedExecutionTraceSummary(
                filesRead = 1,
                commandsExecuted = 1,
                toolsUsed = 1,
                searches = 1,
                memoryOperations = 1,
                tasks = 2,
            ),
        )
    }

    private fun stressProjection(): UnifiedExecutionTraceProjection {
        val categories = listOf(
            UnifiedTraceCategory.FILE,
            UnifiedTraceCategory.COMMAND,
            UnifiedTraceCategory.TOOL,
            UnifiedTraceCategory.SEARCH,
            UnifiedTraceCategory.MEMORY,
        )
        val events = buildList {
            categories.forEach { category ->
                repeat(1_000) { index ->
                    add(
                        event(
                            id = "stress-${category.name}-$index",
                            category = category,
                            status = if (index == 999 && category == UnifiedTraceCategory.TOOL) {
                                TaskStatus.FAILED
                            } else {
                                TaskStatus.COMPLETED
                            },
                            title = "${category.name} event $index",
                        ),
                    )
                }
            }
        }
        val groups = events.groupBy { it.category }.map { (category, groupEvents) ->
            UnifiedExecutionTraceGroup(
                id = "stress-${category.name}",
                title = category.name,
                category = category,
                events = groupEvents,
            )
        }
        return UnifiedExecutionTraceProjection(
            events = events,
            groups = groups,
            summary = UnifiedExecutionTraceSummary(
                filesRead = 1_000,
                commandsExecuted = 1_000,
                toolsUsed = 1_000,
                searches = 1_000,
                memoryOperations = 1_000,
            ),
        )
    }

    private fun technicalToolProjection(): UnifiedExecutionTraceProjection {
        val tool = event(
            id = "technical-tool",
            category = UnifiedTraceCategory.TOOL,
            status = TaskStatus.FAILED,
            title = "skill_github_repository_read",
            detailLog = "Repository access was denied",
        )
        return UnifiedExecutionTraceProjection(
            events = listOf(tool),
            groups = listOf(
                UnifiedExecutionTraceGroup(
                    id = "tools",
                    title = "Tools",
                    category = UnifiedTraceCategory.TOOL,
                    events = listOf(tool),
                ),
            ),
            summary = UnifiedExecutionTraceSummary(toolsUsed = 1),
        )
    }

    private fun treeProjection(): UnifiedExecutionTraceProjection {
        val step = event("tree-step", UnifiedTraceCategory.TASK, TaskStatus.COMPLETED, "Read the inbox")
        val tool = event("tree-tool", UnifiedTraceCategory.TOOL, TaskStatus.COMPLETED, "gmail_read")
        return UnifiedExecutionTraceProjection(
            events = listOf(step, tool),
            groups = emptyList(),
            summary = UnifiedExecutionTraceSummary(tasks = 1, toolsUsed = 1),
            taskTree = listOf(UnifiedExecutionTraceTaskNode(step, listOf(UnifiedExecutionTraceTaskNode(tool)))),
            admitted = true,
        )
    }

    private fun event(
        id: String,
        category: UnifiedTraceCategory,
        status: TaskStatus,
        title: String,
        detailLog: String? = null,
    ) = UnifiedExecutionTraceEvent(
        executionId = "ui-test-run",
        eventId = id,
        actionId = "action-$id",
        sequence = id.hashCode().toLong(),
        timestampMs = 1_000L,
        title = title,
        status = status,
        detailLog = detailLog,
        category = category,
    )
}
