package com.airi.assistant.ui.trace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.agent.trace.NodeExecutionState
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.viewmodel.ChatItem

private const val COLLAPSE_THRESHOLD = 3

@Composable
fun TraceGroup(agentTrace: ChatItem.AgentTrace, onToggleCollapse: (String) -> Unit, modifier: Modifier = Modifier) {
    val doneCount = agentTrace.nodes.count { it.executionState == NodeExecutionState.DONE }
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (agentTrace.isCollapsed) {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable { onToggleCollapse(agentTrace.id) }, color = AiriTheme.surfaceVariant.copy(alpha = .45f), shape = AIRIShapes.sm) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text("اكتملت $doneCount خطوات", color = AiriTheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("▾", color = AiriTheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
        } else {
            agentTrace.nodes.forEach { LiveTraceNode(it) }
            if (doneCount > COLLAPSE_THRESHOLD) TextButton(onClick = { onToggleCollapse(agentTrace.id) }, modifier = Modifier.padding(start = 12.dp)) { Text("طي السجل ▲", color = AiriTheme.onSurfaceVariant, fontSize = 11.sp) }
        }
    }
}
