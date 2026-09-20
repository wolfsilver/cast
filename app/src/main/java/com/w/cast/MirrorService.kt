package com.w.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.net.wifi.WifiManager
import android.view.Surface
import com.w.cast.airplay.AirPlayReceiver
import com.w.cast.caststub.GoogleCastReceiver
import com.w.cast.core.ReceiverRegistry
import com.w.cast.dlna.DlnaReceiver

class MirrorService : Service() {
    private lateinit var registry: ReceiverRegistry
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        acquireMulticastLock()
        registry = ReceiverRegistry(
            listOf(
                AirPlayReceiver(this),
                DlnaReceiver(this),
                GoogleCastReceiver()
            )
        )
        registry.startAll()
        pendingSurface?.let(registry::setVideoSurface)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        registry.stopAll()
        multicastLock?.let { if (it.isHeld) it.release() }
        pendingSurface = null
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireMulticastLock() {
        val wifiManager = getSystemService(WifiManager::class.java)
        multicastLock = wifiManager.createMulticastLock("cast-airplay").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "屏幕镜像接收",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun notification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("i投屏")
                .setContentText("正在等待 AirPlay 设备连接")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("i投屏")
                .setContentText("正在等待 AirPlay 设备连接")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        }
    }

    companion object {
        private const val CHANNEL_ID = "mirror-service"
        private const val NOTIFICATION_ID = 1001
        private var instance: MirrorService? = null
        private var pendingSurface: Surface? = null

        fun setVideoSurface(surface: Surface?) {
            pendingSurface = surface
            instance?.registry?.setVideoSurface(surface)
        }
    }
}
