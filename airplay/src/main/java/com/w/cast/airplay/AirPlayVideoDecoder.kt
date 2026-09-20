package com.w.cast.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface

internal class AirPlayVideoDecoder {
    private var codec: MediaCodec? = null
    private var mime: String? = null
    private var surface: Surface? = null
    private var firstTimestampNtp = Long.MIN_VALUE
    private val bufferInfo = MediaCodec.BufferInfo()

    @Synchronized
    fun setSurface(newSurface: Surface?) {
        if (surface === newSurface) return
        surface = newSurface
        release()
    }

    @Synchronized
    fun queue(data: ByteArray, h265: Boolean, timestampNtp: Long) {
        val targetMime = if (h265) "video/hevc" else "video/avc"
        if (surface == null) return
        if (codec == null || mime != targetMime) start(targetMime)
        val decoder = codec ?: return
        val inputIndex = decoder.dequeueInputBuffer(0)
        if (inputIndex >= 0) {
            decoder.getInputBuffer(inputIndex)?.let { input ->
                input.clear()
                input.put(data)
                decoder.queueInputBuffer(inputIndex, 0, data.size, timestampUs(timestampNtp), 0)
            }
        }
        while (true) {
            val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
            if (outputIndex < 0) break
            decoder.releaseOutputBuffer(outputIndex, true)
        }
    }

    @Synchronized
    fun release() {
        codec?.runCatching { stop() }
        codec?.release()
        codec = null
        mime = null
        firstTimestampNtp = Long.MIN_VALUE
    }

    private fun start(targetMime: String) {
        release()
        val decoder = MediaCodec.createDecoderByType(targetMime)
        decoder.configure(
            MediaFormat.createVideoFormat(targetMime, 1920, 1080),
            surface,
            null,
            0
        )
        decoder.start()
        codec = decoder
        mime = targetMime
    }

    private fun timestampUs(timestampNtp: Long): Long {
        if (firstTimestampNtp == Long.MIN_VALUE) firstTimestampNtp = timestampNtp
        return ((timestampNtp - firstTimestampNtp).coerceAtLeast(0L) / 1_000L)
    }
}
