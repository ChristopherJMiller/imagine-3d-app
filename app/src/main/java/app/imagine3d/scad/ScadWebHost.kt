package app.imagine3d.scad

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class CompileResult(
    val exitCode: Int,
    val log: List<String>,
    val ms: Long,
    val stl: ByteArray?,
    val sizeMm: List<Double>?,
) {
    val ok: Boolean get() = exitCode == 0 && stl != null
}

/**
 * Owns the single WebView that runs OpenSCAD (WebAssembly, in a worker) and the three.js
 * preview. The same view is moved into whichever screen shows the preview.
 */
class ScadWebHost(private val appContext: Context) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ready = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<CompileResult>>()
    private val ids = AtomicInteger()
    private var webView: WebView? = null
    private var theme: JSONObject? = null

    /** The idea whose design screen is showing; background runs for other ideas don't redraw. */
    @Volatile var visibleIdeaId: String? = null

    /** Bumped when the WebView had to be recreated, so screens re-attach the new one. */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    /** Returns the WebView (creating it on first use), detached from any previous parent. */
    @SuppressLint("SetJavaScriptEnabled")
    fun view(): WebView {
        check(Looper.myLooper() == Looper.getMainLooper())
        val existing = webView
        if (existing != null) {
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        WebView.setWebContentsDebuggingEnabled(true)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
            .build()
        val wv = WebView(appContext)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                theme?.let { view.evaluateJavascript("window.Imagine && Imagine.setTheme($it)", null) }
            }

            // Without this, a crashed or killed renderer (e.g. out of memory during a big compile)
            // takes the whole app down. Drop the view and build a fresh one on next use.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "WebView renderer gone (crash=${detail.didCrash()})")
                if (webView === view) {
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                    webView = null
                    ready = CompletableDeferred()
                    failPending("ERROR: the 3D engine restarted, try again")
                    _generation.value++
                }
                return true
            }
        }
        wv.addJavascriptInterface(Bridge(), "AndroidBridge")
        // Designs keep compiling while the app is in the background (DesignService keeps the
        // process alive); don't let the renderer drop to background priority when off screen.
        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        wv.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
        webView = wv
        return wv
    }

    /** Make sure the page is loading even before any screen shows it. */
    fun warmUp() = main.post { view() }

    fun setTheme(bg: String, fg: String, part: String, emptyText: String) {
        val t = JSONObject().put("bg", bg).put("fg", fg).put("part", part).put("empty", emptyText)
        theme = t
        main.post { webView?.evaluateJavascript("window.Imagine && Imagine.setTheme($t)", null) }
    }

    fun clear() = main.post { webView?.evaluateJavascript("window.Imagine && Imagine.clear()", null) }
    fun resetView() = main.post { webView?.evaluateJavascript("window.Imagine && Imagine.resetView()", null) }

    private val sandbox = ScadSandbox(appContext)

    /**
     * Compiles in the background-safe [ScadSandbox] and hands the STL to the viewer when [show]
     * is set. Falls back to compiling inside the WebView where the sandbox isn't available.
     */
    suspend fun compile(code: String, defines: Map<String, String> = emptyMap(), show: Boolean = true, timeoutMs: Long = 120_000): CompileResult {
        if (show) main.post { webView?.evaluateJavascript("window.Imagine && Imagine.setBusy(true)", null) }
        val viaSandbox = sandbox.compile(code, defines, timeoutMs)
        if (viaSandbox != null) {
            if (show) display(viaSandbox.stl)
            return viaSandbox
        }
        return compileInWebView(code, defines, show, timeoutMs)
    }

    private suspend fun display(stl: ByteArray?) {
        if (webView == null) withContext(Dispatchers.Main) { view() }
        withTimeoutOrNull(30_000) { ready.await() }
        val js = if (stl != null) "Imagine.showStl(${JSONObject.quote(Base64.encodeToString(stl, Base64.NO_WRAP))})" else "Imagine.setBusy(false)"
        main.post { webView?.evaluateJavascript("window.Imagine && $js", null) }
    }

    private suspend fun compileInWebView(code: String, defines: Map<String, String>, show: Boolean, timeoutMs: Long): CompileResult {
        if (webView == null) withContext(Dispatchers.Main) { view() }
        val ready = ready
        withTimeoutOrNull(30_000) { ready.await() } ?: return CompileResult(-1, listOf("ERROR: OpenSCAD engine did not start"), 0, null, null)
        val id = "c${ids.incrementAndGet()}"
        val deferred = CompletableDeferred<CompileResult>()
        pending[id] = deferred
        val req = JSONObject().put("id", id).put("code", code).put("show", show).put("defines", JSONObject(defines as Map<*, *>))
        main.post { webView?.evaluateJavascript("Imagine.compile($req)", null) }
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: run {
            // The worker is stuck inside OpenSCAD; everything queued behind it would time out too.
            pending.remove(id)
            failPending("ERROR: the 3D engine was restarted after a timeout")
            main.post { webView?.evaluateJavascript("window.Imagine && Imagine.resetWorker()", null) }
            CompileResult(-1, listOf("ERROR: OpenSCAD timed out after ${timeoutMs / 1000} s. Simplify the model (fewer or smaller curved parts)."), timeoutMs, null, null)
        }
    }

    private fun failPending(message: String) {
        val all = pending.keys.toList()
        for (k in all) pending.remove(k)?.complete(CompileResult(-1, listOf(message), 0, null, null))
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onReady() {
            Log.i(TAG, "OpenSCAD worker ready")
            ready.complete(Unit)
        }

        @JavascriptInterface
        fun onResult(json: String) {
            val o = JSONObject(json)
            val id = o.getString("id")
            val logArr = o.optJSONArray("log")
            val log = (0 until (logArr?.length() ?: 0)).map { logArr!!.getString(it) }
            val stl = o.optString("stl").takeIf { it.isNotEmpty() && it != "null" }?.let { Base64.decode(it, Base64.DEFAULT) }
            val sizeArr = o.optJSONArray("size")
            val size = sizeArr?.let { a -> (0 until a.length()).map { a.getDouble(it) } }
            pending.remove(id)?.complete(CompileResult(o.optInt("exit", -1), log, o.optLong("ms"), stl, size))
        }
    }

    companion object {
        private const val TAG = "ScadWebHost"
    }
}
