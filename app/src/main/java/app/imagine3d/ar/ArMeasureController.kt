package app.imagine3d.ar

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

data class ArSegment(val a: Offset?, val b: Offset?, val mm: Double, val kindA: String, val kindB: String)

data class ArUiState(
    val tracking: Boolean = false,
    val message: String = "Starting camera…",
    /** What the centre reticle is currently on, null when nothing usable is under it. */
    val reticleKind: String? = null,
    val points: List<Offset?> = emptyList(),
    val segments: List<ArSegment> = emptyList(),
    /** Live distance from the dangling first point to the reticle. */
    val pendingMm: Double? = null,
    val pendingFrom: Offset? = null,
    val viewSize: Pair<Int, Int> = 0 to 0,
)

/** A captured frame plus where each measurement's ends were on it (0..1 coordinates). */
class ArCapture(val bitmap: Bitmap, val segments: List<ArSegment>, val width: Int, val height: Int)

/**
 * ARCore measuring: the user aims a centre reticle at each end of a distance and taps "+".
 * Hits are taken from detected planes first, then the depth map, then oriented feature points,
 * the same order ARCore's samples use. All ARCore calls happen on the GL thread.
 */
class ArMeasureController(private val activity: Activity, private val session: Session) : GLSurfaceView.Renderer {
    val view: GLSurfaceView = GLSurfaceView(activity).apply {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(this@ArMeasureController)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        setWillNotDraw(false)
    }

    private sealed interface Command {
        data object Add : Command
        data object Undo : Command
        class Capture(val done: (ArCapture?) -> Unit) : Command
    }

    private class Placed(val anchor: Anchor, val kind: String)

    private val background = BackgroundRenderer()
    private val commands = ConcurrentLinkedQueue<Command>()
    private val placed = mutableListOf<Placed>()
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val viewProj = FloatArray(16)
    private var width = 0
    private var height = 0
    private var geometryDirty = true

    private val _state = MutableStateFlow(ArUiState())
    val state: StateFlow<ArUiState> = _state.asStateFlow()

    /** Screen positions from the last tracked frame; anchors keep their poses while tracking is lost. */
    private var lastScreen: List<Offset?> = emptyList()

    val depthSupported: Boolean = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)

    init {
        val config = Config(session).apply {
            focusMode = Config.FocusMode.AUTO
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            depthMode = if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        }
        session.configure(config)
    }

    fun addPoint() = commands.add(Command.Add)
    fun undo() = commands.add(Command.Undo)
    fun capture(done: (ArCapture?) -> Unit) = commands.add(Command.Capture(done))

    fun resume() {
        session.resume()
        view.onResume()
    }

    fun pause() {
        view.onPause()
        session.pause()
    }

    fun close() {
        placed.forEach { it.anchor.detach() }
        session.close()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.create()
        session.setCameraTextureName(background.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
        GLES20.glViewport(0, 0, w, h)
        geometryDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (width == 0) return
        if (geometryDirty) {
            @Suppress("DEPRECATION")
            session.setDisplayGeometry(activity.windowManager.defaultDisplay.rotation, width, height)
            geometryDirty = false
        }
        val frame: Frame = try {
            session.update()
        } catch (e: Exception) {
            Log.w(TAG, "session.update failed", e)
            return
        }
        background.draw(frame)
        val camera = frame.camera

        // Captures read back what was just drawn, before anything else touches the framebuffer.
        val captures = mutableListOf<Command.Capture>()
        var cmd = commands.poll()
        val pendingOps = mutableListOf<Command>()
        while (cmd != null) {
            if (cmd is Command.Capture) captures += cmd else pendingOps += cmd
            cmd = commands.poll()
        }
        val captured = if (captures.isNotEmpty()) readPixels() else null

        val tracking = camera.trackingState == TrackingState.TRACKING
        val hit = if (tracking) bestHit(frame.hitTest(width / 2f, height / 2f), camera.pose) else null
        for (op in pendingOps) when (op) {
            // "+" is disabled without a hit, so an Add that lost its hit in between is dropped.
            Command.Add -> hit?.let { (h, kind) ->
                try {
                    placed += Placed(h.createAnchor(), kind)
                } catch (e: Exception) {
                    Log.w(TAG, "could not create anchor", e)  // NotTracking / ResourceExhausted
                }
            }
            Command.Undo -> placed.removeLastOrNull()?.anchor?.detach()
            else -> {}
        }

        if (tracking) {
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, 0.02f, 50f)
            android.opengl.Matrix.multiplyMM(viewProj, 0, projMatrix, 0, viewMatrix, 0)
            lastScreen = placed.map { if (it.anchor.trackingState == TrackingState.STOPPED) null else project(it.anchor.pose) }
        }
        val screen = placed.indices.map { lastScreen.getOrNull(it) }
        // Pair consecutive points; distances come from anchor poses, which survive brief tracking loss.
        val segments = (0 until placed.size / 2).mapNotNull { i ->
            val a = placed[2 * i]
            val b = placed[2 * i + 1]
            if (a.anchor.trackingState == TrackingState.STOPPED || b.anchor.trackingState == TrackingState.STOPPED) null
            else ArSegment(screen[2 * i], screen[2 * i + 1], distanceMm(a.anchor.pose, b.anchor.pose), a.kind, b.kind)
        }
        val dangling = if (placed.size % 2 == 1) placed.last() else null
        val pendingMm = if (dangling != null && hit != null) distanceMm(dangling.anchor.pose, hit.first.hitPose) else null

        val message = when {
            !tracking -> trackingMessage(camera.trackingFailureReason)
            hit == null && placed.isEmpty() -> "Move your phone slowly so it can see the surfaces"
            hit == null -> "Aim the dot at a surface"
            dangling != null -> "Aim at the other end and tap +"
            segments.isEmpty() -> "Aim the dot at one end and tap +"
            else -> "Add another measurement, or take the photo"
        }
        _state.value = ArUiState(
            tracking = tracking, message = message, reticleKind = hit?.second, points = screen, segments = segments,
            pendingMm = pendingMm, pendingFrom = dangling?.let { screen.last() }, viewSize = width to height,
        )
        captures.forEach { it.done(captured?.let { b -> ArCapture(b, segments, width, height) }) }
    }

    private fun bestHit(hits: List<HitResult>, cameraPose: Pose): Pair<HitResult, String>? {
        for (h in hits) {
            when (val t = h.trackable) {
                is Plane -> if (t.isPoseInPolygon(h.hitPose) && distanceToPlane(h.hitPose, cameraPose) > 0) {
                    return h to if (t.type == Plane.Type.VERTICAL) "vertical surface" else "horizontal surface"
                }
                is DepthPoint -> return h to "depth"
                is Point -> if (t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) return h to "feature point"
            }
        }
        return null
    }

    private fun project(pose: Pose): Offset? {
        val p = floatArrayOf(pose.tx(), pose.ty(), pose.tz(), 1f)
        val clip = FloatArray(4)
        android.opengl.Matrix.multiplyMV(clip, 0, viewProj, 0, p, 0)
        if (clip[3] <= 0f) return null  // behind the camera
        val x = (clip[0] / clip[3] * 0.5f + 0.5f) * width
        val y = (1f - (clip[1] / clip[3] * 0.5f + 0.5f)) * height
        return Offset(x, y)
    }

    private fun readPixels(): Bitmap {
        val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buf.rewind()
        raw.copyPixelsFromBuffer(buf)
        val flipped = Bitmap.createBitmap(raw, 0, 0, width, height, Matrix().apply { preScale(1f, -1f) }, false)
        raw.recycle()
        return flipped
    }

    private fun trackingMessage(reason: TrackingFailureReason) = when (reason) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark: turn on a light"
        TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast: slow down"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Aim at something with more texture"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable"
        else -> "Move your phone slowly to start tracking"
    }

    companion object {
        private const val TAG = "ArMeasure"

        fun distanceMm(a: Pose, b: Pose): Double {
            val dx = a.tx() - b.tx()
            val dy = a.ty() - b.ty()
            val dz = a.tz() - b.tz()
            return sqrt((dx * dx + dy * dy + dz * dz).toDouble()) * 1000.0
        }

        /** Signed distance from the camera to the plane; negative means we are looking at its back. */
        private fun distanceToPlane(planePose: Pose, cameraPose: Pose): Float {
            val normal = FloatArray(3)
            planePose.getTransformedAxis(1, 1f, normal, 0)
            return (cameraPose.tx() - planePose.tx()) * normal[0] + (cameraPose.ty() - planePose.ty()) * normal[1] +
                (cameraPose.tz() - planePose.tz()) * normal[2]
        }
    }
}
