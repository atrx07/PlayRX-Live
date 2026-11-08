package com.playrx.live.audio

/**
 * Scale Keyboard for the DRUM view (2026-11-08) — the expandable
 * keyboard at the bottom of Remixlive's Drum View, as pure Kotlin.
 *
 * A scale is a set of semitone offsets from a root. The keyboard
 * root follows the **project key** (so the keys you play are always
 * in the project's key), the user picks the scale flavour and the
 * octave, and taps play synth notes live over the loops. Octave
 * range is 2..6 (MIDI octave numbering, C4 = middle C, MIDI 60).
 *
 * Recording: a scale tap while recording writes a step into the
 * selected sequence on the selected pad, with the step's pitch set
 * to the note's offset from middle C (clamped to the step pitch
 * range -12..+12) — so a recorded melody replays through the
 * sequencer's pitch parameter. See [SequenceStore.recordNoteStep].
 */
object ScaleKeyboard {
    val SCALES = listOf("Major", "Minor", "Pentatonic", "Blues", "Dorian", "Chromatic")

    /** Semitone offsets from the root, one octave. */
    fun intervals(scale: String): List<Int> = when (scale) {
        "Minor" -> listOf(0, 2, 3, 5, 7, 8, 10)
        "Pentatonic" -> listOf(0, 2, 4, 7, 9)
        "Blues" -> listOf(0, 3, 5, 6, 7, 10)
        "Dorian" -> listOf(0, 2, 3, 5, 7, 9, 10)
        "Chromatic" -> (0..11).toList()
        else -> listOf(0, 2, 4, 5, 7, 9, 11) // Major
    }

    /** (midi, name) for two octaves of [scale] at [rootPitchClass]
     * starting at [octave]. Root follows the project key. */
    fun notes(rootPitchClass: Int, scale: String, octave: Int): List<Pair<Int, String>> {
        val root = ((rootPitchClass % 12) + 12) % 12
        val base = (octave.coerceIn(2, 6) + 1) * 12 + root
        val result = mutableListOf<Pair<Int, String>>()
        for (oct in 0..1) {
            for (interval in intervals(scale)) {
                val midi = base + oct * 12 + interval
                if (midi in 24..108) result.add(midi to SampleEngine.midiNoteName(midi))
            }
        }
        return result
    }

    /** Frequency of a MIDI note (A4 = 440 Hz). */
    fun frequency(midi: Int): Double = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)

    /** Step pitch for a recorded note: offset from middle C,
     * clamped to the sequencer's per-step pitch range. */
    fun recordedPitch(midi: Int): Int = (midi - 60).coerceIn(-12, 12)
}
