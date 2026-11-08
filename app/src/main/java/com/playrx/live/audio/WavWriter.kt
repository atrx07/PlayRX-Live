package com.playrx.live.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV export (2026-11-08) — Master Audio recording writes the live
 * mix to a 16-bit PCM WAV file, pure Kotlin + java.io so the writer
 * itself is unit-testable on the JVM with temp files.
 *
 * Format: RIFF/WAVE, PCM (format 1), mono or stereo, 44100 Hz,
 * 16-bit samples. Float samples are expected in -1..1 and are
 * hard-clipped on write (the live mixer already soft-clips with
 * tanh; this is the last-resort guard for an offline render).
 */
object WavWriter {
    fun writeWav(file: File, samples: FloatArray, sampleRate: Int, channels: Int = 1): Boolean {
        if (samples.isEmpty() || sampleRate <= 0 || channels !in 1..2) return false
        return try {
            val bytesPerSample = 2
            val blockAlign = channels * bytesPerSample
            val byteRate = sampleRate * blockAlign
            val frames = samples.size / channels
            val dataSize = frames * blockAlign
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1) // PCM
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(16) // bits per sample
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize)
            FileOutputStream(file).use { out ->
                out.write(header.array())
                val pcm = ByteBuffer.allocate(frames * blockAlign).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until frames * channels) {
                    val v = samples[i].coerceIn(-1f, 1f)
                    pcm.putShort((v * 32767).toInt().toShort())
                }
                out.write(pcm.array())
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Minimal RIFF header read-back, for the unit test and for
     * validating an export before sharing it. */
    data class WavInfo(val sampleRate: Int, val channels: Int, val frames: Int)

    fun readInfo(file: File): WavInfo? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 44) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (String(bytes.copyOfRange(0, 4), Charsets.US_ASCII) != "RIFF") return null
            if (String(bytes.copyOfRange(8, 12), Charsets.US_ASCII) != "WAVE") return null
            val channels = buf.getShort(22).toInt()
            val sampleRate = buf.getInt(24)
            val dataSize = buf.getInt(40)
            val frames = if (channels > 0) dataSize / (channels * 2) else 0
            WavInfo(sampleRate, channels, frames)
        } catch (_: Exception) {
            null
        }
    }
}
