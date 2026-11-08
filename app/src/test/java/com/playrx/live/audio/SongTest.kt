package com.playrx.live.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongTest {

    @Test
    fun addClip_refusesOverlapOnSameTrack() {
        val song = SongTimeline(16)
        val first = song.addClip(0, 1, 4, 5, "Pad 1")
        assertNotNull(first)
        // Overlapping on the same track is refused.
        assertNull(song.addClip(0, 3, 2, 6, "Pad 2"))
        // Same bars on another track are fine.
        assertNotNull(song.addClip(1, 3, 2, 6, "Pad 2"))
        // Adjacent (no overlap) is fine.
        assertNotNull(song.addClip(0, 5, 2, 7, "FX 1"))
        assertEquals(3, song.list().size)
    }

    @Test
    fun addClip_clampsLengthToSongEnd() {
        val song = SongTimeline(8)
        val clip = song.addClip(2, 7, 4, 0, "Kick 1")
        assertNotNull(clip)
        assertEquals(2, clip!!.lengthBars) // bars 7..8 only
        assertNull(song.addClip(2, 9, 1, 0, "x"))
        assertNull(song.addClip(9, 1, 1, 0, "x"))
    }

    @Test
    fun clipsAtBar_andClipAt() {
        val song = SongTimeline(16)
        song.addClip(0, 1, 4, 5, "Pad 1")
        song.addClip(1, 3, 2, 6, "Pad 2")
        assertEquals(1, song.clipsAtBar(1).size)
        assertEquals(2, song.clipsAtBar(3).size)
        assertEquals(0, song.clipsAtBar(5).size)
        assertEquals("Pad 1", song.clipAt(0, 2)?.name)
        assertNull(song.clipAt(0, 5))
    }

    @Test
    fun moveResizeDuplicateSplit() {
        val song = SongTimeline(16)
        val clip = song.addClip(0, 1, 4, 5, "Pad 1")!!
        assertTrue(song.moveClip(clip.id, 0, 5))
        assertEquals(5, song.clipAt(0, 6)?.startBar)
        // Move into an occupied cell is refused.
        song.addClip(0, 1, 2, 6, "Pad 2")
        assertFalse(song.moveClip(clip.id, 0, 1))
        // Resize shorter, then longer.
        assertTrue(song.resizeClip(clip.id, 2))
        assertEquals(2, song.clipAt(0, 5)?.lengthBars)
        assertTrue(song.resizeClip(clip.id, 4))
        // Duplicate to bar 9.
        val dup = song.duplicateClip(clip.id, 9)
        assertNotNull(dup)
        assertEquals(9, dup!!.startBar)
        assertEquals(4, dup.lengthBars)
        // Split the duplicate at bar 11: 9..10 and 11..12.
        val right = song.splitClip(dup.id, 11)
        assertNotNull(right)
        assertEquals(11, right!!.startBar)
        assertEquals(2, right.lengthBars)
        assertEquals(2, song.clipAt(0, 9)?.lengthBars)
        // Split outside the clip is refused.
        assertNull(song.splitClip(dup.id, 9))
        assertNull(song.splitClip(dup.id, 13))
        // Remove.
        assertTrue(song.removeClip(clip.id))
        assertFalse(song.removeClip(clip.id))
    }

    @Test
    fun jsonRoundTrip() {
        val song = SongTimeline(16)
        song.addClip(0, 1, 4, 5, "Pad 1")
        song.addClip(3, 5, 2, 27, "Bass 4")
        val parsed = SongTimeline.fromJson(song.toJson())
        assertNotNull(parsed)
        assertEquals(16, parsed!!.lengthBars)
        assertEquals(2, parsed.list().size)
        assertEquals("Pad 1", parsed.clipAt(0, 1)?.name)
        assertEquals(27, parsed.clipAt(3, 5)?.pad)
        assertNull(SongTimeline.fromJson("garbage"))
    }
}
