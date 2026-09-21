package com.w.cast.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface

internal class AirPlayVideoDecoder(
    private val logger: (String) -> Unit = {}
) {
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
        try {
            if (codec == null || mime != targetMime) {
                if (!start(targetMime)) return
            }
            val decoder = codec ?: return
            val inputIndex = decoder.dequeueInputBuffer(0)
            if (inputIndex >= 0) {
                decoder.getInputBuffer(inputIndex)?.let { input ->
                    input.clear()
                    if (data.size <= input.remaining()) {
                        input.put(data)
                        decoder.queueInputBuffer(inputIndex, 0, data.size, timestampUs(timestampNtp), 0)
                    } else {
                        logger("视频帧超过解码器输入缓冲区，已丢弃: ${data.size} bytes")
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    }
                }
            }
            while (true) {
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                if (outputIndex < 0) break
                decoder.releaseOutputBuffer(outputIndex, true)
            }
        } catch (error: Exception) {
            logger("视频解码异常，已重置: ${error.message ?: error.javaClass.simpleName}")
            release()
        }
    }

    @Synchronized
    fun release() {
        codec?.runCatching { stop() }
        codec?.runCatching { release() }
        codec = null
        mime = null
        firstTimestampNtp = Long.MIN_VALUE
    }

    private fun start(targetMime: String): Boolean {
        release()
        var decoder: MediaCodec? = null
        return try {
            decoder = MediaCodec.createDecoderByType(targetMime)
            decoder.configure(
                MediaFormat.createVideoFormat(targetMime, 1920, 1080),
                surface,
                null,
                0
            )
            decoder.start()
            codec = decoder
            mime = targetMime
            true
        } catch (error: Exception) {
            decoder?.runCatching { stop() }
            decoder?.runCatching { release() }
            logger("视频解码器启动失败，格式=$targetMime: ${error.message ?: error.javaClass.simpleName}")
            false
        }
    }

    private fun timestampUs(timestampNtp: Long): Long {
        if (firstTimestampNtp == Long.MIN_VALUE) firstTimestampNtp = timestampNtp
        return ((timestampNtp - firstTimestampNtp).coerceAtLeast(0L) / 1_000L)
    }
}
