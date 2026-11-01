package com.playrx.live.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android decoder for non-WAV files (OGG Vorbis, MP3, AAC/M4A).
 *
 * WAV files never reach here — SampleEngine.parseWav handles them
 * in pure Kotlin. This file exists so "import" is not WAV-only:
 * MediaExtractor + MediaCodec decode the file to PCM 16-bit, which
 * is then mixed to mono float, exactly like a WAV buffer.
 *
 * Failure is not fatal: callers catch and report "could not decode
 * this file" instead of crashing. A 30-second cap keeps a bad file
 * from hanging the import.
 */
object SampleDecoder {
    data class Decoded(val sampleRate: Int, val channels: Int, val frames: FloatArray)

    fun decodeToPcm(context: Context, uri: Uri): Decoded? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2
            extractor.selectTrack(trackIndex)
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val pcmShorts = mutableListOf<Short>()
                var inputDone = false
                var outputDone = false
                val deadline = System.currentTimeMillis() + 30_000
                val info = MediaCodec.BufferInfo()
                while (!outputDone && System.currentTimeMillis() < deadline) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buf = codec.getInputBuffer(inIndex) ?: continue
                            buf.clear()
                            val read = extractor.readSampleData(buf, 0)
                            if (read < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIndex)
                            if (outBuf != null && info.size > 0) {
                                val shorts = ShortArray(info.size / 2)
                                outBuf.order(ByteOrder.LITTLE_ENDIAN)
                                outBuf.asShortBuffer().get(shorts)
                                pcmShorts.addAll(shorts.toList())
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* ignore */ }
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* loop */ }
                    }
                    if (inputDone && pcmShorts.isNotEmpty() && outputDone) break
                }
                if (pcmShorts.isEmpty()) return null
                // Mix to mono float.
                val frames = FloatArray(pcmShorts.size / channels.coerceAtLeast(1))
                for (f in frames.indices) {
                    var sum = 0f
                    for (ch in 0 until channels) {
                        sum += pcmShorts[f * channels + ch] / 32768f
                    }
                    frames[f] = sum / channels
                }
                return Decoded(sampleRate, channels, frames)
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { extractor.release() }
        }
    }
}
