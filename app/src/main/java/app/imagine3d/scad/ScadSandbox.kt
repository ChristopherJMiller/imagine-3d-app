package app.imagine3d.scad

import android.content.Context
import android.util.Log
import androidx.concurrent.futures.await
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Runs OpenSCAD (the same WebAssembly build the viewer uses) in androidx.javascriptengine:
 * a headless V8 isolate in its own process. Unlike a WebView page it is not throttled when the
 * app is in the background, so designs keep compiling while the user is elsewhere.
 * [compile] returns null when the device's WebView provider can't host it; callers fall back
 * to compiling inside the WebView.
 */
class ScadSandbox(private val context: Context) {
    private val lock = Mutex()
    private var sandbox: JavaScriptSandbox? = null
    private var isolate: JavaScriptIsolate? = null
    @Volatile private var unsupported = false

    suspend fun compile(code: String, defines: Map<String, String>, timeoutMs: Long): CompileResult? {
        if (unsupported) return null
        return lock.withLock {
            val iso = try {
                isolate ?: start()
            } catch (e: Exception) {
                Log.w(TAG, "JavaScriptSandbox unavailable, using the WebView instead", e)
                unsupported = true
                null
            } ?: return@withLock null
            val t0 = System.currentTimeMillis()
            val call = "ImagineSandbox.compile(${JSONObject.quote(code)}, ${JSONObject(defines as Map<*, *>)})"
            val json = withTimeoutOrNull(timeoutMs) {
                try {
                    iso.evaluateJavaScriptAsync(call).await()
                } catch (e: Exception) {
                    Log.w(TAG, "sandbox evaluation failed", e)
                    JSONObject().put("exit", -1).put("log", org.json.JSONArray().put("ERROR: the 3D engine stopped: ${e.message}")).toString()
                }
            }
            val ms = System.currentTimeMillis() - t0
            if (json == null) {
                reset()  // the only way to stop a runaway OpenSCAD evaluation
                return@withLock CompileResult(-1, listOf("ERROR: OpenSCAD timed out after ${timeoutMs / 1000} s. Simplify the model (fewer or smaller curved parts)."), ms, null, null)
            }
            val o = JSONObject(json)
            if (o.optInt("exit", -1) == -1 && o.optJSONArray("log")?.toString()?.contains("the 3D engine stopped") == true) reset()
            val logArr = o.optJSONArray("log")
            val log = (0 until (logArr?.length() ?: 0)).map { logArr!!.getString(it) }
            val stl = o.optString("stl").takeIf { it.isNotEmpty() && it != "null" }?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
            CompileResult(o.optInt("exit", -1), log, ms, stl, stl?.let(::boundingBox))
        }
    }

    private suspend fun start(): JavaScriptIsolate = withContext(Dispatchers.IO) {
        if (!JavaScriptSandbox.isSupported()) throw IllegalStateException("JavaScriptSandbox not supported")
        val sb = JavaScriptSandbox.createConnectedInstanceAsync(context.applicationContext).await()
        val needed = listOf(
            JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN,
            JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER,
            JavaScriptSandbox.JS_FEATURE_WASM_COMPILATION,
        )
        val missing = needed.filterNot { sb.isFeatureSupported(it) }
        if (missing.isNotEmpty()) {
            sb.close()
            throw IllegalStateException("JavaScriptSandbox lacks $missing")
        }
        val params = IsolateStartupParameters()
        if (sb.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) params.maxHeapSizeBytes = 1L shl 30
        if (sb.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)) params.maxEvaluationReturnSizeBytes = 64 shl 20
        val iso = sb.createIsolate(params)
        val assets = context.assets
        val wasm = assets.open("web/openscad/openscad.wasm").use { it.readBytes() }
        @Suppress("DEPRECATION")
        iso.provideNamedData("openscad.wasm", wasm)  // throws if it cannot be delivered
        // The emscripten glue is an ES module written for browsers; evaluate it as a plain script.
        val glue = assets.open("web/openscad/openscad.js").bufferedReader().use { it.readText() }
            .replace("import.meta.url", "\"https://appassets.androidplatform.net/assets/web/openscad/openscad.js\"")
            .replace(Regex("export default OpenSCAD;?"), "globalThis.OpenSCAD = OpenSCAD;")
        iso.evaluateJavaScriptAsync(assets.open("web/scad-sandbox.js").bufferedReader().use { it.readText() }).await()
        iso.evaluateJavaScriptAsync(glue + "\n;'ok'").await()
        val library = assets.open("scad/imagine.scad").bufferedReader().use { it.readText() }
        iso.evaluateJavaScriptAsync("ImagineSandbox.init(${JSONObject.quote(library)})").await()
        Log.i(TAG, "OpenSCAD sandbox ready")
        sandbox = sb
        isolate = iso
        iso
    }

    private fun reset() {
        runCatching { isolate?.close() }
        runCatching { sandbox?.close() }
        isolate = null
        sandbox = null
    }

    companion object {
        private const val TAG = "ScadSandbox"

        /** Width/depth/height of a binary STL, the same numbers the viewer shows. */
        fun boundingBox(stl: ByteArray): List<Double>? {
            if (stl.size < 84) return null
            val buf = java.nio.ByteBuffer.wrap(stl).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val n = buf.getInt(80)
            if (n <= 0 || 84L + n * 50L > stl.size) return null
            val min = FloatArray(3) { Float.MAX_VALUE }
            val max = FloatArray(3) { -Float.MAX_VALUE }
            for (t in 0 until n) for (v in 0 until 3) for (k in 0 until 3) {
                val f = buf.getFloat(84 + t * 50 + 12 + v * 12 + k * 4)
                if (f < min[k]) min[k] = f
                if (f > max[k]) max[k] = f
            }
            return (0 until 3).map { (max[it] - min[it]).toDouble() }
        }
    }
}
