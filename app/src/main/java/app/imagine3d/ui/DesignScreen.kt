package app.imagine3d.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import app.imagine3d.AppContainer
import app.imagine3d.ai.CodeTools
import app.imagine3d.ai.PromptAssets
import app.imagine3d.ai.ScadParam
import app.imagine3d.ai.Step
import app.imagine3d.data.DesignStats
import app.imagine3d.data.DesignVersion
import app.imagine3d.data.Idea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignScreen(app: AppContainer, ideaId: String, autoStart: Boolean, onBack: () -> Unit, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ideas by app.repo.ideas.collectAsState()
    val idea = ideas.firstOrNull { it.id == ideaId } ?: return
    val run by app.run.collectAsState()
    val myRun = run?.takeIf { it.ideaId == ideaId }
    var selectedId by remember { mutableStateOf(idea.latestDesign?.id) }
    val selected = idea.designs.firstOrNull { it.id == selectedId } ?: idea.latestDesign
    var tab by remember { mutableStateOf(0) }
    val colors = MaterialTheme.colorScheme
    val adjust = remember(selected?.id) { AdjustState(selected) }

    /** Slider changes become a new version before anything that relies on the saved design. */
    suspend fun commitAdjustments(): DesignVersion? {
        val v = selected ?: return null
        if (!adjust.dirty) return v
        val id = saveVersion(app, idea, adjust.code(), "Adjusted " + adjust.changedNames().joinToString(), v.stats) ?: return v
        selectedId = id
        return app.repo.get(ideaId)?.designs?.firstOrNull { it.id == id }
    }

    androidx.compose.runtime.DisposableEffect(ideaId) {
        app.scad.visibleIdeaId = ideaId
        onDispose { if (app.scad.visibleIdeaId == ideaId) app.scad.visibleIdeaId = null }
    }
    val webGeneration by app.scad.generation.collectAsState()
    LaunchedEffect(Unit) {
        app.scad.setTheme(colors.surfaceContainerHighest.hex(), colors.onSurface.hex(), colors.primary.hex(), "Your part will appear here")
        if (autoStart && idea.designs.isEmpty() && app.run.value?.running != true) app.startDesign(ideaId)
    }
    // Show the selected version (and switch to new ones as they finish).
    LaunchedEffect(myRun?.done?.id) { myRun?.done?.let { selectedId = it.id } }
    LaunchedEffect(selected?.id, myRun?.running, webGeneration) {
        if (myRun?.running != true) {
            if (selected != null) app.scad.compile(CodeTools.applyOverrides(selected.code, selected.overrides)) else app.scad.clear()
        }
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/stl")) { uri: Uri? ->
        val v = app.repo.get(ideaId)?.designs?.firstOrNull { it.id == selectedId } ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch(Dispatchers.IO) {
            ctx.contentResolver.openOutputStream(uri)?.use { out -> app.repo.file(ideaId, v.stlFile!!).inputStream().use { it.copyTo(out) } }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(idea.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { commitAdjustments(); app.clearRun(ideaId); onBack() } }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = onEdit, enabled = myRun?.running != true) { Icon(Icons.Default.Edit, "Edit request") }
                    IconButton(onClick = { scope.launch { commitAdjustments()?.let { v -> shareStl(ctx, app, app.repo.get(ideaId) ?: idea, v) } } }, enabled = selected?.stlFile != null) {
                        Icon(Icons.Default.Share, "Share STL")
                    }
                    IconButton(onClick = { scope.launch { commitAdjustments()?.let { saveAs.launch(fileName(app.repo.get(ideaId) ?: idea, it)) } } }, enabled = selected?.stlFile != null) {
                        Icon(Icons.Default.Save, "Save STL")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Box(Modifier.fillMaxWidth().weight(0.9f).padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp))) {
                androidx.compose.runtime.key(webGeneration) {
                    AndroidView(factory = { app.scad.view() }, modifier = Modifier.fillMaxSize())
                }
                IconButton(onClick = { app.scad.resetView() }, modifier = Modifier.align(Alignment.TopStart)) {
                    Icon(Icons.Default.CenterFocusStrong, "Reset view", tint = colors.onSurface)
                }
            }
            Column(Modifier.fillMaxWidth().weight(1.1f)) {
                when {
                    myRun?.running == true -> RunPanel(myRun.step, myRun.attempt, myRun.detail, myRun.coldCache, myRun.text, myRun.startedAt, onStop = { app.cancelDesign() })
                    myRun?.error != null -> ErrorPanel(myRun.error, onRetry = { app.clearRun(ideaId); app.startDesign(ideaId, myRun.refinement) })
                    selected == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Button(onClick = { app.startDesign(ideaId) }) { Text("Design it") }
                    }
                    else -> {
                        VersionBar(idea, selected) { selectedId = it }
                        SecondaryTabRow(selectedTabIndex = tab) {
                            listOf("Adjust", "Refine", "Code", "Details").forEachIndexed { i, t ->
                                Tab(tab == i, onClick = { if (tab == 0 && i != 0 && adjust.dirty) scope.launch { commitAdjustments() }; tab = i }, text = { Text(t) })
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            when (tab) {
                                0 -> AdjustPanel(app, adjust, onSave = { scope.launch { commitAdjustments() } })
                                1 -> RefinePanel { change -> scope.launch { commitAdjustments(); app.clearRun(ideaId); app.startDesign(ideaId, change) } }
                                2 -> CodePanel(app, idea, selected) { selectedId = it }
                                else -> DetailsPanel(idea, selected)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunPanel(step: Step, attempt: Int, detail: String, coldCache: Boolean, text: String, startedAt: Long, onStop: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(500); now = System.currentTimeMillis() } }
    val scroll = rememberScrollState()
    LaunchedEffect(text.length) { scroll.scrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(step.label + (if (attempt > 0) " (try ${attempt + 1})" else "") + "…", style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(detail.ifBlank { null }, "${(now - startedAt) / 1000} s").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onStop) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(4.dp)); Text("Stop") }
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        if (step == Step.ReadingPrompt && coldCache && text.isEmpty()) {
            Text("First design with this model: reading the design guide once (about a minute on a phone). After that it is cached.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (step == Step.ReadingPhoto) text else CodeTools.streamingView(text), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp).verticalScroll(scroll),
        )
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(message, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun VersionBar(idea: Idea, selected: DesignVersion, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        idea.designs.forEachIndexed { i, v ->
            FilterChip(selected = v.id == selected.id, onClick = { onSelect(v.id) }, label = { Text("v${i + 1}") },
                leadingIcon = if (!v.ok) ({ Icon(Icons.Default.Warning, null, Modifier.size(16.dp)) }) else null)
        }
        selected.sizeMm?.let { Text(it.joinToString(" × ") { d -> "%.1f".format(d) } + " mm", style = MaterialTheme.typography.labelMedium) }
    }
}

/** Slider values for the selected version; they become a new version when committed. */
private class AdjustState(val version: DesignVersion?) {
    val params: List<ScadParam> = version?.let { CodeTools.parseParams(it.code) }.orEmpty()
    val values = mutableStateMapOf<String, Double>().apply { params.forEach { put(it.name, it.value) } }
    val dirty: Boolean get() = params.any { values[it.name] != it.value }
    fun changedNames() = params.filter { values[it.name] != it.value }.map { it.name }
    fun code() = CodeTools.applyOverrides(version!!.code, values.toMap())
    fun reset() = params.forEach { values[it.name] = it.value }
}

@Composable
private fun AdjustPanel(app: AppContainer, state: AdjustState, onSave: () -> Unit) {
    val scope = rememberCoroutineScope()
    var problem by remember(state) { mutableStateOf<String?>(null) }

    fun preview() = scope.launch {
        val r = app.scad.compile(state.code())
        problem = app.pipeline.problemsAsync(r).firstOrNull()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (state.dirty) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Changes are kept as a new version.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { state.reset(); preview() }) { Text("Reset") }
                Button(onClick = onSave) { Text("Save") }
            }
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (state.params.isEmpty()) Text("This design has no top-level numbers to adjust. Use Refine or Code.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (p in state.params) {
            val v = state.values[p.name] ?: p.value
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(PromptAssets.formatMm(v), style = MaterialTheme.typography.labelLarge)
            }
            if (p.comment.isNotBlank()) Text(p.comment, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = v.toFloat().coerceIn(p.min.toFloat(), p.max.toFloat()),
                onValueChange = { state.values[p.name] = (Math.round(it / p.step) * p.step) },
                onValueChangeFinished = { preview() },
                valueRange = p.min.toFloat()..p.max.toFloat(),
            )
        }
    }
}

@Composable
private fun RefinePanel(onRefine: (String) -> Unit) {
    var change by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Ask for a change and the model will edit the design.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(change, { change = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
            placeholder = { Text("e.g. make the hook deeper and add a second one") })
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf("Make it stronger", "Use adhesive instead of screws", "Make it 10 mm wider", "Round the edges").forEach { s ->
                FilterChip(selected = false, onClick = { change = s }, label = { Text(s) })
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onRefine(change) }, enabled = change.isNotBlank()) { Text("Refine") }
    }
}

@Composable
private fun CodePanel(app: AppContainer, idea: Idea, version: DesignVersion, onNewVersion: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember(version.id) { mutableStateOf(CodeTools.applyOverrides(version.code, version.overrides)) }
    var status by remember(version.id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(status ?: "OpenSCAD source (imagine.scad is included automatically)", style = MaterialTheme.typography.bodySmall,
                color = if (status?.startsWith("Error") == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f), maxLines = 3)
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val r = app.scad.compile(code)
                    val problems = app.pipeline.problemsAsync(r)
                    status = if (r.ok) (problems.firstOrNull()?.let { "Warning: $it" } ?: "Compiled in ${r.ms} ms") else "Error: " + problems.joinToString("; ")
                    if (r.ok && code != version.code) saveVersion(app, idea, code, "Edited by hand", DesignStats(model = "you"))?.let(onNewVersion)
                    busy = false
                }
            }) { Icon(Icons.Default.PlayArrow, null); Text("Run") }
        }
        OutlinedTextField(
            code, { code = it }, modifier = Modifier.fillMaxSize(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
        )
    }
}

@Composable
private fun DetailsPanel(idea: Idea, v: DesignVersion) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Asked: ${v.instruction}", style = MaterialTheme.typography.bodyMedium)
        if (v.problems.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("Kept despite: " + v.problems.joinToString(" "), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        val s = v.stats
        Text("Model: ${s.model}", style = MaterialTheme.typography.bodySmall)
        if (s.genTokens > 0) {
            Text("Prompt: ${s.promptTokens} tokens (${s.cachedTokens} cached) in %.1f s".format(s.prefillMs / 1000.0), style = MaterialTheme.typography.bodySmall)
            Text("Writing: ${s.genTokens} tokens in %.1f s (%.1f tok/s)".format(s.genMs / 1000.0, if (s.genMs > 0) s.genTokens * 1000.0 / s.genMs else 0.0), style = MaterialTheme.typography.bodySmall)
            Text("OpenSCAD: %.1f s · tries: ${s.attempts} · total %.0f s".format(s.compileMs / 1000.0, s.totalMs / 1000.0), style = MaterialTheme.typography.bodySmall)
        }
        idea.photoNotes?.let { Text("From the photo: $it", style = MaterialTheme.typography.bodySmall) }
        if (idea.measurements.isNotEmpty()) Text("Measurements: " + idea.measurements.joinToString { "${it.label} ${PromptAssets.formatMm(it.mm)} mm" }, style = MaterialTheme.typography.bodySmall)
    }
}

private suspend fun saveVersion(app: AppContainer, idea: Idea, code: String, instruction: String, stats: DesignStats): String? {
    val r = app.scad.compile(code)
    val stl = r.stl ?: return null
    val id = UUID.randomUUID().toString().take(8)
    val name = app.repo.writeBytes(idea.id, "design-$id.stl", stl)
    val problems = app.pipeline.problemsAsync(r)
    val v = DesignVersion(id, System.currentTimeMillis(), instruction, code, problems.isEmpty(), problems, emptyMap(), r.sizeMm,
        stats.copy(compileMs = r.ms, totalMs = r.ms, genTokens = 0, promptTokens = 0, cachedTokens = 0, prefillMs = 0, genMs = 0), name)
    app.repo.update(idea.id) { it.copy(designs = it.designs + v) }
    return id
}

private fun fileName(idea: Idea, v: DesignVersion?): String {
    val base = idea.displayTitle.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "part" }
    val n = idea.designs.indexOfFirst { it.id == v?.id } + 1
    return "$base-v$n.stl"
}

private suspend fun shareStl(ctx: android.content.Context, app: AppContainer, idea: Idea, v: DesignVersion) {
    val src = app.repo.file(idea.id, v.stlFile ?: return)
    // Share under a readable name: copy into cache/share/<name>.stl
    val out = withContext(Dispatchers.IO) {
        val dir = java.io.File(ctx.cacheDir, "share").apply { mkdirs() }
        java.io.File(dir, fileName(idea, v)).also { src.copyTo(it, overwrite = true) }
    }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", out)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "model/stl"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, idea.displayTitle)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Send STL to…"))
}

