package app.imagine3d

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.imagine3d.ar.ArCapture
import app.imagine3d.ar.ArMeasureScreen
import app.imagine3d.data.Measurement
import app.imagine3d.data.PhotoPoint
import app.imagine3d.ui.AnnotateScreen
import app.imagine3d.ui.DesignScreen
import app.imagine3d.ui.HomeScreen
import app.imagine3d.ui.Imagine3DTheme
import app.imagine3d.ui.ModelsScreen
import app.imagine3d.ui.NewIdeaSource
import com.google.ar.core.ArCoreApk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val app get() = (application as Imagine3DApp).container

    /** Idea to open, from a "design ready" notification tap. */
    private val openIdea = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        app.scad.warmUp()
        openIdea.value = intent?.getStringExtra(DesignService.EXTRA_IDEA)
        setContent {
            Imagine3DTheme {
                AppNav(app, openIdea)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(DesignService.EXTRA_IDEA)?.let { openIdea.value = it }
    }

    override fun onStart() {
        super.onStart()
        app.uiVisible = true
    }

    override fun onStop() {
        app.uiVisible = false
        super.onStop()
    }
}

@Composable
private fun AppNav(app: AppContainer, openIdea: kotlinx.coroutines.flow.MutableStateFlow<String?>) {
    val nav = rememberNavController()
    val toOpen by openIdea.collectAsState()
    LaunchedEffect(toOpen) {
        val id = toOpen ?: return@LaunchedEffect
        openIdea.value = null
        if (app.repo.get(id) == null) app.repo.load()
        nav.navigate("design/$id?auto=false") { popUpTo("home") }
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var arAvailable by remember { mutableStateOf(false) }
    var pendingPhoto by remember { mutableStateOf<Pair<String, File>?>(null) }

    LaunchedEffect(Unit) {
        // The availability check can answer "checking" first; poll briefly.
        repeat(20) {
            val a = ArCoreApk.getInstance().checkAvailability(ctx)
            if (!a.isTransient) { arAvailable = a.isSupported; return@LaunchedEffect }
            delay(300)
        }
    }

    fun openAnnotate(ideaId: String) = nav.navigate("annotate/$ideaId") { popUpTo("home") }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val (ideaId, file) = pendingPhoto ?: return@rememberLauncherForActivityResult
        pendingPhoto = null
        if (ok && file.exists()) scope.launch {
            val idea = app.repo.newIdea().copy(id = ideaId)
            app.repo.save(idea.copy(photoFile = file.name))
            openAnnotate(ideaId)
        }
    }
    // Because the manifest declares CAMERA, ACTION_IMAGE_CAPTURE throws SecurityException unless
    // the permission has been granted, even though the camera app takes the picture.
    fun launchCamera() {
        val idea = app.repo.newIdea()
        val file = app.repo.file(idea.id, "photo.jpg")
        pendingPhoto = idea.id to file
        try {
            camera.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file))
        } catch (e: Exception) {
            pendingPhoto = null
            android.widget.Toast.makeText(ctx, "Could not open the camera: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
        else android.widget.Toast.makeText(ctx, "Camera permission is needed to take a photo", android.widget.Toast.LENGTH_LONG).show()
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val idea = app.repo.newIdea()
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { input -> app.repo.file(idea.id, "photo.jpg").outputStream().use { input.copyTo(it) } }
            }
            app.repo.save(idea.copy(photoFile = "photo.jpg"))
            openAnnotate(idea.id)
        }
    }

    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                app, arAvailable,
                onNew = { source ->
                    when (source) {
                        NewIdeaSource.AR -> nav.navigate("ar")
                        NewIdeaSource.Camera ->
                            if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) launchCamera() else cameraPermission.launch(android.Manifest.permission.CAMERA)
                        NewIdeaSource.Gallery -> gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        NewIdeaSource.TextOnly -> scope.launch {
                            val idea = app.repo.newIdea()
                            app.repo.save(idea)
                            openAnnotate(idea.id)
                        }
                    }
                },
                onOpen = { idea -> if (idea.designs.isEmpty() && app.run.value?.ideaId != idea.id) openAnnotate(idea.id) else nav.navigate("design/${idea.id}?auto=false") },
                onModels = { nav.navigate("models") },
            )
        }
        composable("ar") {
            ArMeasureScreen(
                onCaptured = { capture -> scope.launch { openAnnotate(saveCapture(app, capture)) } },
                onBack = { nav.popBackStack() },
            )
        }
        composable("annotate/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments!!.getString("id")!!
            AnnotateScreen(app, id, onBack = { nav.popBackStack("home", false) }, onDesign = { idea ->
                if (idea.designs.isNotEmpty()) app.startDesign(idea.id)
                nav.navigate("design/${idea.id}?auto=true") { popUpTo("home") }
            })
        }
        composable(
            "design/{id}?auto={auto}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }, navArgument("auto") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            val id = entry.arguments!!.getString("id")!!
            DesignScreen(app, id, entry.arguments!!.getBoolean("auto"), onBack = { nav.popBackStack("home", false) }, onEdit = { navToAnnotate(nav, id) })
        }
        composable("models") { ModelsScreen(app, onBack = { nav.popBackStack() }) }
    }
}

private fun navToAnnotate(nav: NavHostController, id: String) = nav.navigate("annotate/$id")

/** Stores an AR capture as a new idea: the photo plus one measurement per measured segment. */
private suspend fun saveCapture(app: AppContainer, capture: ArCapture): String {
    val idea = app.repo.newIdea()
    val scale = 1600f / maxOf(capture.width, capture.height)
    val bmp = if (scale < 1f) Bitmap.createScaledBitmap(capture.bitmap, (capture.width * scale).toInt(), (capture.height * scale).toInt(), true) else capture.bitmap
    app.repo.writeBitmap(idea.id, "photo.jpg", bmp)
    val measurements = capture.segments.map { s ->
        Measurement(
            id = UUID.randomUUID().toString().take(6),
            label = "",
            mm = Math.round(s.mm * 2) / 2.0,  // ARCore is not better than ~half a millimetre anyway
            source = "ar",
            a = s.a?.let { PhotoPoint(it.x / capture.width, it.y / capture.height) },
            b = s.b?.let { PhotoPoint(it.x / capture.width, it.y / capture.height) },
            surfaceA = s.kindA, surfaceB = s.kindB,
        )
    }
    app.repo.save(idea.copy(photoFile = "photo.jpg", measurements = measurements))
    return idea.id
}

