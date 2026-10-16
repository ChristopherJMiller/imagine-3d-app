package app.imagine3d.ui

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.imagine3d.AppContainer
import app.imagine3d.models.ModelSpec
import app.imagine3d.models.ModelStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(app: AppContainer, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status by app.models.status.collectAsState()
    val codeId by app.models.codeModelId.collectAsState()
    val visionId by app.models.visionModelId.collectAsState()
    val useVision by app.models.useVision.collectAsState()
    val scope = rememberCoroutineScope()
    var sysInfo by remember { mutableStateOf("") }
    var importTarget by remember { mutableStateOf<Pair<ModelSpec, Boolean>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val target = importTarget
        if (uri != null && target != null) scope.launch { app.models.import(target.first, uri, target.second) }
    }
    LaunchedEffect(Unit) {
        app.models.refresh()
        sysInfo = runCatching { app.engine.systemInfo() }.getOrElse { "llama.cpp failed to start: ${it.message}" }
    }
    val activeCode = app.models.activeCodeModel()?.id
    val activeVision = app.models.activeVisionModel()?.id

    Scaffold(topBar = {
        TopAppBar(title = { Text("Models") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Everything runs on this phone with llama.cpp. Models download from Hugging Face once; nothing you type or photograph leaves the device.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Read photos with a vision model", style = MaterialTheme.typography.titleSmall)
                        Text("Adds what it sees in your photo to the request. Costs about 20–60 s.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = useVision, onCheckedChange = { app.models.setUseVision(it) })
                }
            }
            items(app.models.catalog, key = { it.id }) { spec ->
                ModelCard(
                    spec = spec,
                    status = status[spec.id] ?: ModelStatus.Missing,
                    isCode = activeCode == spec.id,
                    isVision = activeVision == spec.id,
                    selectedCode = codeId == spec.id,
                    selectedVision = visionId == spec.id,
                    onDownload = { app.models.download(spec) },
                    onCancel = { app.models.cancel(spec) },
                    onDelete = { app.models.delete(spec) },
                    onUseCode = { app.models.setCodeModel(spec.id) },
                    onUseVision = { app.models.setVisionModel(spec.id) },
                    onImport = { mmproj -> importTarget = spec to mmproj; picker.launch(arrayOf("*/*")) },
                )
            }
            item {
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Device", style = MaterialTheme.typography.titleSmall)
                Text(deviceSummary(ctx), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text("Model folder (adb push works): ${app.models.dir.path}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                Text(sysInfo, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ModelCard(
    spec: ModelSpec,
    status: ModelStatus,
    isCode: Boolean,
    isVision: Boolean,
    selectedCode: Boolean,
    selectedVision: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onUseCode: () -> Unit,
    onUseVision: () -> Unit,
    onImport: (mmproj: Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(spec.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(gb(spec.totalBytes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(spec.description, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (spec.canCode) AssistChip(onClick = {}, label = { Text(if (isCode) "Designing ✓" else "Designs") })
                if (spec.canSee) AssistChip(onClick = {}, label = { Text(if (isVision) "Reading photos ✓" else "Reads photos") })
                AssistChip(onClick = {}, label = { Text(spec.license) })
            }
            Spacer(Modifier.height(8.dp))
            when (status) {
                is ModelStatus.Downloading -> {
                    LinearProgressIndicator(progress = { status.fraction }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${gb(status.bytes)} of ${gb(status.total)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
                ModelStatus.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (spec.canCode) {
                        RadioButton(selected = selectedCode || (isCode && !selectedCode), onClick = onUseCode)
                        Text("Use to design")
                        Spacer(Modifier.width(8.dp))
                    }
                    if (spec.canSee && !spec.canCode) {
                        RadioButton(selected = selectedVision || isVision, onClick = onUseVision)
                        Text("Use for photos")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
                is ModelStatus.Failed, ModelStatus.Missing -> {
                    if (status is ModelStatus.Failed) Text(status.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Row {
                        Button(onClick = onDownload) { Text("Download") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { onImport(false) }) { Text("Import .gguf") }
                        if (spec.mmproj != null) {
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = { onImport(true) }) { Text("+ mmproj") }
                        }
                    }
                }
            }
        }
    }
}

private fun gb(bytes: Long) = "%.2f GB".format(bytes / 1e9)

private fun deviceSummary(ctx: Context): String {
    val am = ctx.getSystemService(ActivityManager::class.java)
    val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    return "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · ${android.os.Build.SOC_MODEL} · " +
        "%.1f GB RAM (%.1f free) · %d cores".format(mem.totalMem / 1e9, mem.availMem / 1e9, Runtime.getRuntime().availableProcessors())
}
