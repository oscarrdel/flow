package com.rainyworks.flow.feature.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps a running export alive in the background and shows its progress. */
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            manager().cancel()
            return START_NOT_STICKY
        }
        ensureChannel()
        startForegroundWithAnyType()
        if (!observing) {
            observing = true
            scope.launch {
                manager().job.collectLatest { job ->
                    when (val phase = job?.phase) {
                        is ExportPhase.Rendering -> notify(progress(phase.progress, job.projectName))
                        is ExportPhase.Done -> finish(result("Video guardado en la galería", job.projectName))
                        is ExportPhase.Error -> finish(result("No se pudo exportar", phase.message))
                        else -> finish(null)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Some builds refuse a type that the manifest declares and the app holds the permission for
     * (seen on Samsung Android 17 with mediaProcessing). Try each declared type; if the system
     * refuses all of them the export still runs while the app is open, just without the
     * background guarantee, instead of taking the app down.
     */
    private fun startForegroundWithAnyType() {
        val types = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                add(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            }
            add(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            add(ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        }
        for (type in types) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, progress(0f, null), type)
                return
            } catch (e: RuntimeException) {
                Log.w(TAG, "Foreground type $type refused", e)
            }
        }
        Log.w(TAG, "Export runs without a foreground service")
    }

    private fun manager(): ExportManager = (application as ExportManagerHolder).exportManager

    private fun progress(fraction: Float, name: String?): Notification {
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Exportando ${name.orEmpty()}".trim())
            .setContentText("${(fraction * 100).toInt()}%")
            .setProgress(100, (fraction * 100).toInt(), fraction <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, "Cancelar", cancel)
            .build()
    }

    private fun result(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    private fun openApp(): PendingIntent? = packageManager.getLaunchIntentForPackage(packageName)?.let {
        it.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun notify(notification: Notification) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
        }
    }

    private fun finish(notification: Notification?) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (notification != null) {
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                runCatching { NotificationManagerCompat.from(this).notify(RESULT_NOTIFICATION_ID, notification) }
            }
        }
        stopSelf()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Exportación", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ExportService"
        private const val CHANNEL_ID = "export"
        private const val NOTIFICATION_ID = 41
        private const val RESULT_NOTIFICATION_ID = 42
        private const val ACTION_CANCEL = "com.rainyworks.flow.export.CANCEL"
    }
}

/** Implemented by the Application so the service can reach the shared [ExportManager]. */
interface ExportManagerHolder {
    val exportManager: ExportManager
}
