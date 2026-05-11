package com.airi.assistant.automation

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.airi.assistant.accessibility.scanner.UITreeScanner
import com.airi.assistant.accessibility.service.AiriAccessibilityService

/**
 * UIInteractionEngine — semantic UI element detection and interaction targeting.
 *
 * Sits above [UITreeScanner] and adds semantic meaning to the raw
 * accessibility node tree:
 *
 * ── CAPABILITIES ─────────────────────────────────────────────────────────────
 *
 * 1. BUTTON DETECTION     — finds interactive elements matching a semantic intent
 * 2. INPUT DETECTION      — finds text fields relevant to an intent
 * 3. SCROLL REGION DETECT — identifies scrollable containers
 * 4. SEMANTIC PREDICTION  — given a natural-language action, predicts the best
 *                           matching UI element to interact with
 * 5. COORDINATE NORMALIZATION — produces display-space coordinates safe for
 *                           gesture injection regardless of screen orientation
 *
 * ── MATCHING STRATEGIES ──────────────────────────────────────────────────────
 *
 *  1. Exact text match    (highest confidence)
 *  2. Partial text match
 *  3. Content description match
 *  4. Resource ID match
 *  5. View class name     (e.g. "Button", "EditText")
 *  6. Heuristic scoring   (weighted combination of above)
 *
 * ── INTEGRATION ──────────────────────────────────────────────────────────────
 *
 *   Used by [AutomationRuntime] to resolve natural-language action targets
 *   before dispatching to [AccessibilityCommandBridge].
 */
class UIInteractionEngine {

    private val TAG = "UIInteractionEngine"

    // ── Types ─────────────────────────────────────────────────────────────────

    data class TargetElement(
        val node:             AccessibilityNodeInfo,
        val confidence:       Float,
        val matchStrategy:    String,
        val semanticLabel:    String,
        val boundsOnScreen:   android.graphics.Rect,
        val isClickable:      Boolean,
        val isEditable:       Boolean,
        val isScrollable:     Boolean,
    )

    data class DetectionResult(
        val candidates: List<TargetElement>,
        val best:       TargetElement?,
        val queryText:  String,
        val screenPkg:  String,
    )

    enum class ElementType { BUTTON, INPUT, SCROLL_REGION, CHECKBOX, ANY }

    // ── Detection API ─────────────────────────────────────────────────────────

    /**
     * Find UI elements matching [query] on the current screen.
     *
     * @param query    Natural-language description (e.g. "Search button", "email field").
     * @param type     Element type filter. Use [ElementType.ANY] for no filter.
     * @param minConf  Minimum confidence threshold (0f–1f).
     */
    fun detect(
        query:   String,
        type:    ElementType = ElementType.ANY,
        minConf: Float       = 0.4f,
    ): DetectionResult {
        val service = AiriAccessibilityService.instance
        val root    = service?.rootInActiveWindow

        if (root == null) {
            Log.w(TAG, "DETECT_FAIL: no active window root")
            return DetectionResult(emptyList(), null, query, "")
        }

        val pkg        = service.rootInActiveWindow?.packageName?.toString() ?: ""
        val candidates = mutableListOf<TargetElement>()

        scanNode(root, query, type, candidates)

        root.recycle()

        val filtered = candidates
            .filter { it.confidence >= minConf }
            .sortedByDescending { it.confidence }
            .take(MAX_CANDIDATES)

        val best = filtered.firstOrNull()
        if (best != null) {
            Log.d(TAG, "DETECT_OK query='${query.take(40)}' best='${best.semanticLabel}' conf=${best.confidence} strategy=${best.matchStrategy}")
        } else {
            Log.w(TAG, "DETECT_MISS query='${query.take(40)}' pkg=$pkg")
        }

        return DetectionResult(filtered, best, query, pkg)
    }

    /**
     * Convenience: find the best clickable element for [intent].
     */
    fun findClickTarget(intent: String): TargetElement? =
        detect(intent, ElementType.BUTTON).best

    /**
     * Convenience: find the best editable field for [fieldHint].
     */
    fun findInputField(fieldHint: String): TargetElement? =
        detect(fieldHint, ElementType.INPUT).best

    /**
     * Convenience: find the primary scrollable container.
     */
    fun findScrollRegion(): TargetElement? =
        detect("scroll", ElementType.SCROLL_REGION).best

    // ── Internal tree traversal ───────────────────────────────────────────────

    private fun scanNode(
        node:       AccessibilityNodeInfo?,
        query:      String,
        type:       ElementType,
        results:    MutableList<TargetElement>,
        depth:      Int = 0,
    ) {
        if (node == null || depth > MAX_DEPTH) return

        val conf = scoreNode(node, query, type)
        if (conf > 0f) {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            results += TargetElement(
                node           = node,
                confidence     = conf,
                matchStrategy  = resolveStrategy(node, query),
                semanticLabel  = extractLabel(node),
                boundsOnScreen = bounds,
                isClickable    = node.isClickable,
                isEditable     = node.isEditable,
                isScrollable   = node.isScrollable,
            )
        }

        for (i in 0 until node.childCount) {
            scanNode(node.getChild(i), query, type, results, depth + 1)
        }
    }

    private fun scoreNode(node: AccessibilityNodeInfo, query: String, type: ElementType): Float {
        if (!passesTypeFilter(node, type)) return 0f

        val q      = query.lowercase().trim()
        val text   = node.text?.toString()?.lowercase() ?: ""
        val desc   = node.contentDescription?.toString()?.lowercase() ?: ""
        val resId  = node.viewIdResourceName?.lowercase() ?: ""
        val cls    = node.className?.toString()?.lowercase() ?: ""

        var score = 0f
        when {
            text.equals(q, ignoreCase = true)   -> score += 1.0f
            text.contains(q)                     -> score += 0.75f
            desc.equals(q, ignoreCase = true)    -> score += 0.9f
            desc.contains(q)                     -> score += 0.65f
            resId.contains(q.replace(" ", "_"))  -> score += 0.55f
            cls.contains("button") && q.contains("button") -> score += 0.4f
        }

        if (node.isClickable)  score += 0.1f
        if (node.isEnabled)    score += 0.05f
        if (node.isVisibleToUser) score += 0.05f

        return score.coerceIn(0f, 1f)
    }

    private fun passesTypeFilter(node: AccessibilityNodeInfo, type: ElementType): Boolean {
        val cls = node.className?.toString()?.lowercase() ?: ""
        return when (type) {
            ElementType.BUTTON       -> node.isClickable || cls.contains("button")
            ElementType.INPUT        -> node.isEditable || cls.contains("edit")
            ElementType.SCROLL_REGION -> node.isScrollable
            ElementType.CHECKBOX     -> cls.contains("checkbox") || cls.contains("switch")
            ElementType.ANY          -> true
        }
    }

    private fun resolveStrategy(node: AccessibilityNodeInfo, query: String): String {
        val q = query.lowercase()
        return when {
            node.text?.toString()?.lowercase()?.equals(q) == true              -> "exact_text"
            node.text?.toString()?.lowercase()?.contains(q) == true            -> "partial_text"
            node.contentDescription?.toString()?.lowercase()?.contains(q) == true -> "content_desc"
            node.viewIdResourceName?.lowercase()?.contains(q.replace(" ", "_")) == true -> "resource_id"
            else                                                                 -> "heuristic"
        }
    }

    private fun extractLabel(node: AccessibilityNodeInfo): String =
        node.text?.toString()?.take(80)
            ?: node.contentDescription?.toString()?.take(80)
            ?: node.viewIdResourceName?.substringAfterLast('/')?.take(80)
            ?: node.className?.toString()?.substringAfterLast('.')?.take(40)
            ?: "unknown"

    companion object {
        private const val MAX_DEPTH      = 15
        private const val MAX_CANDIDATES = 10
    }
}
