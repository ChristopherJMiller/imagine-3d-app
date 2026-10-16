package app.imagine3d.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.imagine3d.AppContainer
import app.imagine3d.ai.PromptAssets
import app.imagine3d.data.Idea
import app.imagine3d.data.Measurement
import app.imagine3d.data.PhotoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import app.imagine3d.data.Stroke as InkStroke

private const val INK = 0xFFFF5252L
private val MeasureColor = Color(0xFFFFC857)

private class MeasurementDraft(val m: Measurement) {
    var label by mutableStateOf(m.label)
    var value by mutableStateOf(if (m.mm > 0) PromptAssets.formatMm(m.mm) else "")
    fun toMeasurement() = m.copy(label = label.trim(), mm = value.replace(',', '.').toDoubleOrNull() ?: 0.0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotateScreen(app: AppContainer, ideaId: String, onBack: () -> Unit, onDesign: (Idea) -> Unit) {
    val initial = remember { app.repo.get(ideaId) } ?: return
    val scope = rememberCoroutineScope()
    var description by remember { mutableStateOf(initial.description) }
    val strokes = remember { mutableStateListOf<InkStroke>().apply { addAll(initial.strokes) } }
    val drafts = remember { mutableStateListOf<MeasurementDraft>().apply { addAll(initial.measurements.map(::MeasurementDraft)) } }
    val hasPhoto = initial.photoFile != null
    var drawing by remember { mutableStateOf(hasPhoto) }
    var current by remember { mutableStateOf<List<PhotoPoint>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    val photo = rememberFullPhoto(initial.photoFile?.let { app.repo.file(ideaId, it) })
    androidx.compose.runtime.LaunchedEffect(Unit) { app.prewarm(ideaId) }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val said = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (said.isNotBlank()) description = listOf(description.trim(), said.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun collect(): Idea = initial.copy(
        description = description.trim(),
        strokes = strokes.toList(),
        measurements = drafts.map { it.toMeasurement() }.filter { it.mm > 0 || it.label.isNotBlank() },
        photoNotes = if (description.trim() == initial.description.trim() && strokes.toList() == initial.strokes) initial.photoNotes else null,
    )

    fun design() {
        // Designs can take minutes; ask once so we can say when one finishes in the background.
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        saving = true
        scope.launch {
            var idea = collect()
            if (photo != null && idea.photoFile != null) idea = idea.copy(annotatedFile = renderAnnotated(app, idea))
            app.repo.save(idea)
            onDesign(idea)
        }
    }

    // The system back gesture saves too, like the toolbar arrow.
    androidx.activity.compose.BackHandler { scope.launch { app.repo.save(collect()); onBack() } }

    // The photo gets the screen; the inputs live on a card that swipes up from the bottom.
    // Collapsed, the card shows the description and the Design button. Its collapsed height is
    // measured from that section and grows by the keyboard height while typing, so the field
    // being typed in always sits just above the keyboard.
    val sheetState = rememberStandardBottomSheetState(
        initialValue = if (hasPhoto) SheetValue.PartiallyExpanded else SheetValue.Expanded,
        skipHiddenState = true,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val density = LocalDensity.current
    var topSectionPx by remember { mutableIntStateOf(0) }
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    val bottomInset = maxOf(WindowInsets.ime.getBottom(density), navBottom)
    val collapsedCard = with(density) { (topSectionPx + navBottom).toDp() }
    val peek = with(density) { (topSectionPx + bottomInset).toDp() }
    val expanded = sheetState.currentValue == SheetValue.Expanded

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = peek,
        sheetDragHandle = null,
        topBar = {
            TopAppBar(
                title = { Text("Describe the part") },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { app.repo.save(collect()); onBack() } }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
        sheetContent = {
            Column(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
            ) {
                // Always-visible part of the card.
                Column(Modifier.fillMaxWidth().onSizeChanged { topSectionPx = it.height }.padding(horizontal = 16.dp)) {
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outlineVariant))
                    }
                    OutlinedTextField(
                        value = description, onValueChange = { description = it },
                        label = { Text("What should it be?") },
                        placeholder = { Text("e.g. a hook over this cabinet door to hang a towel") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                        trailingIcon = {
                            IconButton(onClick = {
                                speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Describe the part")
                                })
                            }) { Icon(Icons.Default.Mic, "Dictate") }
                        },
                    )
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { scope.launch { if (expanded) sheetState.partialExpand() else sheetState.expand() } }) {
                            Icon(Icons.Default.Straighten, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (drafts.isEmpty()) "Measurements" else "Measurements (${drafts.size})")
                            Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, null)
                        }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { design() }, enabled = !saving && description.isNotBlank(), modifier = Modifier.height(48.dp)) {
                            Icon(Icons.Default.AutoAwesome, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (initial.designs.isEmpty()) "Design it" else "Redesign")
                        }
                    }
                }

                // Revealed by swiping the card up.
                val showMore = expanded || sheetState.targetValue == SheetValue.Expanded
                Column(
                    Modifier.fillMaxWidth().alpha(if (showMore) 1f else 0f).heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                ) {
                    if (drafts.isEmpty()) {
                        Text("Add the sizes the part must fit, like a door thickness or a bottle diameter.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (drafts.any { it.m.source == "ar" }) {
                        Text("AR distances are usually within a few millimetres; check anything that must fit tightly with a ruler.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    for (d in drafts) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            OutlinedTextField(d.label, { d.label = it }, label = { Text(if (d.m.source == "ar") "What is this? (AR)" else "What is this?") },
                                singleLine = true, modifier = Modifier.weight(1.6f))
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(d.value, { d.value = it }, label = { Text("mm") }, singleLine = true, modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                            IconButton(onClick = { drafts.remove(d) }) { Icon(Icons.Default.Close, "Remove") }
                        }
                    }
                    TextButton(onClick = { drafts += MeasurementDraft(Measurement(UUID.randomUUID().toString().take(6), "", 0.0)) }) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add measurement")
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        },
    ) { padding ->
        // Fixed bottom gap (collapsed card without the keyboard) so the photo doesn't jump while typing.
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = collapsedCard)) {
            if (hasPhoto) {
                BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                    val ratio = photo?.let { it.width.toFloat() / it.height } ?: 0.75f
                    val fitWidth = minOf(maxWidth, maxHeight * ratio)
                    Box(Modifier.size(fitWidth, fitWidth / ratio).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
                        photo?.let { Image(it, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
                        Canvas(
                            Modifier.fillMaxSize().pointerInput(drawing) {
                                if (!drawing) return@pointerInput
                                detectDragGestures(
                                    onDragStart = { current = listOf(PhotoPoint(it.x / size.width, it.y / size.height)) },
                                    onDrag = { change, _ -> current = current + PhotoPoint(change.position.x / size.width, change.position.y / size.height) },
                                    onDragEnd = { if (current.size > 1) strokes += InkStroke(INK, 0.008f, current); current = emptyList() },
                                )
                            },
                        ) {
                            fun pt(p: PhotoPoint) = Offset(p.x * size.width, p.y * size.height)
                            for (s in strokes + listOfNotNull(current.takeIf { it.size > 1 }?.let { InkStroke(INK, 0.008f, it) })) {
                                val path = Path().apply { moveTo(pt(s.points[0]).x, pt(s.points[0]).y); s.points.drop(1).forEach { lineTo(pt(it).x, pt(it).y) } }
                                drawPath(path, Color(s.color), style = Stroke(width = s.width * size.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                            val paint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 36f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(5f, 0f, 0f, android.graphics.Color.BLACK) }
                            for (d in drafts) {
                                val a = d.m.a ?: continue
                                val b = d.m.b ?: continue
                                drawLine(MeasureColor, pt(a), pt(b), strokeWidth = 6f, cap = StrokeCap.Round)
                                drawCircle(MeasureColor, 8f, pt(a)); drawCircle(MeasureColor, 8f, pt(b))
                                val mid = (pt(a) + pt(b)) / 2f
                                val text = d.label.ifBlank { "?" } + " " + d.value + " mm"
                                drawContext.canvas.nativeCanvas.drawText(text, mid.x - paint.measureText(text) / 2, mid.y - 16f, paint)
                            }
                        }
                        // Drawing tools float over the photo's top-left corner.
                        Row(
                            Modifier.align(Alignment.TopStart).padding(8.dp)
                                .background(Color(0x99000000), RoundedCornerShape(20.dp)).padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconToggleButton(checked = drawing, onCheckedChange = { drawing = it }) {
                                Icon(Icons.Default.Draw, if (drawing) "Stop drawing" else "Draw on photo", tint = if (drawing) Color(INK) else Color.White)
                            }
                            IconButton(onClick = { strokes.removeLastOrNull() }, enabled = strokes.isNotEmpty()) {
                                Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = if (strokes.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No photo for this idea", style = MaterialTheme.typography.titleMedium)
                    Text("Describe the part and add the sizes it must fit.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun rememberFullPhoto(file: File?): ImageBitmap? = produceState<ImageBitmap?>(null, file) {
    value = file?.takeIf { it.exists() }?.let { f -> withContext(Dispatchers.IO) { decodeScaled(f, 1600)?.asImageBitmap() } }
}.value

private fun decodeScaled(f: File, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample; inMutable = true })
}

/** Burns the strokes and measurement labels into a copy of the photo: thumbnails and the vision model see this. */
private suspend fun renderAnnotated(app: AppContainer, idea: Idea): String = withContext(Dispatchers.IO) {
    val src = decodeScaled(app.repo.file(idea.id, idea.photoFile!!), 1600)!!
    val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
    val c = android.graphics.Canvas(bmp)
    val w = bmp.width.toFloat()
    val h = bmp.height.toFloat()
    val ink = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    for (s in idea.strokes) {
        ink.color = s.color.toInt(); ink.strokeWidth = s.width * w
        val path = android.graphics.Path()
        s.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x * w, p.y * h) else path.lineTo(p.x * w, p.y * h) }
        c.drawPath(path, ink)
    }
    val line = Paint().apply { isAntiAlias = true; color = 0xFFFFC857.toInt(); strokeWidth = w * 0.006f; strokeCap = Paint.Cap.ROUND }
    val text = Paint().apply { isAntiAlias = true; color = android.graphics.Color.WHITE; textSize = w * 0.035f; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK) }
    for (m in idea.measurements) {
        val a = m.a ?: continue
        val b = m.b ?: continue
        c.drawLine(a.x * w, a.y * h, b.x * w, b.y * h, line)
        val label = "${m.label.ifBlank { "?" }} ${PromptAssets.formatMm(m.mm)} mm"
        c.drawText(label, (a.x + b.x) / 2 * w - text.measureText(label) / 2, (a.y + b.y) / 2 * h - w * 0.02f, text)
    }
    app.repo.writeBitmap(idea.id, "annotated.jpg", bmp)
}
