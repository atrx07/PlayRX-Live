package com.playrx.live.audio

import kotlin.math.PI
import kotlin.math.exp

/**
 * FX state + the pure math behind the FX panel and the Mixer
 * "Eq Filter" tab (2026-11-08). The mixer owns the live buffers;
 * this file holds the values, the division tables and the
 * one-pole filter math, all unit-testable on the JVM.
 *
 * What the panel offers (Remixlive parity, see docs/FX.md):
 * - Delay: on/off, tempo-synced division, mix, feedback.
 * - Beat repeat: freeze the last division of the mix and stutter
 *   it until switched off.
 * - XY pad: X = master low-pass cutoff, Y = delay mix.
 * - Routing: a per-column send (0..1) into the delay.
 * - Per-column filter: a one-pole low-pass per instrument column
 *   in the Mixer "Eq Filter" tab (0 = bypass).
 */
enum class BeatRepeat(val label: String, val beats: Float) {
    OFF("Off", 0f),
    SIXTEENTH("1/16", 0.25f),
    EIGHTH("1/8", 0.5f),
    QUARTER("1/4", 1f),
    HALF("1/2", 2f),
    ONE("1", 4f),
    TWO("2", 8f);

    companion object {
        fun fromLabel(label: String): BeatRepeat =
            values().firstOrNull { it.label == label } ?: OFF
    }
}

/** Delay divisions in beats (a dotted eighth is 0.75). */
enum class DelayDivision(val label: String, val beats: Float) {
    SIXTEENTH("1/16", 0.25f),
    EIGHTH("1/8", 0.5f),
    DOTTED_EIGHTH("1/8.", 0.75f),
    QUARTER("1/4", 1f),
    HALF("1/2", 2f);

    companion object {
        fun fromBeats(beats: Float): DelayDivision =
            values().minByOrNull { kotlin.math.abs(it.beats - beats) } ?: DOTTED_EIGHTH
    }
}

data class FxState(
    val delayEnabled: Boolean = false,
    val delayDivisionBeats: Float = 0.75f,
    val delayMix: Float = 0.25f,
    val delayFeedback: Float = 0.35f,
    val beatRepeat: BeatRepeat = BeatRepeat.OFF,
    val beatRepeatMix: Float = 0.7f,
    /** Master low-pass cutoff in Hz; 0 (or >= 18k) = bypass. */
    val masterFilterCutoffHz: Float = 0f,
    /** Per-column delay send, 0..1 (index = column 0..7). */
    val columnSends: List<Float> = List(8) { 0.5f },
    /** Per-column low-pass cutoff in Hz; 0 = bypass. */
    val columnFilterCutoffHz: List<Float> = List(8) { 0f },
) {
    fun withColumnSend(col: Int, value: Float): FxState =
        copy(columnSends = columnSends.mapIndexed { i, v -> if (i == col) value.coerceIn(0f, 1f) else v })

    fun withColumnFilter(col: Int, hz: Float): FxState =
        copy(columnFilterCutoffHz = columnFilterCutoffHz.mapIndexed { i, v -> if (i == col) hz.coerceIn(0f, 18000f) else v })
}

object FxMath {
    /** Frames in [beats] beats at [bpm] and [sampleRate]. */
    fun divisionFrames(beats: Float, bpm: Float, sampleRate: Int): Int =
        ((beats * 60f / bpm) * sampleRate).toInt().coerceAtLeast(1)

    /** One-pole low-pass coefficient for cutoff [hz] at [sampleRate]. */
    fun onePoleAlpha(hz: Float, sampleRate: Int): Float {
        if (hz <= 0f) return 1f // bypass: y follows x exactly
        val rc = 1.0 / (2.0 * PI * hz.toDouble())
        val dt = 1.0 / sampleRate.toDouble()
        return (dt / (rc + dt)).toFloat()
    }

    /** One-pole step: y += alpha * (x - y). */
    fun onePole(x: Float, y: Float, alpha: Float): Float = y + alpha * (x - y)

    /** XY pad X (0..1) -> master cutoff in Hz, log scale 80..18000. */
    fun xyToCutoff(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return (80.0 * exp(t * kotlin.math.ln(18000.0 / 80.0))).toFloat()
    }

    /** Master cutoff in Hz -> XY pad X (0..1), inverse of xyToCutoff. */
    fun cutoffToXy(hz: Float): Float {
        if (hz <= 0f) return 1f
        val t = kotlin.math.ln(hz.toDouble() / 80.0) / kotlin.math.ln(18000.0 / 80.0)
        return t.toFloat().coerceIn(0f, 1f)
    }

    /** Ring-buffer read position for a frozen beat-repeat slice:
     * [writePos] is where the next frame lands, [start] is the
     * first frame of the frozen slice, [length] its length. */
    fun repeatReadPos(writePos: Int, start: Int, length: Int, size: Int): Int {
        val offset = ((writePos - start) % length + length) % length
        return (start + offset) % size
    }

    /** Stereo balance gains for a pan in -1 (left) .. 1 (right).
     * Centre stays at full level on both sides; hard left/right
     * silences the other side. */
    fun panGains(pan: Float): Pair<Float, Float> {
        val p = pan.coerceIn(-1f, 1f)
        return minOf(1f, 1f - p) to minOf(1f, 1f + p)
    }
}
