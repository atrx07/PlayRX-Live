package com.playrx.live.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SequencerTest {

    @Test
    fun stepCell_sanitizedClamps() {
        val cell = StepCell(
            on = true, velocity = 5f, offsetBeats = -1f,
            durationBeats = 9f, roll = 3, pitchSemitones = 40,
        ).sanitized()
        assertEquals(1f, cell.velocity, 0.001f)
        assertEquals(-0.25f, cell.offsetBeats, 0.001f)
        assertEquals(1f, cell.durationBeats, 0.001f)
        assertEquals(1, cell.roll)
        assertEquals(12, cell.pitchSemitones)
    }

    @Test
    fun stepCell_rollKeeps2And4() {
        assertEquals(2, StepCell(roll = 2).sanitized().roll)
        assertEquals(4, StepCell(roll = 4).sanitized().roll)
    }

    @Test
    fun sequence_jsonRoundTrip() {
        val seq = Sequence(id = "seq-9", name = "Test", loop = false, gain = 0.5f)
            .withCell(0, 0, StepCell(on = true, velocity = 0.5f, pitchSemitones = 7))
            .withCell(3, 4, StepCell(on = true, roll = 2, offsetBeats = 0.1f, durationBeats = 0.5f))
        val parsed = Sequence.fromJson(seq.toJson())
        assertNotNull(parsed)
        assertEquals("seq-9", parsed!!.id)
        assertEquals("Test", parsed.name)
        assertFalse(parsed.loop)
        assertEquals(0.5f, parsed.gain, 0.001f)
        val c0 = parsed.cell(0, 0)
        assertTrue(c0.on)
        assertEquals(0.5f, c0.velocity, 0.001f)
        assertEquals(7, c0.pitchSemitones)
        val c1 = parsed.cell(3, 4)
        assertTrue(c1.on)
        assertEquals(2, c1.roll)
        assertEquals(0.1f, c1.offsetBeats, 0.001f)
        assertEquals(0.5f, c1.durationBeats, 0.001f)
        // Off cells are not serialized and come back off.
        assertFalse(parsed.cell(1, 1).on)
        assertEquals(2, parsed.stepCount())
    }

    @Test
    fun sequence_padsWithSteps() {
        val seq = Sequence(id = "s", name = "x")
            .withCell(0, 0, StepCell(on = true))
            .withCell(0, 8, StepCell(on = true))
            .withCell(5, 3, StepCell(on = true))
        assertEquals(listOf(0, 5), seq.padsWithSteps())
    }

    @Test
    fun store_createSelectDelete() {
        val store = SequenceStore()
        assertEquals(1, store.list().size)
        val second = store.create()
        assertEquals(2, store.list().size)
        assertEquals(second.id, store.selected()!!.id)
        assertTrue(store.select(store.list().first().id))
        assertFalse(store.select("nope"))
        // Delete down to one, then the last delete is refused.
        assertTrue(store.delete(second.id))
        assertEquals(1, store.list().size)
        assertFalse(store.delete(store.list().first().id))
        assertEquals(1, store.list().size)
    }

    @Test
    fun store_rename() {
        val store = SequenceStore()
        val id = store.selected()!!.id
        assertTrue(store.rename(id, "  Verse  "))
        assertEquals("Verse", store.selected()!!.name)
        assertFalse(store.rename(id, "   "))
        assertFalse(store.rename("nope", "x"))
    }

    @Test
    fun store_recordTapQuantizesTo16th() {
        val store = SequenceStore()
        assertEquals(0, store.recordTap(0, 0.0))
        assertEquals(1, store.recordTap(0, 0.26))
        assertEquals(4, store.recordTap(0, 1.0))
        assertEquals(15, store.recordTap(0, 3.99))
        assertEquals(0, store.recordTap(0, 4.0))
        assertEquals(-1, store.recordTap(99, 1.0))
        // The recorded cells are on.
        val seq = store.selected()!!
        assertTrue(seq.cell(0, 0).on)
        assertTrue(seq.cell(0, 1).on)
        assertTrue(seq.cell(0, 4).on)
        assertTrue(seq.cell(0, 15).on)
    }

    @Test
    fun store_recordTapKeepsExistingParams() {
        val store = SequenceStore()
        store.updateSelected { it.withCell(2, 4, StepCell(on = true, velocity = 0.3f, roll = 4)) }
        store.recordTap(2, 1.0)
        val cell = store.selected()!!.cell(2, 4)
        assertTrue(cell.on)
        assertEquals(0.3f, cell.velocity, 0.001f)
        assertEquals(4, cell.roll)
    }

    @Test
    fun store_jsonRoundTrip() {
        val store = SequenceStore()
        store.create("Chorus")
        store.updateSelected { it.withCell(1, 2, StepCell(on = true, velocity = 0.8f)) }
        val json = store.toJson()
        val other = SequenceStore()
        assertTrue(other.loadJson(json))
        assertEquals(2, other.list().size)
        assertEquals("Chorus", other.list()[1].name)
        assertTrue(other.list()[1].cell(1, 2).on)
        assertFalse(other.loadJson("not json"))
        assertFalse(other.loadJson("[]"))
    }

    @Test
    fun engine_stepCellRoundTripAndRecord() {
        val engine = PlaybackEngine()
        engine.setStepCell(0, 0, StepCell(on = true, velocity = 0.6f, pitchSemitones = -5))
        val cell = engine.getStepCell(0, 0)
        assertTrue(cell.on)
        assertEquals(0.6f, cell.velocity, 0.001f)
        assertEquals(-5, cell.pitchSemitones)
        // setStep/getStep stay compatible with the cell store.
        engine.setStep(1, 8, true)
        assertTrue(engine.getStep(1, 8))
        engine.setStep(1, 8, false)
        assertFalse(engine.getStep(1, 8))
        // Recording is off by default; on, a tap lands quantized.
        assertEquals(-1, engine.recordPadTap(0, 1.0))
        engine.isRecording = true
        assertEquals(4, engine.recordPadTap(0, 1.0))
        assertTrue(engine.getStep(0, 4))
        // Sequence list through the engine.
        assertEquals(1, engine.sequences().size)
        engine.createSequence()
        assertEquals(2, engine.sequences().size)
        assertNotNull(engine.selectedSequence())
        assertNull(engine.sequences().firstOrNull { it.id == "nope" })
    }
}
