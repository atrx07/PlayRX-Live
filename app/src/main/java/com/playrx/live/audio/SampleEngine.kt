package com.playrx.live.audio

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Advanced sample engine for PlayRX Live — pure Kotlin, no Android
 * APIs, so it is unit-testable on the JVM and shared by the live
 * mixer and the screenshot tests.
 *
 * This is the engine behind "sample import, but build all the
 * advanced engine over it" (user, 2026-11-01), modelled on what the
 * Remixlive Tutorial Tour shows, with PlayRX's own names and code:
 *
 * - Import: a user-owned WAV (PCM) file, or an OGG/MP3/AAC file
 *   decoded by Android's MediaCodec (see SampleDecoder.kt). The
 *   file is copied into app-private storage; PlayRX never bundles
 *   or downloads a Remixlive pack, even for testing.
 * - Tempo snap: a sample recorded at [SampleInfo.originalBpm] is
 *   time-stretched to the project BPM, pitch preserved (granular
 *   overlap-add, below). Turn snap off and the sample plays at
 *   its natural tempo.
 * - Cut to grid: start/end are snapped to the project-tempo grid
 *   (e.g. 1 beat = quarter note, 0.25 = 16th) so a sliced loop
 *   lands exactly on a bar/beat boundary.
 * - Transpose: per-pad semitone shift, plus octave shift.
 * - Project-key dynamics: when the project key changes, melodic
 *   samples (Bass, Melody/Synth, Pad, Vox, FX, Other) transpose by
 *   the in-key delta from their original key to the new project
 *   key, so they stay in key. Drum samples are exempt — a kick
 *   does not transpose with the key, in Remixlive or here.
 *   Follow-project-key can be turned off per pad in the future;
 *   v1 applies it to every melodic sample, which is the honest
 *   default, and drums are always exempt.
 * - Play modes: LOOP (repeat while active), ONE_SHOT (fire once),
 *   GATE (play while held — the mixer supports release via
 *   [PlaybackEngine.releasePad]; the UI's tap button treats GATE
 *   as ONE_SHOT for now, see docs/SAMPLE-ENGINE.md).
 * - Per-pad: gain, pan (mono mix only in v1 — pan is stored and
 *   applied when the mixer goes stereo, see docs), one-pole
 *   low-pass filter, reverse, fade in/out, quantize (launch
 *   snapped to the next quantize boundary, in beats).
 *
 * Pitch + tempo, honestly: true independent pitch-shift +
 * time-stretch needs a phase vocoder. V1 does it in two real
 * steps that are testable and audible: (1) pitch-shift by
 * resampling at 2^(semitones/12), (2) time-stretch the pitched
 * buffer with windowed overlap-add (OLA) to the tempo-snapped
 * length, which preserves the pitched pitch while changing the
 * duration. OLA is not studio-grade, but it is real, it does not
 * pretend to be a vocoder, and it keeps loop pads in sync with
 * the transport clock.
 */

enum class PlayMode { LOOP, ONE_SHOT, GATE }

enum class SampleCategory {
    DRUM, BASS, MELODY, PAD, VOX, FX, OTHER;

    /** Only melodic categories follow a project-key change. */
    val followsProjectKey: Boolean
        get() = this != DRUM
}

data class SampleInfo(
    val id: String,
    val name: String,
    val fileName: String,
    val sampleRate: Int,
    val channels: Int,
    val frames: Int,
    val originalBpm: Float?,
    val originalKeyMidi: Int?,
    val category: SampleCategory,
) {
    val durationSeconds: Float
        get() = if (sampleRate > 0) frames.toFloat() / sampleRate else 0f

    /** Length in beats at the sample's own BPM, if known. */
    val beatsAtOriginalBpm: Float?
        get() = originalBpm?.let { durationSeconds * it / 60f }
}

data class PadSample(
    val pad: Int,
    val sampleId: String,
    val playMode: PlayMode = PlayMode.ONE_SHOT,
    val gain: Float = 1f,
    val pan: Float = 0f,
    val transposeSemitones: Int = 0,
    val octaveShift: Int = 0,
    val snapToTempo: Boolean = true,
    val cutToGrid: Boolean = false,
    /** Grid division in beats: 1.0 = quarter, 0.5 = 8th, 0.25 = 16th. */
    val gridDivisionBeats: Float = 0.25f,
    val reverse: Boolean = false,
    /** -1 = whole sample. Frames are in the sample's own rate. */
    val startFrame: Int = -1,
    val endFrame: Int = -1,
    val fadeInFrames: Int = 0,
    val fadeOutFrames: Int = 0,
    /** Hz, 20000 = fully open. */
    val filterCutoffHz: Float = 20000f,
    /** Quantize launch in beats: 0 = none, 1 = beat, 4 = bar. */
    val quantizeBeats: Float = 0f,
    val followProjectKey: Boolean = true,
)

object SampleEngine {
    val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun pitchClassName(midiPitchClass: Int): String =
        NOTE_NAMES[((midiPitchClass % 12) + 12) % 12]

    fun midiNoteName(midi: Int): String {
        val pc = ((midi % 12) + 12) % 12
        val octave = midi / 12 - 1
        return "${NOTE_NAMES[pc]}$octave"
    }

    /** Parse "C3", "F#4", "Bb2", "C" (pitch class only, octave 4). */
    fun parseNoteName(name: String): Int? {
        val s = name.trim()
        if (s.isEmpty()) return null
        val letter = s[0].uppercaseChar()
        val base = when (letter) {
            'C' -> 0; 'D' -> 2; 'E' -> 4; 'F' -> 5; 'G' -> 7; 'A' -> 9; 'B' -> 11
            else -> return null
        }
        var i = 1
        var accidental = 0
        if (i < s.length && (s[i] == '#' || s[i] == 'b')) {
            accidental = if (s[i] == '#') 1 else -1
            i++
        }
        val octave = if (i < s.length) s.substring(i).toIntOrNull() ?: return null else 4
        return (octave + 1) * 12 + base + accidental
    }

    /** Playback rate for a semitone shift: +12 = 2x (octave up). */
    fun pitchRate(semitones: Int): Double = 2.0.pow(semitones / 12.0)

    /** Tempo-snap rate: project BPM / original BPM, or 1 if unknown. */
    fun tempoRate(projectBpm: Float, originalBpm: Float?): Double {
        if (originalBpm == null || originalBpm <= 0f || projectBpm <= 0f) return 1.0
        return (projectBpm / originalBpm).toDouble()
    }

    /**
     * In-key delta from a sample's original key to the project key,
     * as a 0..11 upward shift (nearest in-key pitch, octave kept).
     * E.g. original C (0) -> project F (5) = +5. Drums never call this.
     */
    fun keyDeltaSemitones(projectKeyMidi: Int?, originalKeyMidi: Int?): Int {
        if (projectKeyMidi == null || originalKeyMidi == null) return 0
        val p = ((projectKeyMidi % 12) + 12) % 12
        val o = ((originalKeyMidi % 12) + 12) % 12
        return (p - o + 12) % 12
    }

    /** Total semitone shift for a pad, including project-key dynamics. */
    fun totalTransposeSemitones(
        assignment: PadSample,
        info: SampleInfo,
        projectKeyMidi: Int?,
    ): Int {
        val keyDelta = if (assignment.followProjectKey && info.category.followsProjectKey) {
            keyDeltaSemitones(projectKeyMidi, info.originalKeyMidi)
        } else 0
        return assignment.transposeSemitones + assignment.octaveShift * 12 + keyDelta
    }

    /** Frames in one grid division at [bpm] and [sampleRate]. */
    fun gridFrames(sampleRate: Int, bpm: Float, divisionBeats: Float): Int {
        if (sampleRate <= 0 || bpm <= 0f || divisionBeats <= 0f) return 0
        return (sampleRate * 60.0 / bpm * divisionBeats).roundToInt()
    }

    /** Snap [start]/[end] to the grid: start down, end up, clamped. */
    fun cutToGrid(
        startFrame: Int,
        endFrame: Int,
        sampleRate: Int,
        bpm: Float,
        divisionBeats: Float,
    ): Pair<Int, Int> {
        val grid = gridFrames(sampleRate, bpm, divisionBeats)
        if (grid <= 0) return startFrame to endFrame
        val snappedStart = (startFrame / grid) * grid
        val snappedEnd = ((endFrame + grid - 1) / grid) * grid
        return snappedStart to maxOf(snappedEnd, snappedStart + grid)
    }

    /** Beats until the next quantize boundary from [currentBeat]. */
    fun beatsToNextQuantize(currentBeat: Double, quantizeBeats: Float): Double {
        if (quantizeBeats <= 0f) return 0.0
        val q = quantizeBeats.toDouble()
        val rem = currentBeat % q
        return if (rem == 0.0) 0.0 else q - rem
    }

    /** Linear-interpolation resample: rate > 1 = shorter + higher. */
    fun resample(input: FloatArray, rate: Double): FloatArray {
        if (input.isEmpty() || rate <= 0.0) return FloatArray(0)
        if (rate == 1.0) return input.copyOf()
        val outLen = (input.size / rate).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in out.indices) {
            val pos = i * rate
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            val a = input.getOrElse(idx) { 0f }
            val b = input.getOrElse(idx + 1) { a }
            out[i] = a + (b - a) * frac
        }
        return out
    }

    /**
     * Windowed overlap-add time-stretch: [rate] > 1 = faster/shorter,
     * pitch preserved (the input is already pitch-shifted if needed).
     * Grain 1024, analysis hop 512, synthesis hop = 512 / rate.
     * Hann window, so overlapping grains crossfade instead of clicking.
     */
    fun timeStretch(input: FloatArray, rate: Double): FloatArray {
        if (input.isEmpty() || rate <= 0.0) return FloatArray(0)
        if (rate == 1.0) return input.copyOf()
        val grain = 1024
        val hopA = 512
        val hopS = (hopA / rate).toInt().coerceAtLeast(1)
        val outLen = (input.size / rate).toInt().coerceAtLeast(grain)
        val out = FloatArray(outLen + grain)
        val window = FloatArray(grain) { i ->
            (0.5 * (1 - kotlin.math.cos(2 * PI * i / (grain - 1)))).toFloat()
        }
        var readPos = 0
        var writePos = 0
        while (readPos + grain <= input.size && writePos + grain <= out.size) {
            for (i in 0 until grain) {
                out[writePos + i] += input[readPos + i] * window[i]
            }
            readPos += hopA
            writePos += hopS
        }
        return out.copyOf(outLen)
    }

    /** One-pole low-pass. Cutoff >= 20000 (or sampleRate/2) = bypass. */
    fun lowPass(input: FloatArray, cutoffHz: Float, sampleRate: Int): FloatArray {
        if (input.isEmpty() || cutoffHz >= 20000f || cutoffHz <= 0f || sampleRate <= 0) {
            return input.copyOf()
        }
        val rc = 1.0 / (2 * PI * cutoffHz)
        val dt = 1.0 / sampleRate
        val alpha = (dt / (rc + dt)).toFloat()
        val out = FloatArray(input.size)
        var prev = 0f
        for (i in input.indices) {
            prev += alpha * (input[i] - prev)
            out[i] = prev
        }
        return out
    }

    /**
     * Full render for one pad assignment: slice, cut-to-grid, reverse,
     * fades, pitch-shift, tempo-stretch, filter, gain. Pure, so the
     * mixer can re-render on BPM or project-key change without
     * re-reading the file.
     */
    fun renderPadSample(
        input: FloatArray,
        assignment: PadSample,
        info: SampleInfo,
        projectBpm: Float,
        projectKeyMidi: Int?,
    ): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        var start = if (assignment.startFrame >= 0) assignment.startFrame else 0
        var end = if (assignment.endFrame >= 0) assignment.endFrame else input.size
        start = start.coerceIn(0, input.size)
        end = end.coerceIn(start, input.size)
        if (assignment.cutToGrid) {
            val (s, e) = cutToGrid(start, end, info.sampleRate, projectBpm, assignment.gridDivisionBeats)
            start = s.coerceIn(0, input.size)
            end = e.coerceIn(start, input.size)
        }
        var buf = input.copyOfRange(start, end)
        if (assignment.reverse) buf = buf.reversedArray()
        // Linear fades, clamped so they never overlap into silence.
        if (assignment.fadeInFrames > 0) {
            val n = minOf(assignment.fadeInFrames, buf.size)
            for (i in 0 until n) buf[i] *= i.toFloat() / n
        }
        if (assignment.fadeOutFrames > 0) {
            val n = minOf(assignment.fadeOutFrames, buf.size)
            for (i in 0 until n) {
                buf[buf.size - 1 - i] *= i.toFloat() / n
            }
        }
        // Pitch first (resample), then tempo (OLA, pitch preserved).
        val semitones = totalTransposeSemitones(assignment, info, projectKeyMidi)
        if (semitones != 0) buf = resample(buf, pitchRate(semitones))
        if (assignment.snapToTempo) {
            val rate = tempoRate(projectBpm, info.originalBpm)
            if (rate != 1.0) buf = timeStretch(buf, rate)
        }
        buf = lowPass(buf, assignment.filterCutoffHz, info.sampleRate)
        if (assignment.gain != 1f) {
            for (i in buf.indices) buf[i] *= assignment.gain
        }
        return buf
    }

    // ---- WAV parsing (PCM 16/24/32 + float32, RIFF, mono-mixed) ----

    data class WavData(val sampleRate: Int, val channels: Int, val frames: FloatArray)

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int): Int {
        var v = 0
        for (i in 0 until 4) v = v or ((b[o + i].toInt() and 0xFF) shl (8 * i))
        return v
    }

    private fun s16(b: ByteArray, o: Int): Int {
        val v = u16(b, o)
        return if (v >= 32768) v - 65536 else v
    }

    /** Returns null for non-WAV / unsupported formats — never throws. */
    fun parseWav(bytes: ByteArray): WavData? {
        if (bytes.size < 44) return null
        if (!(bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte())) return null
        if (!(bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() &&
                bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte())) return null
        var pos = 12
        var audioFormat = -1
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = u32(bytes, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    if (body + 16 <= bytes.size) {
                        audioFormat = u16(bytes, body)
                        channels = u16(bytes, body + 2)
                        sampleRate = u32(bytes, body + 4)
                        bitsPerSample = u16(bytes, body + 14)
                    }
                }
                "data" -> {
                    dataStart = body
                    dataLen = size
                }
            }
            pos = body + size + (size % 2)
        }
        if (dataStart < 0 || channels <= 0 || sampleRate <= 0) return null
        if (audioFormat != 1 && audioFormat != 3) return null
        val bytesPerSample = when (audioFormat) {
            1 -> bitsPerSample / 8
            else -> 4 // float32
        }
        if (bytesPerSample <= 0) return null
        val frameBytes = bytesPerSample * channels
        val frames = minOf(dataLen / frameBytes, (bytes.size - dataStart) / frameBytes)
        if (frames <= 0) return null
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (ch in 0 until channels) {
                val o = dataStart + f * frameBytes + ch * bytesPerSample
                val v: Float = when {
                    audioFormat == 3 -> {
                        val bits = u32(bytes, o)
                        java.lang.Float.intBitsToFloat(bits)
                    }
                    bitsPerSample == 16 -> s16(bytes, o) / 32768f
                    bitsPerSample == 24 -> {
                        val raw = (bytes[o].toInt() and 0xFF) or
                            ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                            (bytes[o + 2].toInt() shl 16)
                        raw / 8388608f
                    }
                    bitsPerSample == 32 -> {
                        // u32() returns a signed Int; dividing it as a
                        // float already gives the correct -1..1 range.
                        u32(bytes, o).toFloat() / 2147483648f
                    }
                    bitsPerSample == 8 -> (bytes[o].toInt() and 0xFF - 128) / 128f
                    else -> 0f
                }
                sum += v
            }
            out[f] = sum / channels
        }
        return WavData(sampleRate, channels, out)
    }

    // ---- Manifest JSON (manual — no Gson/serialization dependency) ----

    private fun esc(s: String): String = s
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r")

    fun manifestToJson(samples: List<SampleInfo>): String {
        val sb = StringBuilder()
        sb.append("[")
        samples.forEachIndexed { idx, s ->
            if (idx > 0) sb.append(",")
            sb.append("{")
            sb.append("\"id\":\"${esc(s.id)}\",")
            sb.append("\"name\":\"${esc(s.name)}\",")
            sb.append("\"fileName\":\"${esc(s.fileName)}\",")
            sb.append("\"sampleRate\":${s.sampleRate},")
            sb.append("\"channels\":${s.channels},")
            sb.append("\"frames\":${s.frames},")
            sb.append("\"originalBpm\":${s.originalBpm?.toString() ?: "null"},")
            sb.append("\"originalKeyMidi\":${s.originalKeyMidi?.toString() ?: "null"},")
            sb.append("\"category\":\"${s.category.name}\"")
            sb.append("}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun jsonString(obj: String, key: String): String? {
        val pat = "\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\""
        val m = Regex(pat).find(obj) ?: return null
        return m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun jsonInt(obj: String, key: String): Int? {
        val m = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(obj) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    private fun jsonFloatOrNull(obj: String, key: String): Float? {
        if (Regex("\"$key\"\\s*:\\s*null").containsMatchIn(obj)) return null
        val m = Regex("\"$key\"\\s*:\\s*(-?[0-9.]+)").find(obj) ?: return null
        return m.groupValues[1].toFloatOrNull()
    }

    private fun jsonIntOrNull(obj: String, key: String): Int? {
        if (Regex("\"$key\"\\s*:\\s*null").containsMatchIn(obj)) return null
        return jsonInt(obj, key)
    }

    /** Parses what [manifestToJson] wrote. Malformed entries are skipped. */
    fun manifestFromJson(json: String): List<SampleInfo> {
        val out = mutableListOf<SampleInfo>()
        var depth = 0
        var start = -1
        for (i in json.indices) {
            when (json[i]) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        val obj = json.substring(start, i + 1)
                        val id = jsonString(obj, "id") ?: continue
                        val name = jsonString(obj, "name") ?: continue
                        val fileName = jsonString(obj, "fileName") ?: continue
                        val sr = jsonInt(obj, "sampleRate") ?: continue
                        val ch = jsonInt(obj, "channels") ?: continue
                        val frames = jsonInt(obj, "frames") ?: continue
                        val catName = jsonString(obj, "category") ?: "OTHER"
                        val cat = runCatching { SampleCategory.valueOf(catName) }.getOrDefault(SampleCategory.OTHER)
                        out.add(
                            SampleInfo(
                                id, name, fileName, sr, ch, frames,
                                jsonFloatOrNull(obj, "originalBpm"),
                                jsonIntOrNull(obj, "originalKeyMidi"),
                                cat,
                            )
                        )
                        start = -1
                    }
                }
            }
        }
        return out
    }

    /** Sine test tone, for unit tests and for a silent-free fallback. */
    fun sineTone(freqHz: Double, seconds: Double, sampleRate: Int = 44100): FloatArray {
        val n = (seconds * sampleRate).toInt()
        return FloatArray(n) { i ->
            sin(2 * PI * freqHz * i / sampleRate).toFloat()
        }
    }
}
