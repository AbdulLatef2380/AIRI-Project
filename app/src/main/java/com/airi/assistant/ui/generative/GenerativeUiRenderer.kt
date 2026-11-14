package com.airi.assistant.ui.generative

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.theme.AIRIShapes
import com.airi.assistant.ui.theme.AiriTheme

@Composable
fun GenerativeUiRenderer(
    compiled: CompiledGenerativeUi,
    modifier: Modifier = Modifier,
    onAction: (GenerativeUiAction) -> Unit = {},
) {
    val document = compiled.document
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = AIRIShapes.lg,
        colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            document.title?.let { Text(it, color = AiriTheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
            document.description?.let { Text(it, color = AiriTheme.onSurfaceVariant, fontSize = 13.sp) }
            document.blocks.forEach { block ->
                when (block) {
                    is GenerativeUiBlock.Stat -> StatBlock(block)
                    is GenerativeUiBlock.BarChart -> BarChartBlock(block)
                    is GenerativeUiBlock.Diagram -> DiagramBlock(block)
                    is GenerativeUiBlock.Form -> FormBlock(block, onAction)
                }
            }
        }
    }
}

@Composable
private fun StatBlock(block: GenerativeUiBlock.Stat) {
    Card(colors = CardDefaults.cardColors(containerColor = AiriTheme.surface.copy(alpha = 0.72f)), shape = AIRIShapes.md) {
        Column(Modifier.padding(12.dp)) {
            block.title?.let { Text(it, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp) }
            Text(block.value, color = AiriTheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 27.sp)
            block.supportingText?.let { Text(it, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun BarChartBlock(block: GenerativeUiBlock.BarChart) {
    val max = block.values.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(1f) ?: 1f
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.title?.let { Text(it, color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        block.labels.forEachIndexed { index, label ->
            val value = block.values[index]
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, color = AiriTheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.size(width = 76.dp, height = 24.dp))
                Canvas(
                    modifier = Modifier.weight(1f).height(22.dp).semantics { contentDescription = "$label $value ${block.unit.orEmpty()}" },
                ) {
                    val width = size.width * (kotlin.math.abs(value) / max)
                    drawRoundRect(
                        color = Color(0xFF7C5CFC),
                        topLeft = Offset(0f, 3f),
                        size = androidx.compose.ui.geometry.Size(width.coerceAtLeast(2f), size.height - 6f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
                    )
                }
                Text("${formatNumber(value)}${block.unit.orEmpty()}", color = AiriTheme.onBackground, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun DiagramBlock(block: GenerativeUiBlock.Diagram) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.title?.let { Text(it, color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            block.nodes.forEachIndexed { index, node ->
                Card(colors = CardDefaults.cardColors(containerColor = AiriTheme.surface.copy(alpha = 0.85f)), shape = AIRIShapes.sm) {
                    Column(Modifier.padding(9.dp).size(width = 120.dp, height = 70.dp)) {
                        Text(node.label, color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 2)
                        node.description?.let { Text(it, color = AiriTheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 2) }
                    }
                }
                if (index < block.nodes.lastIndex) Text("→", color = AiriTheme.onSurfaceVariant, fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun FormBlock(block: GenerativeUiBlock.Form, onAction: (GenerativeUiAction) -> Unit) {
    val values = remember(block.id) { mutableStateMapOf<String, String>() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        block.title?.let { Text(it, color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        block.fields.forEach { field ->
            OutlinedTextField(
                value = values[field.id].orEmpty(),
                onValueChange = { values[field.id] = it.take(500) },
                label = { Text(field.label) },
                placeholder = field.placeholder?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            onClick = { onAction(GenerativeUiAction(block.actionId, values.toMap())) },
            modifier = Modifier.align(Alignment.End),
            shape = AIRIShapes.md,
        ) { Text(block.submitLabel) }
    }
}

private fun formatNumber(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else "%.2f".format(value)
