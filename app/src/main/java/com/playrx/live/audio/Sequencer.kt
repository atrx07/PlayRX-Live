package com.playrx.live.audio

import kotlin.math.roundToInt

/**
 * SEQ for real (2026-11-08) — the sequence list + step editor from
 * the Remixlive Tutorial Tour, as pure Kotlin with no Android APIs.
 *
 * A [Sequence] is one named pattern: the whole 48-pad x 16-step grid
 * (one bar at 16th notes), where every step is a [StepCell] instead
 * of a bare on/off boolean. Per-step parameters are the second tab
 * of Remixlive's step editor:
 * - Velocity: how hard the step hits (0.05..1).
 * - Offset: timing nudge in beats (-0.25..+0.25, a 16th either way).
 * - Duration: how long the hit rings (0.0625..1 beat); one-shots
 *   are cut short at this length, so a closed hat stays closed.
 * - Roll: retriggers inside the step (1, 2 or 4 hits per step).
 * - Pitch: per-step semitone shift (-12..+12), synth pads only —
 *   an imported sample keeps its own transpose (see docs).
 *
 * A [SequenceStore] holds the list (Remixlive's Seq list view):
 * create / select / delete / rename, with at least one sequence
 * always present. PlaybackEngine owns one store and plays the
 * selected sequence's cells from the transport clock; switching
 * sequence swaps the grid live. Recording (Drums & Sounds mode)
 * writes taps into the selected sequence, quantized to 16ths.
 *
 * JSON is hand-rolled (no Gson/kotlinx-serialization in this repo):
 * only steps that are on are serialized, so a typical sequence is
 * a few hundred bytes. The Projects slice reuses this format.
 */
data class StepCell(
    val on: Boolean = false,
    /** 0.05..1, how hard the step hits. */
    val velocity: Float = 1f,
    /** -0.25..+0.25 beats, timing nudge (a 16th either way). */
    val offsetBeats: Float = 0f,
    /** 0.0625..1 beats, how long the hit rings. */
    val durationBeats: Float = 0.25f,
    /** Retriggers inside the step: 1, 2 or 4. */
    val roll: Int = 1,
    /** -12..+12 semitones, synth pads only. */
    val pitchSemitones: Int = 0,
) {
    fun sanitized(): StepCell = copy(
        velocity = velocity.coerceIn(0.05f, 1f),
        offsetBeats = offsetBeats.coerceIn(-0.25f, 0.25f),
        durationBeats = durationBeats.coerceIn(0.0625f, 1f),
        roll = when (roll) {
            2 -> 2
            4 -> 4
            else -> 1
        },
        pitchSemitones = pitchSemitones.coerceIn(-12, 12),
    )
}

data class Sequence(
    val id: String,
    val name: String,
    /** Loop the bar, or play once then stop (Remixlive Loop/One shot). */
    val loop: Boolean = true,
    val gain: Float = 1f,
    val cells: Array<Array<StepCell>> = Array(SampleBank.PAD_COUNT) { Array(16) { StepCell() } },
) {
    fun cell(pad: Int, step: Int): StepCell =
        if (pad in 0 until SampleBank.PAD_COUNT && step in 0..15) cells[pad][step] else StepCell()

    fun withCell(pad: Int, step: Int, cell: StepCell): Sequence {
        if (pad !in 0 until SampleBank.PAD_COUNT || step !in 0..15) return this
        val next = Array(SampleBank.PAD_COUNT) { p -> Array(16) { s -> cells[p][s] } }
        next[pad][step] = cell.sanitized()
        return copy(cells = next)
    }

    /** Pads that have at least one step on — the editor's row list. */
    fun padsWithSteps(): List<Int> =
        (0 until SampleBank.PAD_COUNT).filter { pad -> cells[pad].any { it.on } }

    fun stepCount(): Int = cells.sumOf { row -> row.count { it.on } }

    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\"id\":\"").append(escape(id)).append("\",")
        sb.append("\"name\":\"").append(escape(name)).append("\",")
        sb.append("\"loop\":").append(loop).append(",")
        sb.append("\"gain\":").append(gain).append(",\"cells\":[")
        var first = true
        for (pad in 0 until SampleBank.PAD_COUNT) {
            for (step in 0..15) {
                val c = cells[pad][step]
                if (!c.on) continue
                if (!first) sb.append(",")
                first = false
                sb.append("[").append(pad).append(",").append(step).append(",")
                    .append(c.velocity).append(",").append(c.offsetBeats).append(",")
                    .append(c.durationBeats).append(",").append(c.roll).append(",")
                    .append(c.pitchSemitones).append("]")
            }
        }
        sb.append("]}")
        return sb.toString()
    }

    companion object {
        private fun escape(s: String): String =
            s.replace("\\", "\\\\").replace("\"", "\\\"")

        private fun unescape(s: String): String =
            s.replace("\\\"", "\"").replace("\\\\", "\\")

        private fun stringField(json: String, field: String): String? {
            val key = "\"$field\":\""
            val start = json.indexOf(key)
            if (start < 0) return null
            val from = start + key.length
            val sb = StringBuilder()
            var i = from
            while (i < json.length) {
                val ch = json[i]
                if (ch == '\\' && i + 1 < json.length) {
                    sb.append(json[i + 1])
                    i += 2
                } else if (ch == '"') {
                    return sb.toString()
                } else {
                    sb.append(ch)
                    i++
                }
            }
            return null
        }

        fun fromJson(json: String): Sequence? {
            val id = stringField(json, "id") ?: return null
            val name = stringField(json, "name") ?: "Seq"
            val loop = !json.contains("\"loop\":false")
            val gain = Regex("\"gain\":([0-9.]+)").find(json)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
            val seq = Sequence(id = id, name = name, loop = loop, gain = gain.coerceIn(0f, 1f))
            // Only on-cells are stored: [pad,step,vel,off,dur,roll,pitch]
            val cellRe = Regex("\\[(\\d+),(\\d+),([0-9.\\-]+),([0-9.\\-]+),([0-9.\\-]+),(\\d+),(-?\\d+)\\]")
            var result = seq
            for (m in cellRe.findAll(json)) {
                val pad = m.groupValues[1].toIntOrNull() ?: continue
                val step = m.groupValues[2].toIntOrNull() ?: continue
                val cell = StepCell(
                    on = true,
                    velocity = m.groupValues[3].toFloatOrNull() ?: 1f,
                    offsetBeats = m.groupValues[4].toFloatOrNull() ?: 0f,
                    durationBeats = m.groupValues[5].toFloatOrNull() ?: 0.25f,
                    roll = m.groupValues[6].toIntOrNull() ?: 1,
                    pitchSemitones = m.groupValues[7].toIntOrNull() ?: 0,
                )
                result = result.withCell(pad, step, cell)
            }
            return result
        }
    }
}

class SequenceStore {
    private val sequences = mutableListOf<Sequence>()
    private var selectedId: String? = null
    private var nextNumber = 1

    init {
        create("Seq 1")
    }

    fun list(): List<Sequence> = sequences.toList()

    fun selected(): Sequence? =
        sequences.firstOrNull { it.id == selectedId } ?: sequences.firstOrNull()

    fun create(name: String? = null): Sequence {
        val n = nextNumber++
        val seq = Sequence(id = "seq-$n", name = name ?: "Seq $n")
        sequences.add(seq)
        selectedId = seq.id
        return seq
    }

    /** Delete by id. Refuses when it is the last sequence; the
     * caller keeps at least one sequence, like Remixlive keeps at
     * least one sequence in the list. Returns false if refused. */
    fun delete(id: String): Boolean {
        if (sequences.size <= 1) return false
        val removed = sequences.removeAll { it.id == id }
        if (removed && selectedId == id) selectedId = sequences.firstOrNull()?.id
        return removed
    }

    fun select(id: String): Boolean {
        if (sequences.none { it.id == id }) return false
        selectedId = id
        return true
    }

    fun rename(id: String, name: String): Boolean {
        val idx = sequences.indexOfFirst { it.id == id }
        if (idx < 0 || name.isBlank()) return false
        sequences[idx] = sequences[idx].copy(name = name.trim().take(24))
        return true
    }

    fun updateSelected(transform: (Sequence) -> Sequence): Boolean {
        val cur = selected() ?: return false
        val idx = sequences.indexOfFirst { it.id == cur.id }
        if (idx < 0) return false
        sequences[idx] = transform(cur)
        return true
    }

    /** Quantize a live tap to the nearest 16th step at [totalBeats]
     * and turn that cell on (keeping its existing parameters if it
     * is already on). Returns the step, or -1 for an invalid pad. */
    fun recordTap(pad: Int, totalBeats: Double): Int {
        if (pad !in 0 until SampleBank.PAD_COUNT) return -1
        val step = ((totalBeats * 4).toInt() % 16 + 16) % 16
        val cur = selected() ?: return -1
        val existing = cur.cell(pad, step)
        val cell = if (existing.on) existing else StepCell(on = true)
        updateSelected { it.withCell(pad, step, cell) }
        return step
    }

    /** Record a scale-keyboard note: like [recordTap], but the
     * step's pitch is the note's offset from middle C, so the
     * melody replays through the sequencer's pitch parameter. */
    fun recordNoteStep(pad: Int, totalBeats: Double, pitchSemitones: Int): Int {
        if (pad !in 0 until SampleBank.PAD_COUNT) return -1
        val step = ((totalBeats * 4).toInt() % 16 + 16) % 16
        val cur = selected() ?: return -1
        val existing = cur.cell(pad, step)
        val cell = if (existing.on) existing.copy(pitchSemitones = pitchSemitones)
        else StepCell(on = true, pitchSemitones = pitchSemitones)
        updateSelected { it.withCell(pad, step, cell) }
        return step
    }

    fun toJson(): String =
        "[" + sequences.joinToString(",") { it.toJson() } + "]"

    fun loadJson(json: String): Boolean {
        // Split top-level sequence objects by brace depth.
        val parsed = mutableListOf<Sequence>()
        var depth = 0
        var start = -1
        for (i in json.indices) {
            when (json[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        Sequence.fromJson(json.substring(start, i + 1))?.let { parsed.add(it) }
                        start = -1
                    }
                }
            }
        }
        if (parsed.isEmpty()) return false
        sequences.clear()
        sequences.addAll(parsed)
        nextNumber = (parsed.mapNotNull { it.id.removePrefix("seq-").toIntOrNull() }.maxOrNull() ?: 0) + 1
        selectedId = sequences.firstOrNull()?.id
        return true
    }
}
