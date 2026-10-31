package com.playrx.live.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Functional audio core for PlayRX Live (Phase 1).
 *
 * No external sample files are needed: every sound is synthesized in code,
 * so the app is fully open-source with no sample-licensing problem.
 *
 * Architecture (deliberately simple and BPM-accurate):
 * - One AudioTrack streaming thread mixes everything. Pads 0-15 are one-shot
 *   sounds (drums + bass notes) triggered immediately.
 * - Pads 16-23 are LOOP pads. A loop pad is not a rendered WAV — it is a
 *   16-step pattern that repeats every bar while the pad is active. Because
 *   patterns are triggered from the transport clock, changing BPM never
 *   drifts a loop out of sync (a pre-rendered loop would).
 * - The step sequencer (drum pads 0-7, 16 steps each) runs on the same clock.
 * - A simple feedback delay (dotted-eighth at the current BPM) is the v1 FX.
 *
 * MIDI is NOT handled here — MidiEngine feeds chord/transport events in,
 * and this engine only plays sound. MIDI hardware testing comes later.
 *
 * Known v1 limitation: the audio path is AudioTrack (Kotlin), not the
 * native Oboe path. That is a latency trade-off for getting a working,
 * compilable engine now; Oboe replaces this mixer in a later phase without
 * changing the pad/pattern API.
 */
object SampleBank {
    const val SAMPLE_RATE = 44100

    val PAD_NAMES = listOf(
        "Kick", "Snare", "Hat", "Open Hat", "Clap", "Tom", "Rim", "Crash",
        "Bass C", "Bass D", "Bass E", "Bass F", "Bass G", "Bass A", "Bass B", "Bass C+",
        "Drum Loop", "Hats Loop", "Bass Loop", "Synth Loop",
        "Perc Loop", "Chord Loop", "Fill Loop", "Groove Loop",
    )

    fun isLoopPad(pad: Int): Boolean = pad in 16..23
    fun isDrumPad(pad: Int): Boolean = pad in 0..7

    // Internal (non-grid) samples used by loop patterns.
    const val SYNTH_STAB = 100
    const val PAD_CHORD = 101
    const val DRONE = 102

    private val BASS_FREQS = doubleArrayOf(
        65.41, 73.42, 82.41, 87.31, 98.00, 110.00, 123.47, 130.81
    )

    private fun noiseBuffer(seconds: Double, seed: Long): FloatArray {
        val n = (seconds * SAMPLE_RATE).toInt()
        val rnd = java.util.Random(seed)
        val out = FloatArray(n)
        var last = 0f
        for (i in out.indices) {
            // Slight high-pass (difference) so hats/crash are bright, not rumbly.
            val white = rnd.nextFloat() * 2f - 1f
            val hp = white - last * 0.7f
            last = white
            out[i] = hp
        }
        return out
    }

    private fun sineNote(freq: Double, seconds: Double, decay: Double): FloatArray {
        val n = (seconds * SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        for (i in out.indices) {
            val t = i.toDouble() / SAMPLE_RATE
            out[i] = (sin(2 * PI * freq * t) * exp(-t * decay)).toFloat()
        }
        return out
    }

    private fun mixInto(dst: FloatArray, src: FloatArray, offset: Int, vol: Float) {
        for (i in src.indices) {
            val d = offset + i
            if (d in dst.indices) dst[d] += src[i] * vol
        }
    }

    fun oneShot(index: Int): FloatArray {
        return when (index) {
            0 -> { // Kick: pitch drop 150 -> 50 Hz
                val n = (0.35 * SAMPLE_RATE).toInt()
                val out = FloatArray(n)
                var phase = 0.0
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val freq = 50 + 100 * exp(-t * 25)
                    phase += 2 * PI * freq / SAMPLE_RATE
                    out[i] = (sin(phase) * exp(-t * 9)).toFloat()
                }
                out
            }
            1 -> { // Snare: noise + 190 Hz body
                val noise = noiseBuffer(0.25, 11)
                val body = sineNote(190.0, 0.25, 18.0)
                val out = FloatArray(noise.size)
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    out[i] = (noise[i] * exp(-t * 14) * 0.7f + body[i] * 0.6f)
                }
                out
            }
            2 -> { // Closed hat
                val noise = noiseBuffer(0.06, 22)
                val out = FloatArray(noise.size)
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    out[i] = (noise[i] * exp(-t * 90)).toFloat()
                }
                out
            }
            3 -> { // Open hat
                val noise = noiseBuffer(0.35, 33)
                val out = FloatArray(noise.size)
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    out[i] = (noise[i] * exp(-t * 12)).toFloat()
                }
                out
            }
            4 -> { // Clap: burst noise
                val noise = noiseBuffer(0.25, 44)
                val out = FloatArray(noise.size)
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val burst = if (t < 0.05) 1f else 0.7f
                    out[i] = (noise[i] * exp(-t * 16) * burst).toFloat()
                }
                out
            }
            5 -> { // Tom: 120 -> 80 Hz
                val n = (0.3 * SAMPLE_RATE).toInt()
                val out = FloatArray(n)
                var phase = 0.0
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val freq = 80 + 40 * exp(-t * 20)
                    phase += 2 * PI * freq / SAMPLE_RATE
                    out[i] = (sin(phase) * exp(-t * 12)).toFloat()
                }
                out
            }
            6 -> sineNote(1700.0, 0.05, 120.0) // Rim
            7 -> { // Crash: long bright noise
                val noise = noiseBuffer(1.2, 55)
                val out = FloatArray(noise.size)
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    out[i] = (noise[i] * exp(-t * 3)).toFloat()
                }
                out
            }
            in 8..15 -> { // Bass notes: sine + 2nd harmonic
                val freq = BASS_FREQS[index - 8]
                val n = (0.5 * SAMPLE_RATE).toInt()
                val out = FloatArray(n)
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val v = sin(2 * PI * freq * t) + 0.3 * sin(2 * PI * freq * 2 * t)
                    out[i] = (v * exp(-t * 5) * 0.7).toFloat()
                }
                out
            }
            SYNTH_STAB -> { // C major stab
                val n = (0.6 * SAMPLE_RATE).toInt()
                val out = FloatArray(n)
                val freqs = doubleArrayOf(261.63, 329.63, 392.00)
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    var v = 0.0
                    for (f in freqs) v += sin(2 * PI * f * t)
                    out[i] = (v / 3.0 * exp(-t * 6)).toFloat()
                }
                out
            }
            PAD_CHORD -> { // Sustained C major pad, slow attack
                val n = (2.0 * SAMPLE_RATE).toInt()
                val out = FloatArray(n)
                val freqs = doubleArrayOf(261.63, 329.63, 392.00, 523.25)
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val attack = minOf(1.0, t / 0.15)
                    var v = 0.0
                    for (f in freqs) v += sin(2 * PI * f * t)
                    out[i] = (v / 4.0 * attack * exp(-t * 1.2)).toFloat()
                }
                out
            }
            DRONE -> { // Low drone
                val n = (2.0 * SAMPLE_RATE).toInt()
                val out = FloatArray(n)
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    out[i] = ((sin(2 * PI * 55 * t) + 0.5 * sin(2 * PI * 110 * t)) * 0.4 * exp(-t * 0.8)).toFloat()
                }
                out
            }
            else -> FloatArray(0)
        }
    }

    /** Loop patterns: pad -> list of (step 0..15, one-shot sample index). */
    fun loopPattern(pad: Int): List<Pair<Int, Int>> = when (pad) {
        16 -> listOf(0 to 0, 4 to 0, 8 to 0, 12 to 0, 4 to 1, 12 to 1, 0 to 2, 2 to 2, 4 to 2, 6 to 2, 8 to 2, 10 to 2, 12 to 2, 14 to 2, 14 to 3)
        17 -> (0..15 step 2).map { it to 2 } + listOf(4 to 3, 12 to 3)
        18 -> listOf(0 to 8, 3 to 8, 6 to 12, 8 to 13, 10 to 8, 12 to 11, 14 to 12)
        19 -> listOf(0 to SYNTH_STAB, 8 to SYNTH_STAB, 0 to PAD_CHORD)
        20 -> listOf(2 to 6, 6 to 6, 10 to 6, 14 to 6, 7 to 5, 15 to 5, 4 to 4, 12 to 4)
        21 -> listOf(0 to PAD_CHORD, 8 to SYNTH_STAB)
        22 -> listOf(12 to 0, 13 to 0, 14 to 1, 15 to 5, 0 to 7)
        23 -> listOf(0 to 0, 3 to 0, 6 to 0, 10 to 0, 4 to 1, 12 to 1, 1 to 2, 5 to 2, 9 to 2, 13 to 2, 8 to DRONE)
        else -> emptyList()
    }
}

class PlaybackEngine {
    var bpm: Float = 120f
    var isPlaying: Boolean = false
        private set
    var masterVolume: Float = 0.8f
    var delayEnabled: Boolean = false

    var onStepChanged: ((Int) -> Unit)? = null
    var onLoopsChanged: ((Set<Int>) -> Unit)? = null

    // Sequencer: drum pad 0..7 -> 16 steps
    private val pattern = Array(8) { BooleanArray(16) }
    private val activeLoops = mutableSetOf<Int>()

    fun setStep(track: Int, step: Int, on: Boolean) {
        if (track in 0..7 && step in 0..15) pattern[track][step] = on
    }

    fun getStep(track: Int, step: Int): Boolean =
        if (track in 0..7 && step in 0..15) pattern[track][step] else false

    fun activeLoopPads(): Set<Int> = activeLoops.toSet()

    private data class Voice(val sample: FloatArray, var pos: Int, val volume: Float)

    private val pending = java.util.concurrent.ConcurrentLinkedQueue<Voice>()
    private val voices = mutableListOf<Voice>()
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    private var totalBeats = 0.0
    private var lastStep = -1

    // One-shot cache so taps don't re-synthesize every time.
    private val oneShotCache = mutableMapOf<Int, FloatArray>()
    private fun sample(index: Int): FloatArray =
        oneShotCache.getOrPut(index) { SampleBank.oneShot(index) }

    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            SampleBank.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = minBuf * 4
        val at = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SampleBank.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufSize)
            .build()
        track = at
        running = true
        at.play()
        thread = Thread({ mixLoop(at) }, "playrx-mixer").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        voices.clear()
        activeLoops.clear()
    }

    fun setPlaying(playing: Boolean) {
        isPlaying = playing
        if (!playing) lastStep = -1
    }

    fun stopAll() {
        voices.clear()
        activeLoops.clear()
        onLoopsChanged?.invoke(emptySet())
        isPlaying = false
        lastStep = -1
    }

    /** Tap a pad. One-shots fire immediately; loop pads toggle. */
    fun triggerPad(pad: Int) {
        if (SampleBank.isLoopPad(pad)) {
            if (activeLoops.contains(pad)) {
                activeLoops.remove(pad)
            } else {
                activeLoops.add(pad)
                if (!isPlaying) isPlaying = true
            }
            onLoopsChanged?.invoke(activeLoops.toSet())
        } else {
            pending.add(Voice(sample(pad), 0, 1f))
        }
    }

    private fun triggerSample(index: Int, volume: Float = 0.9f) {
        pending.add(Voice(sample(index), 0, volume))
    }

    private fun mixLoop(at: AudioTrack) {
        val blockFrames = 512
        val outShorts = ShortArray(blockFrames * 2)
        // 1-second delay line (mono, shared by both channels).
        val delayBuf = FloatArray(SampleBank.SAMPLE_RATE)
        var delayPos = 0

        while (running) {
            // Drain pending one-shots into the voice list.
            while (true) {
                val v = pending.poll() ?: break
                voices.add(v)
            }

            // Advance the transport clock and fire step events.
            val beatsBefore = totalBeats
            totalBeats += blockFrames.toDouble() / SampleBank.SAMPLE_RATE * (bpm / 60.0)
            if (isPlaying) {
                val step = ((totalBeats * 4).toInt() % 16 + 16) % 16
                if (step != lastStep) {
                    lastStep = step
                    onStepChanged?.invoke(step)
                    for (trk in 0..7) {
                        if (pattern[trk][step]) triggerSample(trk, 0.9f)
                    }
                    for (loopPad in activeLoops.toList()) {
                        for ((s, sampleIdx) in SampleBank.loopPattern(loopPad)) {
                            if (s == step) triggerSample(sampleIdx, 0.85f)
                        }
                    }
                    // Drain voices triggered by this step immediately.
                    while (true) {
                        val v = pending.poll() ?: break
                        voices.add(v)
                    }
                }
            }
            @Suppress("UNUSED_VARIABLE")
            val ignored = beatsBefore

            // Delay time: dotted eighth at current BPM.
            val delaySamples = ((60.0 / bpm * 0.75) * SampleBank.SAMPLE_RATE).toInt()
                .coerceIn(1, delayBuf.size - 1)

            for (frame in 0 until blockFrames) {
                var left = 0f
                var right = 0f
                val iter = voices.iterator()
                while (iter.hasNext()) {
                    val v = iter.next()
                    if (v.pos >= v.sample.size) {
                        iter.remove()
                    } else {
                        val s = v.sample[v.pos] * v.volume
                        left += s
                        right += s
                        v.pos++
                    }
                }
                var dry = (left + right) * 0.5f * masterVolume
                if (delayEnabled) {
                    val delayed = delayBuf[delayPos]
                    dry += delayed * 0.25f
                    delayBuf[delayPos] = dry * 0.35f + (left + right) * 0.5f * masterVolume * 0.65f
                    delayPos = (delayPos + 1) % delaySamples
                    // Keep delayPos within the active delay window.
                    if (delayPos >= delaySamples) delayPos = 0
                }
                val clamped = dry.coerceIn(-1f, 1f)
                val shortVal = (clamped * 32767).toInt().toShort()
                outShorts[frame * 2] = shortVal
                outShorts[frame * 2 + 1] = shortVal
            }
            at.write(outShorts, 0, outShorts.size)
        }
    }
}
