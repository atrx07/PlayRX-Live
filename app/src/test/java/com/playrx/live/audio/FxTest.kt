package com.playrx.live.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FxTest {

    @Test
    fun divisionFrames_dottedEighthAt120() {
        // 0.75 beats at 120 BPM = 0.375 s = 16537 frames (floor).
        assertEquals(16537, FxMath.divisionFrames(0.75f, 120f, 44100))
        // 1/16 at 120 BPM = 0.125 s = 5512 frames.
        assertEquals(5512, FxMath.divisionFrames(0.25f, 120f, 44100))
        // 2 beats at 60 BPM = 2 s.
        assertEquals(88200, FxMath.divisionFrames(2f, 60f, 44100))
    }

    @Test
    fun onePoleAlpha_increasesWithCutoff() {
        val low = FxMath.onePoleAlpha(200f, 44100)
        val mid = FxMath.onePoleAlpha(2000f, 44100)
        val high = FxMath.onePoleAlpha(18000f, 44100)
        assertTrue(low > 0f && low < mid && mid < high && high < 1f)
        // Bypass returns 1 (y follows x exactly).
        assertEquals(1f, FxMath.onePoleAlpha(0f, 44100), 0.001f)
        // One-pole step moves y toward x by alpha.
        assertEquals(0.5f, FxMath.onePole(1f, 0f, 0.5f), 0.001f)
    }

    @Test
    fun xyCutoff_roundTrip() {
        assertEquals(80f, FxMath.xyToCutoff(0f), 1f)
        assertEquals(18000f, FxMath.xyToCutoff(1f), 1f)
        val mid = FxMath.xyToCutoff(0.5f)
        assertTrue(mid > 1000f && mid < 1500f) // geometric middle ~1200 Hz
        assertEquals(0.5f, FxMath.cutoffToXy(mid), 0.01f)
        assertEquals(1f, FxMath.cutoffToXy(0f), 0.001f)
    }

    @Test
    fun repeatReadPos_wrapsInsideSlice() {
        // Ring of 100, slice starts at 90, length 20: write pos 95
        // reads 95; write pos 110 reads 90 (wrapped); write pos 115 reads 95.
        assertEquals(95, FxMath.repeatReadPos(95, 90, 20, 100))
        assertEquals(90, FxMath.repeatReadPos(110, 90, 20, 100))
        assertEquals(95, FxMath.repeatReadPos(115, 90, 20, 100))
    }

    @Test
    fun labels_resolve() {
        assertEquals(BeatRepeat.EIGHTH, BeatRepeat.fromLabel("1/8"))
        assertEquals(BeatRepeat.OFF, BeatRepeat.fromLabel("nope"))
        assertEquals(DelayDivision.DOTTED_EIGHTH, DelayDivision.fromBeats(0.75f))
        assertEquals(DelayDivision.QUARTER, DelayDivision.fromBeats(1.05f))
    }

    @Test
    fun fxState_columnSendAndFilterClamp() {
        val state = FxState()
        val sent = state.withColumnSend(3, 1.5f)
        assertEquals(1f, sent.columnSends[3], 0.001f)
        assertEquals(0.5f, sent.columnSends[0], 0.001f)
        val filtered = state.withColumnFilter(0, 25000f)
        assertEquals(18000f, filtered.columnFilterCutoffHz[0], 0.001f)
        assertEquals(0f, filtered.columnFilterCutoffHz[1], 0.001f)
    }
}
