package com.playrx.live.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Unit tests for SampleEngine — pure Kotlin, no Android, no device.
 * These prove the advanced import engine's math, not just its UI:
 * tempo snap, cut-to-grid, transpose, project-key dynamics, reverse,
 * manifest round-trip, and WAV parsing.
 */
class SampleEngineTest {

    private fun info(bpm: Float? = 120f, key: Int? = 60, cat: SampleCategory = SampleCategory.MELODY): SampleInfo =
        SampleInfo("id1", "test.wav", "test.wav", 44100, 1, 44100, bpm, key, cat)

    private fun assignment(): PadSample =
        PadSample(pad = 0, sampleId = "id1")

    @Test
    fun tempoRate_doublesAtDoubleBpm() {
        assertEquals(2.0, SampleEngine.tempoRate(240f, 120f), 0.001)
        assertEquals(0.5, SampleEngine.tempoRate(60f, 120f), 0.001)
        assertEquals(1.0, SampleEngine.tempoRate(120f, null), 0.001)
    }

    @Test
    fun pitchRate_octaveIsTwo() {
        assertEquals(2.0, SampleEngine.pitchRate(12), 0.001)
        assertEquals(0.5, SampleEngine.pitchRate(-12), 0.001)
        assertEquals(1.0, SampleEngine.pitchRate(0), 0.001)
    }

    @Test
    fun keyDelta_cToFIsFive_andDrumsExempt() {
        // C (0) -> F (5)
        assertEquals(5, SampleEngine.keyDeltaSemitones(5, 0))
        // C -> C is 0, not 12
        assertEquals(0, SampleEngine.keyDeltaSemitones(0, 0))
        // Total transpose: melodic follows key, drum does not.
        val melodic = SampleEngine.totalTransposeSemitones(assignment(), info(key = 60), 65)
        assertEquals(5, melodic)
        val drumInfo = info(key = 60, cat = SampleCategory.DRUM)
        val drum = SampleEngine.totalTransposeSemitones(assignment(), drumInfo, 65)
        assertEquals(0, drum)
        // User transpose still applies on top of key dynamics.
        val withUser = SampleEngine.totalTransposeSemitones(
            assignment().copy(transposeSemitones = 2), info(key = 60), 65
        )
        assertEquals(7, withUser)
    }

    @Test
    fun cutToGrid_snapsStartDownEndUp() {
        // 120 BPM, 44100 Hz: a beat is 22050 frames, a 16th is
        // 5512.5 -> 5513 frames. Start snaps down, end snaps up.
        val (s, e) = SampleEngine.cutToGrid(12000, 23000, 44100, 120f, 0.25f)
        assertEquals(11026, s)
        assertEquals(27565, e)
    }

    @Test
    fun gridFrames_quarterAt120IsHalfSecond() {
        // 120 BPM: a beat lasts 60/120 = 0.5 s = 22050 frames.
        assertEquals(22050, SampleEngine.gridFrames(44100, 120f, 1.0f))
        assertEquals(5513, SampleEngine.gridFrames(44100, 120f, 0.25f))
    }

    @Test
    fun beatsToNextQuantize() {
        assertEquals(0.0, SampleEngine.beatsToNextQuantize(4.0, 1f), 0.001)
        assertEquals(0.5, SampleEngine.beatsToNextQuantize(3.5, 1f), 0.001)
        assertEquals(2.0, SampleEngine.beatsToNextQuantize(2.0, 4f), 0.001)
    }

    @Test
    fun render_reverseAndTempoSnap() {
        val input = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f)
        val reversed = SampleEngine.renderPadSample(
            input, assignment().copy(reverse = true, snapToTempo = false),
            info(), 120f, 0
        )
        assertEquals(8f, reversed[0], 0.001f)
        assertEquals(1f, reversed[7], 0.001f)
        // Tempo snap 120 -> 240 should roughly halve the length (OLA grain tolerance).
        val fast = SampleEngine.renderPadSample(
            SampleEngine.sineTone(440.0, 1.0),
            assignment().copy(snapToTempo = true),
            info(bpm = 120f), 240f, 0
        )
        assertTrue("fast length ${fast.size} should be < 44100", fast.size < 44100)
        assertTrue("fast length ${fast.size} should be > 15000", fast.size > 15000)
    }

    @Test
    fun render_transposeShortensBuffer() {
        val input = SampleEngine.sineTone(440.0, 0.5)
        val up = SampleEngine.renderPadSample(
            input, assignment().copy(transposeSemitones = 12, snapToTempo = false),
            info(), 120f, 0
        )
        // Octave up via resample = half length, within rounding.
        assertTrue(abs(up.size - input.size / 2) < 10)
    }

    @Test
    fun parseNoteName() {
        assertEquals(60, SampleEngine.parseNoteName("C4"))
        assertEquals(61, SampleEngine.parseNoteName("C#4"))
        assertEquals(58, SampleEngine.parseNoteName("Bb3"))
        assertNull(SampleEngine.parseNoteName("Z9"))
        assertEquals("C", SampleEngine.pitchClassName(60))
        assertEquals("F", SampleEngine.pitchClassName(65))
    }

    @Test
    fun manifestRoundTrip() {
        val samples = listOf(info(), info(bpm = null, key = null, cat = SampleCategory.DRUM).copy(id = "id2", name = "kick.wav"))
        val json = SampleEngine.manifestToJson(samples)
        val back = SampleEngine.manifestFromJson(json)
        assertEquals(2, back.size)
        assertEquals("test.wav", back[0].name)
        assertEquals(null, back[1].originalBpm)
        assertEquals(SampleCategory.DRUM, back[1].category)
    }

    @Test
    fun parseWav_pcm16Mono() {
        // Build a minimal 16-bit mono WAV: 4 frames, values 0, 16384, -16384, 32767.
        val pcm = shortArrayOf(0, 16384, -16384, 32767)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + 8)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // mono
        header.putInt(44100)
        header.putInt(44100 * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(8)
        val body = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { body.putShort(it) }
        val bytes = header.array() + body.array()
        val wav = SampleEngine.parseWav(bytes)
        assertNotNull(wav)
        assertEquals(44100, wav!!.sampleRate)
        assertEquals(4, wav.frames.size)
        assertEquals(0f, wav.frames[0], 0.001f)
        assertEquals(0.5f, wav.frames[1], 0.001f)
        assertEquals(-0.5f, wav.frames[2], 0.001f)
    }

    @Test
    fun timeStretch_normalizedLevelStaysFlat() {
        // Without window-sum normalization, a 2x stretch throbs and
        // peaks well above 1. With it, a sine stays near its level.
        val input = SampleEngine.sineTone(440.0, 1.0)
        val stretched = SampleEngine.timeStretch(input, 2.0)
        val middle = stretched.sliceArray(stretched.size / 4 until stretched.size * 3 / 4)
        val peak = middle.maxOf { kotlin.math.abs(it) }
        assertTrue("peak $peak should be <= 1.2", peak <= 1.2f)
        assertTrue("peak $peak should be >= 0.7", peak >= 0.7f)
    }

    @Test
    fun render_converts48kTo44100() {
        // A 1-second 48 kHz file must come out as ~44100 frames,
        // not 48000 frames played slow at 44100 Hz.
        val input = SampleEngine.sineTone(440.0, 1.0, 48000)
        val out = SampleEngine.renderPadSample(
            input, assignment().copy(snapToTempo = false),
            info().copy(sampleRate = 48000, frames = 48000), 120f, 0
        )
        assertTrue("out ${out.size} should be near 44100", kotlin.math.abs(out.size - 44100) < 100)
    }

    @Test
    fun render_loopSnapsLengthToWholeBar() {
        // 1 second at 120 BPM is 2 beats = half a bar; a LOOP pad
        // must stretch to exactly 1 bar (88200 frames at 44100 Hz)
        // so every repeat lands on a bar.
        val input = SampleEngine.sineTone(440.0, 1.0)
        val out = SampleEngine.renderPadSample(
            input, assignment().copy(playMode = PlayMode.LOOP, snapToTempo = false),
            info(), 120f, 0
        )
        assertTrue("out ${out.size} should be near 88200", kotlin.math.abs(out.size - 88200) < 2000)
    }

    private fun clickTrack(bpm: Float, seconds: Double, sampleRate: Int = 44100): FloatArray {
        // Kick-like bursts (short sine with fast decay) on every beat.
        val n = (seconds * sampleRate).toInt()
        val out = FloatArray(n)
        val beatFrames = (60.0 / bpm * sampleRate).toInt()
        var beat = 0
        while (beat * beatFrames < n) {
            val start = beat * beatFrames
            for (i in 0 until 2205) {
                if (start + i >= n) break
                val t = i.toDouble() / sampleRate
                out[start + i] = (kotlin.math.sin(2 * Math.PI * 60 * t) * kotlin.math.exp(-t * 20)).toFloat()
            }
            beat++
        }
        return out
    }

    @Test
    fun detectBpm_clickTrack120() {
        val detected = SampleEngine.detectBpm(clickTrack(120f, 4.0), 44100)
        assertNotNull(detected)
        assertTrue("detected $detected should be near 120", kotlin.math.abs(detected!! - 120f) < 8f)
    }

    @Test
    fun detectBpm_clickTrack100() {
        val detected = SampleEngine.detectBpm(clickTrack(100f, 4.8), 44100)
        assertNotNull(detected)
        assertTrue("detected $detected should be near 100", kotlin.math.abs(detected!! - 100f) < 8f)
    }

    @Test
    fun detectBpm_flatToneReturnsNull() {
        // A continuous sine has no rhythm; detection must not invent a tempo.
        val detected = SampleEngine.detectBpm(SampleEngine.sineTone(440.0, 4.0), 44100)
        assertNull(detected)
    }

    private fun mixedLoop120(): FloatArray {
        // Kick on beats (60 Hz bursts) + hats as high noise on eighths.
        // A detector that follows hats would answer 240/160, not 120.
        val sampleRate = 44100
        val n = (4.0 * sampleRate).toInt()
        val out = FloatArray(n)
        val rnd = java.util.Random(7)
        for (beat in 0 until 8) {
            val start = (beat * 0.5 * sampleRate).toInt()
            for (i in 0 until 2205) {
                if (start + i >= n) break
                val tt = i.toDouble() / sampleRate
                out[start + i] += (kotlin.math.sin(2 * Math.PI * 60 * tt) * kotlin.math.exp(-tt * 20)).toFloat()
            }
        }
        for (eighth in 0 until 16) {
            val start = (eighth * 0.25 * sampleRate).toInt()
            for (i in 0 until 1323) {
                if (start + i >= n) break
                out[start + i] += (rnd.nextFloat() * 2f - 1f) * 0.3f * kotlin.math.exp(-i / 44100.0 * 30).toFloat()
            }
        }
        return out
    }

    @Test
    fun detectBpm_kickPlusHatsStill120() {
        val detected = SampleEngine.detectBpm(mixedLoop120(), 44100)
        assertNotNull(detected)
        assertTrue("detected $detected should be near 120, not a subdivision", kotlin.math.abs(detected!! - 120f) < 10f)
    }

    @Test
    fun detectBpm_sustainedMelodyNotesAt80() {
        // Regression for the 2026-11-02 phone test: a real 80 BPM
        // melody loop was detected as 97 BPM, so it never synced.
        // Root cause was in autocorrPeak, not the envelopes: a
        // sustained melodic loop has a broad, flat-topped
        // autocorrelation peak, the old earliest->=90% pick landed
        // on its rising edge, and unclamped parabolic refinement
        // (denominator ~0.006) shifted it ~9 hops to 97 BPM, while
        // the true peak sat at 80.7. This synthetic sustained-note
        // loop has the same shape (a new sine note every beat,
        // slow decay, short crossfade): the old picker returned
        // ~76 BPM for it, the fixed picker returns 80.
        val sampleRate = 44100
        val beatFrames = (0.75 * sampleRate).toInt()
        val crossfade = (0.05 * sampleRate).toInt()
        val notes = doubleArrayOf(261.63, 293.66, 329.63, 392.0, 440.0, 392.0, 329.63, 293.66)
        val out = FloatArray((12.0 * sampleRate).toInt())
        for (beat in 0 until 16) {
            val freq = notes[beat % notes.size]
            val start = beat * beatFrames
            val end = minOf(out.size, start + beatFrames + crossfade)
            for (i in start until end) {
                val t = (i - start).toDouble() / sampleRate
                val fadeIn = if (i - start < crossfade) (i - start).toDouble() / crossfade else 1.0
                out[i] += (kotlin.math.sin(2 * Math.PI * freq * t) * kotlin.math.exp(-t * 1.0) * fadeIn * 0.5).toFloat()
            }
        }
        val detected = SampleEngine.detectBpm(out, sampleRate)
        assertNotNull(detected)
        assertTrue("detected $detected should be near 80, not a refinement artifact", kotlin.math.abs(detected!! - 80f) < 3f)
    }

    @Test
    fun resample_cubicKeepsSineLevel() {
        val input = SampleEngine.sineTone(440.0, 1.0)
        val up = SampleEngine.resample(input, 2.0)
        assertTrue("up length ${up.size} should be near 22050", kotlin.math.abs(up.size - 22050) < 50)
        val peak = up.sliceArray(up.size / 4 until up.size * 3 / 4).maxOf { kotlin.math.abs(it) }
        assertTrue("peak $peak should be near 1", peak > 0.8f && peak <= 1.2f)
    }

    @Test
    fun timeStretch_nearOneSkipsStretch() {
        val input = SampleEngine.sineTone(440.0, 1.0)
        val out = SampleEngine.timeStretch(input, 1.01)
        assertEquals(input.size, out.size)
    }

    // ---- Tempo-stretch audit tests, 2026-11-02 ----
    // These prove the reported bug, not just length: a loop
    // rendered to a new project tempo must have its *beats* on
    // the new beat grid, not merely the right total length, and
    // a transposed one-shot must keep its tempo length.

    private fun peakInWindow(buf: FloatArray, centre: Int, halfWidth: Int): Float {
        val lo = maxOf(0, centre - halfWidth)
        val hi = minOf(buf.size - 1, centre + halfWidth)
        if (hi < lo) return 0f
        var peak = 0f
        for (i in lo..hi) peak = maxOf(peak, kotlin.math.abs(buf[i]))
        return peak
    }

    @Test
    fun render_loopClickTrack_onsetsLandOnProjectBeatGrid() {
        // 100 BPM click track, 4.8 s = 8 beats = 2 bars, assigned
        // to a drum loop pad. At project 120 BPM it must come out
        // as exactly 2 bars (4.0 s = 176400 frames) with a burst
        // on every 0.5 s beat — and quiet mid-beats. A stretch
        // that only got the length right but smeared/jittered the
        // beats would fail the mid-beat check.
        val input = clickTrack(100f, 4.8)
        val out = SampleEngine.renderPadSample(
            input,
            assignment().copy(playMode = PlayMode.LOOP, snapToTempo = true),
            info(bpm = 100f, cat = SampleCategory.DRUM), 120f, 0
        )
        assertTrue("out ${out.size} should be near 176400 (2 bars at 120)", kotlin.math.abs(out.size - 176400) < 3000)
        val beatFrames = 22050
        for (beat in 0 until 8) {
            val centre = beat * beatFrames
            val onBeat = peakInWindow(out, centre, 1102)
            assertTrue("beat $beat peak $onBeat should be > 0.2", onBeat > 0.2f)
            val midBeat = peakInWindow(out, centre + beatFrames / 2, 1102)
            assertTrue("mid-beat $beat peak $midBeat should be < 0.2", midBeat < 0.2f)
        }
    }

    @Test
    fun render_oneShot_pitchPlusTempo_keepsTempoLength() {
        // Octave up + Snap on, project BPM = original BPM: the
        // pitched buffer is half length, the tempo stretch must
        // double it back — final length is the original length,
        // not half. Before the 2026-11-02 audit fix this came out
        // at half length (tempoRate was applied to the pitched
        // buffer without dividing by the pitch rate).
        val input = clickTrack(120f, 2.0)
        val out = SampleEngine.renderPadSample(
            input,
            assignment().copy(playMode = PlayMode.ONE_SHOT, transposeSemitones = 12, snapToTempo = true),
            info(bpm = 120f, cat = SampleCategory.MELODY), 120f, 0
        )
        assertTrue("out ${out.size} should be near ${input.size}, not half", kotlin.math.abs(out.size - input.size) < input.size * 0.05)
    }

    @Test
    fun render_loop_pitchPlusTempo_snapsToBar() {
        // 100 BPM, 2-bar click loop, transposed +5 semitones,
        // project 120 BPM: still exactly 2 bars at 120.
        val input = clickTrack(100f, 4.8)
        val out = SampleEngine.renderPadSample(
            input,
            assignment().copy(playMode = PlayMode.LOOP, transposeSemitones = 5, snapToTempo = true),
            info(bpm = 100f, cat = SampleCategory.MELODY), 120f, 0
        )
        assertTrue("out ${out.size} should be near 176400", kotlin.math.abs(out.size - 176400) < 3000)
    }

    @Test
    fun parseWav_rejectsNonWav() {
        assertNull(SampleEngine.parseWav(byteArrayOf(1, 2, 3, 4)))
        assertNull(SampleEngine.parseWav("not a wav file at all, far too short".toByteArray()))
    }
}
