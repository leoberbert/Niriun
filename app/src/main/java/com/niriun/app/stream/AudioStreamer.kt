package com.niriun.app.stream

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder

/**
 * Microphone -> Opus (48 kHz, mono, 20 ms frames) -> audio packets. Uses the platform's Opus encoder
 * (`audio/opus`, Android 10+), so no native codec is bundled.
 */
class AudioStreamer(private val sender: MediaSender, private val onFailure: (String) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread(::run, "niriun-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(2_000)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun run() {
        var recorder: AudioRecord? = null
        var codec: MediaCodec? = null
        try {
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, FRAME_BYTES * 8))
                .build()
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }

            val format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            }
            codec = MediaCodec.createEncoderByType(MIME).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
            recorder.startRecording()

            val pcm = ByteArray(FRAME_BYTES)
            val info = MediaCodec.BufferInfo()
            var presentationUs = 0L
            while (running) {
                var offset = 0
                while (offset < FRAME_BYTES && running) {
                    val n = recorder.read(pcm, offset, FRAME_BYTES - offset)
                    check(n >= 0) { "microphone read failed ($n)" }
                    offset += n
                }
                if (!running) break
                val inIndex = codec.dequeueInputBuffer(20_000)
                if (inIndex >= 0) {
                    codec.getInputBuffer(inIndex)?.let {
                        it.clear()
                        it.put(pcm)
                    }
                    codec.queueInputBuffer(inIndex, 0, FRAME_BYTES, presentationUs, 0)
                    presentationUs += FRAME_US
                }
                drain(codec, info)
            }
        } catch (e: Exception) {
            if (running) onFailure("Audio stopped: ${e.message}")
        } finally {
            running = false
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0) {
                        codec.getOutputBuffer(index)?.let {
                            it.position(info.offset)
                            it.limit(info.offset + info.size)
                            sender.sendAudio(it, info.size)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> continue // format/buffer changes
            }
        }
    }

    companion object {
        private const val MIME = "audio/opus"
        const val SAMPLE_RATE = 48_000
        private const val FRAME_SAMPLES = 960 // 20 ms
        private const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val FRAME_US = 20_000L
        private const val BIT_RATE = 64_000

        /** True when the device ships an Opus encoder (otherwise the hello advertises no microphone). */
        fun isSupported(): Boolean {
            val format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, 1)
            return MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) != null
        }
    }
}
