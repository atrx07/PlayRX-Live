package com.playrx.live.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProjectExportTest {

    @Test
    fun recordMode_songCapturesLoopTapsAsClips() {
        val engine = PlaybackEngine()
        engine.setRecordMode(RecordMode.SONG)
        assertFalse(engine.isRecording)
        // Pad 5 is a Pad-column loop pad: tap to start, tap to stop.
        engine.triggerPad(5)
        assertTrue(engine.songTimeline.list().isEmpty())
        engine.triggerPad(5)
        val clips = engine.songTimeline.list()
        assertEquals(1, clips.size)
        assertEquals(5, clips[0].track)
        assertEquals(1, clips[0].startBar)
        assertEquals(1, clips[0].lengthBars)
        assertEquals(5, clips[0].pad)
        // A one-shot tap becomes a 1-bar clip on its track.
        engine.triggerPad(0)
        assertEquals(2, engine.songTimeline.list().size)
        assertEquals(0, engine.songTimeline.clipAt(0, 1)?.pad)
        // Leaving SONG mode stops DRUMS recording from being on.
        engine.setRecordMode(RecordMode.DRUMS)
        assertTrue(engine.isRecording)
        engine.setRecordMode(RecordMode.OFF)
        assertFalse(engine.isRecording)
    }

    @Test
    fun gatePad_pressAndRelease() {
        val engine = PlaybackEngine()
        val raw = SampleEngine.sineTone(440.0, 0.5)
        val info = SampleInfo("g1", "gate.wav", "gate.wav", 44100, 1, 22050, 120f, 60, SampleCategory.DRUM)
        engine.assignImportedSample(
            0, raw,
            PadSample(pad = 0, sampleId = "g1", playMode = PlayMode.GATE), info,
        )
        assertTrue(engine.activeGatePads().isEmpty())
        engine.pressGatePad(0)
        assertEquals(setOf(0), engine.activeGatePads())
        engine.pressGatePad(0) // second press is a no-op
        assertEquals(1, engine.activeGatePads().size)
        engine.releaseGatePad(0)
        assertTrue(engine.activeGatePads().isEmpty())
        // A non-GATE pad ignores presses.
        engine.pressGatePad(1)
        assertTrue(engine.activeGatePads().isEmpty())
        // Peaks: 64 buckets from the full-amplitude sine.
        val peaks = engine.samplePeaks(0)
        assertEquals(64, peaks.size)
        assertTrue(peaks.all { it > 0.5f })
        assertEquals(0, engine.samplePeaks(1).size)
    }

    @Test
    fun recordMode_masterShortCaptureReturnsNull() {
        val engine = PlaybackEngine()
        engine.setRecordMode(RecordMode.MASTER)
        assertTrue(engine.masterRecording)
        assertNull(engine.finishMasterRecording())
        assertFalse(engine.masterRecording)
        // setRecordMode back to OFF is a no-op for the buffer.
        engine.setRecordMode(RecordMode.OFF)
    }

    @Test
    fun songTimeline_loadFrom() {
        val source = SongTimeline(16).apply { addClip(0, 1, 4, 0, "Kick 1") }
        val target = SongTimeline(8)
        target.loadFrom(source)
        assertEquals(16, target.lengthBars)
        assertEquals(1, target.list().size)
        assertEquals("Kick 1", target.clipAt(0, 1)?.name)
    }

    @Test
    fun wavWriter_roundTrip() {
        val tmp = File.createTempFile("playrx-test", ".wav")
        try {
            val samples = FloatArray(4410) { i -> (Math.sin(i * 0.1) * 0.5).toFloat() }
            assertTrue(WavWriter.writeWav(tmp, samples, 44100, 1))
            val info = WavWriter.readInfo(tmp)
            assertNotNull(info)
            assertEquals(44100, info!!.sampleRate)
            assertEquals(1, info.channels)
            assertEquals(4410, info.frames)
            // Stereo: frame count is per-channel.
            assertTrue(WavWriter.writeWav(tmp, FloatArray(8820) { 0.1f }, 44100, 2))
            val stereo = WavWriter.readInfo(tmp)
            assertNotNull(stereo)
            assertEquals(2, stereo!!.channels)
            assertEquals(4410, stereo.frames)
            // Guard rails.
            assertNull(WavWriter.readInfo(File.createTempFile("playrx-empty", ".wav")))
            assertTrue(!WavWriter.writeWav(tmp, FloatArray(0), 44100, 1))
            assertTrue(!WavWriter.writeWav(tmp, FloatArray(10), 44100, 3))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun projectFile_assignmentRoundTrip() {
        val assignment = PadSample(
            pad = 5, sampleId = "abc-1", playMode = PlayMode.LOOP,
            transposeSemitones = 3, snapToTempo = true, quantizeBeats = 4f,
            repeatCount = 3,
        )
        val json = ProjectFile.padAssignmentToJson(5, "abc-1", assignment)
        val parsed = ProjectFile.padAssignmentFromJson(json)
        assertNotNull(parsed)
        assertEquals(5, parsed!!.first)
        assertEquals("abc-1", parsed.second)
        assertEquals(PlayMode.LOOP, parsed.third.playMode)
        assertEquals(3, parsed.third.transposeSemitones)
        assertEquals(4f, parsed.third.quantizeBeats, 0.001f)
        assertEquals(3, parsed.third.repeatCount)
        assertNull(ProjectFile.padAssignmentFromJson("{}"))
    }

    @Test
    fun projectFile_fullRoundTrip() {
        val sequences = SequenceStore().apply {
            updateSelected { it.withCell(0, 0, StepCell(on = true)) }
        }
        val song = SongTimeline(16).apply { addClip(5, 1, 4, 5, "Pad 1") }
        val data = ProjectData(
            name = "My Song", bpm = 127f, projectKeyMidi = 5, masterVolume = 0.9f,
            defaultQuantizeBeats = 4f, defaultSnapToTempo = true,
            sequencesJson = sequences.toJson(), songJson = song.toJson(),
            assignmentsJson = "[" + ProjectFile.padAssignmentToJson(
                5, "abc-1",
                PadSample(pad = 5, sampleId = "abc-1", playMode = PlayMode.LOOP),
            ) + "]",
        )
        val parsed = ProjectFile.fromJson(ProjectFile.toJson(data))
        assertNotNull(parsed)
        assertEquals("My Song", parsed!!.name)
        assertEquals(127f, parsed.bpm, 0.001f)
        assertEquals(5, parsed.projectKeyMidi)
        // The embedded sequences + song parse back.
        val seqStore = SequenceStore()
        assertTrue(seqStore.loadJson(parsed.sequencesJson))
        assertTrue(seqStore.selected()!!.cell(0, 0).on)
        val parsedSong = SongTimeline.fromJson(parsed.songJson)
        assertNotNull(parsedSong)
        assertEquals(1, parsedSong!!.list().size)
        assertEquals("Pad 1", parsedSong.clipAt(5, 1)?.name)
        val assignments = ProjectFile.splitAssignmentObjects(parsed.assignmentsJson)
        assertEquals(1, assignments.size)
        assertEquals(5, ProjectFile.padAssignmentFromJson(assignments[0])?.first)
        assertNull(ProjectFile.fromJson("garbage"))
        assertEquals("Untitled", ProjectStore.sanitizeName("   "))
        assertEquals("My Song", ProjectStore.sanitizeName("My Song!@#"))
    }
}
