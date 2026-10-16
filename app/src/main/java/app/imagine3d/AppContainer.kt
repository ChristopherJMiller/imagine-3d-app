package app.imagine3d

import android.content.Context
import app.imagine3d.ai.DesignEvent
import app.imagine3d.ai.DesignPipeline
import app.imagine3d.ai.PromptAssets
import app.imagine3d.ai.Step
import app.imagine3d.data.DesignVersion
import app.imagine3d.data.IdeaRepository
import app.imagine3d.llama.LlamaEngine
import app.imagine3d.models.ModelManager
import app.imagine3d.scad.ScadWebHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Live state of the design run for one idea. */
data class RunState(
    val ideaId: String,
    val refinement: String?,
    val startedAt: Long = System.currentTimeMillis(),
    val step: Step = Step.LoadingModel,
    val attempt: Int = 0,
    val detail: String = "",
    val coldCache: Boolean = false,
    val text: String = "",
    val error: String? = null,
    val done: DesignVersion? = null,
) {
    val running: Boolean get() = error == null && done == null
}

/** Hand-rolled dependency container: one instance of each long-lived service. */
class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val repo = IdeaRepository(context)
    val models = ModelManager(context)
    val engine = LlamaEngine.get(context)
    val scad = ScadWebHost(context)
    val prompts = PromptAssets(context)
    val pipeline = DesignPipeline(context, repo, models, engine, scad, prompts)

    private val _run = MutableStateFlow<RunState?>(null)
    /** Designs run in the app scope so leaving the screen does not stop them. */
    val run: StateFlow<RunState?> = _run.asStateFlow()
    private var job: Job? = null

    /** True while an activity of ours is on screen (set by MainActivity). */
    @Volatile var uiVisible = false

    /** Whether the user is looking at this idea's design screen right now (no notification needed). */
    fun isWatching(ideaId: String) = uiVisible && scad.visibleIdeaId == ideaId

    init {
        scope.launch { repo.load() }
    }

    fun startDesign(ideaId: String, refinement: String? = null) {
        if (_run.value?.running == true) return
        _run.value = RunState(ideaId, refinement)
        DesignService.start(context)
        job = scope.launch {
            pipeline.run(ideaId, refinement) { event ->
                _run.update { s ->
                    s ?: return@update null
                    when (event) {
                        is DesignEvent.Progress -> s.copy(
                            step = event.step, attempt = event.attempt, detail = event.detail, coldCache = event.coldCache,
                            text = if (event.step != s.step) "" else s.text,
                        )
                        is DesignEvent.Text -> s.copy(text = event.text)
                        is DesignEvent.Named -> {
                            scope.launch { repo.update(ideaId) { if (it.title.isBlank()) it.copy(title = event.name) else it } }
                            s
                        }
                        is DesignEvent.Done -> s.copy(done = event.version)
                        is DesignEvent.Failed -> s.copy(error = event.message)
                    }
                }
            }
        }
    }

    private var warm: Job? = null

    /** Called when the user starts describing an idea: model loading overlaps with their typing. */
    fun prewarm(ideaId: String) {
        if (warm?.isActive == true || _run.value?.running == true) return
        warm = scope.launch { runCatching { pipeline.prewarm(ideaId) } }
    }

    fun cancelDesign() {
        pipeline.cancel()
    }

    fun clearRun(ideaId: String) {
        if (_run.value?.ideaId == ideaId && _run.value?.running == false) _run.value = null
    }
}
