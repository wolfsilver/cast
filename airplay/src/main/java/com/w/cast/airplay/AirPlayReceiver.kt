package com.w.cast.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import android.view.Surface
import com.w.cast.core.CastableReceiver
import com.w.cast.core.ReceiverStatus
import java.io.File

class AirPlayReceiver(
    context: Context
) : CastableReceiver, AirPlayNative.Listener {
    override val id: String = "airplay"
    override val displayName: String = "AirPlay"
    override var status: ReceiverStatus = ReceiverStatus.STOPPED
        private set

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val videoDecoder = AirPlayVideoDecoder()
    private val audioDecoder = AirPlayAudioDecoder()
    private val nativeEngine = AirPlayNative(this)
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()

    override fun start(): Boolean {
        if (status == ReceiverStatus.RUNNING) return true
        status = ReceiverStatus.STARTING
        val keyFile = File(appContext.filesDir, "airplay-pairing.key").absolutePath
        val port = nativeEngine.start(keyFile)
        if (port <= 0) {
            status = ReceiverStatus.ERROR
            return false
        }
        registerService("i投屏", "_airplay._tcp", port, nativeEngine.txtRecords(true))
        registerService("020000000001@i投屏", "_raop._tcp", port, nativeEngine.txtRecords(false))
        status = ReceiverStatus.RUNNING
        return status == ReceiverStatus.RUNNING
    }

    override fun stop() {
        registrations.forEach { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        registrations.clear()
        nativeEngine.stop()
        videoDecoder.release()
        audioDecoder.release()
        status = ReceiverStatus.STOPPED
    }

    override fun setVideoSurface(surface: Surface?) {
        videoDecoder.setSurface(surface)
    }

    override fun onVideoFrame(data: ByteArray, h265: Boolean, timestampNtp: Long) {
        videoDecoder.queue(data, h265, timestampNtp)
    }

    override fun onAudioFrame(data: ByteArray, codecType: Int, timestampNtp: Long) {
        audioDecoder.queue(data, codecType, timestampNtp)
    }

    private fun registerService(
        name: String,
        type: String,
        port: Int,
        records: Array<String>
    ) {
        val service = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            this.port = port
            records.forEach { record ->
                val separator = record.indexOf('=')
                if (separator > 0) {
                    setAttribute(
                        record.substring(0, separator),
                        record.substring(separator + 1)
                    )
                }
            }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(id, "Registered ${info.serviceType} on port $port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(id, "Unable to register ${info.serviceType}: $errorCode")
                status = ReceiverStatus.ERROR
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(id, "Unable to unregister ${info.serviceType}: $errorCode")
            }
        }
        registrations += listener
        nsdManager.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener)
    }
}
