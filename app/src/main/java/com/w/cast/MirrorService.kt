package com.w.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
        AppLog.add("服务: 前台服务已启动")
        logNetworkState()
        acquireMulticastLock()
        registry = ReceiverRegistry(
            listOf(
                AirPlayReceiver(this) { message -> AppLog.add("AirPlay: $message") },
                DlnaReceiver(this),
                GoogleCastReceiver()
            )
        )
        val started = registry.startAll()
        AppLog.add("服务: 已启动接收器=${started.map { it.id }}，全部状态=${registry.all().map { "${it.id}:${it.status}" }}")
        pendingSurface?.let(registry::setVideoSurface)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        AppLog.add("服务: 正在停止接收器")
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
        AppLog.add("网络: Wi-Fi multicast lock=${multicastLock?.isHeld}")
    }

    private fun logNetworkState() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork
        if (network == null) {
            AppLog.add("网络: 没有活动网络")
            return
        }
        val capabilities = connectivity.getNetworkCapabilities(network)
        val transport = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "蜂窝网络"
            else -> "其他网络"
        }
        val addresses = connectivity.getLinkProperties(network)
            ?.linkAddresses
            ?.map { it.address.hostAddress }
            ?.joinToString(",")
            .orEmpty()
        AppLog.add("网络: transport=$transport，地址=$addresses")
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
