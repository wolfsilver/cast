package com.w.cast.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat

internal class AirPlayAudioDecoder(
    private val logger: (String) -> Unit = {}
) {
    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var codecType = 0
    private var unsupportedCodecType = 0
    private var firstTimestampNtp = Long.MIN_VALUE
    private val bufferInfo = MediaCodec.BufferInfo()

    @Synchronized
    fun queue(data: ByteArray, newCodecType: Int, timestampNtp: Long) {
        if (newCodecType != 8) {
            if (unsupportedCodecType != newCodecType) {
                logger("暂不支持 AirPlay 音频编码类型: $newCodecType")
                unsupportedCodecType = newCodecType
            }
            return
        }
        unsupportedCodecType = 0
        try {
            if (codec == null || codecType != newCodecType) {
                if (!start(newCodecType)) return
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
                        logger("音频帧超过解码器输入缓冲区，已丢弃: ${data.size} bytes")
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    }
                }
            }
            while (true) {
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                if (outputIndex < 0) break
                decoder.getOutputBuffer(outputIndex)?.let { output ->
                    val offset = bufferInfo.offset
                    val size = bufferInfo.size
                    if (offset >= 0 && size > 0 && offset + size <= output.limit()) {
                        val pcm = ByteArray(size)
                        output.position(offset)
                        output.get(pcm)
                        audioTrack?.write(pcm, 0, pcm.size)
                    }
                }
                decoder.releaseOutputBuffer(outputIndex, false)
            }
        } catch (error: Exception) {
            logger("音频解码异常，已重置: ${error.message ?: error.javaClass.simpleName}")
            release()
        }
    }

    @Synchronized
    fun release() {
        codec?.runCatching { stop() }
        codec?.runCatching { release() }
        audioTrack?.runCatching { stop() }
        audioTrack?.runCatching { release() }
        codec = null
        audioTrack = null
        codecType = 0
        unsupportedCodecType = 0
        firstTimestampNtp = Long.MIN_VALUE
    }

    private fun start(newCodecType: Int): Boolean {
        release()
        val format = MediaFormat.createAudioFormat("audio/mp4a-latm", 44_100, 2).apply {
            setByteBuffer(
                "csd-0",
                java.nio.ByteBuffer.wrap(byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00))
            )
        }
        var decoder: MediaCodec? = null
        var track: AudioTrack? = null
        return try {
            decoder = MediaCodec.createDecoderByType("audio/mp4a-latm")
            decoder.configure(format, null, null, 0)
            decoder.start()
            val minBuffer = AudioTrack.getMinBufferSize(
                44_100,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(44_100)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer.coerceAtLeast(4096))
                .build()
            track.play()
            codec = decoder
            audioTrack = track
            codecType = newCodecType
            true
        } catch (error: Exception) {
            decoder?.runCatching { stop() }
            decoder?.runCatching { release() }
            track?.runCatching { stop() }
            track?.runCatching { release() }
            logger("音频解码器启动失败: ${error.message ?: error.javaClass.simpleName}")
            false
        }
    }

    private fun timestampUs(timestampNtp: Long): Long {
        if (firstTimestampNtp == Long.MIN_VALUE) firstTimestampNtp = timestampNtp
        return ((timestampNtp - firstTimestampNtp).coerceAtLeast(0L) / 1_000L)
    }
}
