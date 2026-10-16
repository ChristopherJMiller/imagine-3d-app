package app.imagine3d.ar

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session

private val Accent = Color(0xFFFFC857)
private val Good = Color(0xFF7CFFB2)

/** Full-screen AR measuring. Calls [onCaptured] with the photo and measured segments. */
@Composable
fun ArMeasureScreen(onCaptured: (ArCapture) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    var hasCamera by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    var session by remember { mutableStateOf<Session?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var installRequested by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    val lifecycle = owner.lifecycle

    LaunchedEffect(Unit) { if (!hasCamera) permission.launch(Manifest.permission.CAMERA) }

    // Create the session once the camera is allowed and ARCore is installed; requestInstall may
    // bounce through the Play Store, so this re-runs on every resume until it succeeds.
    DisposableEffect(hasCamera, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && hasCamera && session == null && error == null) {
                try {
                    when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                        ArCoreApk.InstallStatus.INSTALL_REQUESTED -> installRequested = true
                        ArCoreApk.InstallStatus.INSTALLED -> session = Session(activity)
                    }
                } catch (e: Exception) {
                    error = "AR is not available: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) observer.onStateChanged(owner, Lifecycle.Event.ON_RESUME)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val s = session
        when {
            error != null -> Message(error!!, onBack)
            !hasCamera -> Message("Camera permission is needed to measure.", onBack)
            s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> ArContent(activity, s, onCaptured, onBack, onError = { error = it })
        }
    }
}

@Composable
private fun ArContent(activity: Activity, session: Session, onCaptured: (ArCapture) -> Unit, onBack: () -> Unit, onError: (String) -> Unit) {
    val controller = remember(session) {
        runCatching { ArMeasureController(activity, session) }.onFailure {
            runCatching { session.close() }
            onError("Could not start AR: ${it.message}")
        }.getOrNull()
    } ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by controller.state.collectAsState()
    var capturing by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> runCatching { controller.resume() }.onFailure { onError("Camera failed: ${it.message}") }
                Lifecycle.Event.ON_PAUSE -> controller.pause()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) runCatching { controller.resume() }
        onDispose {
            lifecycle.removeObserver(observer)
            controller.pause()
            controller.close()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { controller.view }, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) { drawOverlay(state) }

        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x66000000))) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    state.message, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            if (!controller.depthSupported) {
                Text("No depth on this device: only flat surfaces will snap.", color = Color.White, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp).background(Color(0x66000000), RoundedCornerShape(8.dp)).padding(6.dp))
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = { controller.undo() }, enabled = state.points.isNotEmpty(), modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
            }
            FilledIconButton(onClick = { controller.addPoint() }, enabled = state.reticleKind != null, modifier = Modifier.size(80.dp), shape = CircleShape) {
                Icon(Icons.Default.Add, "Add point", Modifier.size(40.dp))
            }
            FilledTonalIconButton(
                onClick = {
                    capturing = true
                    controller.capture { cap -> activity.runOnUiThread { capturing = false; if (cap != null) onCaptured(cap) } }
                },
                enabled = !capturing, modifier = Modifier.size(56.dp),
            ) { Icon(Icons.Default.CameraAlt, "Take photo") }
        }
    }
}

private fun DrawScope.drawOverlay(state: ArUiState) {
    val center = Offset(size.width / 2, size.height / 2)
    val labelPaint = Paint().apply {
        color = android.graphics.Color.WHITE; textSize = 44f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
    }
    fun label(text: String, at: Offset) = drawContext.canvas.nativeCanvas.drawText(text, at.x - labelPaint.measureText(text) / 2, at.y - 24f, labelPaint)

    for (seg in state.segments) {
        val a = seg.a ?: continue
        val b = seg.b ?: continue
        drawLine(Accent, a, b, strokeWidth = 8f, cap = StrokeCap.Round)
        label(formatMm(seg.mm), (a + b) / 2f)
    }
    val from = state.pendingFrom
    if (from != null && state.pendingMm != null) {
        drawLine(Accent.copy(alpha = 0.7f), from, center, strokeWidth = 6f, cap = StrokeCap.Round)
        label(formatMm(state.pendingMm), (from + center) / 2f)
    }
    for (p in state.points) p?.let {
        drawCircle(Color.White, 14f, it)
        drawCircle(Accent, 9f, it)
    }
    val on = state.reticleKind != null
    drawCircle(if (on) Good else Color.White.copy(alpha = 0.6f), 34f, center, style = Stroke(width = 5f))
    drawCircle(if (on) Good else Color.White.copy(alpha = 0.6f), 5f, center)
}

fun formatMm(mm: Double): String = if (mm >= 1000) "%.2f m".format(mm / 1000) else "%.0f mm".format(mm)

@Composable
private fun Message(text: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = Color.White)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}
