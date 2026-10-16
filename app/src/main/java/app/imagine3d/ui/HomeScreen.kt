package app.imagine3d.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.imagine3d.AppContainer
import app.imagine3d.data.Idea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

enum class NewIdeaSource { AR, Camera, Gallery, TextOnly }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    app: AppContainer,
    arAvailable: Boolean,
    onNew: (NewIdeaSource) -> Unit,
    onOpen: (Idea) -> Unit,
    onModels: () -> Unit,
) {
    val ideas by app.repo.ideas.collectAsState()
    val status by app.models.status.collectAsState()
    val run by app.run.collectAsState()
    var sheet by remember { mutableStateOf(false) }
    // Models can appear behind our back (adb push, finished downloads): re-check on every visit.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        app.models.refresh()
        onPauseOrDispose { }
    }
    val hasDesigner = app.models.catalog.any { it.canCode && status[it.id] is app.imagine3d.models.ModelStatus.Ready }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Imagine3D") },
                actions = { IconButton(onClick = onModels) { Icon(Icons.Default.Memory, "Models") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { sheet = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New idea") })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasDesigner) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Get a designer model", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text("Designs are written by an open-weight model running on this phone. Download one (about 2.4 GB) to start.")
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onModels) { Text("Open models") }
                        }
                    }
                }
            }
            if (ideas.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ViewInAr, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("Spot somewhere that needs a hook, clip or organizer?", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text("Take a photo, measure it, say what you want, and get a printable part.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(ideas, key = { it.id }) { idea ->
                IdeaCard(app, idea, running = run?.ideaId == idea.id && run?.running == true, onClick = { onOpen(idea) })
            }
        }
    }

    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text("New idea", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                SourceItem(Icons.Default.Straighten, "Measure with AR", if (arAvailable) "Aim at the spot, tap the ends of what it must fit, take the photo" else "Not available on this device") {
                    if (arAvailable) { sheet = false; onNew(NewIdeaSource.AR) }
                }
                SourceItem(Icons.Default.CameraAlt, "Take a photo", "Then type the measurements yourself") { sheet = false; onNew(NewIdeaSource.Camera) }
                SourceItem(Icons.Default.PhotoLibrary, "Choose a photo", "From your gallery") { sheet = false; onNew(NewIdeaSource.Gallery) }
                SourceItem(Icons.Default.EditNote, "Just describe it", "No photo") { sheet = false; onNew(NewIdeaSource.TextOnly) }
            }
        }
    }
}

@Composable
private fun SourceItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}

@Composable
private fun IdeaCard(app: AppContainer, idea: Idea, running: Boolean, onClick: () -> Unit) {
    val thumb = rememberThumbnail(idea.annotatedFile ?: idea.photoFile, app.repo.dir(idea.id))
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                if (thumb != null) Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Default.ViewInAr, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(idea.displayTitle, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                val design = idea.latestDesign
                val status = when {
                    running -> "Designing…"
                    design == null -> "No design yet"
                    design.sizeMm != null -> "${idea.designs.size} version${if (idea.designs.size == 1) "" else "s"} · " + design.sizeMm.joinToString(" × ") { "%.0f".format(it) } + " mm"
                    else -> "${idea.designs.size} versions"
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(idea.createdAt)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun rememberThumbnail(name: String?, dir: File, maxSide: Int = 256): ImageBitmap? {
    val state = produceState<ImageBitmap?>(null, name) {
        value = if (name == null) null else withContext(Dispatchers.IO) {
            val f = File(dir, name)
            if (!f.exists()) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }
    return state.value
}
