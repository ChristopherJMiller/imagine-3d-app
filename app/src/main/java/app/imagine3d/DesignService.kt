package app.imagine3d

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the app process (and with it llama.cpp and the OpenSCAD WebView) alive while a design
 * runs, so the user can leave the app. Shows live progress, then a "ready" notification that
 * opens the design. Stops itself when the run ends.
 */
class DesignService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var lastUpdate = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = (application as Imagine3DApp).container
        if (intent?.action == ACTION_STOP) {
            app.cancelDesign()
            return START_NOT_STICKY
        }
        ensureChannels(this)
        // startForegroundService() obliges us to call startForeground() even if there is
        // nothing left to do, or the system kills the app.
        val state = app.run.value ?: RunState(ideaId = "", refinement = null)
        startForeground(PROGRESS_ID, progress(this, app, state), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!state.running || state.ideaId.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (job == null) {
            job = scope.launch {
                app.run.collect { s ->
                    if (s == null) return@collect
                    if (s.running) {
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 1000) {  // the token stream updates state many times a second
                            lastUpdate = now
                            notify(PROGRESS_ID, progress(this@DesignService, app, s))
                        }
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        if (!app.isWatching(s.ideaId)) finished(app, s)?.let { notify(s.ideaId.hashCode(), it) }
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notify(id: Int, n: Notification) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(this).notify(id, n)
        }
    }

    private fun finished(app: AppContainer, s: RunState): Notification? {
        val idea = app.repo.get(s.ideaId)
        val name = idea?.displayTitle ?: "your part"
        val (title, text) = when {
            s.done != null -> "$name is ready" to listOfNotNull(
                s.done.sizeMm?.joinToString(" × ") { "%.0f".format(it) }?.let { "$it mm" },
                if (s.done.ok) null else "check the warnings",
            ).joinToString(" · ").ifBlank { "Tap to see it" }
            s.error == "Stopped" -> return null
            else -> "Couldn't design $name" to (s.error ?: "Something went wrong")
        }
        return NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIdea(this, s.ideaId))
            .setAutoCancel(true)
            .build()
    }

    companion object {
        const val EXTRA_IDEA = "ideaId"
        private const val ACTION_STOP = "app.imagine3d.STOP_DESIGN"
        private const val CHANNEL_PROGRESS = "design_progress"
        private const val CHANNEL_DONE = "design_done"
        private const val PROGRESS_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DesignService::class.java))
        }

        private fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Designing", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Design finished", NotificationManager.IMPORTANCE_DEFAULT))
        }

        private fun openIdea(context: Context, ideaId: String): PendingIntent = PendingIntent.getActivity(
            context, ideaId.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_IDEA, ideaId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun progress(context: Context, app: AppContainer, s: RunState): Notification {
            val name = app.repo.get(s.ideaId)?.displayTitle ?: "your part"
            val stop = PendingIntent.getService(
                context, 0, Intent(context, DesignService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            )
            val step = s.step.label + (if (s.attempt > 0) " (try ${s.attempt + 1})" else "")
            return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Designing $name")
                .setContentText("$step · ${(System.currentTimeMillis() - s.startedAt) / 1000} s")
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openIdea(context, s.ideaId))
                .addAction(0, "Stop", stop)
                .build()
        }
    }
}
