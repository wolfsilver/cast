package com.w.cast.airplay

internal class AirPlayNative(
    private val listener: Listener
) {
    interface Listener {
        fun onVideoFrame(data: ByteArray, h265: Boolean, timestampNtp: Long)
        fun onAudioFrame(data: ByteArray, codecType: Int, timestampNtp: Long)
    }

    private var handle: Long = 0

    fun start(keyFile: String): Boolean {
        if (handle == 0L) {
            handle = nativeCreate(listener)
        }
        return nativeStart(handle, keyFile)
    }

    fun stop() {
        if (handle != 0L) {
            nativeStop(handle)
            handle = 0
        }
    }

    private external fun nativeCreate(listener: Listener): Long
    private external fun nativeStart(handle: Long, keyFile: String): Boolean
    private external fun nativeStop(handle: Long)

    companion object {
        init {
            System.loadLibrary("airplay_native")
        }
    }
}
