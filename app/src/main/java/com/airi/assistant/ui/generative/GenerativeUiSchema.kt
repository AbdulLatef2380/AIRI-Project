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

    data class Route(
        override val id: String,
        override val title: String?,
        val stops: List<RouteStop>,
    ) : GenerativeUiBlock

    data class Table(
        override val id: String,
        override val title: String?,
        val headers: List<String>,
        val rows: List<List<String>>,
    ) : GenerativeUiBlock

    data class Checklist(
        override val id: String,
        override val title: String?,
        val items: List<ChecklistItem>,
    ) : GenerativeUiBlock
}

data class DiagramNode(val id: String, val label: String, val description: String?)
data class DiagramEdge(val from: String, val to: String, val label: String?)
data class FormField(val id: String, val label: String, val placeholder: String?, val required: Boolean)
data class ActionOption(val id: String, val label: String, val prompt: String)
data class RouteStop(val id: String, val label: String, val location: String, val latitude: Double?, val longitude: Double?)
data class ChecklistItem(val id: String, val label: String, val detail: String?, val initiallyChecked: Boolean)
data class GenerativeUiAction(
    val id: String,
    val fields: Map<String, String> = emptyMap(),
    val sourceExecutionId: String? = null,
    val sourceMessageId: String? = null,
) {
    val prompt: String
        get() = buildString {
            append("[AIRI_UI_ACTION:")
            append(id)
            append("]")
            fields.toSortedMap().forEach { (key, value) ->
                append(" ")
                append(key)
                append("=")
                append(value.replace(Regex("[\\r\\n]+"), " ").replace("]", "}").take(MAX_ACTION_VALUE_CHARS))
            }
        }
}

data class CompiledGenerativeUi(val document: GenerativeUiDocument, val surroundingText: String)

object GenerativeUiCompiler {
    private const val MAX_RESPONSE_CHARS = 96_000
    private const val MAX_JSON_CHARS = 32_000
    private const val MAX_JSON_DEPTH = 12
    private const val MAX_BLOCKS = 8
    private const val MAX_ITEMS = 30
    private const val MAX_TEXT = 240
    private val fenced = Regex("```airi-ui\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

    fun compile(text: String): CompiledGenerativeUi? {
        if (text.length > MAX_RESPONSE_CHARS) return null
        val match = fenced.find(text) ?: return null
        val raw = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (raw.isBlank() || raw.length > MAX_JSON_CHARS) return null
        val document = runCatching { JSONObject(raw) }
            .mapCatching { json -> parse(json).also { validateDepth(json, 0) } }
            .getOrNull() ?: return null
        val surrounding = text.removeRange(match.range).trim()
        return CompiledGenerativeUi(document, surrounding)
    }

    /** Rebuild an action from its assistant source; callback data is untrusted. */
    fun rebuildAction(sourceText: String, requested: GenerativeUiAction): GenerativeUiAction? {
        val document = compile(sourceText)?.document ?: return null
        document.blocks.forEach { block ->
            when (block) {
                is GenerativeUiBlock.Form -> if (block.actionId == requested.id) {
                    val allowed = block.fields.map { it.id }.toSet()
                    if (requested.fields.keys.any { it !in allowed }) return null
                    if (block.fields.any { it.required && requested.fields[it.id].orEmpty().isBlank() }) return null
                    if (requested.fields.values.any { it.length > MAX_ACTION_FIELD_CHARS }) return null
                    return GenerativeUiAction(block.actionId, requested.fields, requested.sourceExecutionId, requested.sourceMessageId)
                }
                is GenerativeUiBlock.Actions -> block.options.firstOrNull { it.id == requested.id }?.let { option ->
                    return GenerativeUiAction(option.id, mapOf("prompt" to option.prompt), requested.sourceExecutionId, requested.sourceMessageId)
                }
                else -> Unit
            }
        }
        return null
    }

    private fun parse(root: JSONObject): GenerativeUiDocument {
        requireOnlyKeys(root, setOf("version", "title", "description", "blocks"))
        require(root.has("version") && root.get("version") is Number) { "version must be numeric" }
        require(root.getInt("version") == 1) { "unsupported ui schema" }
        val blocksJson = root.get("blocks") as? JSONArray ?: error("blocks must be an array")
        require(blocksJson.length() in 1..MAX_BLOCKS) { "invalid block count" }
        val blocks = buildList {
            for (index in 0 until blocksJson.length()) {
                add(parseBlock(blocksJson.getJSONObject(index), index))
            }
        }
        val ids = mutableSetOf<String>()
        blocks.forEach { block ->
            require(ids.add(block.id)) { "duplicate block id" }
            when (block) {
                is GenerativeUiBlock.Diagram -> block.nodes.forEach { require(ids.add(it.id)) { "duplicate node id" } }
                is GenerativeUiBlock.Form -> block.fields.forEach { require(ids.add(it.id)) { "duplicate field id" } }
                is GenerativeUiBlock.Actions -> block.options.forEach { require(ids.add(it.id)) { "duplicate action id" } }
                is GenerativeUiBlock.Route -> block.stops.forEach { require(ids.add(it.id)) { "duplicate stop id" } }
                is GenerativeUiBlock.Checklist -> block.items.forEach { require(ids.add(it.id)) { "duplicate checklist id" } }
                else -> Unit
            }
        }
        return GenerativeUiDocument(
            version = 1,
            title = optionalText(root, "title"),
            description = optionalText(root, "description"),
            blocks = blocks,
        )
    }

    private fun parseBlock(json: JSONObject, index: Int): GenerativeUiBlock {
        require(json.get("type") is String) { "block type must be text" }
        require(json.get("id") is String) { "block id must be text" }
        val id = safeId(json.getString("id"))
        val type = json.getString("type").lowercase()
        val title = optionalText(json, "title")
        return when (type) {
            "stat" -> {
                requireOnlyKeys(json, setOf("type", "id", "title", "value", "supportingText"))
                GenerativeUiBlock.Stat(
                    id = id,
                    title = title,
                    value = requiredText(json, "value"),
                    supportingText = optionalText(json, "supportingText"),
                )
            }
            "bar_chart" -> {
                requireOnlyKeys(json, setOf("type", "id", "title", "labels", "values", "unit"))
                val labels = json.optJSONArray("labels").boundedStrings()
                val values = json.optJSONArray("values").boundedNumbers()
                require(labels.isNotEmpty() && labels.size == values.size) { "chart labels/values mismatch" }
                GenerativeUiBlock.BarChart(id, title, labels, values, optionalText(json, "unit"))
            }
            "diagram" -> {
                requireOnlyKeys(json, setOf("type", "id", "title", "nodes", "edges"))
                val nodesJson = json.optJSONArray("nodes") ?: JSONArray()
                require(nodesJson.length() in 1..MAX_ITEMS) { "invalid diagram nodes" }
                val nodes = buildList {
                    for (i in 0 until nodesJson.length()) {
                        val node = nodesJson.getJSONObject(i)
                        requireOnlyKeys(node, setOf("id", "label", "description"))
                        add(DiagramNode(requiredId(node, "id"), requiredText(node, "label"), optionalText(node, "description")))
                    }
                }
                val nodeIds = nodes.map { it.id }.toSet()
                val edgesJson = json.optJSONArray("edges") ?: JSONArray()
                require(edgesJson.length() <= MAX_ITEMS) { "too many diagram edges" }
                val edges = buildList {
                    for (i in 0 until edgesJson.length()) {
                        val edge = edgesJson.getJSONObject(i)
                        requireOnlyKeys(edge, setOf("from", "to", "label"))
                        val from = requiredId(edge, "from")
                        val to = requiredId(edge, "to")
                        require(from in nodeIds && to in nodeIds) { "edge references unknown node" }
                        add(DiagramEdge(from, to, optionalText(edge, "label")))
                    }
                }
                GenerativeUiBlock.Diagram(id, title, nodes, edges)
            }
            "form" -> {
                requireOnlyKeys(json, setOf("type", "id", "title", "fields", "submitLabel", "actionId"))
                val fieldsJson = json.optJSONArray("fields") ?: JSONArray()
                require(fieldsJson.length() in 1..8) { "invalid form fields" }
                val fields = buildList {
                    for (i in 0 until fieldsJson.length()) {
                        val field = fieldsJson.getJSONObject(i)
                        requireOnlyKeys(field, setOf("id", "label", "placeholder", "required"))
                        add(FormField(requiredId(field, "id"), requiredText(field, "label"), optionalText(field, "placeholder"), optionalBoolean(field, "required") ?: false))
                    }
                }
                GenerativeUiBlock.Form(id, title, fields, optionalText(json, "submitLabel") ?: "Submit", safeId(optionalText(json, "actionId") ?: id))
            }
            "actions" -> {
                requireOnlyKeys(json, setOf("type", "id", "title", "options"))
                val optionsJson = json.optJSONArray("options") ?: JSONArray()
                require(optionsJson.length() in 1..8) { "invalid action count" }
                val options = buildList {
                    for (i in 0 until optionsJson.length()) {
                        val option = optionsJson.getJSONObject(i)
                        requireOnlyKeys(option, setOf("id", "label", "prompt"))
                        add(ActionOption(
                            id = requiredId(option, "id"),
                            label = requiredText(option, "label"),
                            prompt = requiredText(option, "prompt"),
                        ))
                    }
                }
                GenerativeUiBlock.Actions(id, title, options)
            }
            "route" -> {
                requireOnlyKeys(json, setOf("type", "id", "title", "stops"))
                val stopsJson = json.optJSONArray("stops") ?: JSONArray()
                require(stopsJson.length() in 2..20) { "invalid route stops" }
                val stops = buildList {
                    for (i in 0 until stopsJson.length()) {
                        val stop = stopsJson.getJSONObject(i)
                        requireOnlyKeys(stop, setOf("id", "label", "location", "latitude", "longitude"))
                        val latitude = strictOptionalDouble(stop, "latitude")
                        val longitude = strictOptionalDouble(stop, "longitude")
                        require(latitude == null || latitude in -90.0..90.0) { "latitude out of range" }
                        require(longitude == null || longitude in -180.0..180.0) { "longitude out of range" }
                        add(RouteStop(
                            id = requiredId(stop, "id"),
                            label = requiredText(stop, "label"),
                            location = requiredText(stop, "location"),
                            latitude = latitude,
                            longitude = longitude,
                        ))
                    }
                }
                GenerativeUiBlock.Route(id, title, stops)
            }
            "table" -> {
                requireOnlyKeys(json, setOf("type", "id", "title", "headers", "rows"))
                val headers = json.optJSONArray("headers").boundedStrings()
                require(headers.size in 2..6) { "table needs two to six headers" }
                val rowsJson = json.optJSONArray("rows") ?: JSONArray()
                require(rowsJson.length() in 1..20) { "invalid table rows" }
                val rows = buildList {
                    for (i in 0 until rowsJson.length()) {
                        val row = rowsJson.getJSONArray(i).boundedStrings()
                        require(row.size == headers.size) { "table row mismatch" }
                        add(row)
                    }
                }
                GenerativeUiBlock.Table(id, title, headers, rows)
            }
            "checklist" -> {
                requireOnlyKeys(json, setOf("type", "id", "title", "items"))
                val itemsJson = json.optJSONArray("items") ?: JSONArray()
                require(itemsJson.length() in 1..20) { "invalid checklist items" }
                val items = buildList {
                    for (i in 0 until itemsJson.length()) {
                        val item = itemsJson.getJSONObject(i)
                        requireOnlyKeys(item, setOf("id", "label", "detail", "checked"))
                        add(ChecklistItem(
                            id = requiredId(item, "id"),
                            label = requiredText(item, "label"),
                            detail = optionalText(item, "detail"),
                            initiallyChecked = optionalBoolean(item, "checked") ?: false,
                        ))
                    }
                }
                GenerativeUiBlock.Checklist(id, title, items)
            }
            else -> error("unsupported ui block")
        }
    }

    private fun requiredText(json: JSONObject, key: String): String {
        require(json.has(key) && json.get(key) is String) { "$key must be text" }
        return safeText(json.getString(key)) ?: error("missing $key")
    }
    private fun requiredId(json: JSONObject, key: String): String = safeId(requiredText(json, key))
    private fun optionalText(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        require(json.get(key) is String) { "$key must be text" }
        return safeText(json.getString(key))
    }
    private fun optionalBoolean(json: JSONObject, key: String): Boolean? {
        if (!json.has(key) || json.isNull(key)) return null
        require(json.get(key) is Boolean) { "$key must be boolean" }
        return json.getBoolean(key)
    }
    private fun safeText(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT)
    private fun safeId(value: String): String = value.trim().take(48).takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: error("invalid id")

    private fun requireOnlyKeys(json: JSONObject, allowed: Set<String>) {
        json.keys().forEach { key -> require(key in allowed) { "unknown key: $key" } }
    }

    private fun validateDepth(value: Any?, depth: Int) {
        require(depth <= MAX_JSON_DEPTH) { "ui schema nesting is too deep" }
        when (value) {
            is JSONObject -> value.keys().forEach { key -> validateDepth(value.get(key), depth + 1) }
            is JSONArray -> for (index in 0 until value.length()) {
                validateDepth(value.get(index), depth + 1)
            }
        }
    }

    private fun strictOptionalDouble(json: JSONObject, key: String): Double? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.get(key)
        require(value is Number) { "$key must be numeric" }
        return value.toDouble().also { require(it.isFinite()) { "$key must be finite" } }
    }

    private fun JSONArray?.boundedStrings(): List<String> {
        require(this != null && length() in 1..MAX_ITEMS)
        return buildList { for (i in 0 until length()) add(safeText(getString(i)) ?: error("empty array text")) }
    }

    private fun JSONArray?.boundedNumbers(): List<Float> {
        val values = requireNotNull(this) { "chart values must be an array" }
        require(values.length() in 1..MAX_ITEMS)
        return buildList {
            for (i in 0 until values.length()) {
                val raw = values.get(i)
                require(raw is Number) { "chart value must be numeric" }
                val value = raw.toDouble()
                require(value.isFinite()) { "non-finite chart value" }
                require(value in -1_000_000.0..1_000_000.0) { "chart value out of range" }
                add(value.toFloat())
            }
        }
    }
}

private const val MAX_ACTION_VALUE_CHARS = 400
private const val MAX_ACTION_FIELD_CHARS = 500
