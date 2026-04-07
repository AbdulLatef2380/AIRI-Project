package com.airi.assistant

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.airi.assistant.accessibility.service.ScreenContextHolder
import com.airi.assistant.agent.planning.ActionPlan
import com.airi.assistant.agent.planning.BrainInput
import com.airi.assistant.agent.planning.PlanStep
import com.airi.assistant.core.CognitiveResult
import com.airi.assistant.core.UnifiedCognitiveLoop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val cognitiveLoop = UnifiedCognitiveLoop()

    private lateinit var statusView: TextView
    private lateinit var inputField: EditText
    private lateinit var executeButton: Button
    private lateinit var testButton: Button
    private lateinit var clearButton: Button
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView

    private val dp get() = resources.displayMetrics.density
    private fun Int.dp() = (this * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        checkAccessibilityStatus()
    }

    override fun onResume() {
        super.onResume()
        checkAccessibilityStatus()
    }

    // ─── Build UI Programmatically ────────────────────────────────────────

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1a1a2e"))
            setPadding(16.dp(), 24.dp(), 16.dp(), 16.dp())
        }

        // Title
        root.addView(TextView(this).apply {
            text = "AIRI Debug Interface"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#e94560"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8.dp())
        }, matchWidth(WRAP_CONTENT))

        // Accessibility status banner
        statusView = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(statusView, matchWidth(WRAP_CONTENT).apply {
            bottomMargin = 12.dp()
        })

        // Input label
        root.addView(TextView(this).apply {
            text = "Command / JSON Plan:"
            textSize = 13f
            setTextColor(Color.parseColor("#a8a8b3"))
            setPadding(0, 0, 0, 4.dp())
        }, matchWidth(WRAP_CONTENT))

        // Input field
        inputField = EditText(this).apply {
            hint = "e.g.  افتح Chrome   or paste JSON plan"
            setHintTextColor(Color.parseColor("#555577"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.parseColor("#16213e"))
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            minLines = 3
            maxLines = 6
            setHorizontallyScrolling(false)
        }
        root.addView(inputField, matchWidth(WRAP_CONTENT).apply {
            bottomMargin = 10.dp()
        })

        // Button row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }

        executeButton = makeButton("▶ Execute", "#e94560") { onExecute() }
        testButton    = makeButton("⚡ Test Chrome", "#0f3460") { onTestChrome() }
        clearButton   = makeButton("🗑 Clear", "#444466") { clearLogs() }

        buttonRow.addView(executeButton, weightParam(1f).apply { rightMargin = 6.dp() })
        buttonRow.addView(testButton,    weightParam(1f).apply { rightMargin = 6.dp() })
        buttonRow.addView(clearButton,   weightParam(1f))
        root.addView(buttonRow, matchWidth(WRAP_CONTENT).apply { bottomMargin = 12.dp() })

        // "Open Accessibility Settings" button
        val a11yButton = Button(this).apply {
            text = "Open Accessibility Settings"
            textSize = 12f
            setTextColor(Color.parseColor("#a8a8b3"))
            setBackgroundColor(Color.parseColor("#222244"))
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        root.addView(a11yButton, matchWidth(WRAP_CONTENT).apply { bottomMargin = 12.dp() })

        // Divider
        root.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#333355"))
        }, matchWidth(1.dp()).apply { bottomMargin = 8.dp() })

        // Log label
        root.addView(TextView(this).apply {
            text = "Execution Log"
            textSize = 13f
            setTextColor(Color.parseColor("#a8a8b3"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 4.dp())
        }, matchWidth(WRAP_CONTENT))

        // Scrollable log view
        logView = TextView(this).apply {
            setText(SpannableStringBuilder("Ready. Type a command or tap ⚡ Test Chrome to begin.\n"), TextView.BufferType.EDITABLE)
            textSize = 12f
            setTextColor(Color.parseColor("#ccccdd"))
            setBackgroundColor(Color.parseColor("#0d0d1a"))
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            typeface = Typeface.MONOSPACE
        }

        scrollView = ScrollView(this).apply {
            addView(logView, matchWidth(MATCH_PARENT))
        }

        root.addView(scrollView, LinearLayout.LayoutParams(MATCH_PARENT, 0).apply {
            weight = 1f
        })

        setContentView(root)
    }

    // ─── Actions ─────────────────────────────────────────────────────────

    private fun onExecute() {
        val rawInput = inputField.text.toString().trim()
        if (rawInput.isEmpty()) {
            appendLog("⚠️ No input provided.", "#ffcc00")
            return
        }
        val llmJson = if (rawInput.startsWith("{")) rawInput else buildSimulatedPlan(rawInput)
        runPipeline(rawInput, llmJson)
    }

    private fun onTestChrome() {
        val testInput = "افتح Chrome"
        val testJson = """
        {
          "goal": "Open the Chrome browser",
          "steps": [
            {
              "id": "1",
              "action": "open_app",
              "params": {"app_name": "Chrome"},
              "depends_on": [],
              "expected": "Chrome browser opened"
            }
          ]
        }
        """.trimIndent()
        inputField.setText(testJson)
        appendLog("━━━ Quick Test: '$testInput' ━━━", "#e94560")
        runPipeline(testInput, testJson)
    }

    private fun clearLogs() {
        logView.text = ""
        appendLog("Logs cleared.", "#666688")
    }

    // ─── Pipeline Execution ───────────────────────────────────────────────

    private fun runPipeline(userInput: String, llmJson: String) {
        executeButton.isEnabled = false
        testButton.isEnabled = false
        appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", "#333355")
        appendLog("[${timestamp()}] Input received: \"$userInput\"", "#a8a8b3")

        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    // Stage 1: Generate plan
                    log("[PLAN] Generating plan from input...", "#7ec8e3")
                    val brainInput = BrainInput(text = userInput)
                    val actionPlan = cognitiveLoop.planGenerator.createActionPlanFromLLM(
                        llmResponse = llmJson,
                        fallbackDescription = userInput
                    )

                    log("[PLAN] Intent: \"${actionPlan.intent}\"", "#7ec8e3")
                    log("[PLAN] Steps (${actionPlan.steps.size}):", "#7ec8e3")
                    actionPlan.steps.forEachIndexed { i, step ->
                        log("  [${i + 1}] ${describeStep(step)}", "#99ddff")
                    }

                    // Stage 2: Check accessibility
                    val isConnected = ScreenContextHolder.isConnected
                    if (!isConnected) {
                        log("⚠️ [WARN] Accessibility Service NOT connected.", "#ffcc00")
                        log("⚠️ Enable it in Settings → Accessibility → AIRI", "#ffcc00")
                    } else {
                        log("[A11Y] ✅ Accessibility Service connected.", "#66ff99")
                    }

                    // Stage 3: Execute plan
                    log("[EXEC] Executing ${actionPlan.steps.size} step(s)...", "#e94560")
                    val result = cognitiveLoop.process(brainInput, llmJson)

                    // Stage 4: Report results
                    when (result) {
                        is CognitiveResult.Success -> {
                            log("[DONE] ✅ All steps succeeded.", "#66ff99")
                            result.results.forEachIndexed { i, sr ->
                                val icon = if (sr.result.success) "✅" else "❌"
                                log("  Step ${i + 1}: $icon ${sr.result.message ?: "ok"}", if (sr.result.success) "#66ff99" else "#ff6666")
                            }
                        }
                        is CognitiveResult.PartialSuccess -> {
                            log("[DONE] ⚠️ Partial success.", "#ffcc00")
                            result.results.forEachIndexed { i, sr ->
                                val icon = if (sr.result.success) "✅" else "❌"
                                log("  Step ${i + 1}: $icon ${sr.result.message ?: ""}", if (sr.result.success) "#66ff99" else "#ff6666")
                            }
                        }
                        is CognitiveResult.Failed -> {
                            log("[DONE] ❌ Failed: ${result.reason}", "#ff6666")
                            result.results.forEachIndexed { i, sr ->
                                val icon = if (sr.result.success) "✅" else "❌"
                                log("  Step ${i + 1}: $icon ${sr.result.message ?: ""}", if (sr.result.success) "#66ff99" else "#ff6666")
                            }
                        }
                        is CognitiveResult.AwaitingConfirmation ->
                            log("[DONE] ⏳ Awaiting user confirmation.", "#ffcc00")
                        is CognitiveResult.Error ->
                            log("[DONE] ❌ Error: ${result.message}", "#ff6666")
                    }

                } catch (e: Exception) {
                    log("[ERROR] ${e.javaClass.simpleName}: ${e.message}", "#ff6666")
                }
            }

            // Re-enable buttons on main thread
            executeButton.isEnabled = true
            testButton.isEnabled = true
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun buildSimulatedPlan(input: String): String {
        val lower = input.lowercase()
        return when {
            lower.contains("chrome") || lower.contains("كروم") ->
                """{"goal":"$input","steps":[{"id":"1","action":"open_app","params":{"app_name":"Chrome"},"depends_on":[],"expected":"Chrome opened"}]}"""
            lower.contains("search") || lower.contains("ابحث") || lower.contains("بحث") -> {
                val query = input.replace(Regex("(search|ابحث عن|ابحث|بحث)\\s*", RegexOption.IGNORE_CASE), "").trim()
                """{"goal":"$input","steps":[{"id":"1","action":"search","params":{"query":"$query"},"depends_on":[],"expected":"Search results shown"}]}"""
            }
            lower.contains("back") || lower.contains("رجوع") ->
                """{"goal":"$input","steps":[{"id":"1","action":"navigate","params":{"direction":"back"},"depends_on":[]}]}"""
            lower.contains("home") || lower.contains("الرئيسية") ->
                """{"goal":"$input","steps":[{"id":"1","action":"navigate","params":{"direction":"home"},"depends_on":[]}]}"""
            lower.contains("scroll down") || lower.contains("تمرير للأسفل") ->
                """{"goal":"$input","steps":[{"id":"1","action":"scroll","params":{"direction":"down"},"depends_on":[]}]}"""
            else ->
                """{"goal":"$input","steps":[{"id":"1","action":"conversation","params":{"text":"$input"},"depends_on":[],"expected":"Response"}]}"""
        }
    }

    private fun describeStep(step: PlanStep): String = when (step) {
        is PlanStep.OpenApp   -> "OpenApp(appName='${step.appName}')"
        is PlanStep.Search    -> "Search(query='${step.query}')"
        is PlanStep.Click     -> "Click(target='${step.targetText}')"
        is PlanStep.Type      -> "Type(text='${step.text}')"
        is PlanStep.Navigate  -> "Navigate(direction=${step.direction})"
        is PlanStep.Wait      -> "Wait(${step.durationMs}ms)"
        is PlanStep.Scroll    -> "Scroll(direction=${step.direction})"
        is PlanStep.Custom    -> "Custom(action='${step.action}')"
    }

    private fun checkAccessibilityStatus() {
        val connected = ScreenContextHolder.isConnected
        statusView.apply {
            if (connected) {
                text = "✅ Accessibility Service: CONNECTED"
                setTextColor(Color.parseColor("#66ff99"))
                setBackgroundColor(Color.parseColor("#003322"))
            } else {
                text = "⚠️ Accessibility Service NOT enabled — tap button below to enable"
                setTextColor(Color.parseColor("#ffcc00"))
                setBackgroundColor(Color.parseColor("#332200"))
            }
        }
    }

    private suspend fun log(message: String, color: String = "#ccccdd") {
        withContext(Dispatchers.Main) {
            appendLog(message, color)
        }
    }

    private fun appendLog(message: String, color: String = "#ccccdd") {
        val line = "${timestamp()} $message\n"
        val spannable = SpannableString(line)
        spannable.setSpan(
            ForegroundColorSpan(Color.parseColor(color)),
            0, line.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        val ssb = (logView.editableText as? SpannableStringBuilder) ?: SpannableStringBuilder()
        ssb.append(spannable)
        logView.setText(ssb, TextView.BufferType.EDITABLE)
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun timestamp() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    // ─── Layout helpers ───────────────────────────────────────────────────

    private fun matchWidth(height: Int) = LinearLayout.LayoutParams(MATCH_PARENT, height)

    private fun weightParam(weight: Float) = LinearLayout.LayoutParams(0, WRAP_CONTENT, weight)

    private fun makeButton(label: String, bgColor: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(bgColor))
            setOnClickListener { onClick() }
        }
}
