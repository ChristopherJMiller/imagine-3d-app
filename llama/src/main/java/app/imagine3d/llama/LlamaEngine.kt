package app.imagine3d.llama

import android.content.Context
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors

data class ChatMessage(val role: String, val content: String) {
    companion object {
        fun system(content: String) = ChatMessage("system", content)
        fun user(content: String) = ChatMessage("user", content)
        fun assistant(content: String) = ChatMessage("assistant", content)
    }
}

data class SamplingParams(
    val maxTokens: Int = 1200,
    val temperature: Float = 0.3f,
    val topP: Float = 0.9f,
    val topK: Int = 20,
    val seed: Int = -1,
)

data class GenerationResult(
    val text: String,
    val stopReason: String,
    val promptTokens: Int,
    val cachedTokens: Int,
    val prefillMs: Long,
    val genTokens: Int,
    val genMs: Long,
    val error: String?,
) {
    val tokensPerSecond: Double get() = if (genMs > 0) genTokens * 1000.0 / genMs else 0.0
    val prefillTokensPerSecond: Double
        get() = if (prefillMs > 0) (promptTokens - cachedTokens) * 1000.0 / prefillMs else 0.0
}

data class ModelInfo(val description: String, val sizeBytes: Long, val params: Long, val contextSize: Int, val vision: Boolean)

/**
 * On-device LLM backed by llama.cpp. All native calls run on one dedicated thread, so
 * calls are serialised; [cancel] can be called from any thread.
 */
class LlamaEngine private constructor(private val nativeLibDir: String) {
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "llama").apply { priority = Thread.MAX_PRIORITY } }
    private val dispatcher = thread.asCoroutineDispatcher()
    @Volatile private var handle = 0L
    private var backendReady = false

    var loadedModelPath: String? = null
        private set
    var loadedMmprojPath: String? = null
        private set

    /** The prefix-cache file whose state is currently in memory, if any. */
    var prefixCachePath: String? = null
        private set

    /** System prompt that the in-memory prefix state belongs to. */
    private var prefixOwner: String? = null

    val isLoaded: Boolean get() = handle != 0L

    suspend fun load(modelPath: String, mmprojPath: String? = null, contextSize: Int = 6144, threads: Int = defaultThreads()): ModelInfo =
        withContext(dispatcher) {
            if (!backendReady) {
                LlamaNative.backendInit(nativeLibDir)
                backendReady = true
            }
            if (handle != 0L && modelPath == loadedModelPath && mmprojPath == loadedMmprojPath) return@withContext infoLocked()
            unloadLocked()
            prefixCachePath = null
            prefixOwner = null
            val h = LlamaNative.load(modelPath, mmprojPath, contextSize, threads)
            check(h != 0L) { "Could not load model $modelPath" }
            handle = h
            loadedModelPath = modelPath
            loadedMmprojPath = mmprojPath
            infoLocked()
        }

    suspend fun unload() = withContext(dispatcher) { unloadLocked() }

    suspend fun systemInfo(): String = withContext(dispatcher) {
        if (!backendReady) {
            LlamaNative.backendInit(nativeLibDir)
            backendReady = true
        }
        LlamaNative.systemInfo()
    }

    suspend fun generate(
        messages: List<ChatMessage>,
        params: SamplingParams = SamplingParams(),
        images: List<ByteArray> = emptyList(),
        onToken: (String) -> Boolean = { true },
    ): GenerationResult = withContext(dispatcher) {
        check(handle != 0L) { "No model loaded" }
        // A different system prompt replaces the cached prefix state inside llama.cpp.
        val system = messages.firstOrNull()?.takeIf { it.role == "system" }?.content
        if (system != prefixOwner) {
            prefixCachePath = null
            prefixOwner = system
        }
        val json = LlamaNative.generate(
            handle,
            messages.map { it.role }.toTypedArray(),
            messages.map { it.content }.toTypedArray(),
            images.takeIf { it.isNotEmpty() }?.toTypedArray(),
            params.maxTokens, params.temperature, params.topP, params.topK, params.seed,
            TokenCallback { onToken(it) },
        )
        val o = JSONObject(json)
        GenerationResult(
            text = o.optString("text"),
            stopReason = o.optString("stop", "error"),
            promptTokens = o.optInt("prompt_tokens"),
            cachedTokens = o.optInt("cached_tokens"),
            prefillMs = o.optLong("prefill_ms"),
            genTokens = o.optInt("gen_tokens"),
            genMs = o.optLong("gen_ms"),
            error = if (o.has("error")) o.getString("error") else null,
        )
    }

    /** Persists the cached system-prompt state so the next app start can skip prefilling it. */
    suspend fun savePrefixCache(path: String): Boolean = withContext(dispatcher) {
        (handle != 0L && LlamaNative.prefixSave(handle, path)).also { if (it) prefixCachePath = path }
    }

    /** Loads a state saved by [savePrefixCache]; it is used if the next prompt starts with the same system prompt. */
    suspend fun loadPrefixCache(path: String, systemPrompt: String): Int = withContext(dispatcher) {
        if (handle == 0L) -1 else LlamaNative.prefixLoad(handle, path).also {
            if (it > 0) { prefixCachePath = path; prefixOwner = systemPrompt }
        }
    }

    /** Interrupts a running [generate] (prompt processing or sampling). */
    fun cancel() {
        val h = handle
        if (h != 0L) LlamaNative.cancel(h)
    }

    private fun infoLocked(): ModelInfo {
        val o = JSONObject(LlamaNative.info(handle))
        return ModelInfo(o.optString("desc"), o.optLong("size_bytes"), o.optLong("n_params"), o.optInt("n_ctx"), o.optBoolean("vision"))
    }

    private fun unloadLocked() {
        if (handle != 0L) {
            LlamaNative.free(handle)
            handle = 0L
            loadedModelPath = null
            loadedMmprojPath = null
        }
    }

    companion object {
        @Volatile private var instance: LlamaEngine? = null

        fun get(context: Context): LlamaEngine = instance ?: synchronized(this) {
            instance ?: LlamaEngine(context.applicationInfo.nativeLibraryDir).also { instance = it }
        }

        /** Big + mid cores on a Tensor G4 (1x X4 + 3x A720); the A520s only slow things down. */
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8).let { if (it >= 8) 4 else (it / 2).coerceAtLeast(2) }
    }
}
