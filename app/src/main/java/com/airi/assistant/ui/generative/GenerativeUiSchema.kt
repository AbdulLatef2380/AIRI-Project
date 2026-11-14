package com.airi.assistant.ui.generative

import org.json.JSONArray
import org.json.JSONObject

/** A small, versioned allow-list for interactive response surfaces.
 *
 * The model can describe data and actions, but it cannot provide Kotlin,
 * JavaScript, HTML, colors, URLs, or arbitrary layout instructions.
 */
data class GenerativeUiDocument(
    val version: Int,
    val title: String?,
    val description: String?,
    val blocks: List<GenerativeUiBlock>,
)

sealed interface GenerativeUiBlock {
    val id: String
    val title: String?

    data class Stat(
        override val id: String,
        override val title: String?,
        val value: String,
        val supportingText: String?,
    ) : GenerativeUiBlock

    data class BarChart(
        override val id: String,
        override val title: String?,
        val labels: List<String>,
        val values: List<Float>,
        val unit: String?,
    ) : GenerativeUiBlock

    data class Diagram(
        override val id: String,
        override val title: String?,
        val nodes: List<DiagramNode>,
        val edges: List<DiagramEdge>,
    ) : GenerativeUiBlock

    data class Form(
        override val id: String,
        override val title: String?,
        val fields: List<FormField>,
        val submitLabel: String,
        val actionId: String,
    ) : GenerativeUiBlock

    data class Actions(
        override val id: String,
        override val title: String?,
        val options: List<ActionOption>,
    ) : GenerativeUiBlock
}

data class DiagramNode(val id: String, val label: String, val description: String?)
data class DiagramEdge(val from: String, val to: String, val label: String?)
data class FormField(val id: String, val label: String, val placeholder: String?, val required: Boolean)
data class ActionOption(val id: String, val label: String, val prompt: String)
data class GenerativeUiAction(val id: String, val fields: Map<String, String> = emptyMap()) {
    val prompt: String
        get() = buildString {
            append("[AIRI_UI_ACTION:")
            append(id)
            append("]")
            fields.forEach { (key, value) ->
                append(" ")
                append(key)
                append("=")
                append(value.replace(Regex("[\\r\\n]+"), " ").replace("]", "}").take(MAX_ACTION_VALUE_CHARS))
            }
        }
}

data class CompiledGenerativeUi(val document: GenerativeUiDocument, val surroundingText: String)

object GenerativeUiCompiler {
    private const val MAX_JSON_CHARS = 32_000
    private const val MAX_BLOCKS = 8
    private const val MAX_ITEMS = 30
    private const val MAX_TEXT = 240
    private val fenced = Regex("```airi-ui\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

    fun compile(text: String): CompiledGenerativeUi? {
        val match = fenced.find(text) ?: return null
        val raw = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (raw.isBlank() || raw.length > MAX_JSON_CHARS) return null
        val document = runCatching { parse(JSONObject(raw)) }.getOrNull() ?: return null
        val surrounding = text.removeRange(match.range).trim()
        return CompiledGenerativeUi(document, surrounding)
    }

    private fun parse(root: JSONObject): GenerativeUiDocument {
        require(root.optInt("version", 1) == 1) { "unsupported ui schema" }
        val blocksJson = root.optJSONArray("blocks") ?: JSONArray()
        require(blocksJson.length() in 1..MAX_BLOCKS) { "invalid block count" }
        val blocks = buildList {
            for (index in 0 until blocksJson.length()) {
                add(parseBlock(blocksJson.getJSONObject(index), index))
            }
        }
        return GenerativeUiDocument(
            version = 1,
            title = safeText(root.optString("title", null)),
            description = safeText(root.optString("description", null)),
            blocks = blocks,
        )
    }

    private fun parseBlock(json: JSONObject, index: Int): GenerativeUiBlock {
        val id = safeId(json.optString("id", "block-$index"))
        val type = json.optString("type").lowercase()
        val title = safeText(json.optString("title", null))
        return when (type) {
            "stat" -> GenerativeUiBlock.Stat(
                id = id,
                title = title,
                value = requiredText(json, "value"),
                supportingText = safeText(json.optString("supportingText", null)),
            )
            "bar_chart" -> {
                val labels = json.optJSONArray("labels").boundedStrings()
                val values = json.optJSONArray("values").boundedNumbers()
                require(labels.isNotEmpty() && labels.size == values.size) { "chart labels/values mismatch" }
                GenerativeUiBlock.BarChart(id, title, labels, values, safeText(json.optString("unit", null)))
            }
            "diagram" -> {
                val nodesJson = json.optJSONArray("nodes") ?: JSONArray()
                require(nodesJson.length() in 1..MAX_ITEMS) { "invalid diagram nodes" }
                val nodes = buildList {
                    for (i in 0 until nodesJson.length()) {
                        val node = nodesJson.getJSONObject(i)
                        add(DiagramNode(safeId(node.optString("id", "node-$i")), requiredText(node, "label"), safeText(node.optString("description", null))))
                    }
                }
                val nodeIds = nodes.map { it.id }.toSet()
                val edgesJson = json.optJSONArray("edges") ?: JSONArray()
                require(edgesJson.length() <= MAX_ITEMS) { "too many diagram edges" }
                val edges = buildList {
                    for (i in 0 until edgesJson.length()) {
                        val edge = edgesJson.getJSONObject(i)
                        val from = safeId(edge.optString("from"))
                        val to = safeId(edge.optString("to"))
                        require(from in nodeIds && to in nodeIds) { "edge references unknown node" }
                        add(DiagramEdge(from, to, safeText(edge.optString("label", null))))
                    }
                }
                GenerativeUiBlock.Diagram(id, title, nodes, edges)
            }
            "form" -> {
                val fieldsJson = json.optJSONArray("fields") ?: JSONArray()
                require(fieldsJson.length() in 1..8) { "invalid form fields" }
                val fields = buildList {
                    for (i in 0 until fieldsJson.length()) {
                        val field = fieldsJson.getJSONObject(i)
                        add(FormField(safeId(field.optString("id", "field-$i")), requiredText(field, "label"), safeText(field.optString("placeholder", null)), field.optBoolean("required", false)))
                    }
                }
                GenerativeUiBlock.Form(id, title, fields, safeText(json.optString("submitLabel", "Submit")) ?: "Submit", safeId(json.optString("actionId", id)))
            }
            "actions" -> {
                val optionsJson = json.optJSONArray("options") ?: JSONArray()
                require(optionsJson.length() in 1..8) { "invalid action count" }
                val options = buildList {
                    for (i in 0 until optionsJson.length()) {
                        val option = optionsJson.getJSONObject(i)
                        add(ActionOption(
                            id = safeId(option.optString("id", "action-$i")),
                            label = requiredText(option, "label"),
                            prompt = requiredText(option, "prompt"),
                        ))
                    }
                }
                GenerativeUiBlock.Actions(id, title, options)
            }
            else -> error("unsupported ui block")
        }
    }

    private fun requiredText(json: JSONObject, key: String): String = safeText(json.optString(key, null)) ?: error("missing $key")
    private fun safeText(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT)
    private fun safeId(value: String): String = value.trim().take(48).takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: error("invalid id")

    private fun JSONArray?.boundedStrings(): List<String> {
        require(this != null && length() in 1..MAX_ITEMS)
        return buildList { for (i in 0 until length()) add(safeText(getString(i)) ?: error("empty chart label")) }
    }

    private fun JSONArray?.boundedNumbers(): List<Float> {
        require(this != null && length() in 1..MAX_ITEMS)
        return buildList { for (i in 0 until length()) add(getDouble(i).toFloat().coerceIn(-1_000_000f, 1_000_000f)) }
    }
}

private const val MAX_ACTION_VALUE_CHARS = 400
