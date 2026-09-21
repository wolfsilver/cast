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
    context: Context,
    private val logger: (String) -> Unit = {}
) : CastableReceiver, AirPlayNative.Listener {
    override val id: String = "airplay"
    override val displayName: String = "AirPlay"
    override var status: ReceiverStatus = ReceiverStatus.STOPPED
        private set

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val videoDecoder = AirPlayVideoDecoder { message -> log(message) }
    private val audioDecoder = AirPlayAudioDecoder { message -> log(message) }
    private val nativeEngine = AirPlayNative(this)
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()

    override fun start(): Boolean {
        if (status == ReceiverStatus.RUNNING) return true
        status = ReceiverStatus.STARTING
        val keyFile = File(appContext.filesDir, "airplay-pairing.key").absolutePath
        log("开始启动，配对密钥路径: $keyFile")
        val port = nativeEngine.start(keyFile)
        if (port <= 0) {
            log("native 启动失败，返回码: $port")
            status = ReceiverStatus.ERROR
            return false
        }
        val airplayRecords = nativeEngine.txtRecords(true)
        val raopRecords = nativeEngine.txtRecords(false)
        log("native 已监听端口: $port，AirPlay TXT: ${airplayRecords.size} 条，RAOP TXT: ${raopRecords.size} 条")
        registerService("i投屏", "_airplay._tcp.", port, airplayRecords)
        registerService("020000000001@i投屏", "_raop._tcp.", port, raopRecords)
        status = ReceiverStatus.RUNNING
        log("已提交 Android DNS-SD 注册请求")
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

    override fun onLog(message: String) {
        log("native: $message")
    }

    override fun onVideoFrame(data: ByteArray, h265: Boolean, timestampNtp: Long) {
        videoDecoder.queue(data, h265, timestampNtp)
    }

    override fun onAudioFrame(data: ByteArray, codecType: Int, timestampNtp: Long) {
        audioDecoder.queue(data, codecType, timestampNtp)
    }

    private fun log(message: String) {
        logger(message)
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
        log("注册 $type，名称=$name，端口=$port，TXT keys=${records.map { it.substringBefore('=') }}")
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                val message = "DNS-SD 注册成功: ${info.serviceType}，名称=${info.serviceName}，端口=${info.port}"
                Log.i(id, message)
                log(message)
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                val message = "DNS-SD 注册失败: ${info.serviceType}，错误码=$errorCode"
                Log.e(id, message)
                log(message)
                status = ReceiverStatus.ERROR
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                val message = "DNS-SD 注销失败: ${info.serviceType}，错误码=$errorCode"
                Log.w(id, message)
                log(message)
            }
        }
        registrations += listener
        runCatching {
            nsdManager.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { error ->
            status = ReceiverStatus.ERROR
            log("DNS-SD 注册抛出异常: ${error.message ?: error.javaClass.simpleName}")
        }
    }
}
