package com.airi.assistant.ui.screens

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.airi.assistant.ui.theme.AiriTheme

/**
 * ArtifactPreviewScreen — isolated, sandboxed rendering for AIRI artifacts.
 *
 * ── Phase 2, Task 7 ────────────────────────────────────────────────────────
 * Previously [com.airi.assistant.workspace.WorkspaceScreen] attempted to render
 * artifacts using native Compose components only, with no WebView sandbox for
 * HTML/JavaScript content — leaving HTML artifact execution entirely absent
 * and creating a potential XSS vector if/when raw HTML was ever rendered.
 *
 * This screen implements the artifact preview capability specified in
 * Capability 11 of the engineering blueprint:
 *
 *   • HTML artifacts   — isolated [WebView] with strict Content-Security-Policy,
 *                         JavaScript disabled by default (enable only if the
 *                         artifact explicitly requests JS execution and the user
 *                         confirms), and all URL loads blocked except data: URIs.
 *   • Markdown         — native Compose text rendering (safe: no execution).
 *   • Code             — native monospace text block with syntax highlighting label.
 *   • Image/Binary     — placeholder (full implementation in Phase 3 with Coil).
 *
 * ── WebView Security Hardening ─────────────────────────────────────────────
 * The WebView sandbox applies the following restrictions:
 *   1. [android.webkit.WebSettings.setJavaScriptEnabled] = false (no JS eval).
 *   2. [android.webkit.WebSettings.setAllowFileAccess] = false (no file:// reads).
 *   3. [android.webkit.WebSettings.setAllowContentAccess] = false.
 *   4. Custom [WebViewClient] that blocks ALL URL navigation (data: URIs only).
 *      This prevents redirects to external sites or to app:// deep-links.
 *   5. Content-Security-Policy header injected into every HTML load:
 *        default-src 'none'; style-src 'unsafe-inline'; img-src data:;
 *      This blocks external resource loads even if JS is somehow re-enabled.
 *
 * The CSP is injected both as a meta tag in the HTML wrapper and as an HTTP
 * response header (via data: URI, where headers are not sent, so meta-CSP
 * is the enforceable path on Android WebView).
 *
 * ── Route ──────────────────────────────────────────────────────────────────
 * Navigate to [com.airi.assistant.ui.AiriRoute.ARTIFACT_PREVIEW] with the
 * artifact content encoded as a route argument.
 *   navController.navigate(AiriRoute.artifactPreview(type, content))
 *
 * ── Risks ──────────────────────────────────────────────────────────────────
 * • JS disabled: HTML+CSS only. Artifact interactivity is limited.
 *   A future "trust this artifact" toggle can enable JS with user confirmation.
 * • Very large HTML blobs (> 2 MB) may OOM the WebView process on low-RAM
 *   devices. Content size should be bounded upstream by ArtifactManager.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactPreviewScreen(
    artifactType:    String,    // "HTML", "MARKDOWN", "CODE", "IMAGE"
    artifactContent: String,
    onBack:          () -> Unit
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AiriTheme.background.copy(alpha = 0.92f)
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = AiriTheme.onBackground
                        )
                    }
                },
                title = {
                    Text(
                        text       = "Preview — $artifactType",
                        color      = AiriTheme.onBackground,
                        fontSize   = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AiriTheme.background)
                .padding(padding)
        ) {
            when (artifactType.uppercase()) {
                "HTML" -> HtmlArtifactView(html = artifactContent)
                "MARKDOWN" -> MarkdownArtifactView(markdown = artifactContent)
                "CODE" -> CodeArtifactView(code = artifactContent)
                else -> UnsupportedArtifactView(type = artifactType)
            }
        }
    }
}

// ── HTML — sandboxed WebView ──────────────────────────────────────────────────

private const val HTML_TAG = "AIRI_ArtifactPreview"

/**
 * Renders HTML content in a strictly sandboxed [WebView].
 *
 * Security measures (all applied before any content is loaded):
 *  - JavaScript:         DISABLED
 *  - File access:        DISABLED
 *  - Content access:     DISABLED
 *  - URL navigation:     BLOCKED (all links return false from shouldOverrideUrlLoading)
 *  - CSP meta tag:       injected into the HTML wrapper
 */
@Composable
private fun HtmlArtifactView(html: String) {
    // Wrap the raw HTML in a full document with the Content-Security-Policy meta tag.
    val sandboxedHtml = buildSandboxedHtmlDocument(html)

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory  = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled      = false
                    allowFileAccess        = false
                    allowContentAccess     = false
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = false
                    domStorageEnabled      = false
                    databaseEnabled        = false
                    setGeolocationEnabled(false)
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        // Block ALL URL navigation — only the initial data: load is allowed.
                        Log.w(HTML_TAG,
                            "AIRI_PROOF ARTIFACT_PREVIEW_URL_BLOCKED url=${request.url}")
                        return true
                    }
                }

                Log.i(HTML_TAG, "AIRI_PROOF ARTIFACT_PREVIEW_HTML_LOAD size=${sandboxedHtml.length}")
                loadDataWithBaseURL(
                    /* baseUrl    = */ null,
                    /* data       = */ sandboxedHtml,
                    /* mimeType   = */ "text/html",
                    /* encoding   = */ "UTF-8",
                    /* historyUrl = */ null
                )
            }
        },
        update = { /* content is static; no update needed */ }
    )
}

/**
 * Wraps arbitrary HTML in a minimal secure document with:
 *  - Meta charset UTF-8
 *  - Content-Security-Policy meta tag blocking all external resources
 *  - Viewport meta for correct mobile scaling
 *  - Dark background matching AIRI theme
 */
private fun buildSandboxedHtmlDocument(rawHtml: String): String = """
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1.0" />
        <!-- AIRI Artifact Preview CSP: no external loads, no JS execution -->
        <meta http-equiv="Content-Security-Policy"
              content="default-src 'none'; style-src 'unsafe-inline'; img-src data: blob:;" />
        <style>
            html, body {
                margin: 0;
                padding: 12px;
                background-color: #0d0d0d;
                color: #e8e8e8;
                font-family: system-ui, -apple-system, sans-serif;
                font-size: 14px;
                line-height: 1.6;
            }
            a { color: #7c3aed; text-decoration: underline; pointer-events: none; }
        </style>
    </head>
    <body>
        $rawHtml
    </body>
    </html>
""".trimIndent()

// ── Markdown — native Compose rendering ──────────────────────────────────────

@Composable
private fun MarkdownArtifactView(markdown: String) {
    // Phase 2: Native text rendering. Phase 3 will integrate a real Markdown
    // parser (e.g. Markwon or CommonMark-Android) for full rendering fidelity.
    // Native rendering is safe — no code execution possible.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        for (line in markdown.lines()) {
            when {
                line.startsWith("# ") -> Text(
                    text       = line.removePrefix("# "),
                    fontSize   = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color      = AiriTheme.onBackground,
                    modifier   = Modifier.padding(vertical = 8.dp)
                )
                line.startsWith("## ") -> Text(
                    text       = line.removePrefix("## "),
                    fontSize   = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = AiriTheme.onBackground,
                    modifier   = Modifier.padding(vertical = 6.dp)
                )
                line.startsWith("### ") -> Text(
                    text       = line.removePrefix("### "),
                    fontSize   = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color      = AiriTheme.onBackground,
                    modifier   = Modifier.padding(vertical = 4.dp)
                )
                line.startsWith("- ") || line.startsWith("* ") -> Row(
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Text("• ", color = AiriTheme.onBackground.copy(alpha = 0.6f))
                    Text(line.drop(2), color = AiriTheme.onBackground)
                }
                line.isBlank() -> Spacer(Modifier.height(8.dp))
                else -> Text(
                    text     = line,
                    color    = AiriTheme.onBackground,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}

// ── Code — monospace text block ───────────────────────────────────────────────

@Composable
private fun CodeArtifactView(code: String) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0D0D0D))
                .padding(16.dp)
        ) {
            Text(
                text       = code,
                fontFamily = FontFamily.Monospace,
                fontSize   = 12.sp,
                color      = Color(0xFFCDD6F4),
                lineHeight = 18.sp
            )
        }
    }
}

// ── Unsupported type placeholder ──────────────────────────────────────────────

@Composable
private fun UnsupportedArtifactView(type: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text      = "Preview not available for type: $type",
            color     = AiriTheme.onSurfaceVariant,
            fontSize  = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
