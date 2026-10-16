package app.imagine3d.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import app.imagine3d.data.DesignStats
import app.imagine3d.data.DesignVersion
import app.imagine3d.data.Idea
import app.imagine3d.data.IdeaRepository
import app.imagine3d.llama.ChatMessage
import app.imagine3d.llama.LlamaEngine
import app.imagine3d.llama.SamplingParams
import app.imagine3d.models.ModelManager
import app.imagine3d.models.ModelSpec
import app.imagine3d.scad.CompileResult
import app.imagine3d.scad.ScadWebHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

enum class Step(val label: String) {
    LoadingModel("Loading model"),
    ReadingPhoto("Looking at your photo"),
    ReadingPrompt("Reading the request"),
    Writing("Writing OpenSCAD"),
    Compiling("Building the 3D model"),
    Repairing("Fixing a problem"),
}

sealed interface DesignEvent {
    /** [coldCache]: the design guide is not cached yet, so this step reads it in full (slow, once). */
    data class Progress(val step: Step, val attempt: Int, val detail: String = "", val coldCache: Boolean = false) : DesignEvent
    /** Streaming model output for the current step. */
    data class Text(val step: Step, val text: String) : DesignEvent
    /** The model's `// Name:` for the part, as soon as it has been written. */
    data class Named(val name: String) : DesignEvent
    data class Done(val idea: Idea, val version: DesignVersion) : DesignEvent
    data class Failed(val message: String) : DesignEvent
}

/**
 * idea (+ optional refinement) -> photo notes -> OpenSCAD -> compile -> check -> repair.
 * The same loop as tools/eval/run_eval.py, which is how the prompts were tuned.
 */
class DesignPipeline(
    private val context: Context,
    private val repo: IdeaRepository,
    private val models: ModelManager,
    private val engine: LlamaEngine,
    private val scad: ScadWebHost,
    private val prompts: PromptAssets,
) {
    private val cacheDir = File(context.filesDir, "prefix-cache").apply { mkdirs() }

    /** Serialises design runs and prewarming: both swap models and own the prompt cache. */
    private val lock = Mutex()
    @Volatile private var cancelled = false

    private class Stopped : Exception("Stopped")

    private fun checkStop() {
        if (cancelled) throw Stopped()
    }

    suspend fun run(ideaId: String, refinement: String?, emit: (DesignEvent) -> Unit) {
        cancelled = false
        lock.withLock { runLocked(ideaId, refinement, emit) }
    }

    private suspend fun runLocked(ideaId: String, refinement: String?, emit: (DesignEvent) -> Unit) {
        val t0 = System.currentTimeMillis()
        var idea = repo.get(ideaId) ?: return emit(DesignEvent.Failed("Idea not found"))
        val codeModel = models.activeCodeModel()
            ?: return emit(DesignEvent.Failed("No designer model downloaded yet. Open Models to get one."))
        // Only redraw the preview if this idea is the one on screen.
        val show = { scad.visibleIdeaId == ideaId }

        try {
            checkStop()
            // 1. Photo notes from a vision model (once per idea; refinements reuse them).
            if (refinement == null && idea.photoNotes == null) {
                val vision = models.activeVisionModel()
                val photo = (idea.annotatedFile ?: idea.photoFile)?.let { repo.file(idea.id, it) }?.takeIf { it.exists() }
                if (vision != null && photo != null && idea.description.isNotBlank()) {
                    emit(DesignEvent.Progress(Step.LoadingModel, 0, vision.name))
                    load(vision, withVision = true)
                    checkStop()
                    emit(DesignEvent.Progress(Step.ReadingPhoto, 0))
                    val notes = describePhoto(photo, idea.description) { emit(DesignEvent.Text(Step.ReadingPhoto, it)) }
                    checkStop()
                    if (notes.isNotBlank()) idea = repo.update(idea.id) { it.copy(photoNotes = notes) } ?: idea
                }
            }

            // 2. Conversation: original request, then the previous design + change request when refining.
            emit(DesignEvent.Progress(Step.LoadingModel, 0, codeModel.name))
            load(codeModel, withVision = false)
            checkStop()
            val cacheFile = prefixCacheFile(codeModel)
            if (cacheFile.exists() && engine.prefixCachePath != cacheFile.path) engine.loadPrefixCache(cacheFile.path, prompts.system)
            val coldCache = engine.prefixCachePath != cacheFile.path

            val request = idea.description.ifBlank { "A small useful part for this spot." }
            val messages = mutableListOf(
                ChatMessage.system(prompts.system),
                ChatMessage.user(prompts.userMessage(request, idea.measurements, idea.photoNotes)),
            )
            val previous = idea.latestDesign
            if (refinement != null && previous != null) {
                messages += ChatMessage.assistant("```openscad\n${CodeTools.applyOverrides(previous.code, previous.overrides).trimEnd()}\n```")
                messages += ChatMessage.user(prompts.refineMessage(refinement))
            }

            // 3. Generate -> compile -> check, feeding problems back up to MAX_REPAIRS times.
            var stats = DesignStats(model = codeModel.name)
            var best: Pair<String, CompileResult>? = null
            var lastResult: CompileResult? = null
            var bestProblems: List<String> = emptyList()
            for (attempt in 0..MAX_REPAIRS) {
                val writing = if (attempt == 0) Step.Writing else Step.Repairing
                emit(DesignEvent.Progress(if (attempt == 0) Step.ReadingPrompt else Step.Repairing, attempt, coldCache = attempt == 0 && coldCache))
                var firstToken = true
                var named = idea.title.isNotBlank()
                val reply = StringBuilder()
                val gen = engine.generate(messages, SamplingParams(maxTokens = 1400)) { piece ->
                    if (firstToken) { firstToken = false; emit(DesignEvent.Progress(writing, attempt)) }
                    reply.append(piece)
                    val soFar = reply.toString()
                    if (!named) CodeTools.extractName(soFar)?.let { named = true; emit(DesignEvent.Named(it)) }
                    emit(DesignEvent.Text(writing, soFar))
                    !CodeTools.codeBlockClosed(soFar)
                }
                if (gen.stopReason == "cancelled") throw Stopped()
                checkStop()
                gen.error?.let { return emit(DesignEvent.Failed("Model error: $it")) }
                // A fresh prefix means the cache file was missing or stale (e.g. older format): (re)write it.
                if (gen.cachedTokens == 0) engine.savePrefixCache(cacheFile.path)
                stats = stats.copy(
                    promptTokens = stats.promptTokens + gen.promptTokens, cachedTokens = stats.cachedTokens + gen.cachedTokens,
                    prefillMs = stats.prefillMs + gen.prefillMs, genTokens = stats.genTokens + gen.genTokens,
                    genMs = stats.genMs + gen.genMs, attempts = attempt + 1,
                )
                val code = CodeTools.extractCode(gen.text).trimEnd() + "\n"

                emit(DesignEvent.Progress(Step.Compiling, attempt))
                val result = scad.compile(code, show = show())
                lastResult = result
                checkStop()
                stats = stats.copy(compileMs = stats.compileMs + result.ms)
                val problems = problems(result)
                Log.i(TAG, "attempt $attempt: exit=${result.exitCode} problems=$problems")
                if (result.ok && (best == null || problems.size < bestProblems.size)) {
                    best = code to result
                    bestProblems = problems
                }
                if (result.ok && problems.isEmpty()) break
                if (attempt < MAX_REPAIRS) {
                    messages += ChatMessage.assistant("```openscad\n${code.trimEnd()}\n```")
                    messages += ChatMessage.user(prompts.repairMessage(problems.ifEmpty { listOf("OpenSCAD produced no geometry.") }))
                }
            }

            val (code, result) = best ?: return emit(DesignEvent.Failed("The model could not produce a design that compiles. Try rewording the request."))
            if (result !== lastResult && show()) scad.compile(code) // the preview shows the last compile; switch it to the kept version
            val versionId = UUID.randomUUID().toString().take(8)
            val stlName = "design-$versionId.stl"
            repo.writeBytes(idea.id, stlName, result.stl!!)
            val version = DesignVersion(
                id = versionId,
                createdAt = System.currentTimeMillis(),
                instruction = refinement ?: request,
                code = code,
                ok = bestProblems.isEmpty(),
                problems = bestProblems,
                sizeMm = result.sizeMm,
                stats = stats.copy(totalMs = System.currentTimeMillis() - t0),
                stlFile = stlName,
            )
            val name = if (idea.title.isBlank()) CodeTools.extractName(code + "\n") else null
            val updated = repo.update(idea.id) { cur ->
                cur.copy(designs = cur.designs + version, title = cur.title.ifBlank { name ?: "" })
            } ?: idea
            emit(DesignEvent.Done(updated, version))
        } catch (e: Stopped) {
            emit(DesignEvent.Failed("Stopped"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "pipeline failed", e)
            emit(DesignEvent.Failed(e.message ?: e.toString()))
        }
    }

    /** Stops the current run: interrupts the model now, and every step checks the flag. */
    fun cancel() {
        cancelled = true
        engine.cancel()
    }

    /**
     * Gets ready for [run] while the user is still describing the idea: loads whichever model
     * will be needed first. For the designer that includes getting the system prompt into its
     * cache (restored from disk, or processed once and saved).
     */
    suspend fun prewarm(ideaId: String) = lock.withLock {
        val idea = repo.get(ideaId)
        val vision = models.activeVisionModel()
        if (vision != null && idea?.photoFile != null && idea.photoNotes == null) {
            load(vision, withVision = true)  // the photo is read first; the designer loads after
            return@withLock
        }
        val spec = models.activeCodeModel() ?: return@withLock
        load(spec, withVision = false)
        val cacheFile = prefixCacheFile(spec)
        if (engine.prefixCachePath == cacheFile.path) return@withLock
        if (cacheFile.exists() && engine.loadPrefixCache(cacheFile.path, prompts.system) > 0) return@withLock
        val r = engine.generate(listOf(ChatMessage.system(prompts.system), ChatMessage.user("Ready?")), SamplingParams(maxTokens = 1))
        if (r.error == null) engine.savePrefixCache(cacheFile.path)
        Log.i(TAG, "prewarmed ${spec.name}: ${r.promptTokens} tokens in ${r.prefillMs} ms")
    }

    /** [problems] off the main thread: the mesh check walks every vertex. */
    suspend fun problemsAsync(result: CompileResult): List<String> = withContext(Dispatchers.Default) { problems(result) }

    /** Compile errors, then printability problems found by inspecting the mesh. */
    fun problems(result: CompileResult): List<String> {
        val fatal = result.log.filter { line -> prompts.fatalPatterns.any { it.containsMatchIn(line) } }
            .map { it.replace("/input.scad", "the program") }
        if (fatal.isNotEmpty()) return fatal.distinct()
        val stl = result.stl ?: return listOf("OpenSCAD produced no geometry." + (result.log.lastOrNull()?.let { " Last message: $it" } ?: ""))
        val mesh = StlTools.analyze(stl) ?: return listOf("OpenSCAD produced an empty or invalid model.")
        val out = mutableListOf<String>()
        val dims = mesh.size.joinToString(" x ") { PromptAssets.formatMm(Math.round(it).toDouble()) }
        if (mesh.size.max() > BED_MM) out += "The part is $dims mm, which does not fit a 250 mm print bed. Make it smaller or split it so it fits."
        if (mesh.size.min() < 1.0) out += "The part is thinner than 1 mm in one direction, so it cannot be printed. Check the dimensions."
        if (mesh.components > 1) out += "The model is ${mesh.components} separate pieces that do not touch. Make them overlap by at least 1 mm (or add a bar between them) so it prints as one solid part."
        return out
    }

    private suspend fun load(spec: ModelSpec, withVision: Boolean) {
        val mmproj = if (withVision) models.mmprojFile(spec)?.path else null
        // An all-in-one model keeps its projector loaded for both steps to avoid a reload.
        val keepProjector = spec.canSee && engine.loadedModelPath == models.modelFile(spec).path && engine.loadedMmprojPath != null
        engine.load(models.modelFile(spec).path, if (keepProjector) engine.loadedMmprojPath else mmproj, contextSize = CONTEXT)
    }

    private suspend fun describePhoto(photo: File, request: String, onText: (String) -> Unit): String {
        val jpeg = withContext(Dispatchers.IO) { downscaleJpeg(photo, 768) }
        val reply = StringBuilder()
        val gen = engine.generate(
            listOf(ChatMessage.system(PromptAssets.VISION_SYSTEM), ChatMessage.user(PromptAssets.visionUser(request))),
            SamplingParams(maxTokens = 160, temperature = 0.2f),
            images = listOf(jpeg),
        ) { piece -> reply.append(piece); onText(reply.toString()); true }
        if (gen.stopReason == "cancelled") throw Stopped()
        if (gen.error != null) {
            Log.w(TAG, "photo description failed: ${gen.error}")
            return ""
        }
        return gen.text.trim().replace(Regex("\\s+"), " ")
    }

    private fun prefixCacheFile(spec: ModelSpec): File {
        val file = prefixCacheName(spec)
        // Older caches for this model (other prompt or context size) are dead weight: ~330 MB each.
        cacheDir.listFiles { f -> f.name.startsWith("${spec.id}-") && f.name != file.name }?.forEach { it.delete() }
        return file
    }

    private fun prefixCacheName(spec: ModelSpec): File {
        val digest = MessageDigest.getInstance("SHA-256").digest((spec.file.name + "\nctx=$CONTEXT\n" + prompts.system).toByteArray())
        val hex = digest.take(8).joinToString("") { "%02x".format(it) }
        return File(cacheDir, "${spec.id}-$hex.bin")
    }

    companion object {
        private const val TAG = "DesignPipeline"
        const val MAX_REPAIRS = 2
        /** System prompt ~2.3k tokens + request + up to three code replies with repair notes. */
        const val CONTEXT = 6144
        const val BED_MM = 250.0

        fun downscaleJpeg(file: File, maxSide: Int): ByteArray {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, opts)
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        }
    }
}
