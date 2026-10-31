package com.playrx.live.midi

/**
 * Pure-Kotlin chord detection. No Android dependencies, so this is unit-testable
 * on the JVM and reusable in any future desktop build.
 *
 * Input: the set of currently held MIDI note numbers.
 * Output: the best-matching chord, or null if fewer than 2 distinct pitch classes
 * are held. The root is the lowest held note's pitch class, unless an inversion
 * is explicitly detectable from the bass note.
 *
 * Supported shapes (intervals from root, in semitones):
 * major {0,4,7}, minor {0,3,7}, diminished {0,3,6}, augmented {0,4,8},
 * sus2 {0,2,7}, sus4 {0,5,7}, major 7 {0,4,7,11}, dominant 7 {0,4,7,10},
 * minor 7 {0,3,7,10}, minor-major 7 {0,3,7,11}, major 6 {0,4,7,9},
 * minor 6 {0,3,7,9}.
 */
object ChordDetector {

    private val NOTE_NAMES = arrayOf(
        "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
    )

    private val SHAPES: List<Pair<Set<Int>, String>> = listOf(
        setOf(0, 4, 7, 11) to "maj7",
        setOf(0, 3, 7, 11) to "m(maj7)",
        setOf(0, 4, 7, 10) to "7",
        setOf(0, 3, 7, 10) to "m7",
        setOf(0, 4, 7, 9) to "6",
        setOf(0, 3, 7, 9) to "m6",
        setOf(0, 4, 7) to "",
        setOf(0, 3, 7) to "m",
        setOf(0, 3, 6) to "dim",
        setOf(0, 4, 8) to "aug",
        setOf(0, 2, 7) to "sus2",
        setOf(0, 5, 7) to "sus4",
    )

    data class Chord(
        val rootPitchClass: Int,
        val bassPitchClass: Int,
        val suffix: String,
    ) {
        val name: String
            get() {
                val root = NOTE_NAMES[rootPitchClass]
                val base = root + suffix
                return if (bassPitchClass != rootPitchClass) {
                    "$base/${NOTE_NAMES[bassPitchClass]}"
                } else base
            }
    }

    /** Detect a chord from held notes. Returns null for < 2 distinct pitch classes. */
    fun detect(heldNotes: Set<Int>): Chord? {
        if (heldNotes.size < 2) return null
        val pitchClasses = heldNotes.map { it % 12 }.toSet()
        if (pitchClasses.size < 2) return null

        // Bass = lowest held note. Try every pitch class present as a candidate root,
        // preferring exact shape matches, then the bass note as root.
        val candidates = pitchClasses.sortedBy { if (it == heldNotes.min() % 12) 0 else 1 }
        for (root in candidates) {
            val intervals = pitchClasses.map { (it - root + 12) % 12 }.toSet()
            for ((shape, suffix) in SHAPES) {
                if (intervals == shape) {
                    return Chord(root, heldNotes.min() % 12, suffix)
                }
            }
        }
        // Fallback: if it looks like a triad plus extras, match the triad subset.
        for (root in candidates) {
            val intervals = pitchClasses.map { (it - root + 12) % 12 }.toSet()
            for ((shape, suffix) in SHAPES) {
                if (shape.size == 3 && intervals.containsAll(shape)) {
                    return Chord(root, heldNotes.min() % 12, suffix)
                }
            }
        }
        return null
    }
}
