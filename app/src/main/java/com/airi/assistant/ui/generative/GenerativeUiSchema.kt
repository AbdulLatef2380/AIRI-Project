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
    data class LineChart(
        override val id: String,
        override val title: String?,
        val labels: List<String>,
        val values: List<Float>,
        val unit: String?,
    ) : GenerativeUiBlock

    data class PieChart(
        override val id: String,
        override val title: String?,
        val labels: List<String>,
        val values: List<Float>,
    ) : GenerativeUiBlock

    data class Image(
        override val id: String,
        override val title: String?,
        val alt: String,
        val source: String,
    ) : GenerativeUiBlock

    data class Cards(
        override val id: String,
        override val title: String?,
        val items: List<CardItem>,
    ) : GenerativeUiBlock

    data class Tabs(
        override val id: String,
        override val title: String?,
        val items: List<TabItem>,
    ) : GenerativeUiBlock

    data class Timeline(
        override val id: String,
        override val title: String?,
        val items: List<TimelineItem>,
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
enum class FormFieldKind { TEXT, SELECT, SLIDER, DATE }
data class FormField(
    val id: String,
    val label: String,
    val placeholder: String?,
    val required: Boolean,
    val kind: FormFieldKind = FormFieldKind.TEXT,
    val options: List<String> = emptyList(),
    val min: Float? = null,
    val max: Float? = null,
    val step: Float? = null,
)
data class ActionOption(val id: String, val label: String, val prompt: String, val local: Boolean = false)
data class CardItem(val id: String, val title: String, val body: String, val actionId: String?)
data class TabItem(val id: String, val label: String, val body: String)
data class TimelineItem(val id: String, val label: String, val detail: String?, val status: String?)
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

enum class GenerativeUiRejectReason {
    NO_FENCE,
    UNCLOSED_FENCE,
    OVERSIZE,
    BAD_JSON,
    BAD_VERSION,
    UNKNOWN_KEY,
    UNKNOWN_BLOCK,
    BAD_ID,
    DUPLICATE_ID,
    LIMIT_EXCEEDED,
    INVALID_SCHEMA,
}

sealed interface GenerativeUiCompileResult {
    data class Accepted(val value: CompiledGenerativeUi) : GenerativeUiCompileResult
    data class Rejected(val reason: GenerativeUiRejectReason) : GenerativeUiCompileResult
}

/**
 * Hard limits of the airi-ui contract. They are public so the model-facing
 * prompt contract and the parser are generated from one set of numbers and
 * cannot drift apart.
 */
object GenerativeUiLimits {
    const val MAX_BLOCKS = 8
    const val MAX_ITEMS = 30
    const val MAX_TEXT = 240
    const val MIN_FORM_FIELDS = 1
    const val MAX_FORM_FIELDS = 8
    const val MAX_ACTION_OPTIONS = 8
    const val MIN_ROUTE_STOPS = 2
    const val MAX_ROUTE_STOPS = 20
    const val MIN_TABLE_COLUMNS = 2
    const val MAX_TABLE_COLUMNS = 6
    const val MAX_TABLE_ROWS = 20
    const val MAX_CHECKLIST_ITEMS = 20
    const val MAX_ID_CHARS = 48
}

/**
 * Typed parser failure. The rejection category is part of the exception, so
 * classification can never depend on the wording of an error message.
 */
private class SchemaViolation(
    val reason: GenerativeUiRejectReason,
    message: String,
) : IllegalArgumentException(message)

private fun reject(reason: GenerativeUiRejectReason, message: String): Nothing =
    throw SchemaViolation(reason, message)

private inline fun ensure(
    condition: Boolean,
    reason: GenerativeUiRejectReason = GenerativeUiRejectReason.INVALID_SCHEMA,
    message: () -> String,
) {
    if (!condition) reject(reason, message())
}

object GenerativeUiCompiler {
    private const val MAX_RESPONSE_CHARS = 96_000
    private const val MAX_JSON_CHARS = 32_000
    private const val MAX_JSON_DEPTH = 12
    private const val CACHE_ENTRIES = 4
    private const val FENCE_OPEN = "```airi-ui"
    private val fenced = Regex("```airi-ui\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    private val idPattern = Regex("[A-Za-z0-9_-]+")
    private val noFence = GenerativeUiCompileResult.Rejected(GenerativeUiRejectReason.NO_FENCE)

    /**
     * Small access-ordered cache. A finished response is compiled by the
     * view-model, the composition planner and the renderer; the cache makes
     * those three calls cost one parse. Results are immutable value objects.
     */
    private val cache = object : LinkedHashMap<String, GenerativeUiCompileResult>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GenerativeUiCompileResult>?): Boolean =
            size > CACHE_ENTRIES
    }

    fun compileDetailed(text: String): GenerativeUiCompileResult {
        if (text.length > MAX_RESPONSE_CHARS) return GenerativeUiCompileResult.Rejected(GenerativeUiRejectReason.OVERSIZE)
        if (!text.contains(FENCE_OPEN, ignoreCase = true)) return noFence
        synchronized(cache) { cache[text] }?.let { return it }
        val result = compileUncached(text)
        synchronized(cache) { cache[text] = result }
        return result
    }

    /** Backwards-compatible nullable wrapper for existing renderers. */
    fun compile(text: String): CompiledGenerativeUi? =
        (compileDetailed(text) as? GenerativeUiCompileResult.Accepted)?.value

    /**
     * Returns [text] without any airi-ui payload (closed fences, and an
     * unclosed trailing fence). Keyword-based classifiers must run on this
     * value so that interface keys such as "route" or "bar_chart" never
     * influence the response lane.
     */
    fun withoutGenerativeUi(text: String): String {
        if (!text.contains(FENCE_OPEN, ignoreCase = true)) return text
        val closedRemoved = fenced.replace(text, " ")
        val open = closedRemoved.indexOf(FENCE_OPEN, ignoreCase = true)
        return if (open >= 0) closedRemoved.substring(0, open) else closedRemoved
    }

    internal fun clearCacheForTesting() {
        synchronized(cache) { cache.clear() }
    }

    private fun compileUncached(text: String): GenerativeUiCompileResult {
        val match = fenced.find(text) ?: return GenerativeUiCompileResult.Rejected(GenerativeUiRejectReason.UNCLOSED_FENCE)
        val raw = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (raw.length > MAX_JSON_CHARS) return GenerativeUiCompileResult.Rejected(GenerativeUiRejectReason.OVERSIZE)
        if (raw.isBlank()) return GenerativeUiCompileResult.Rejected(GenerativeUiRejectReason.BAD_JSON)
        val document = try {
            val json = JSONObject(raw)
            validateDepth(json, 0)
            parse(json)
        } catch (error: Exception) {
            return GenerativeUiCompileResult.Rejected(reasonFor(error))
        }
        val surrounding = text.removeRange(match.range).trim()
        return GenerativeUiCompileResult.Accepted(CompiledGenerativeUi(document, surrounding))
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
        val version = root.opt("version")
        ensure(version is Number, GenerativeUiRejectReason.BAD_VERSION) { "version must be numeric" }
        ensure((version as Number).toDouble() == 1.0, GenerativeUiRejectReason.BAD_VERSION) { "unsupported ui schema" }
        val blocksJson = root.opt("blocks") as? JSONArray
            ?: reject(GenerativeUiRejectReason.INVALID_SCHEMA, "blocks must be an array")
        ensure(blocksJson.length() in 1..GenerativeUiLimits.MAX_BLOCKS, GenerativeUiRejectReason.LIMIT_EXCEEDED) { "invalid block count" }
        val blocks = buildList {
            for (index in 0 until blocksJson.length()) {
                add(parseBlock(objectAt(blocksJson, index)))
            }
        }
        // One flat identifier namespace: block ids, nested ids and form action
        // ids may never collide, because an action is later rebuilt by id.
        val ids = mutableSetOf<String>()
        fun claim(id: String) = ensure(ids.add(id), GenerativeUiRejectReason.DUPLICATE_ID) { "duplicate id" }
        blocks.forEachIndexed { index, block ->
            val rawBlock = objectAt(blocksJson, index)
            claim(block.id)
            when (block) {
                is GenerativeUiBlock.Diagram -> block.nodes.forEach { claim(it.id) }
                is GenerativeUiBlock.Form -> {
                    block.fields.forEach { claim(it.id) }
                    // An explicitly supplied actionId participates in the same
                    // namespace as block and field IDs. The implicit default
                    // keeps the legacy action id equal to the form id.
                    if ((rawBlock as JSONObject).has("actionId")) claim(block.actionId)
                }
                is GenerativeUiBlock.Actions -> block.options.forEach { claim(it.id) }
                is GenerativeUiBlock.Route -> block.stops.forEach { claim(it.id) }
                is GenerativeUiBlock.Checklist -> block.items.forEach { claim(it.id) }
                is GenerativeUiBlock.Cards -> block.items.forEach { claim(it.id); it.actionId?.let(::claim) }
                is GenerativeUiBlock.Tabs -> block.items.forEach { claim(it.id) }
                is GenerativeUiBlock.Timeline -> block.items.forEach { claim(it.id) }
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

    private fun parseBlock(json: JSONObject): GenerativeUiBlock {
        ensure(json.opt("type") is String) { "block type must be text" }
        ensure(json.opt("id") is String) { "block id must be text" }
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
                ensure(labels.size == values.size) { "chart labels/values mismatch" }
                GenerativeUiBlock.BarChart(id, title, labels, values, optionalText(json, "unit"))
            }
            "line_chart" -> {
                requireOnlyKeys(json, setOf("type", "id", "title", "labels", "values", "unit"))
                val labels = json.optJSONArray("labels").boundedStrings()
                val values = json.optJSONArray("values").boundedNumbers()
                ensure(labels.size == values.size) { "chart labels/values mismatch" }
                GenerativeUiBlock.LineChart(id, title, labels, values, optionalText(json, "unit"))
            }
            "pie_chart" -> {
                requireOnlyKeys(json, setOf("type", "id", "title", "labels", "values"))
                val labels = json.optJSONArray("labels").boundedStrings()
                val values = json.optJSONArray("values").boundedNumbers()
                ensure(labels.size == values.size) { "chart labels/values mismatch" }
                GenerativeUiBlock.PieChart(id, title, labels, values)
            }
            "image" -> {
                requireOnlyKeys(json, setOf("type", "id", "title", "alt", "source"))
                GenerativeUiBlock.Image(id, title, requiredText(json, "alt"), requiredText(json, "source"))
            }
            "cards" -> {
                requireOnlyKeys(json, setOf("type", "id", "title", "items"))
                val itemsJson = json.optJSONArray("items") ?: JSONArray()
                ensure(itemsJson.length() in 1..GenerativeUiLimits.MAX_ITEMS, GenerativeUiRejectReason.LIMIT_EXCEEDED) { "invalid card count" }
                val items = buildList {
                    for (i in 0 until itemsJson.length()) {
                        val item = objectAt(itemsJson, i)
                        requireOnlyKeys(item, setOf("id", "title", "body", "actionId"))
                        add(CardItem(requiredId(item, "id"), requiredText(item, "title"), requiredText(item, "body"), optionalText(item, "actionId")?.let(::safeId)))
                    }
                }
                GenerativeUiBlock.Cards(id, title, items)
            }
            "tabs" -> {
                requireOnlyKeys(json, setOf("type", "id", "title", "items"))
                val itemsJson = json.optJSONArray("items") ?: JSONArray()
                ensure(itemsJson.length() in 1..GenerativeUiLimits.MAX_ITEMS, GenerativeUiRejectReason.LIMIT_EXCEEDED) { "invalid tab count" }
                val items = buildList {
                    for (i in 0 until itemsJson.length()) {
                        val item = objectAt(itemsJson, i)
                        requireOnlyKeys(item, setOf("id", "label", "body"))
                        add(TabItem(requiredId(item, "id"), requiredText(item, "label"), requiredText(item, "body")))
                    }
                }
                GenerativeUiBlock.Tabs(id, title, items)
            }
            "timeline" -> {
                requireOnlyKeys(json, setOf("type", "id", "title", "items"))
                val itemsJson = json.optJSONArray("items") ?: JSONArray()
                ensure(itemsJson.length() in 1..GenerativeUiLimits.MAX_ITEMS, GenerativeUiRejectReason.LIMIT_EXCEEDED) { "invalid timeline count" }
                val items = buildList {
                    for (i in 0 until itemsJson.length()) {
                        val item = objectAt(itemsJson, i)
                        requireOnlyKeys(item, setOf("id", "label", "detail", "status"))
                        add(TimelineItem(requiredId(item, "id"), requiredText(item, "label"), optionalText(item, "detail"), optionalText(item, "status")))
                    }
                }
                GenerativeUiBlock.Timeline(id, title, items)
            }
            "diagram" -> {
                requireOnlyKeys(json, setOf("type", "id", "title", "nodes", "edges"))
                val nodesJson = json.optJSONArray("nodes") ?: JSONArray()
                ensure(nodesJson.length() in 1..GenerativeUiLimits.MAX_ITEMS, GenerativeUiRejectReason.LIMIT_EXCEEDED) { "invalid diagram nodes" }
                val nodes = buildList {
                    for (i in 0 until nodesJson.length()) {
                        val node = objectAt(nodesJson, i)
                        requireOnlyKeys(node, setOf("id", "label", "description"))
                        add(DiagramNode(requiredId(node, "id"), requiredText(node, "label"), optionalText(node, "description")))
                    }
                }
                val nodeIds = nodes.map { it.id }.toSet()
                val edgesJson = json.optJSONArray("edges") ?: JSONArray()
                ensure(edgesJson.length() <= GenerativeUiLimits.MAX_ITEMS, GenerativeUiRejectReason.LIMIT_EXCEEDED) { "too many diagram edges" }
                val edges = buildList {
                    for (i in 0 until edgesJson.length()) {
                        val edge = objectAt(edgesJson, i)
                        requireOnlyKeys(edge, setOf("from", "to", "label"))
                        val from = requiredId(edge, "from")
                        val to = requiredId(edge, "to")
                        ensure(from in nodeIds && to in nodeIds) { "edge references unknown node" }
                        add(DiagramEdge(from, to, optionalText(edge, "label")))
                    }
                }
                GenerativeUiBlock.Diagram(id, title, nodes, edges)
            }
            "form" -> {
                requireOnlyKeys(json, setOf("type", "id", "title", "fields", "submitLabel", "actionId"))
                val fieldsJson = json.optJSONArray("fields") ?: JSONArray()
                ensure(
                    fieldsJson.length() in GenerativeUiLimits.MIN_FORM_FIELDS..GenerativeUiLimits.MAX_FORM_FIELDS,
                    GenerativeUiRejectReason.LIMIT_EXCEEDED,
                ) { "invalid form fields" }
                val fields = buildList {
                    for (i in 0 until fieldsJson.length()) {
                        val field = objectAt(fieldsJson, i)
                        requireOnlyKeys(field, setOf("id", "label", "placeholder", "required", "kind", "options", "min", "max", "step"))
                        val kind = when (optionalText(field, "kind")?.lowercase()) {
                            "select" -> FormFieldKind.SELECT
                            "slider" -> FormFieldKind.SLIDER
                            "date" -> FormFieldKind.DATE
                            else -> FormFieldKind.TEXT
                        }
                        val options = field.optJSONArray("options").boundedStringsOrEmpty()
                        ensure(kind != FormFieldKind.SELECT || options.isNotEmpty()) { "select needs options" }
                        val min = strictOptionalDouble(field, "min")?.toFloat()
                        val max = strictOptionalDouble(field, "max")?.toFloat()
                        ensure(kind != FormFieldKind.SLIDER || (min != null && max != null && max > min)) { "slider needs a valid range" }
                        add(FormField(requiredId(field, "id"), requiredText(field, "label"), optionalText(field, "placeholder"), optionalBoolean(field, "required") ?: false, kind, options, min, max, strictOptionalDouble(field, "step")?.toFloat()))
                    }
                }
                GenerativeUiBlock.Form(id, title, fields, optionalText(json, "submitLabel") ?: "Submit", safeId(optionalText(json, "actionId") ?: id))
            }
            "actions" -> {
                requireOnlyKeys(json, setOf("type", "id", "title", "options"))
                val optionsJson = json.optJSONArray("options") ?: JSONArray()
                ensure(optionsJson.length() in 1..GenerativeUiLimits.MAX_ACTION_OPTIONS, GenerativeUiRejectReason.LIMIT_EXCEEDED) { "invalid action count" }
                val options = buildList {
                    for (i in 0 until optionsJson.length()) {
                        val option = objectAt(optionsJson, i)
                        requireOnlyKeys(option, setOf("id", "label", "prompt", "local"))
                        add(ActionOption(
                            id = requiredId(option, "id"),
                            label = requiredText(option, "label"),
                            prompt = requiredText(option, "prompt"),
                            local = optionalBoolean(option, "local") ?: false,
                        ))
                    }
                }
                GenerativeUiBlock.Actions(id, title, options)
            }
            "route" -> {
                requireOnlyKeys(json, setOf("type", "id", "title", "stops"))
                val stopsJson = json.optJSONArray("stops") ?: JSONArray()
                ensure(
                    stopsJson.length() in GenerativeUiLimits.MIN_ROUTE_STOPS..GenerativeUiLimits.MAX_ROUTE_STOPS,
                    GenerativeUiRejectReason.LIMIT_EXCEEDED,
                ) { "invalid route stops" }
                val stops = buildList {
                    for (i in 0 until stopsJson.length()) {
                        val stop = objectAt(stopsJson, i)
                        requireOnlyKeys(stop, setOf("id", "label", "location", "latitude", "longitude"))
                        val latitude = strictOptionalDouble(stop, "latitude")
                        val longitude = strictOptionalDouble(stop, "longitude")
                        ensure(latitude == null || latitude in -90.0..90.0, GenerativeUiRejectReason.LIMIT_EXCEEDED) { "latitude out of range" }
                        ensure(longitude == null || longitude in -180.0..180.0, GenerativeUiRejectReason.LIMIT_EXCEEDED) { "longitude out of range" }
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
                ensure(
                    headers.size in GenerativeUiLimits.MIN_TABLE_COLUMNS..GenerativeUiLimits.MAX_TABLE_COLUMNS,
                    GenerativeUiRejectReason.LIMIT_EXCEEDED,
                ) { "table needs two to six headers" }
                val rowsJson = json.optJSONArray("rows") ?: JSONArray()
                ensure(rowsJson.length() in 1..GenerativeUiLimits.MAX_TABLE_ROWS, GenerativeUiRejectReason.LIMIT_EXCEEDED) { "invalid table rows" }
                val rows = buildList {
                    for (i in 0 until rowsJson.length()) {
                        val rowJson = rowsJson.optJSONArray(i)
                            ?: reject(GenerativeUiRejectReason.INVALID_SCHEMA, "table row must be an array")
                        val row = rowJson.boundedStrings()
                        ensure(row.size == headers.size) { "table row mismatch" }
                        add(row)
                    }
                }
                GenerativeUiBlock.Table(id, title, headers, rows)
            }
            "checklist" -> {
                requireOnlyKeys(json, setOf("type", "id", "title", "items"))
                val itemsJson = json.optJSONArray("items") ?: JSONArray()
                ensure(itemsJson.length() in 1..GenerativeUiLimits.MAX_CHECKLIST_ITEMS, GenerativeUiRejectReason.LIMIT_EXCEEDED) { "invalid checklist items" }
                val items = buildList {
                    for (i in 0 until itemsJson.length()) {
                        val item = objectAt(itemsJson, i)
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
            else -> reject(GenerativeUiRejectReason.UNKNOWN_BLOCK, "unsupported ui block")
        }
    }

    private fun objectAt(array: JSONArray, index: Int): JSONObject =
        array.optJSONObject(index) ?: reject(GenerativeUiRejectReason.INVALID_SCHEMA, "array item must be an object")

    private fun requiredText(json: JSONObject, key: String): String {
        ensure(json.has(key) && json.get(key) is String) { "$key must be text" }
        return safeText(json.getString(key)) ?: reject(GenerativeUiRejectReason.INVALID_SCHEMA, "missing $key")
    }
    private fun requiredId(json: JSONObject, key: String): String = safeId(requiredText(json, key))
    private fun optionalText(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        ensure(json.get(key) is String) { "$key must be text" }
        return safeText(json.getString(key))
    }
    private fun optionalBoolean(json: JSONObject, key: String): Boolean? {
        if (!json.has(key) || json.isNull(key)) return null
        ensure(json.get(key) is Boolean) { "$key must be boolean" }
        return json.getBoolean(key)
    }
    private fun safeText(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }?.take(GenerativeUiLimits.MAX_TEXT)
    private fun safeId(value: String): String =
        value.trim().take(GenerativeUiLimits.MAX_ID_CHARS).takeIf { idPattern.matches(it) }
            ?: reject(GenerativeUiRejectReason.BAD_ID, "invalid id")

    private fun requireOnlyKeys(json: JSONObject, allowed: Set<String>) {
        json.keys().forEach { key -> ensure(key in allowed, GenerativeUiRejectReason.UNKNOWN_KEY) { "unknown key" } }
    }

    private fun validateDepth(value: Any?, depth: Int) {
        ensure(depth <= MAX_JSON_DEPTH, GenerativeUiRejectReason.LIMIT_EXCEEDED) { "ui schema nesting is too deep" }
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
        ensure(value is Number) { "$key must be numeric" }
        return (value as Number).toDouble().also { ensure(it.isFinite()) { "$key must be finite" } }
    }

    private fun reasonFor(error: Throwable): GenerativeUiRejectReason = when (error) {
        is SchemaViolation -> error.reason
        is org.json.JSONException -> GenerativeUiRejectReason.BAD_JSON
        else -> GenerativeUiRejectReason.INVALID_SCHEMA
    }

    private fun JSONArray?.boundedStrings(): List<String> {
        ensure(this != null, GenerativeUiRejectReason.INVALID_SCHEMA) { "list must be an array" }
        val array = this!!
        ensure(array.length() in 1..GenerativeUiLimits.MAX_ITEMS, GenerativeUiRejectReason.LIMIT_EXCEEDED) { "invalid list size" }
        return buildList {
            for (i in 0 until array.length()) {
                val raw = when (val item = array.opt(i)) {
                    is String -> item
                    is Number -> item.toString()
                    else -> reject(GenerativeUiRejectReason.INVALID_SCHEMA, "list item must be text")
                }
                add(safeText(raw) ?: reject(GenerativeUiRejectReason.INVALID_SCHEMA, "empty array text"))
            }
        }
    }

    private fun JSONArray?.boundedNumbers(): List<Float> {
        ensure(this != null, GenerativeUiRejectReason.INVALID_SCHEMA) { "chart values must be an array" }
        val values = this!!
        ensure(values.length() in 1..GenerativeUiLimits.MAX_ITEMS, GenerativeUiRejectReason.LIMIT_EXCEEDED) { "invalid list size" }
        return buildList {
            for (i in 0 until values.length()) {
                val raw = values.opt(i)
                ensure(raw is Number) { "chart value must be numeric" }
                val value = (raw as Number).toDouble()
                ensure(value.isFinite()) { "non-finite chart value" }
                ensure(value in -1_000_000.0..1_000_000.0, GenerativeUiRejectReason.LIMIT_EXCEEDED) { "chart value out of range" }
                add(value.toFloat())
            }
        }
    }

    private fun JSONArray?.boundedStringsOrEmpty(): List<String> =
        if (this == null) emptyList() else boundedStrings()
}

private const val MAX_ACTION_VALUE_CHARS = 400
private const val MAX_ACTION_FIELD_CHARS = 500
