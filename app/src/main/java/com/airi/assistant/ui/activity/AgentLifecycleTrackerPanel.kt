package com.airi.assistant.ui.activity

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.material3.MaterialTheme
import androidx.test.platform.app.InstrumentationRegistry
import com.airi.assistant.ui.trace.UnifiedExecutionTraceEvent
import com.airi.assistant.ui.trace.UnifiedExecutionTraceGroup
import com.airi.assistant.ui.trace.UnifiedExecutionTraceProjection
import com.airi.assistant.ui.trace.UnifiedExecutionTraceSummary
import com.airi.assistant.ui.trace.UnifiedTraceCategory
import org.junit.Rule
import org.junit.Test

class AgentLifecycleTrackerPanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersSummaryAndAllProjectionGroups() {
        setPanel(isWorking = false)

        composeRule.onNodeWithText("Read 1 files").assertIsDisplayed()
        composeRule.onNodeWithText("Ran 1 commands").assertIsDisplayed()
        composeRule.onNodeWithText("Used 1 tools").assertIsDisplayed()
        composeRule.onNodeWithText("Searched 1 times").assertIsDisplayed()
        composeRule.onNodeWithText("Memory operations 1").assertIsDisplayed()
        composeRule.onNodeWithText("2 tasks").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Files · 1. Expand group").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Commands · 1. Expand group").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Tools · 1. Expand group").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Search · 1. Expand group").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Memory · 1. Expand group").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Tasks · 2. Expand group").assertIsDisplayed()
    }

    @Test
    fun groupExpansionShowsEventsAndCollapseHidesThem() {
        setPanel(isWorking = false)

        composeRule.onNodeWithText("Read file").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Files · 1. Expand group").performClick()
        composeRule.onNodeWithText("Read file").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Files · 1. Collapse group").performClick()
        composeRule.onNodeWithText("Read file").assertDoesNotExist()
    }

    @Test
    fun statusSemanticsExposeWaitingRunningFailedAndCancelled() {
        setPanel(isWorking = true)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithContentDescription(context.getString(R.string.agent_lifecycle_status_waiting), substring = true).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.agent_lifecycle_status_running), substring = true).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.agent_lifecycle_status_failed), substring = true).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.agent_lifecycle_status_cancelled), substring = true).assertExists()
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

        composeRule.onNodeWithContentDescription("Files · 1. Expand group").assertIsDisplayed()
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

        composeRule.onNodeWithText("Read 1 files").assertExists()
        composeRule.onNodeWithContentDescription("Files · 1. Expand group").assertExists()
    }

    @Test
    fun fiveThousandEventsRenderThroughBoundedLazyGroups() {
        composeRule.setContent {
            MaterialTheme {
                AgentLifecycleTrackerPanel(
                    projection = stressProjection(),
                    isWorking = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        composeRule.onNodeWithText("Read 1000 files").assertExists()
        composeRule.onNodeWithContentDescription("Files · 1000. Collapse group").assertExists()
        composeRule.onNodeWithContentDescription("Commands · 1000. Collapse group").assertExists()
        composeRule.onNodeWithContentDescription("Tools · 1000. Collapse group").assertExists()
        composeRule.onNodeWithContentDescription("Search · 1000. Collapse group").assertExists()
        composeRule.onNodeWithContentDescription("Memory · 1000. Collapse group").assertExists()
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
        composeRule.onNodeWithContentDescription("Files · 2. Collapse group").assertExists()
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

    private fun event(
        id: String,
        category: UnifiedTraceCategory,
        status: TaskStatus,
        title: String,
    ) = UnifiedExecutionTraceEvent(
        executionId = "ui-test-run",
        eventId = id,
        actionId = "action-$id",
        sequence = id.hashCode().toLong(),
        timestampMs = 1_000L,
        title = title,
        status = status,
        detailLog = null,
        category = category,
    )
}
