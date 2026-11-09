package com.airi.assistant.ui.run

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.airi.assistant.R
import com.airi.assistant.ui.run.code.LineDiff
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Part 5 §7.6. Device tests for the approval card (the choices call back, a destructive request never
 * offers "allow for this run") and a screenshot harness: the approval card and the diff viewer are
 * rendered in English/Arabic and light/dark and written as PNGs to the app's external files folder
 * (`files/run_screenshots/`). [screenshotsMatchTheReferenceImages] compares them with reference PNGs using
 * [PixelComparator], so no golden-image library (and no new dependency checksums) is needed.
 *
 * Not run in the environment that wrote it (no Android SDK). Run: ./gradlew :app:connectedDebugAndroidTest
 */
class AgentRunScreensInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    private companion object {
        const val MAX_DIFF_RATIO = 0.005
    }

    private val base: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun localized(tag: String): Context {
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(config)
    }

    @Composable
    private fun Themed(tag: String, dark: Boolean, content: @Composable () -> Unit) {
        val ctx = localized(tag)
        val direction = if (tag == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalContext provides ctx, LocalLayoutDirection provides direction) {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(12.dp)) { content() }
            }
        }
    }

    private fun approval(destructive: Boolean) = ApprovalCardModel(
        actionName = "Delete file",
        description = "calculator.py",
        destructive = destructive,
        requestedAtMs = System.currentTimeMillis(),
    )

    @Test fun approvalChoicesCallBack() {
        val chosen = mutableListOf<ApprovalChoice>()
        rule.setContent { Themed("en", dark = false) { ApprovalCardView(approval(destructive = false)) { chosen += it } } }
        val ctx = localized("en")
        rule.onNodeWithText(ctx.getString(R.string.run_approval_allow_once)).performClick()
        rule.onNodeWithText(ctx.getString(R.string.run_approval_allow_run)).performClick()
        rule.onNodeWithText(ctx.getString(R.string.run_approval_deny)).performClick()
        assertEquals(listOf(ApprovalChoice.ALLOW_ONCE, ApprovalChoice.ALLOW_THIS_RUN, ApprovalChoice.DENY), chosen)
    }

    @Test fun destructiveRequestNeverOffersAllowForThisRun() {
        rule.setContent { Themed("en", dark = true) { ApprovalCardView(approval(destructive = true)) {} } }
        val ctx = localized("en")
        rule.onNodeWithText(ctx.getString(R.string.run_approval_allow_once)).assertIsDisplayed()
        rule.onNodeWithText(ctx.getString(R.string.run_approval_deny)).assertIsDisplayed()
        rule.onAllNodesWithTextCount(ctx.getString(R.string.run_approval_allow_run), expected = 0)
    }

    @Test fun diffViewerShowsChangedAndFoldedLines() {
        val old = (1..20).joinToString("\n") { "line $it" }
        val new = old.replace("line 10", "line ten")
        rule.setContent { Themed("en", dark = false) { CodeDiffViewer(LineDiff.view(old, new)) } }
        rule.onNodeWithText("line ten").assertIsDisplayed()
    }

    /** Renders the approval card and the diff viewer in en/ar x light/dark; [onShot] gets each capture. */
    private fun renderVariants(onShot: (name: String, bitmap: Bitmap) -> Unit) {
        val old = "def add(a, b):\n    return a + b\n"
        val new = "def add(a, b):\n    # sum\n    return a + b + 0\n"
        // One setContent per test: language and theme are state, switched between captures.
        var tag by mutableStateOf("en")
        var dark by mutableStateOf(false)
        rule.setContent {
            Themed(tag, dark) {
                androidx.compose.foundation.layout.Column {
                    ApprovalCardView(approval(destructive = false)) {}
                    CodeDiffViewer(LineDiff.view(old, new))
                }
            }
        }
        listOf("en", "ar").forEach { language ->
            listOf(false, true).forEach { isDark ->
                tag = language
                dark = isDark
                rule.waitForIdle()
                onShot("run_${language}_${if (isDark) "dark" else "light"}", rule.onRoot().captureToImage().asAndroidBitmap())
            }
        }
    }

    private fun Bitmap.pixels(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    private fun writePng(file: File, bitmap: Bitmap) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun captureScreenshotsForAllLocalesAndThemes() {
        val out = File(base.getExternalFilesDir(null), "run_screenshots").apply { mkdirs() }
        renderVariants { name, bitmap -> writePng(File(out, "$name.png"), bitmap) }
        assertEquals(4, out.listFiles { f -> f.name.endsWith(".png") }?.size)
    }

    /**
     * Compares each capture with a reference PNG kept in `app/src/androidTest/assets/run_goldens/`
     * (tolerance: 2 per channel, 0.5% of pixels). With no reference present the test fails and says
     * how to create one, because a reference check that passes without references checks nothing.
     *
     * Record the references on the device class you test on (the layout depends on density):
     *   ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.recordGoldens=true
     * then copy the PNG files from `files/run_goldens_new/` in the app's external files folder into the assets folder.
     * On a mismatch the actual image and a red diff are written to `files/run_goldens_failed/`.
     */
    @Test fun screenshotsMatchTheReferenceImages() {
        val record = InstrumentationRegistry.getArguments().getString("recordGoldens") == "true"
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val external = base.getExternalFilesDir(null)
        val newDir = File(external, "run_goldens_new").apply { mkdirs() }
        val failedDir = File(external, "run_goldens_failed").apply { mkdirs() }
        val problems = mutableListOf<String>()
        renderVariants { name, actual ->
            if (record) {
                writePng(File(newDir, "$name.png"), actual)
                return@renderVariants
            }
            val golden = try {
                assets.open("run_goldens/$name.png").use { android.graphics.BitmapFactory.decodeStream(it) }
            } catch (e: java.io.IOException) {
                null
            }
            if (golden == null) {
                writePng(File(newDir, "$name.png"), actual)
                problems += "$name: no reference image (candidate written to ${newDir.name}/)"
                return@renderVariants
            }
            val result = PixelComparator.compare(golden.pixels(), golden.width, golden.height, actual.pixels(), actual.width, actual.height)
            if (!result.matches(MAX_DIFF_RATIO)) {
                writePng(File(failedDir, "$name.actual.png"), actual)
                if (result.sameSize) {
                    val diff = Bitmap.createBitmap(PixelComparator.diffImage(golden.pixels(), actual.pixels()), actual.width, actual.height, Bitmap.Config.ARGB_8888)
                    writePng(File(failedDir, "$name.diff.png"), diff)
                }
                problems += "$name: " + if (result.sameSize) "%.2f%% of pixels differ".format(result.ratio * 100) else "size changed (${golden.width}x${golden.height} -> ${actual.width}x${actual.height})"
            }
        }
        assertEquals("Screenshot references: " + problems.joinToString("; "), emptyList<String>(), problems)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String, expected: Int) {
        assertEquals(expected, onAllNodesWithText(text).fetchSemanticsNodes().size)
    }
}
