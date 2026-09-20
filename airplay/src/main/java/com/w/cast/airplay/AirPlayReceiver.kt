package com.w.cast.airplay

import android.content.Context
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
    private val videoDecoder = AirPlayVideoDecoder()
    private val audioDecoder = AirPlayAudioDecoder()
    private val nativeEngine = AirPlayNative(this)

    override fun start(): Boolean {
        if (status == ReceiverStatus.RUNNING) return true
        status = ReceiverStatus.STARTING
        val keyFile = File(appContext.filesDir, "airplay-pairing.key").absolutePath
        status = if (nativeEngine.start(keyFile)) ReceiverStatus.RUNNING else ReceiverStatus.ERROR
        return status == ReceiverStatus.RUNNING
    }

    override fun stop() {
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
}
