package com.niriun.app.stream

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
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.niriun.app.MainActivity
import com.niriun.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Foreground service that keeps the device discoverable and streams while a PC is connected. */
class StreamService : Service() {
    private var session: StreamSession? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NiriunRuntime.service = this
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!has(Manifest.permission.CAMERA)) {
            stopSelf()
            return START_NOT_STICKY
        }
        enterForeground(NiriunRuntime.state.value.phase)
        if (session == null) {
            acquireLocks()
            session = StreamSession(applicationContext, NiriunRuntime::update, ::stopSelf).also {
                it.setPreviewSurface(NiriunRuntime.previewSurface)
                it.start()
            }
            scope.launch {
                NiriunRuntime.state.map { it.phase }.distinctUntilChanged().collect { enterForeground(it) }
            }
        }
        if (intent?.getBooleanExtra(EXTRA_BROADCAST, false) == true) session?.setBroadcasting(true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        NiriunRuntime.service = null
        scope.cancel()
        session?.stop()
        session = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        NiriunRuntime.update { UiState() }
        super.onDestroy()
    }

    fun setPreviewSurface(surface: Surface?) {
        session?.setPreviewSurface(surface)
    }

    fun selectCamera(index: Int) {
        session?.selectCamera(index)
    }

    fun setTorch(enabled: Boolean) {
        session?.setTorch(enabled)
    }

    fun setMirror(enabled: Boolean) {
        session?.setMirror(enabled)
    }

    fun setZoom(percent: Int) {
        session?.setZoom(percent)
    }

    fun setBroadcasting(enabled: Boolean) {
        session?.setBroadcasting(enabled)
    }

    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "niriun:stream").apply {
            setReferenceCounted(false)
            acquire()
        }
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "niriun:stream").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun enterForeground(phase: Phase) {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (has(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(phase), type)
    }

    private fun buildNotification(phase: Phase): Notification {
        val text = when (phase) {
            Phase.STREAMING -> R.string.notification_streaming
            Phase.CONNECTED -> R.string.notification_connected
            Phase.STOPPED -> R.string.notification_paused
            else -> R.string.notification_waiting
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val CHANNEL_ID = "niriun_stream"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.niriun.app.STOP"

        const val EXTRA_BROADCAST = "broadcast"

        /** Starts the service (camera preview only); [broadcast] also turns discovery/serving on. */
        fun start(context: Context, broadcast: Boolean = false) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, StreamService::class.java).putExtra(EXTRA_BROADCAST, broadcast),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamService::class.java))
        }
    }
}
