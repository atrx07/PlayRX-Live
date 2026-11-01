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
    fun parseWav_rejectsNonWav() {
        assertNull(SampleEngine.parseWav(byteArrayOf(1, 2, 3, 4)))
        assertNull(SampleEngine.parseWav("not a wav file at all, far too short".toByteArray()))
    }
}
