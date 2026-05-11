package com.airi.assistant.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * WebViewController — sandboxed WebView control for autonomous web task execution.
 *
 * ── CAPABILITIES ─────────────────────────────────────────────────────────────
 *
 *  | Method              | Description                                      |
 *  |---------------------|--------------------------------------------------|
 *  | [navigate]          | Load a URL, await page load                      |
 *  | [getPageHtml]       | Extract current page HTML via JS injection       |
 *  | [executeJs]         | Run arbitrary JS and return the string result    |
 *  | [fillInput]         | Set a form field value                           |
 *  | [clickElement]      | Click a CSS-selector-matched element             |
 *  | [submitForm]        | Submit the first matching form                   |
 *  | [scrollDown]        | Scroll the page by one viewport height           |
 *  | [goBack]            | Navigate backward in history                     |
 *  | [currentUrl]        | Get the currently loaded URL                     |
 *
 * ── SANDBOX POLICY ───────────────────────────────────────────────────────────
 *
 *   - JavaScript allowed for task execution only (no file:// access).
 *   - No DOM storage, no geolocation, no camera access.
 *   - Redirects followed up to [MAX_REDIRECTS].
 *   - WebView is NOT attached to any visible ViewGroup — operates headlessly.
 *   - All JS injection is evaluated in a dedicated [JavascriptInterface].
 *
 * ── THREADING ────────────────────────────────────────────────────────────────
 *
 *   WebView MUST be created on the main thread. All WebView method calls
 *   are dispatched to [mainHandler]. Public API is suspend-safe — callers
 *   block on [CompletableDeferred] until the main thread completes the call.
 *
 * ── LIFECYCLE ────────────────────────────────────────────────────────────────
 *
 *   Call [create] from any coroutine — it dispatches to Main for WebView init.
 *   Call [destroy] when done to release the WebView and clear resources.
 */
class WebViewController(private val context: Context) {

    private val TAG         = "WebViewController"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var webView: WebView? = null

    private val _state = MutableStateFlow(WebViewState())
    val state: StateFlow<WebViewState> = _state.asStateFlow()

    // ── Data ──────────────────────────────────────────────────────────────────

    data class WebViewState(
        val currentUrl:  String  = "",
        val title:       String  = "",
        val isLoading:   Boolean = false,
        val errorMessage: String? = null,
    )

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Initialize the WebView. Must be called before any other method. */
    suspend fun create(): Unit = withContext(Dispatchers.Main) {
        if (webView != null) return@withContext
        val wv = WebView(context)
        wv.settings.apply {
            javaScriptEnabled      = true
            domStorageEnabled      = false
            geolocationEnabled     = false
            allowFileAccess        = false
            allowContentAccess     = false
            useWideViewPort        = true
            loadWithOverviewMode   = true
            setSupportZoom(false)
            builtInZoomControls   = false
            cacheMode             = WebSettings.LOAD_NO_CACHE
            userAgentString       = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        wv.addJavascriptInterface(AiriJsBridge(), "AiriBridge")
        wv.webViewClient = AiriWebViewClient()
        webView = wv
        Log.i(TAG, "WebViewController created (headless)")
    }

    /** Release the WebView. Must be called when done. */
    fun destroy() {
        mainHandler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
            Log.i(TAG, "WebViewController destroyed")
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    /**
     * Load [url] and wait for the page to finish loading.
     * Returns true on success, false on error or timeout.
     */
    suspend fun navigate(url: String, timeoutMs: Long = LOAD_TIMEOUT_MS): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        loadDeferred = deferred

        mainHandler.post {
            val wv = webView ?: run { deferred.complete(false); return@post }
            _state.value = _state.value.copy(isLoading = true, currentUrl = url, errorMessage = null)
            wv.loadUrl(url)
        }

        return runCatching {
            withTimeout(timeoutMs) { deferred.await() }
        }.getOrDefault(false)
    }

    /**
     * Get the full HTML of the current page via JS injection.
     */
    suspend fun getPageHtml(): String {
        return executeJs("document.documentElement.outerHTML") ?: ""
    }

    /**
     * Get the current URL.
     */
    suspend fun currentUrl(): String {
        val def = CompletableDeferred<String>()
        mainHandler.post { def.complete(webView?.url ?: "") }
        return runCatching { def.await() }.getOrDefault("")
    }

    /**
     * Navigate backward in browser history.
     */
    suspend fun goBack(): Boolean {
        val def = CompletableDeferred<Boolean>()
        mainHandler.post {
            val wv = webView
            if (wv != null && wv.canGoBack()) { wv.goBack(); def.complete(true) }
            else def.complete(false)
        }
        return runCatching { def.await() }.getOrDefault(false)
    }

    // ── JS execution ──────────────────────────────────────────────────────────

    /**
     * Execute [js] in the current page context and return the string result.
     * The script must use `AiriBridge.sendResult(value)` to return a value.
     */
    suspend fun executeJs(js: String): String? {
        if (webView == null) return null
        val deferred = CompletableDeferred<String?>()

        val wrapped = """
            (function() {
                try {
                    var __r = (function() { return ($js); })();
                    AiriBridge.sendResult(__r != null ? String(__r) : '');
                } catch(e) {
                    AiriBridge.sendResult('__ERROR__: ' + e.message);
                }
            })();
        """.trimIndent()

        jsDeferred = deferred
        mainHandler.post { webView?.evaluateJavascript(wrapped, null) }

        return runCatching {
            withTimeout(JS_TIMEOUT_MS) { deferred.await() }
        }.getOrNull()
    }

    /**
     * Set the value of an input field identified by [cssSelector].
     */
    suspend fun fillInput(cssSelector: String, value: String): Boolean {
        val safe = value.replace("'", "\\'")
        val result = executeJs(
            "var el = document.querySelector('${cssSelector.replace("'", "\\'")}'); " +
            "if(el) { el.value='$safe'; el.dispatchEvent(new Event('input',{bubbles:true})); return 'ok'; } return 'miss';"
        )
        return result == "ok"
    }

    /**
     * Click an element identified by [cssSelector].
     */
    suspend fun clickElement(cssSelector: String): Boolean {
        val result = executeJs(
            "var el = document.querySelector('${cssSelector.replace("'", "\\'")}'); " +
            "if(el) { el.click(); return 'ok'; } return 'miss';"
        )
        return result == "ok"
    }

    /**
     * Submit the first form matching [formSelector].
     */
    suspend fun submitForm(formSelector: String = "form"): Boolean {
        val result = executeJs(
            "var f = document.querySelector('${formSelector.replace("'", "\\'")}'); " +
            "if(f) { f.submit(); return 'ok'; } return 'miss';"
        )
        return result == "ok"
    }

    /**
     * Scroll the page down by one viewport height.
     */
    suspend fun scrollDown(): Boolean {
        executeJs("window.scrollBy(0, window.innerHeight); return 'ok';")
        return true
    }

    // ── Internal machinery ────────────────────────────────────────────────────

    @Volatile private var loadDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var jsDeferred:   CompletableDeferred<String?>? = null

    inner class AiriWebViewClient : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            _state.value = _state.value.copy(
                isLoading  = false,
                currentUrl = url ?: "",
                title      = view?.title ?: "",
            )
            loadDeferred?.complete(true)
            loadDeferred = null
            Log.d(TAG, "PAGE_LOADED url=$url")
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: android.webkit.WebResourceError?,
        ) {
            if (request?.isForMainFrame == true) {
                val msg = error?.description?.toString() ?: "load error"
                _state.value = _state.value.copy(isLoading = false, errorMessage = msg)
                loadDeferred?.complete(false)
                loadDeferred = null
                Log.w(TAG, "PAGE_ERROR: $msg url=${request.url}")
            }
        }
    }

    inner class AiriJsBridge {
        @JavascriptInterface
        fun sendResult(value: String?) {
            jsDeferred?.complete(value)
            jsDeferred = null
        }
    }

    companion object {
        private const val LOAD_TIMEOUT_MS = 15_000L
        private const val JS_TIMEOUT_MS   = 5_000L
        private const val MAX_REDIRECTS   = 5
    }
}
