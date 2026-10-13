package com.airi.assistant.ui.trace

import com.airi.assistant.agent.trace.NodeExecutionState
import com.airi.assistant.agent.trace.TraceStep

fun traceNodeLabel(step: TraceStep, state: NodeExecutionState): String = when (step) {
    is TraceStep.Planning -> if (state == NodeExecutionState.RUNNING) "تخطيط المهمة… (${step.completedCount}/${step.tasks.size})" else "قائمة المهمة (${step.completedCount}/${step.tasks.size})"
    is TraceStep.WebSearch -> if (state == NodeExecutionState.RUNNING) "يبحث في الويب…" else "تم البحث في ${step.sourcesCount} مصدر"
    is TraceStep.UiInteraction -> step.action
    is TraceStep.ScreenRead -> if (state == NodeExecutionState.RUNNING) "يقرأ الشاشة…" else "تمت قراءة الشاشة"
    is TraceStep.MemoryAccess -> if (state == NodeExecutionState.RUNNING) "يسترجع المعرفة…" else "تم استرجاع المعرفة"
    is TraceStep.DeviceAction -> step.description
    is TraceStep.ConfirmationRequest -> "بانتظار التأكيد…"
    is TraceStep.Thinking -> step.thought.take(80)
    is TraceStep.StepError -> "خطأ: ${step.errorMessage.take(60)}"
}
