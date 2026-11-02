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

    /** Cubic (Catmull-Rom) resample: rate > 1 = shorter + higher.
     * Linear interpolation aliases badly on pitched-up material;
     * cubic keeps transients and highs far cleaner for the same cost
     * class (render-time only, never realtime). */
    fun resample(input: FloatArray, rate: Double): FloatArray {
        if (input.isEmpty() || rate <= 0.0) return FloatArray(0)
        if (rate == 1.0) return input.copyOf()
        val outLen = (input.size / rate).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in out.indices) {
            val pos = i * rate
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            val p0 = input.getOrElse(idx - 1) { input.first() }
            val p1 = input.getOrElse(idx) { 0f }
            val p2 = input.getOrElse(idx + 1) { p1 }
            val p3 = input.getOrElse(idx + 2) { p2 }
            val f2 = frac * frac
            val f3 = f2 * frac
            out[i] = (0.5f * ((2 * p1) + (-p0 + p2) * frac + (2 * p0 - 5 * p1 + 4 * p2 - p3) * f2 + (-p0 + 3 * p1 - 3 * p2 + p3) * f3))
        }
        return out
    }

    /**
     * Windowed synchronous overlap-add (WSOLA) time-stretch:
     * [rate] > 1 = faster/shorter, pitch preserved (the input is
     * already pitch-shifted if needed).
     *
     * Two fixes over the naive OLA that shipped in v1 (and that a
     * unit test caught, 2026-11-02):
     * - Fixed synthesis hop (512, 50% overlap) with the analysis
     *   hop scaled by rate, instead of a fixed analysis hop with a
     *   varying synthesis hop. The old version left gaps when
     *   slowing down (synthesis hop = grain size) and over-overlapped
     *   when speeding up, so the level throbbed even after window
     *   normalization.
     * - Pitch-period search: each grain after the first is chosen
     *   near its expected input position (±512 samples, coarse then
     *   fine) to minimize the difference between the previous
     *   grain's tail and the new grain's head in the overlap region.
     *   Without this, a tonal loop's grains land at arbitrary phase
     *   and cancel in the overlap — a sine stretched 2x dipped
     *   below 0.7 amplitude in the middle, which is exactly the
     *   "trash" quality reported on the phone.
     * Grain 1024, Hann window, window-sum normalization so the
     * output level stays flat at the edges and in the overlap.
     */
    fun timeStretch(input: FloatArray, rate: Double, grain: Int = 1024): FloatArray {
        if (input.isEmpty() || rate <= 0.0) return FloatArray(0)
        if (rate == 1.0) return input.copyOf()
        // Skip stretches within 2%: the tempo error is inaudible,
        // the stretch artifacts are not.
        if (kotlin.math.abs(rate - 1.0) < 0.02) return input.copyOf()
        val hopS = grain / 2
        val hopA = (hopS * rate).toInt().coerceAtLeast(1)
        val outLen = (input.size / rate).toInt().coerceAtLeast(grain)
        val out = FloatArray(outLen + grain)
        val winSum = FloatArray(outLen + grain)
        val window = FloatArray(grain) { i ->
            (0.5 * (1 - kotlin.math.cos(2 * PI * i / (grain - 1)))).toFloat()
        }
        fun place(readPos: Int, writePos: Int) {
            for (i in 0 until grain) {
                if (readPos + i >= input.size || writePos + i >= out.size) break
                out[writePos + i] += input[readPos + i] * window[i]
                winSum[writePos + i] += window[i]
            }
        }
        var readPos = 0
        var writePos = 0
        place(readPos, writePos)
        var prevGrainStart = readPos
        writePos += hopS
        while (writePos + grain <= out.size) {
            val expected = prevGrainStart + hopA
            if (expected + grain > input.size) break
            // Coarse search, then refine around the best candidate.
            fun errorAt(cand: Int): Double {
                var err = 0.0
                for (i in 0 until hopS) {
                    val d = (input[prevGrainStart + hopS + i] - input[cand + i]).toDouble()
                    err += d * d
                }
                return err
            }
            val lo = maxOf(0, expected - 512)
            val hi = minOf(input.size - grain, expected + 512)
            var best = expected
            var bestErr = Double.MAX_VALUE
            var c = lo
            while (c <= hi) {
                val e = errorAt(c)
                if (e < bestErr) { bestErr = e; best = c }
                c += 4
            }
            var c2 = maxOf(lo, best - 4)
            val hi2 = minOf(hi, best + 4)
            while (c2 <= hi2) {
                val e = errorAt(c2)
                if (e < bestErr) { bestErr = e; best = c2 }
                c2++
            }
            readPos = best
            place(readPos, writePos)
            prevGrainStart = readPos
            writePos += hopS
        }
        for (i in 0 until outLen) {
            val s = winSum[i]
            out[i] = if (s > 0.01f) out[i] / s else 0f
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
    /** Output rate of the mixer. Rendered buffers are always this rate. */
    const val OUTPUT_SAMPLE_RATE = 44100

    /**
     * Full render for one pad assignment: rate-convert to the mixer
     * rate, slice, cut-to-grid, reverse, fades, pitch-shift,
     * tempo-stretch, bar-length snap (LOOP only), filter, gain.
     * Pure, so the mixer can re-render on BPM or project-key change
     * without re-reading the file.
     *
     * Order matters, and two of these were the tempo bugs reported
     * 2026-11-02:
     * - Rate conversion FIRST: a 48 kHz file played at 44.1 kHz
     *   without conversion sounds ~8% slow and flat, and its tempo
     *   never matches the project. Converted buffers are always
     *   44100 Hz, so grid/bar math below is in output frames.
     * - Bar-length snap for LOOP pads: after tempo stretch, the
     *   buffer is stretched again to the nearest whole number of
     *   bars at the project BPM (min 1 bar). Without it a loop
     *   whose length is not a bar multiple drifts a little further
     *   out of phase on every repeat, even if it starts on a bar.
     */
    fun renderPadSample(
        input: FloatArray,
        assignment: PadSample,
        info: SampleInfo,
        projectBpm: Float,
        projectKeyMidi: Int?,
    ): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        val outRate = OUTPUT_SAMPLE_RATE
        // Convert the whole file to the mixer rate, then scale the
        // slice bounds into output frames. At 44100 Hz this is a no-op.
        var buf = input
        if (info.sampleRate > 0 && info.sampleRate != outRate) {
            buf = resample(input, info.sampleRate.toDouble() / outRate)
        }
        val scale = if (info.sampleRate > 0) outRate.toDouble() / info.sampleRate else 1.0
        var start = if (assignment.startFrame >= 0) (assignment.startFrame * scale).toInt() else 0
        var end = if (assignment.endFrame >= 0) (assignment.endFrame * scale).toInt() else buf.size
        start = start.coerceIn(0, buf.size)
        end = end.coerceIn(start, buf.size)
        if (assignment.cutToGrid) {
            val (s, e) = cutToGrid(start, end, outRate, projectBpm, assignment.gridDivisionBeats)
            start = s.coerceIn(0, buf.size)
            end = e.coerceIn(start, buf.size)
        }
        buf = buf.copyOfRange(start, end)
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
        // Pitch first (resample), then ONE tempo stretch (WSOLA,
        // pitch preserved). V2 combines tempo snap and bar-length
        // snap into a single stretch: v1 stretched twice in a row
        // (tempo, then bar), and every stretch adds artifacts.
        // Pitch-up also gets an anti-alias low-pass first: frequencies
        // above Nyquist/rate would otherwise fold back harshly.
        val semitones = totalTransposeSemitones(assignment, info, projectKeyMidi)
        var stretchRate = 1.0
        if (semitones != 0) {
            val pr = pitchRate(semitones)
            if (pr > 1.0) {
                buf = lowPass(buf, (outRate / 2f / pr).toFloat(), outRate)
            }
            buf = resample(buf, pr)
        }
        if (assignment.snapToTempo) {
            val rate = tempoRate(projectBpm, info.originalBpm)
            if (rate != 1.0) stretchRate = rate
        }
        // LOOP bar snap, folded into the same stretch: compute the
        // length after the tempo stretch, round it to whole bars,
        // and stretch once to that length instead of twice.
        val grain = if (info.category == SampleCategory.DRUM) 1024 else 2048
        if (assignment.playMode == PlayMode.LOOP && projectBpm > 0f && buf.size > 4096) {
            val afterTempo = (buf.size / stretchRate).toInt()
            val barFrames = gridFrames(outRate, projectBpm, 4f)
            if (barFrames > 0) {
                val bars = maxOf(1, ((afterTempo.toDouble() / barFrames) + 0.5).toInt())
                val target = bars * barFrames
                if (kotlin.math.abs(target - afterTempo) > afterTempo * 0.02) {
                    stretchRate = buf.size.toDouble() / target
                }
            }
        }
        if (stretchRate != 1.0) buf = timeStretch(buf, stretchRate, grain)
        // LOOP wrap fade, after stretching, so repeats do not click.
        if (assignment.playMode == PlayMode.LOOP && buf.size > 4096) {
            val fade = minOf(256, buf.size / 4)
            for (i in 0 until fade) {
                val f = i.toFloat() / fade
                buf[i] *= f
                buf[buf.size - 1 - i] *= f
            }
        }
        buf = lowPass(buf, assignment.filterCutoffHz, outRate)
        if (assignment.gain != 1f) {
            for (i in buf.indices) buf[i] *= assignment.gain
        }
        return buf
    }


    /**
     * Autodetect a sample's tempo (2026-11-02, user request).
     * Returns BPM in 60..200, or null when no clear tempo exists
     * (one-shot, pad, noise, too short). Never throws.
     *
     * Method, deliberately simple and loop-shaped:
     * - Low-pass the audio (one-pole, ~200 Hz) so kick/bass — which
     *   sit on beats — dominate over hats, which sit on eighths and
     *   would otherwise pull the answer to double tempo.
     * - RMS envelope, hop 512 samples. Autocorrelate the envelope
     *   for beat lags 60..200 BPM. Take the strongest peak, then
     *   prefer the earliest peak within 90% of it (so a 2-beat
     *   repetition does not win over the beat itself).
     * - Bar snap for loops: if the loop's duration is close to a
     *   whole number of bars at a nearby BPM (4/8/16/32 beats,
     *   within 5%), snap to that BPM. Loops are almost always whole
     *   bars, and this corrects small autocorrelation errors.
     * - A full-band envelope is tried too; the stronger peak wins.
     * Analysis is capped at the first 30 seconds so a long file
     * does not stall the import.
     */
    /**
     * Autodetect a sample's tempo (2026-11-02, v2 2026-11-02).
     * Returns BPM in 60..200, or null when no clear tempo exists
     * (one-shot, pad, noise, too short). Never throws.
     *
     * Why v2: v1 used only an RMS envelope. On real loops that
     * failed in two ways — eighth-note hats pulled it to double
     * tempo, and a flat tone's tiny RMS ripple invented a tempo
     * (fixed by a variation check, but the envelope itself was
     * still weak for melodic loops with no kick). V2 combines three
     * onset envelopes and lets them vote:
     * - Low-pass RMS (kick/bass, ~200 Hz one-pole): beats, not hats.
     * - Full-band RMS: general energy onsets.
     * - Spectral flux (STFT, Hann 1024, hop 512): magnitude increases
     *   across bins — responds to pitch changes and drum hits even
     *   when the overall level barely moves (melodic loops).
     * Each envelope is autocorrelated for beat lags 60..200 BPM.
     * Candidates within 4 BPM cluster together and their peaks sum;
     * the strongest cluster wins. If no cluster forms, the strongest
     * single candidate wins, but the low-pass (kick) candidate wins
     * any near-tie (within 10% of the best peak) — kick onsets are
     * the beat, hat onsets are the subdivision.
     * Analysis uses the loudest 30-second segment for long files
     * (a song's quiet intro must not set the tempo), and the whole
     * file for loops (almost always under 30 s). A whole-bar snap
     * (within 3%, tighter than v1's 5%) still applies to loops.
     * Tempo is inherently ambiguous for half-time/double-time feels;
     * the EDIT panel's x2 / /2 buttons exist for exactly that case,
     * and manual edit is never removed.
     */
    fun detectBpm(frames: FloatArray, sampleRate: Int): Float? {
        if (frames.isEmpty() || sampleRate <= 0) return null
        if (frames.size < sampleRate) return null // under 1 second
        // Loudest 30-second segment for long files.
        val maxFrames = sampleRate * 30
        val data: FloatArray = if (frames.size <= maxFrames) {
            frames
        } else {
            val block = sampleRate * 5
            var bestBlock = 0
            var bestRms = -1.0
            var b = 0
            while (b * block < frames.size) {
                val start = b * block
                val end = minOf(frames.size, start + block)
                var sum = 0.0
                for (i in start until end) sum += (frames[i] * frames[i]).toDouble()
                val rms = sum / (end - start).coerceAtLeast(1)
                if (rms > bestRms) { bestRms = rms; bestBlock = b }
                b++
            }
            val segStart = (bestBlock * block).coerceAtMost(frames.size - sampleRate * 10)
            val segEnd = minOf(frames.size, segStart + maxFrames)
            if (segEnd - segStart >= sampleRate * 10) frames.copyOfRange(segStart, segEnd) else frames.copyOfRange(0, maxFrames)
        }
        val hop = 512
        fun rmsEnvelope(lowPass: Boolean): FloatArray {
            val win = 512
            val n = maxOf(1, (data.size - win) / hop + 1)
            val env = FloatArray(n)
            var prev = 0f
            val alpha = if (lowPass) {
                val rc = 1.0 / (2 * PI * 200.0)
                val dt = 1.0 / sampleRate
                (dt / (rc + dt)).toFloat()
            } else 1f
            for (h in 0 until n) {
                var sum = 0.0
                for (i in 0 until win) {
                    var v = data[h * hop + i]
                    if (lowPass) {
                        prev += alpha * (v - prev)
                        v = prev
                    }
                    sum += (v * v).toDouble()
                }
                env[h] = kotlin.math.sqrt(sum / win).toFloat()
            }
            return finishEnvelope(env)
        }
        fun fluxEnvelope(): FloatArray {
            // STFT magnitude flux, Hann 1024, hop 512. Pure Kotlin FFT.
            val fftSize = 1024
            val nFrames = maxOf(1, (data.size - fftSize) / hop + 1)
            val env = FloatArray(nFrames)
            val window = DoubleArray(fftSize) { i ->
                0.5 * (1 - kotlin.math.cos(2 * PI * i / (fftSize - 1)))
            }
            var prevMag = DoubleArray(fftSize / 2 + 1)
            for (f in 0 until nFrames) {
                val re = DoubleArray(fftSize)
                val im = DoubleArray(fftSize)
                for (i in 0 until fftSize) {
                    re[i] = data[f * hop + i].toDouble() * window[i]
                }
                fft(re, im)
                var flux = 0.0
                for (k in 0..fftSize / 2) {
                    val mag = kotlin.math.sqrt(re[k] * re[k] + im[k] * im[k])
                    val diff = mag - prevMag[k]
                    if (diff > 0) flux += diff
                    prevMag[k] = mag
                }
                env[f] = flux.toFloat()
            }
            return finishEnvelope(env)
        }
        fun autocorrPeak(env: FloatArray): Pair<Float, Float>? {
            val hopsPerSecond = sampleRate.toDouble() / hop
            val lagMin = (hopsPerSecond * 60.0 / 200.0).toInt().coerceAtLeast(1)
            val lagMax = (hopsPerSecond * 60.0 / 60.0).toInt()
            if (env.size <= lagMax + 4) return null
            var varSum = 0.0
            for (v in env) varSum += (v * v).toDouble()
            if (varSum <= 0.0) return null
            val ac = DoubleArray(lagMax + 1)
            for (lag in lagMin..lagMax) {
                var s = 0.0
                for (i in 0 until env.size - lag) {
                    s += env[i].toDouble() * env[i + lag].toDouble()
                }
                ac[lag] = s / varSum
            }
            var maxVal = 0.0
            for (lag in lagMin..lagMax) if (ac[lag] > maxVal) maxVal = ac[lag]
            if (maxVal < 0.25) return null
            var bestLag = lagMin
            for (lag in lagMin..lagMax) {
                if (ac[lag] >= maxVal * 0.9) { bestLag = lag; break }
            }
            var refined = bestLag.toDouble()
            if (bestLag > lagMin && bestLag < lagMax) {
                val a = ac[bestLag - 1]; val b = ac[bestLag]; val c = ac[bestLag + 1]
                val denom = a - 2 * b + c
                if (denom != 0.0) refined += 0.5 * (a - c) / denom
            }
            val bpm = (60.0 * hopsPerSecond / refined).toFloat()
            if (bpm < 60f || bpm > 200f) return null
            return bpm to maxVal.toFloat()
        }
        val candidates = mutableListOf<Pair<Float, Float>>()
        val low = autocorrPeak(rmsEnvelope(lowPass = true))
        val full = autocorrPeak(rmsEnvelope(lowPass = false))
        val flux = autocorrPeak(fluxEnvelope())
        if (low != null) candidates.add(low)
        if (full != null) candidates.add(full)
        if (flux != null) candidates.add(flux)
        if (candidates.isEmpty()) return null
        // Cluster candidates within 4 BPM, sum peaks per cluster.
        val clusters = mutableListOf<MutableList<Pair<Float, Float>>>()
        for (cand in candidates.sortedBy { it.first }) {
            val cluster = clusters.firstOrNull { kotlin.math.abs(it[0].first - cand.first) <= 4f }
            if (cluster != null) cluster.add(cand) else clusters.add(mutableListOf(cand))
        }
        val bestCluster = clusters.maxByOrNull { cluster -> cluster.sumOf { it.second.toDouble() } }!!
        var bpm = (bestCluster.sumOf { (it.first * it.second).toDouble() } / bestCluster.sumOf { it.second.toDouble() }).toFloat()
        // Kick tie-break: if the low-pass candidate is within 10% of
        // the best peak but was outvoted by a subdivision cluster,
        // trust the kick — it marks beats, not subdivisions.
        val bestPeak = candidates.maxOf { it.second }
        if (low != null && low.second >= bestPeak * 0.9f && kotlin.math.abs(low.first - bpm) > 4f) {
            bpm = low.first
        }
        // Bar snap, tightened to 3%: loops are whole bars.
        val durationSec = data.size.toDouble() / sampleRate
        var snapped = bpm
        var bestErr = 0.03f
        for (beats in listOf(4, 8, 16, 32, 64)) {
            val candidate = (beats * 60.0 / durationSec).toFloat()
            if (candidate < 60f || candidate > 200f) continue
            val err = kotlin.math.abs(candidate - bpm) / bpm
            if (err < bestErr) { bestErr = err; snapped = candidate }
        }
        return snapped
    }

    /** Mean-removal + flat-envelope guard, shared by all envelopes. */
    private fun finishEnvelope(env: FloatArray): FloatArray {
        if (env.isEmpty()) return env
        val mean = env.average().toFloat()
        var varSum = 0.0
        for (v in env) varSum += (v - mean) * (v - mean)
        val std = kotlin.math.sqrt(varSum / env.size)
        if (mean > 0f && std < 0.05f * mean) return FloatArray(env.size)
        for (i in env.indices) env[i] -= mean
        return env
    }

    /** Iterative radix-2 FFT, in place on [re]/[im]. Size must be a power of two. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = kotlin.math.cos(ang)
            val wi = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var wpr = 1.0
                var wpi = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * wpr - im[i + k + len / 2] * wpi
                    val vi = re[i + k + len / 2] * wpi + im[i + k + len / 2] * wpr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nwr = wpr * wr - wpi * wi
                    wpi = wpr * wi + wpi * wr
                    wpr = nwr
                }
                i += len
            }
            len *= 2
        }
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
