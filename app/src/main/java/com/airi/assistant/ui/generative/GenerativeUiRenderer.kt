package com.airi.assistant.ui.generative

import androidx.compose.foundation.Canvas
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R
import com.airi.assistant.ui.theme.AIRIShapes
import com.airi.assistant.ui.theme.AiriTheme

@Composable
fun GenerativeUiRenderer(
    compiled: CompiledGenerativeUi,
    modifier: Modifier = Modifier,
    onAction: (GenerativeUiAction) -> Unit = {},
    sourceExecutionId: String? = null,
    sourceMessageId: String? = null,
    stateStore: GenerativeUiStateStore? = null,
) {
    val effectiveStateStore = stateStore ?: remember { GenerativeUiStateStore() }
    val actionVersion by effectiveStateStore.actionVersion.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val observedActionVersion = actionVersion
    val document = compiled.document
    val accessibleResponseLabel = document.title ?: stringResource(R.string.generative_ui_accessible_response)
    var pendingAction by remember(compiled) { mutableStateOf<GenerativeUiAction?>(null) }
    val requestAction: (GenerativeUiAction) -> Unit = { pendingAction = it }
    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(stringResource(R.string.generative_ui_action_preview_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.generative_ui_action_preview_notice), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
                    Text(action.prompt, color = AiriTheme.onBackground, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingAction = null
                    onAction(action)
                }) { Text(stringResource(R.string.generative_ui_action_preview_use)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text(stringResource(R.string.generative_ui_action_preview_cancel))
                }
            },
        )
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .animateContentSize(),
        shape = AIRIShapes.lg,
        colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .semantics { contentDescription = accessibleResponseLabel },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            document.title?.let { Text(it, color = AiriTheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
            document.description?.let { Text(it, color = AiriTheme.onSurfaceVariant, fontSize = 13.sp) }
            document.blocks.forEach { block ->
                when (block) {
                    is GenerativeUiBlock.Stat -> StatBlock(block)
                    is GenerativeUiBlock.BarChart -> BarChartBlock(block, GenerativeUiStateStore.key(sourceMessageId, block.id), effectiveStateStore)
                    is GenerativeUiBlock.LineChart -> LineChartBlock(block, GenerativeUiStateStore.key(sourceMessageId, block.id), effectiveStateStore)
                    is GenerativeUiBlock.Diagram -> DiagramBlock(block, GenerativeUiStateStore.key(sourceMessageId, block.id), effectiveStateStore)
                    is GenerativeUiBlock.Form -> FormBlock(block, requestAction, sourceExecutionId, sourceMessageId, GenerativeUiStateStore.key(sourceMessageId, block.id), effectiveStateStore)
                    is GenerativeUiBlock.Actions -> ActionsBlock(block, requestAction, sourceExecutionId, sourceMessageId, effectiveStateStore)
                    is GenerativeUiBlock.Route -> RouteBlock(block)
                    is GenerativeUiBlock.Table -> TableBlock(block)
                    is GenerativeUiBlock.Checklist -> ChecklistBlock(block, GenerativeUiStateStore.key(sourceMessageId, block.id), effectiveStateStore)
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
private fun BarChartBlock(block: GenerativeUiBlock.BarChart, stateKey: String, stateStore: GenerativeUiStateStore) {
    val minValue = block.values.minOrNull() ?: 0f
    val maxValue = block.values.maxOrNull() ?: 1f
    val range = (maxValue - minValue).coerceAtLeast(1f)
    val zero = when {
        minValue < 0f && maxValue > 0f -> (-minValue / range).coerceIn(0f, 1f)
        maxValue <= 0f -> 1f
        else -> 0f
    }
    var selectedIndex by remember(stateKey) { mutableIntStateOf(stateStore.state(stateKey).selectedIndex) }
    val axisColor = AiriTheme.outline.copy(alpha = 0.65f)
    val negativeBarColor = AiriTheme.error
    val positiveBarColor = AiriTheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.title?.let { Text(it, color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        block.labels.forEachIndexed { index, label ->
            val value = block.values[index]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .clickable {
                        selectedIndex = index
                        stateStore.update(stateKey) { it.copy(selectedIndex = index) }
                    }
                    .semantics {
                        contentDescription = "$label ${formatNumber(value)}${block.unit.orEmpty()}"
                        role = Role.Button
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(label, color = if (selectedIndex == index) AiriTheme.onBackground else AiriTheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.size(width = 76.dp, height = 24.dp))
                Canvas(
                    modifier = Modifier.weight(1f).height(22.dp),
                ) {
                    val valuePosition = ((value - minValue) / range).coerceIn(0f, 1f)
                    val zeroPosition = size.width * zero
                    val valuePositionPx = size.width * valuePosition
                    val left = minOf(zeroPosition, valuePositionPx)
                    val width = kotlin.math.abs(valuePositionPx - zeroPosition)
                    if (minValue < 0f && maxValue > 0f) {
                        drawLine(
                            color = axisColor,
                            start = Offset(zeroPosition, 1f),
                            end = Offset(zeroPosition, size.height - 1f),
                            strokeWidth = 1f,
                        )
                    }
                    drawRoundRect(
                        color = if (value < 0f) negativeBarColor else positiveBarColor,
                        topLeft = Offset(left, 3f),
                        size = androidx.compose.ui.geometry.Size(width.coerceAtLeast(2f), size.height - 6f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
                    )
                }
                Text("${formatNumber(value)}${block.unit.orEmpty()}", color = AiriTheme.onBackground, fontSize = 11.sp)
            }
        }
        if (selectedIndex >= 0) {
            val label = block.labels[selectedIndex]
            Text("$label: ${formatNumber(block.values[selectedIndex])}${block.unit.orEmpty()}", color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun LineChartBlock(block: GenerativeUiBlock.LineChart, stateKey: String, stateStore: GenerativeUiStateStore) {
    var selectedIndex by remember(stateKey) { mutableIntStateOf(stateStore.state(stateKey).selectedIndex) }
    val minValue = block.values.minOrNull() ?: 0f
    val maxValue = block.values.maxOrNull() ?: 1f
    val range = (maxValue - minValue).coerceAtLeast(1f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.title?.let { Text(it, color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .semantics {
                    contentDescription = block.labels.mapIndexed { index, label ->
                        "$label ${formatNumber(block.values[index])}${block.unit.orEmpty()}"
                    }.joinToString()
                },
        ) {
            val step = if (block.values.size == 1) size.width else size.width / (block.values.size - 1)
            val points = block.values.mapIndexed { index, value ->
                Offset(index * step, size.height - ((value - minValue) / range) * size.height)
            }
            points.zipWithNext().forEach { (from, to) -> drawLine(AiriTheme.primary, from, to, strokeWidth = 5f) }
            points.forEachIndexed { index, point ->
                drawCircle(if (index == selectedIndex) AiriTheme.secondary else AiriTheme.primary, 7f, point)
            }
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            block.labels.forEachIndexed { index, label ->
                Text(
                    label,
                    color = if (index == selectedIndex) AiriTheme.onBackground else AiriTheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clickable {
                            selectedIndex = index
                            stateStore.update(stateKey) { it.copy(selectedIndex = index) }
                        }
                        .semantics { contentDescription = "$label ${formatNumber(block.values[index])}${block.unit.orEmpty()}" },
                )
            }
        }
        if (selectedIndex >= 0) {
            Text("${block.labels[selectedIndex]}: ${formatNumber(block.values[selectedIndex])}${block.unit.orEmpty()}", color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DiagramBlock(block: GenerativeUiBlock.Diagram, stateKey: String, stateStore: GenerativeUiStateStore) {
    var selectedNodeId by remember(stateKey) { mutableStateOf(stateStore.state(stateKey).selectedNodeId) }
    val selected = block.nodes.firstOrNull { it.id == selectedNodeId }
    val connected = block.edges.filter { it.from == selectedNodeId || it.to == selectedNodeId }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.title?.let { Text(it, color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            block.nodes.forEach { node ->
                Card(
                    modifier = Modifier
                        .sizeIn(minWidth = 120.dp, minHeight = 70.dp)
                        .clickable {
                            selectedNodeId = node.id
                            stateStore.update(stateKey) { it.copy(selectedNodeId = node.id) }
                        }
                        .semantics {
                            contentDescription = node.description?.let { "${node.label}: $it" } ?: node.label
                            role = Role.Button
                        },
                    colors = CardDefaults.cardColors(containerColor = if (selectedNodeId == node.id) AiriTheme.primary.copy(alpha = 0.22f) else AiriTheme.surface.copy(alpha = 0.85f)),
                    shape = AIRIShapes.sm,
                ) {
                    Column(Modifier.padding(9.dp).sizeIn(minWidth = 120.dp, minHeight = 70.dp)) {
                        Text(node.label, color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 2)
                        node.description?.let { Text(it, color = AiriTheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 2) }
                    }
                }
            }
        }
        selected?.let { node ->
            Text(node.description ?: node.label, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
            connected.forEach { edge ->
                val suffix = edge.label?.let { stringResource(R.string.generative_ui_edge_label_suffix, it) }.orEmpty()
                Text(stringResource(R.string.generative_ui_edge, edge.from, edge.to, suffix), color = AiriTheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun FormBlock(block: GenerativeUiBlock.Form, onAction: (GenerativeUiAction) -> Unit, sourceExecutionId: String?, sourceMessageId: String?, stateKey: String, stateStore: GenerativeUiStateStore) {
    var savedValues by rememberSaveable(stateKey) { mutableStateOf(stateStore.state(stateKey).formValues) }
    val values = remember(stateKey, savedValues) {
        mutableStateMapOf<String, String>().apply { putAll(savedValues) }
    }
    var submitted by rememberSaveable(stateKey) { mutableStateOf(stateStore.state(stateKey).submitted) }
    val missingRequired = block.fields.any { it.required && values[it.id].orEmpty().isBlank() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        block.title?.let { Text(it, color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        block.fields.forEach { field ->
            OutlinedTextField(
                value = values[field.id].orEmpty(),
                onValueChange = {
                    values[field.id] = it.take(500)
                    savedValues = values.toMap()
                    stateStore.update(stateKey) { it.copy(formValues = savedValues, submitted = false) }
                    submitted = false
                },
                label = { Text(field.label + if (field.required) " *" else "") },
                placeholder = field.placeholder?.let { { Text(it) } },
                singleLine = true,
                isError = submitted && field.required && values[field.id].orEmpty().isBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AnimatedVisibility(
            visible = submitted && missingRequired,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Text(
                stringResource(R.string.generative_ui_required_fields),
                color = AiriTheme.error,
                fontSize = 12.sp,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        val phase = stateStore.actionPhase(sourceMessageId, block.actionId)
        Button(
            enabled = phase != GenerativeUiStateStore.ActionPhase.RUNNING,
            onClick = {
                if (missingRequired) {
                    submitted = true
                    stateStore.update(stateKey) { it.copy(submitted = true) }
                } else {
                    stateStore.setActionPhase(sourceMessageId, block.actionId, GenerativeUiStateStore.ActionPhase.STAGED)
                    onAction(GenerativeUiAction(block.actionId, values.toMap(), sourceExecutionId, sourceMessageId))
                }
            },
            modifier = Modifier.align(Alignment.End),
            shape = AIRIShapes.md,
        ) { Text(block.submitLabel) }
        ActionPhaseText(phase)
    }
}

@Composable
private fun ActionsBlock(block: GenerativeUiBlock.Actions, onAction: (GenerativeUiAction) -> Unit, sourceExecutionId: String?, sourceMessageId: String?, stateStore: GenerativeUiStateStore) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.title?.let { Text(it, color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        block.options.forEach { option ->
            val phase = stateStore.actionPhase(sourceMessageId, option.id)
            Button(
                enabled = phase != GenerativeUiStateStore.ActionPhase.RUNNING,
                onClick = {
                    stateStore.setActionPhase(sourceMessageId, option.id, GenerativeUiStateStore.ActionPhase.STAGED)
                    onAction(GenerativeUiAction(option.id, mapOf("prompt" to option.prompt), sourceExecutionId, sourceMessageId))
                },
                modifier = Modifier.fillMaxWidth(),
                shape = AIRIShapes.md,
            ) { Text(option.label) }
            ActionPhaseText(phase)
        }
    }
}

@Composable
private fun ActionPhaseText(phase: GenerativeUiStateStore.ActionPhase?) {
    phase?.let {
        Text(
            stringResource(R.string.generative_ui_action_phase, it.name.lowercase()),
            color = AiriTheme.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun RouteBlock(block: GenerativeUiBlock.Route) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.title?.let { Text(it, color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        block.stops.forEachIndexed { index, stop ->
            Row(
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = buildString {
                        append("${index + 1}. ${stop.label}, ${stop.location}")
                        if (stop.latitude != null && stop.longitude != null) append(", ${stop.latitude}, ${stop.longitude}")
                    }
                },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text("${index + 1}", color = Color(0xFFB79BFF), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Column {
                    Text(stop.label, color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(stop.location, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
                    if (stop.latitude != null && stop.longitude != null) {
                        Text("${stop.latitude}, ${stop.longitude}", color = AiriTheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TableBlock(block: GenerativeUiBlock.Table) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.title?.let { Text(it, color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                block.headers.forEach { header ->
                    Surface(color = AiriTheme.surfaceVariant, shape = AIRIShapes.sm) {
                        Text(
                            header,
                            color = AiriTheme.onBackground,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            softWrap = true,
                            modifier = Modifier.padding(9.dp).widthIn(min = 118.dp),
                        )
                    }
                }
            }
            block.rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    row.forEach { cell ->
                        Surface(color = AiriTheme.surface.copy(alpha = 0.8f), shape = AIRIShapes.sm) {
                            Text(
                                cell,
                                color = AiriTheme.onSurface,
                                fontSize = 12.sp,
                                softWrap = true,
                                modifier = Modifier.padding(9.dp).widthIn(min = 118.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecklistBlock(block: GenerativeUiBlock.Checklist, stateKey: String, stateStore: GenerativeUiStateStore) {
    var savedChecked by rememberSaveable(stateKey) {
        mutableStateOf(stateStore.state(stateKey).checkedItems.ifEmpty { block.items.associate { it.id to it.initiallyChecked } })
    }
    val checked = remember(stateKey, savedChecked) {
        mutableStateMapOf<String, Boolean>().apply {
            putAll(savedChecked)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.title?.let { Text(it, color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        block.items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = item.label },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked[item.id] == true, onCheckedChange = {
                    checked[item.id] = it
                    savedChecked = checked.toMap()
                    stateStore.update(stateKey) { it.copy(checkedItems = savedChecked) }
                })
                Column(Modifier.weight(1f)) {
                    Text(item.label, color = AiriTheme.onBackground, fontSize = 13.sp)
                    item.detail?.let { Text(it, color = AiriTheme.onSurfaceVariant, fontSize = 11.sp) }
                }
            }
        }
    }
}

private fun formatNumber(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else "%.2f".format(value)
