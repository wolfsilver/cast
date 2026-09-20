package com.w.cast.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat

internal class AirPlayAudioDecoder {
    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var codecType = 0
    private var firstTimestampNtp = Long.MIN_VALUE
    private val bufferInfo = MediaCodec.BufferInfo()

    @Synchronized
    fun queue(data: ByteArray, newCodecType: Int, timestampNtp: Long) {
        if (codec == null || codecType != newCodecType) start(newCodecType)
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
            decoder.getOutputBuffer(outputIndex)?.let { output ->
                val pcm = ByteArray(bufferInfo.size)
                output.position(bufferInfo.offset)
                output.get(pcm)
                audioTrack?.write(pcm, 0, pcm.size)
            }
            decoder.releaseOutputBuffer(outputIndex, false)
        }
    }

    @Synchronized
    fun release() {
        codec?.runCatching { stop() }
        codec?.release()
        audioTrack?.runCatching { stop() }
        audioTrack?.release()
        codec = null
        audioTrack = null
        codecType = 0
        firstTimestampNtp = Long.MIN_VALUE
    }

    private fun start(newCodecType: Int) {
        release()
        val format = MediaFormat.createAudioFormat("audio/mp4a-latm", 44_100, 2).apply {
            setByteBuffer("csd-0", if (newCodecType == 8) {
                java.nio.ByteBuffer.wrap(byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00))
            } else {
                java.nio.ByteBuffer.wrap(byteArrayOf(0x12, 0x10))
            })
        }
        val decoder = MediaCodec.createDecoderByType("audio/mp4a-latm")
        decoder.configure(format, null, null, 0)
        decoder.start()
        val minBuffer = AudioTrack.getMinBufferSize(
            44_100,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
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
    }

    private fun timestampUs(timestampNtp: Long): Long {
        if (firstTimestampNtp == Long.MIN_VALUE) firstTimestampNtp = timestampNtp
        return ((timestampNtp - firstTimestampNtp).coerceAtLeast(0L) / 1_000L)
    }
}
