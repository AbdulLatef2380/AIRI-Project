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
import com.airi.assistant.ui.trace.UnifiedExecutionTraceEvent
import com.airi.assistant.ui.trace.UnifiedExecutionTraceGroup
import com.airi.assistant.ui.trace.UnifiedExecutionTraceProjection
import com.airi.assistant.ui.trace.UnifiedExecutionTraceSummary
import com.airi.assistant.ui.trace.UnifiedTraceCategory
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AgentLifecycleTrackerPanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersSummaryAndAllProjectionGroups() {
        setPanel(isWorking = false)

        composeRule.onNodeWithText("Read 1 files", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("2 tasks", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("agent-trace-summary").performClick()
        assertGroupVisible("Files · 1. Expand group", index = 0)
        assertGroupVisible("Commands · 1. Expand group", index = 1)
        assertGroupVisible("Tools · 1. Expand group", index = 2)
        assertGroupVisible("Search · 1. Expand group", index = 3)
        assertGroupVisible("Memory · 1. Expand group", index = 4)
        // PLAN and TASK are distinct projector categories but share the Tasks
        // presentation title, so each has its own one-event header.
        composeRule.onNodeWithTag("agent-trace-list")
            .performScrollToIndex(5)
        composeRule.onAllNodesWithContentDescription("Tasks · 1. Expand group")
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
    fun groupExpansionShowsEventsAndCollapseHidesThem() {
        setPanel(isWorking = false)

        assertTrue(composeRule.onAllNodesWithText("Read file").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("agent-trace-summary").performClick()
        composeRule.onNodeWithContentDescription("Files · 1. Expand group").performClick()
        composeRule.onNodeWithText("Read file").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Files · 1. Collapse group").performClick()
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
        composeRule.onNodeWithContentDescription("Tools · 1. Expand group").performClick()
        composeRule.onNodeWithText("Tool action").assertIsDisplayed()
        composeRule.onNodeWithText("skill_github_repository_read", substring = true).assertIsDisplayed()
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
        assertGroupVisible("Files · 1. Expand group", index = 0)
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

        composeRule.onNodeWithText("Read 1 files", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("agent-trace-summary").performClick()
        assertGroupVisible("Files · 1. Expand group", index = 0)
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

        composeRule.onNodeWithText("Read 1000 files").assertIsDisplayed()
        composeRule.onNodeWithTag("agent-trace-summary").performClick()
        assertGroupVisible("Files · 1000. Expand group", index = 0)
        assertGroupVisible("Commands · 1000. Expand group", index = 1)
        assertGroupVisible("Tools · 1000. Expand group", index = 2)
        assertGroupVisible("Search · 1000. Expand group", index = 3)
        assertGroupVisible("Memory · 1000. Expand group", index = 4)

        composeRule.onNodeWithContentDescription("Files · 1000. Expand group").performClick()
        composeRule.onNodeWithContentDescription("Files · 1000. Collapse group").assertIsDisplayed()
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
        composeRule.onNodeWithContentDescription("Files · 1. Expand group").performClick()
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
        composeRule.onNodeWithContentDescription("Files · 2. Collapse group").assertIsDisplayed()
        composeRule.onNodeWithText("Read streamed file").assertIsDisplayed()
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

    private fun assertStatusVisible(status: String) {
        composeRule.onNodeWithTag("agent-trace-list")
            .performScrollToNode(hasContentDescription(status, substring = true))
        composeRule.onNodeWithContentDescription(status, substring = true).assertIsDisplayed()
    }

    private fun expandFixtureGroups() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expand = context.getString(R.string.agent_trace_expand_group)
        listOf(
            R.string.agent_trace_group_files to 0,
            R.string.agent_trace_group_commands to 0,
            R.string.agent_trace_group_tools to 0,
            R.string.agent_trace_group_search to 0,
            R.string.agent_trace_group_memory to 0,
            R.string.agent_trace_group_tasks to 0,
            R.string.agent_trace_group_tasks to 1,
        ).forEachIndexed { index, (titleRes, occurrence) ->
            composeRule.onNodeWithTag("agent-trace-list").performScrollToIndex(index * 2)
            composeRule.onAllNodesWithContentDescription(
                "${context.getString(titleRes)} · 1. $expand",
            ).get(occurrence).performClick()
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
