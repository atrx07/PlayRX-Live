package com.playrx.live.audio

/**
 * Audio engine interface. Phase 1 implements this with Oboe (native C++,
 * AAudio on Android 8.1+, OpenSL ES fallback) for low-latency pad playback.
 *
 * Do NOT implement this on the Java/Kotlin audio path in production: that
 * path is typically 100-250 ms touch-to-sound, versus sub-20 ms achievable
 * with AAudio MMAP on supported hardware.
 *
 * Rules for the native implementation (see the Remixlive research notes):
 * - Request LowLatency performance mode + Exclusive sharing mode.
 * - Use the device's native sample rate (usually 48 kHz); never force a rate.
 * - Pre-decode samples to PCM; never do file I/O, allocation, or blocking
 *   calls inside the audio callback.
 * - Schedule pad launches on the audio clock, quantized to the next beat.
 */
interface AudioEngine {
    fun start()
    fun stop()
    fun setBpm(bpm: Float)
    fun triggerPad(padIndex: Int)
    fun stopPad(padIndex: Int)
    fun setPadMuted(padIndex: Int, muted: Boolean)
}
