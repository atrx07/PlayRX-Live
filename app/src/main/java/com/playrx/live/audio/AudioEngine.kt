package com.playrx.live.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Functional audio core for PlayRX Live (Phase 1).
 *
 * No external sample files are needed: every sound is synthesized in code,
 * so the app is fully open-source with no sample-licensing problem.
 *
 * Architecture (deliberately simple and BPM-accurate):
 * - One AudioTrack streaming thread mixes everything. Pads 0-15 are one-shot
 *   sounds (drums + bass notes) triggered immediately.
 * - Pads 16-23 are LOOP pads. A loop pad is not a rendered WAV — it is a
 *   16-step pattern that repeats every bar while the pad is active. Because
 *   patterns are triggered from the transport clock, changing BPM never
 *   drifts a loop out of sync (a pre-rendered loop would).
 * - The step sequencer (drum pads 0-7, 16 steps each) runs on the same clock.
 * - A simple feedback delay (dotted-eighth at the current BPM) is the v1 FX.
 *
 * MIDI is NOT handled here — MidiEngine feeds chord/transport events in,
 * and this engine only plays sound. MIDI hardware testing comes later.
 *
 * Known v1 limitation: the audio path is AudioTrack (Kotlin), not the
 * native Oboe path. That is a latency trade-off for getting a working,
 * compilable engine now; Oboe replaces this mixer in a later phase without
 * changing the pad/pattern API.
 */
object SampleBank {
    const val SAMPLE_RATE = 44100

    // 8x6 grid, familiar to Remixlive users, PlayRX's own sounds and names.
    // Columns are instrument types; rows are variations 1..6.
    // Pad index = row * 8 + column.
    const val COLUMNS = 8
    const val ROWS = 6
    const val PAD_COUNT = 48
    val COLUMN_NAMES = listOf("Kick", "Snare", "Tops", "Bass", "Synth", "Pad", "Vox", "FX")
    val PAD_NAMES: List<String> = (0 until ROWS).flatMap { row ->
        COLUMN_NAMES.map { col -> "$col ${row + 1}" }
    }

    fun columnOf(pad: Int): Int = pad % COLUMNS
    fun rowOf(pad: Int): Int = pad / COLUMNS
    fun isLoopPad(pad: Int): Boolean = columnOf(pad) in listOf(5, 7)
    fun isDrumPad(pad: Int): Boolean = columnOf(pad) in listOf(0, 1, 2)

    // Internal (non-grid) samples used by loop patterns.
    const val SYNTH_STAB = 100
    const val PAD_CHORD = 101
    const val DRONE = 102

    private val BASS_FREQS = doubleArrayOf(
        65.41, 73.42, 82.41, 87.31, 98.00, 110.00, 123.47, 130.81
    )

    private fun noiseBuffer(seconds: Double, seed: Long): FloatArray {
        val n = (seconds * SAMPLE_RATE).toInt()
        val rnd = java.util.Random(seed)
        val out = FloatArray(n)
        var last = 0f
        for (i in out.indices) {
            // Slight high-pass (difference) so hats/crash are bright, not rumbly.
            val white = rnd.nextFloat() * 2f - 1f
            val hp = white - last * 0.7f
            last = white
            out[i] = hp
        }
        return out
    }

    private fun sineNote(freq: Double, seconds: Double, decay: Double): FloatArray {
        val n = (seconds * SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        for (i in out.indices) {
            val t = i.toDouble() / SAMPLE_RATE
            out[i] = (sin(2 * PI * freq * t) * exp(-t * decay)).toFloat()
        }
        return out
    }

    private fun mixInto(dst: FloatArray, src: FloatArray, offset: Int, vol: Float) {
        for (i in src.indices) {
            val d = offset + i
            if (d in dst.indices) dst[d] += src[i] * vol
        }
    }

    fun oneShot(index: Int): FloatArray {
        if (index in 0 until PAD_COUNT) return oneShotPad(index)
        return when (index) {
            SYNTH_STAB -> { // C major stab (legacy internal sample)
                val n = (0.6 * SAMPLE_RATE).toInt()
                val out = FloatArray(n)
                val freqs = doubleArrayOf(261.63, 329.63, 392.00)
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    var v = 0.0
                    for (f in freqs) v += sin(2 * PI * f * t)
                    out[i] = (v / 3.0 * exp(-t * 6)).toFloat()
                }
                out
            }
            PAD_CHORD -> {
                val n = (2.0 * SAMPLE_RATE).toInt()
                val out = FloatArray(n)
                val freqs = doubleArrayOf(261.63, 329.63, 392.00, 523.25)
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val attack = minOf(1.0, t / 0.15)
                    var v = 0.0
                    for (f in freqs) v += sin(2 * PI * f * t)
                    out[i] = (v / 4.0 * attack * exp(-t * 1.2)).toFloat()
                }
                out
            }
            DRONE -> {
                val n = (2.0 * SAMPLE_RATE).toInt()
                val out = FloatArray(n)
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    out[i] = ((sin(2 * PI * 55 * t) + 0.5 * sin(2 * PI * 110 * t)) * 0.4 * exp(-t * 0.8)).toFloat()
                }
                out
            }
            else -> FloatArray(0)
        }
    }

    private fun oneShotPad(pad: Int): FloatArray {
        val col = columnOf(pad)
        val row = rowOf(pad)
        return when (col) {
            0 -> { // Kick: pitch drop, row varies depth/decay
                val n = (0.35 * SAMPLE_RATE).toInt()
                val out = FloatArray(n)
                var phase = 0.0
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val freq = 50.0 + (100 - row * 10).toDouble() * exp(-t * (25 - row * 2).toDouble())
                    phase += 2 * PI * freq / SAMPLE_RATE
                    out[i] = (sin(phase) * exp(-t * (9 - row).toDouble())).toFloat()
                }
                out
            }
            1 -> { // Snare: noise + body
                val noise = noiseBuffer(0.25, (11 + row).toLong())
                val body = sineNote(190.0 - row * 10.0, 0.25, 18.0)
                val out = FloatArray(noise.size)
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    out[i] = (noise[i] * exp(-t * 14).toFloat() * 0.7f + body[i] * 0.6f)
                }
                out
            }
            2 -> { // Tops: even rows closed hat, odd rows open hat
                val noise = noiseBuffer(if (row % 2 == 0) 0.06 else 0.35, (22 + row).toLong())
                val out = FloatArray(noise.size)
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    out[i] = (noise[i] * exp(-t * (if (row % 2 == 0) 90.0 else 12.0))).toFloat()
                }
                out
            }
            3 -> { // Bass: C D E F G A across rows
                val freq = BASS_FREQS[row]
                val n = (0.5 * SAMPLE_RATE).toInt()
                val out = FloatArray(n)
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val v = sin(2 * PI * freq * t) + 0.3 * sin(2 * PI * freq * 2 * t)
                    out[i] = (v * exp(-t * 5) * 0.7).toFloat()
                }
                out
            }
            4 -> { // Synth stab: root transposed by row (C scale)
                val root = BASS_FREQS[row] * 4
                val n = (0.6 * SAMPLE_RATE).toInt()
                val out = FloatArray(n)
                val freqs = doubleArrayOf(root, root * 1.25, root * 1.5)
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    var v = 0.0
                    for (f in freqs) v += sin(2 * PI * f * t)
                    out[i] = (v / 3.0 * exp(-t * 6)).toFloat()
                }
                out
            }
            5 -> { // Pad chord: sustained, row varies root
                val root = BASS_FREQS[row] * 4
                val n = (2.0 * SAMPLE_RATE).toInt()
                val out = FloatArray(n)
                val freqs = doubleArrayOf(root, root * 1.25, root * 1.5, root * 2)
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val attack = minOf(1.0, t / 0.15)
                    var v = 0.0
                    for (f in freqs) v += sin(2 * PI * f * t)
                    out[i] = (v / 4.0 * attack * exp(-t * 1.2)).toFloat()
                }
                out
            }
            6 -> { // Vox: sine + vibrato, row varies pitch
                val freq = 300.0 + row * 50.0
                val n = (0.8 * SAMPLE_RATE).toInt()
                val out = FloatArray(n)
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val vib = sin(2 * PI * 5 * t) * 8
                    out[i] = (sin(2 * PI * (freq + vib) * t) * exp(-t * 3)).toFloat()
                }
                out
            }
            7 -> { // FX: noise sweep, row varies length
                val noise = noiseBuffer(0.4 + row.toDouble() * 0.2, (55 + row).toLong())
                val out = FloatArray(noise.size)
                for (i in out.indices) {
                    val t = i.toDouble() / SAMPLE_RATE
                    out[i] = (noise[i] * exp(-t * (3 + row).toDouble())).toFloat()
                }
                out
            }
            else -> FloatArray(0)
        }
    }

    /** Loop patterns: loop pad -> list of (step 0..15, pad sample index). */
    fun loopPattern(pad: Int): List<Pair<Int, Int>> {
        if (!isLoopPad(pad)) return emptyList()
        val row = rowOf(pad)
        val kick = row * COLUMNS + 0
        val snare = row * COLUMNS + 1
        val tops = row * COLUMNS + 2
        val bass = row * COLUMNS + 3
        val synth = row * COLUMNS + 4
        return when (columnOf(pad)) {
            5 -> listOf(0 to kick, 4 to kick, 8 to kick, 12 to kick, 4 to snare, 12 to snare, 0 to tops, 2 to tops, 4 to tops, 6 to tops, 8 to tops, 10 to tops, 12 to tops, 14 to tops, 0 to synth, 8 to bass)
            else -> listOf(0 to kick, 3 to kick, 6 to kick, 10 to kick, 4 to snare, 12 to snare, 1 to tops, 5 to tops, 9 to tops, 13 to tops, 0 to synth)
        }
    }
}

class PlaybackEngine {
    var bpm: Float = 120f
    var isPlaying: Boolean = false
        private set
    var masterVolume: Float = 0.8f

    /** FX panel state (2026-11-08, see FxEngine.kt). The mixer
     * reads it every block; the UI writes it through the setters
     * below so beat-repeat enable/disable can freeze its slice. */
    var fxState: FxState = FxState()
        private set
    // Per-column filter state, stereo (see the mixer: pan is real
    // stereo since 2026-11-08, so each side has its own state).
    private val columnFilterYL = FloatArray(8)
    private val columnFilterYR = FloatArray(8)
    private var masterFilterYL = 0f
    private var masterFilterYR = 0f
    private val repeatBuf = FloatArray(SampleBank.SAMPLE_RATE * 10)
    private var repeatWritePos = 0
    private var repeatStartPos = 0
    private var repeatLenFrames = 0
    private var repeatActive = false

    fun setDelayEnabled(enabled: Boolean) {
        fxState = fxState.copy(delayEnabled = enabled)
    }

    fun setDelayDivision(beats: Float) {
        fxState = fxState.copy(delayDivisionBeats = beats.coerceIn(0.25f, 2f))
    }

    fun setDelayMix(mix: Float) {
        fxState = fxState.copy(delayMix = mix.coerceIn(0f, 1f))
    }

    fun setDelayFeedback(feedback: Float) {
        fxState = fxState.copy(delayFeedback = feedback.coerceIn(0f, 0.95f))
    }

    /** Enabling freezes the slice that just played (length =
     * the division at the current BPM) and stutters it until Off. */
    fun setBeatRepeat(repeat: BeatRepeat) {
        if (repeat == fxState.beatRepeat) return
        fxState = fxState.copy(beatRepeat = repeat)
        if (repeat == BeatRepeat.OFF) {
            repeatActive = false
        } else {
            repeatLenFrames = FxMath.divisionFrames(repeat.beats, bpm, SampleBank.SAMPLE_RATE)
            repeatStartPos = ((repeatWritePos - repeatLenFrames) % repeatBuf.size + repeatBuf.size) % repeatBuf.size
            repeatActive = true
        }
    }

    fun setBeatRepeatMix(mix: Float) {
        fxState = fxState.copy(beatRepeatMix = mix.coerceIn(0f, 1f))
    }

    fun setMasterFilterCutoff(hz: Float) {
        fxState = fxState.copy(masterFilterCutoffHz = hz.coerceIn(0f, 18000f))
    }

    fun setColumnSend(col: Int, value: Float) {
        if (col in 0..7) fxState = fxState.withColumnSend(col, value)
    }

    fun setColumnFilter(col: Int, hz: Float) {
        if (col in 0..7) fxState = fxState.withColumnFilter(col, hz)
    }

    /** XY pad: X = master filter cutoff (log 80..18000 Hz),
     * Y = delay mix (0..1, top = 1). */
    fun setXy(x: Float, y: Float) {
        fxState = fxState.copy(
            masterFilterCutoffHz = FxMath.xyToCutoff(x),
            delayMix = y.coerceIn(0f, 1f),
        )
    }

    var onStepChanged: ((Int) -> Unit)? = null
    /** Bar (1-based), beat in bar (1..4), progress through the bar 0..1. */
    var onTransportPositionChanged: ((Int, Int, Float) -> Unit)? = null
    var onLoopsChanged: ((Set<Int>) -> Unit)? = null

    // Sequencer: the selected sequence's cells are the pattern.
    // Every step is a StepCell (velocity / offset / duration /
    // roll / pitch), and the store holds the whole sequence list
    // (see Sequencer.kt).
    val sequenceStore = SequenceStore()

    // SONG timeline (2026-11-08, see SongEngine.kt): the
    // arrangement drives loop pads while the transport runs in
    // SONG. songEnabled is set by the UI from the selected tab —
    // the timeline must not hijack pads while the player is in
    // LOOP / DRUM / SEQ. songActivePads are the pads the song
    // itself started, so leaving SONG (or a clip ending) stops
    // exactly those, never a pad the player tapped by hand — with
    // one honest exception, noted in docs/SONG.md: a pad the song
    // started is stopped by the song at the clip end even if the
    // player tapped it too in the meantime.
    val songTimeline = SongTimeline()
    var songEnabled: Boolean = false
        private set
    private var songActivePads = mutableSetOf<Int>()
    private var lastSongBar = 1

    fun setSongEnabled(enabled: Boolean) {
        if (songEnabled == enabled) return
        songEnabled = enabled
        if (!enabled) {
            for (pad in songActivePads) {
                activeLoops.remove(pad)
                voices.removeAll { it.sourcePad == pad }
            }
            songActivePads.clear()
            onLoopsChanged?.invoke(activeLoops.toSet())
        } else if (isPlaying) {
            updateSongClips((totalBeats / 4).toInt() + 1)
        }
    }

    /** Start/stop loop pads for [bar] (1-based, transport bar).
     * Loop pads start for their clip's bars; one-shot pads fire
     * once at the clip's start. Called on every bar change while
     * the transport runs in SONG. */
    fun updateSongClips(bar: Int) {
        if (!songEnabled) return
        val songBar = ((bar - 1) % songTimeline.lengthBars.coerceAtLeast(1)) + 1
        val active = songTimeline.clipsAtBar(songBar)
        val want = active.map { it.pad }.toSet()
        for (pad in songActivePads - want) {
            activeLoops.remove(pad)
            voices.removeAll { it.sourcePad == pad }
        }
        for (clip in active) {
            val pad = clip.pad
            if (pad in songActivePads) continue
            val assignment = importedPads[pad]?.assignment
            val isLooping = assignment?.playMode == PlayMode.LOOP ||
                (assignment == null && SampleBank.isLoopPad(pad))
            if (isLooping) {
                if (pad !in activeLoops) startLoopPad(pad, importedSampleFor(pad))
            } else {
                triggerSample(pad, 0.9f, pad)
            }
        }
        songActivePads = want.toMutableSet()
        onLoopsChanged?.invoke(activeLoops.toSet())
    }
    var isRecording: Boolean = false

    // Recording modes (2026-11-08, Projects slice): the Rec dropdown.
    // DRUMS is the old isRecording path (pad taps -> sequence).
    // SONG captures loop taps as timeline clips. MASTER captures
    // the live mix to a WAV (see finishMasterRecording).
    var recordMode: RecordMode = RecordMode.OFF
        private set
    private var songRecordStarts = mutableMapOf<Int, Int>()
    private val masterCapture = mutableListOf<Float>()
    private val captureLock = Any()
    /** Max master capture: 5 minutes of mono 44.1kHz (~53 MB). */
    private val maxMasterFrames = 44100 * 60 * 5
    var masterRecording: Boolean = false
        private set
    /** The 5-minute capture cap was hit; recording stopped itself. */
    var masterCaptureFull: Boolean = false
        private set

    fun setRecordMode(mode: RecordMode) {
        if (recordMode == mode) return
        if (recordMode == RecordMode.SONG) closeSongRecordClips()
        recordMode = mode
        isRecording = (mode == RecordMode.DRUMS)
        if (mode == RecordMode.MASTER) {
            synchronized(captureLock) { masterCapture.clear() }
            masterCaptureFull = false
            masterRecording = true
        } else {
            masterRecording = false
        }
    }

    /** Stop a master capture and hand the samples to the caller
     * (null when nothing worth saving was captured, under 0.1 s).
     * The caller writes the WAV; the engine keeps no copy. */
    fun finishMasterRecording(): FloatArray? {
        masterRecording = false
        val out = synchronized(captureLock) {
            val copy = masterCapture.toFloatArray()
            masterCapture.clear()
            copy
        }
        if (out.size < 4410) return null
        return out
    }

    private fun rawBarNow(): Int = (totalBeats / 4).toInt() + 1

    /** Close one open SONG-record clip: the loop ran from its start
     * bar to the current bar (at least 1 bar). Overlaps are refused
     * by addClip and dropped silently. */
    private fun closeSongRecordClip(pad: Int) {
        val startRaw = songRecordStarts.remove(pad) ?: return
        val lenBars = songTimeline.lengthBars.coerceAtLeast(1)
        val startBar = ((startRaw - 1) % lenBars) + 1
        val length = (rawBarNow() - startRaw + 1).coerceIn(1, lenBars - startBar + 1)
        songTimeline.addClip(SampleBank.columnOf(pad), startBar, length, pad, SampleBank.PAD_NAMES[pad])
    }

    private fun closeSongRecordClips() {
        for (pad in songRecordStarts.keys.toList()) closeSongRecordClip(pad)
    }

    /** Pads with imported samples: pad -> (sampleId, assignment).
     * Projects persist this; the sample files stay in SampleStore. */
    fun importedPadAssignments(): Map<Int, Pair<String, PadSample>> =
        importedPads.mapValues { (_, v) -> v.info.id to v.assignment }

    fun sequences(): List<Sequence> = sequenceStore.list()
    fun selectedSequence(): Sequence? = sequenceStore.selected()
    fun createSequence(): Sequence = sequenceStore.create()
    fun deleteSequence(id: String): Boolean = sequenceStore.delete(id)
    fun selectSequence(id: String): Boolean = sequenceStore.select(id)
    fun renameSequence(id: String, name: String): Boolean = sequenceStore.rename(id, name)
    fun setSequenceLoop(loop: Boolean) {
        sequenceStore.updateSelected { it.copy(loop = loop) }
    }

    fun setSequenceGain(gain: Float) {
        sequenceStore.updateSelected { it.copy(gain = gain.coerceIn(0f, 1f)) }
    }

    fun getStepCell(pad: Int, step: Int): StepCell =
        sequenceStore.selected()?.cell(pad, step) ?: StepCell()

    fun setStepCell(pad: Int, step: Int, cell: StepCell) {
        sequenceStore.updateSelected { it.withCell(pad, step, cell) }
    }

    /** Quantize a live tap into the selected sequence (Drums &
     * Sounds recording). Returns the step, or -1 when not recording
     * or the pad is invalid. [beats] defaults to the live transport
     * clock; tests pass it explicitly. */
    fun recordPadTap(pad: Int, beats: Double = totalBeats): Int {
        if (!isRecording) return -1
        return sequenceStore.recordTap(pad, beats)
    }

    /** Record a scale-keyboard note into the selected sequence. */
    fun recordNote(pad: Int, midi: Int, beats: Double = totalBeats): Int {
        if (!isRecording) return -1
        return sequenceStore.recordNoteStep(pad, beats, ScaleKeyboard.recordedPitch(midi))
    }

    /** Play a scale-keyboard note live (synth, not a pad sample).
     * If recording, the note is also written into the selected
     * sequence on [pad] (the selected pad, passed by the UI). */
    private val noteCache = mutableMapOf<Int, FloatArray>()
    fun playNote(midi: Int, velocity: Float = 0.9f, pad: Int = 0) {
        if (midi !in 24..108) return
        if (isRecording) recordNote(pad, midi)
        val freq = ScaleKeyboard.frequency(midi)
        val buffer = noteCache.getOrPut(midi) {
            val n = (0.8 * SampleBank.SAMPLE_RATE).toInt()
            val out = FloatArray(n)
            for (i in out.indices) {
                val t = i.toDouble() / SampleBank.SAMPLE_RATE
                val v = sin(2 * PI * freq * t) +
                    0.3 * sin(2 * PI * freq * 2 * t) +
                    0.15 * sin(2 * PI * freq * 3 * t)
                out[i] = (v * exp(-t * 3) * 0.7).toFloat()
            }
            out
        }
        pending.add(Voice(buffer, 0, velocity.coerceIn(0f, 1f), pad))
    }

    /** Drum View tap: fire a pad once, even if it is a loop pad.
     * Loops keep playing underneath (Remixlive: loops keep playing
     * when you switch to Drum). Records like any other tap. */
    fun triggerOneShot(pad: Int) {
        if (pad !in 0 until SampleBank.PAD_COUNT) return
        if (isRecording) recordPadTap(pad)
        val vol = effectiveVolume(pad, 1f)
        if (vol <= 0f) return
        val imported = importedSampleFor(pad)
        if (imported != null) pending.add(Voice(imported, 0, vol, pad))
        else pending.add(Voice(sample(pad), 0, vol, pad))
    }
    private val activeLoops = mutableSetOf<Int>()
    // Loop pads tapped while the transport is running, waiting for
    // their quantize boundary (default: next bar). They are NOT in
    // activeLoops until the boundary, so they make no sound before
    // it — the whole point of bar sync is that a tap mid-bar must
    // not start a loop mid-bar.
    private val pendingLoops = mutableMapOf<Int, Double>()
    fun armedLoopPads(): Set<Int> = pendingLoops.keys.toSet()
    fun armedLoopPadsWithBeat(): Map<Int, Double> = pendingLoops.toMap()

    // Imported samples (SampleEngine, 2026-11-01): per pad, the raw
    // decoded frames + assignment + info, and the rendered buffer
    // actually played. Rendered buffers are rebuilt whenever BPM or
    // project key changes, so tempo snap and key dynamics stay live.
    private data class ImportedPad(
        val raw: FloatArray,
        val assignment: PadSample,
        val info: SampleInfo,
        var rendered: FloatArray,
    )
    private val importedPads = mutableMapOf<Int, ImportedPad>()
    var projectKeyMidi: Int? = null
        private set

    fun setProjectKey(midiPitchClass: Int?) {
        projectKeyMidi = midiPitchClass?.let { ((it % 12) + 12) % 12 }
        refreshImportedSamples()
    }

    fun assignImportedSample(pad: Int, raw: FloatArray, assignment: PadSample, info: SampleInfo) {
        if (pad !in 0 until SampleBank.PAD_COUNT) return
        val rendered = SampleEngine.renderPadSample(raw, assignment, info, bpm, projectKeyMidi)
        importedPads[pad] = ImportedPad(raw, assignment, info, rendered)
        requestVoiceSwap()
    }

    /** Unload one pad's sample (EDIT Unload): the pad returns to
     * the built-in synth, its voices stop, and it leaves the loop
     * and armed sets. The sample stays in the library. */
    fun clearImportedSample(pad: Int) {
        if (!importedPads.containsKey(pad)) return
        importedPads.remove(pad)
        voices.removeAll { it.sourcePad == pad }
        gateVoices.remove(pad)
        activeLoops.remove(pad)
        pendingLoops.remove(pad)
        songRecordStarts.remove(pad)
        onLoopsChanged?.invoke(activeLoops.toSet())
    }

    /** Clear every pad using [sampleId] (delete-sample path):
     * stops their voices, un-arms and un-activates them. */
    fun clearImportedSamplesById(sampleId: String) {
        val pads = importedPads.filter { it.value.info.id == sampleId }.keys.toList()
        for (pad in pads) {
            importedPads.remove(pad)
            voices.removeAll { it.sourcePad == pad }
            activeLoops.remove(pad)
            pendingLoops.remove(pad)
        }
        if (pads.isNotEmpty()) onLoopsChanged?.invoke(activeLoops.toSet())
    }

    fun importedSampleName(pad: Int): String? = importedPads[pad]?.info?.name
    fun importedSampleInfo(pad: Int): SampleInfo? = importedPads[pad]?.info
    fun importedAssignment(pad: Int): PadSample? = importedPads[pad]?.assignment
    fun importedPadIds(): Set<Int> = importedPads.keys.toSet()

    fun updateImportedAssignment(pad: Int, assignment: PadSample) {
        val cur = importedPads[pad] ?: return
        importedPads[pad] = cur.copy(
            assignment = assignment,
            rendered = SampleEngine.renderPadSample(cur.raw, assignment, cur.info, bpm, projectKeyMidi),
        )
        requestVoiceSwap()
    }

    /** Re-detect the tempo of the sample on [pad] from its raw
     * frames. Returns the detected BPM, or null if no clear tempo.
     * On success the info (and every pad using that sample) is
     * updated and re-rendered, like a manual BPM edit. */
    fun detectImportedTempo(pad: Int): Float? {
        val cur = importedPads[pad] ?: return null
        val detected = SampleEngine.detectBpm(cur.raw, cur.info.sampleRate) ?: return null
        updateImportedSampleInfo(cur.info.id, cur.info.copy(originalBpm = detected))
        return detected
    }

    /** Replace a sample's info (original BPM/key edit) and re-render
     * every pad that uses that sample. */
    fun updateImportedSampleInfo(sampleId: String, info: SampleInfo) {
        for ((pad, cur) in importedPads) {
            if (cur.info.id == sampleId) {
                importedPads[pad] = cur.copy(
                    info = info,
                    rendered = SampleEngine.renderPadSample(cur.raw, cur.assignment, info, bpm, projectKeyMidi),
                )
            }
        }
        requestVoiceSwap()
    }

    /** Re-render every imported pad at the current BPM / project key. */
    fun refreshImportedSamples() {
        for ((pad, cur) in importedPads) {
            importedPads[pad] = cur.copy(
                rendered = SampleEngine.renderPadSample(cur.raw, cur.assignment, cur.info, bpm, projectKeyMidi),
            )
        }
        requestVoiceSwap()
    }

    /**
     * Live tempo/key swap (2026-11-02 audit fix, the reported bug).
     *
     * Until now, re-rendering only replaced the buffer stored in
     * [importedPads]. A loop voice already playing holds a direct
     * reference to the buffer it was started with, so it kept
     * playing the OLD tempo forever: changing project BPM, tapping
     * Detect tempo, editing Original BPM, transposing, or changing
     * the project key did literally nothing to a loop already
     * sounding. Only stop + re-trigger picked the new render up,
     * which is why the user reported "detection works but it
     * doesn't stretch" — the only audible change was the bar-sync
     * start of a newly tapped loop. Synth loops never had this bug:
     * they are pattern-driven from the transport clock, so BPM
     * changes reach them immediately; imported loops are
     * pre-rendered buffers, so they needed this swap.
     *
     * The swap itself runs in the mixer thread (see mixLoop), not
     * here, so the voice list is never mutated from the UI thread
     * mid-iteration. This method only flags that a swap is due, and
     * only when an imported loop is actually active — a BPM change
     * with no imported loop playing costs nothing.
     */
    @Volatile private var voiceSwapRequested = false

    private fun requestVoiceSwap() {
        if (activeLoops.any { importedPads.containsKey(it) }) {
            voiceSwapRequested = true
        }
    }

    /**
     * Position in [rendered] that corresponds to the current
     * transport position, for a loop that starts on a bar. A loop
     * rendered to whole bars at the new BPM has
     * rendered.size / barFrames whole bars; its position within
     * the current bar-cycle is totalBeats modulo that cycle. Using
     * the transport position (not a scaled old position) is what
     * keeps a re-rendered loop in phase with the beat counter and
     * with synth loops after a BPM change — the old position was
     * measured in the old tempo and would drift by exactly the
     * tempo ratio on every repeat.
     */
    private fun transportAlignedPos(renderedSize: Int, sample: FloatArray, oldPos: Int): Int {
        if (renderedSize <= 0) return 0
        val barFrames = (SampleBank.SAMPLE_RATE * 60.0 / bpm * 4).toInt()
        if (barFrames <= 0) {
            return ((oldPos.toLong() * renderedSize / sample.size.coerceAtLeast(1)).toInt())
                .coerceIn(0, renderedSize - 1)
        }
        val loopBars = maxOf(1, (renderedSize.toDouble() / barFrames).roundToInt())
        val beatsPerLoop = loopBars * 4.0
        val posBeats = totalBeats % beatsPerLoop
        return ((posBeats / beatsPerLoop) * renderedSize).toInt().coerceIn(0, renderedSize - 1)
    }

    /** Stop all voices belonging to one pad (GATE release). */
    fun releasePad(pad: Int) {
        voices.removeAll { it.sourcePad == pad }
    }

    /** Waveform peaks for the EDIT panel: the assigned sample's raw
     * frames reduced to [buckets] peak-abs values (0..1). Empty
     * when the pad has no sample (built-in synth has no waveform). */
    fun samplePeaks(pad: Int, buckets: Int = 64): FloatArray {
        val raw = importedPads[pad]?.raw ?: return FloatArray(0)
        if (raw.isEmpty() || buckets <= 0) return FloatArray(0)
        val out = FloatArray(buckets)
        val per = raw.size.toDouble() / buckets
        for (b in 0 until buckets) {
            val start = (b * per).toInt()
            val end = minOf(raw.size, ((b + 1) * per).toInt().coerceAtLeast(start + 1))
            var peak = 0f
            for (i in start until end) {
                val v = kotlin.math.abs(raw[i])
                if (v > peak) peak = v
            }
            out[b] = peak.coerceIn(0f, 1f)
        }
        return out
    }

    // GATE hold-to-play (2026-11-08): a GATE pad sounds only while
    // held. pressGatePad starts a voice tracked here; releaseGatePad
    // stops it. Taps through triggerPad still fire once (sequencer
    // steps use that path), so a held pad and a sequenced GATE hit
    // never share a voice.
    private val gateVoices = mutableMapOf<Int, Voice>()

    /** Pads currently sounding from a hold (test + UI state). */
    fun activeGatePads(): Set<Int> = gateVoices.keys.toSet()

    fun pressGatePad(pad: Int) {
        val assignment = importedPads[pad]?.assignment
        if (assignment?.playMode != PlayMode.GATE) return
        if (gateVoices.containsKey(pad)) return
        val vol = effectiveVolume(pad, 1f)
        if (vol <= 0f) return
        val imported = importedSampleFor(pad)
        val voice = if (imported != null) Voice(imported, 0, vol, pad)
            else Voice(sample(pad), 0, vol, pad)
        gateVoices[pad] = voice
        pending.add(voice)
    }

    fun releaseGatePad(pad: Int) {
        val voice = gateVoices.remove(pad) ?: return
        voices.remove(voice)
        pending.remove(voice)
    }

    // Full-pad mixer: per-pad volume, mute, solo (48 pads) plus
    // per-column mixer (8 instrument columns, like a channel strip).
    private val padVolumes = FloatArray(SampleBank.PAD_COUNT) { 1f }
    private val padMuted = BooleanArray(SampleBank.PAD_COUNT)
    private val padSolo = BooleanArray(SampleBank.PAD_COUNT)
    private val columnVolumes = FloatArray(SampleBank.COLUMNS) { 1f }
    private val columnMuted = BooleanArray(SampleBank.COLUMNS)
    private val columnSolo = BooleanArray(SampleBank.COLUMNS)

    fun setPadVolume(pad: Int, volume: Float) {
        if (pad in 0 until SampleBank.PAD_COUNT) padVolumes[pad] = volume.coerceIn(0f, 1f)
    }

    fun getPadVolume(pad: Int): Float = if (pad in 0 until SampleBank.PAD_COUNT) padVolumes[pad] else 1f
    fun setPadMuted(pad: Int, muted: Boolean) {
        if (pad in 0 until SampleBank.PAD_COUNT) padMuted[pad] = muted
    }

    fun isPadMuted(pad: Int): Boolean = pad in 0 until SampleBank.PAD_COUNT && padMuted[pad]
    fun setPadSolo(pad: Int, solo: Boolean) {
        if (pad in 0 until SampleBank.PAD_COUNT) padSolo[pad] = solo
    }

    fun isPadSolo(pad: Int): Boolean = pad in 0 until SampleBank.PAD_COUNT && padSolo[pad]
    fun setColumnVolume(col: Int, volume: Float) {
        if (col in 0 until SampleBank.COLUMNS) columnVolumes[col] = volume.coerceIn(0f, 1f)
    }

    fun getColumnVolume(col: Int): Float = if (col in 0 until SampleBank.COLUMNS) columnVolumes[col] else 1f
    fun setColumnMuted(col: Int, muted: Boolean) {
        if (col in 0 until SampleBank.COLUMNS) columnMuted[col] = muted
    }

    fun isColumnMuted(col: Int): Boolean = col in 0 until SampleBank.COLUMNS && columnMuted[col]
    fun setColumnSolo(col: Int, solo: Boolean) {
        if (col in 0 until SampleBank.COLUMNS) columnSolo[col] = solo
    }

    fun isColumnSolo(col: Int): Boolean = col in 0 until SampleBank.COLUMNS && columnSolo[col]

    /** Effective volume for a pad, applying pad + column mute/solo rules. */
    private fun effectiveVolume(pad: Int, base: Float): Float {
        if (pad !in 0 until SampleBank.PAD_COUNT) return base
        val col = SampleBank.columnOf(pad)
        if (padMuted[pad] || columnMuted[col]) return 0f
        val anySolo = padSolo.any { it } || columnSolo.any { it }
        if (anySolo && !padSolo[pad] && !columnSolo[col]) return 0f
        return base * padVolumes[pad] * columnVolumes[col]
    }

    fun setStep(track: Int, step: Int, on: Boolean) {
        if (track in 0 until SampleBank.PAD_COUNT && step in 0..15) {
            val cur = getStepCell(track, step)
            setStepCell(track, step, if (on) cur.copy(on = true) else cur.copy(on = false))
        }
    }

    fun getStep(track: Int, step: Int): Boolean =
        getStepCell(track, step).on

    fun activeLoopPads(): Set<Int> = activeLoops.toSet()

    private data class Voice(
        val sample: FloatArray,
        var pos: Int,
        val volume: Float,
        val sourcePad: Int = -1,
        val transportLocked: Boolean = false,
        /** Cut the voice short after this many frames (-1 = whole sample).
         * Per-step Duration in the sequencer. */
        val maxFrames: Int = -1,
    )

    /** A step hit waiting out its Offset / Roll subdivision before
     * it sounds. Counted down in frames inside mixLoop, so a 16th
     * nudge or a roll subdivision lands on the sample clock, not
     * on the UI thread. */
    private data class DelayedVoice(
        var framesLeft: Int,
        val sample: FloatArray,
        val volume: Float,
        val sourcePad: Int,
        val maxFrames: Int = -1,
    )

    private val delayedVoices = mutableListOf<DelayedVoice>()

    private val pending = java.util.concurrent.ConcurrentLinkedQueue<Voice>()
    private val voices = mutableListOf<Voice>()
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    private var totalBeats = 0.0
    private var lastStep = -1

    // One-shot cache so taps don't re-synthesize every time.
    private val oneShotCache = mutableMapOf<Int, FloatArray>()
    private fun sample(index: Int): FloatArray =
        oneShotCache.getOrPut(index) { SampleBank.oneShot(index) }

    /** Rendered imported buffer for a pad, or null if it uses synth. */
    private fun importedSampleFor(pad: Int): FloatArray? = importedPads[pad]?.rendered

    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            SampleBank.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = minBuf * 4
        val at = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SampleBank.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufSize)
            .build()
        track = at
        running = true
        at.play()
        thread = Thread({ mixLoop(at) }, "playrx-mixer").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        voices.clear()
        activeLoops.clear()
        pendingLoops.clear()
    }

    fun setPlaying(playing: Boolean) {
        isPlaying = playing
        if (!playing) {
            lastStep = -1
            // A hit still waiting out its offset/roll delay dies on
            // pause — it must not fire late on resume.
            delayedVoices.clear()
        } else if (totalBeats == 0.0 && songEnabled) {
            // Starting from Stop in SONG: bar 1 clips start now.
            lastSongBar = 1
            updateSongClips(1)
        }
    }

    fun stopAll() {
        // Stopping closes any open SONG-record clips at this bar,
        // before totalBeats resets to 0 below.
        if (recordMode == RecordMode.SONG) closeSongRecordClips()
        voices.clear()
        delayedVoices.clear()
        songActivePads.clear()
        lastSongBar = 1
        voiceSwapRequested = false
        activeLoops.clear()
        pendingLoops.clear()
        onLoopsChanged?.invoke(emptySet())
        isPlaying = false
        lastStep = -1
        totalBeats = 0.0
        onTransportPositionChanged?.invoke(1, 1, 0f)
    }

    /** Tap a pad. One-shots fire immediately; loop pads toggle.
     *
     * Bar sync (2026-11-02, the live-performance fix): a loop pad
     * tapped while the transport is running does NOT start at the
     * tap. It is armed and starts at its next quantize boundary —
     * Bar (4 beats) by default for LOOP pads, Beat (1) if the pad's
     * quantize is set to a beat, immediate if quantize is Off.
     * A tap while the transport is stopped starts immediately and
     * starts the transport (there is no bar to sync to yet).
     * Tapping an armed pad again cancels the arm; tapping a playing
     * loop stops it immediately (stop is not quantized).
     */
    fun triggerPad(pad: Int) {
        // Drums & Sounds recording: every tap lands in the selected
        // sequence, quantized to the nearest 16th (see Sequencer.kt).
        if (isRecording) recordPadTap(pad)
        val imported = importedSampleFor(pad)
        val assignment = importedPads[pad]?.assignment
        // An imported pad's play mode wins over the column default:
        // a LOOP-mode imported sample on any column toggles like a
        // loop pad; a ONE_SHOT imported sample on a loop column fires
        // once. GATE is held via releasePad (see docs).
        val isLooping = when {
            assignment != null -> assignment.playMode == PlayMode.LOOP
            else -> SampleBank.isLoopPad(pad)
        }
        if (isLooping) {
            if (pendingLoops.containsKey(pad)) {
                // Cancel the arm — no sound ever started.
                pendingLoops.remove(pad)
                onLoopsChanged?.invoke(activeLoops.toSet())
            } else if (activeLoops.contains(pad)) {
                activeLoops.remove(pad)
                // Stop any still-playing voice of this loop pad.
                voices.removeAll { it.sourcePad == pad }
                // SONG recording: stopping the loop closes its clip.
                if (recordMode == RecordMode.SONG) closeSongRecordClip(pad)
                onLoopsChanged?.invoke(activeLoops.toSet())
            } else {
                val quantize = assignment?.quantizeBeats ?: 4f
                if (!isPlaying || quantize <= 0f) {
                    startLoopPad(pad, imported, userInitiated = true)
                } else {
                    // Next boundary strictly after now: if the tap
                    // lands exactly on a bar, start at once; otherwise
                    // wait. totalBeats only advances while playing,
                    // so the boundary is stable while paused.
                    val q = quantize.toDouble()
                    val boundary = kotlin.math.ceil(totalBeats / q) * q
                    if (boundary <= totalBeats + 1e-6) {
                        startLoopPad(pad, imported, userInitiated = true)
                    } else {
                        pendingLoops[pad] = boundary
                        onLoopsChanged?.invoke(activeLoops.toSet())
                    }
                }
            }
        } else {
            // SONG recording: a one-shot tap becomes a 1-bar clip
            // that fires once at its start bar on playback.
            if (recordMode == RecordMode.SONG) {
                val lenBars = songTimeline.lengthBars.coerceAtLeast(1)
                val startBar = ((rawBarNow() - 1) % lenBars) + 1
                songTimeline.addClip(
                    SampleBank.columnOf(pad), startBar, 1, pad, SampleBank.PAD_NAMES[pad],
                )
            }
            val vol = effectiveVolume(pad, 1f)
            if (vol > 0f) {
                if (imported != null) pending.add(Voice(imported, 0, vol, pad))
                else pending.add(Voice(sample(pad), 0, vol, pad))
            }
        }
    }

    private fun startLoopPad(pad: Int, imported: FloatArray?, userInitiated: Boolean = false) {
        activeLoops.add(pad)
        // SONG recording: a loop the player starts becomes a clip
        // from this bar until the player stops it. Song-driven
        // starts (updateSongClips) are NOT recorded — they are the
        // timeline playing back, not a performance to capture.
        if (userInitiated && recordMode == RecordMode.SONG && pad !in songRecordStarts) {
            songRecordStarts[pad] = rawBarNow()
        }
        if (!isPlaying) isPlaying = true
        // Loop pads with imported samples are voiced by the mixer;
        // synth loop pads use their 16-step pattern there. Imported
        // loops play as a whole buffer, looped by the voice below.
        if (imported != null) {
            val vol = effectiveVolume(pad, 1f)
            if (vol > 0f) pending.add(Voice(imported, 0, vol, pad, transportLocked = true))
        }
        onLoopsChanged?.invoke(activeLoops.toSet())
    }

    // Pitch-shifted synth one-shots, cached per (pad, semitones):
    // a step's Pitch param resamples the synth buffer, it does not
    // re-synthesize. Imported samples ignore per-step pitch — they
    // keep their own transpose (see docs/SEQUENCER.md).
    private val pitchedCache = mutableMapOf<Pair<Int, Int>, FloatArray>()
    private fun pitchedSample(index: Int, semitones: Int): FloatArray {
        if (semitones == 0) return sample(index)
        return pitchedCache.getOrPut(index to semitones) {
            val src = sample(index)
            val rate = SampleEngine.pitchRate(semitones)
            val outLen = maxOf(1, (src.size / rate).toInt())
            val out = FloatArray(outLen)
            for (i in out.indices) {
                val pos = i * rate
                val lo = pos.toInt().coerceIn(0, src.size - 1)
                val hi = (lo + 1).coerceIn(0, src.size - 1)
                val frac = (pos - lo).toFloat()
                out[i] = src[lo] * (1f - frac) + src[hi] * frac
            }
            out
        }
    }

    private fun triggerSample(index: Int, volume: Float = 0.9f, sourcePad: Int = -1) {
        triggerStepSample(index, volume, sourcePad, StepCell(on = true))
    }

    /** Fire one sequencer step, honouring its cell: velocity scales
     * the hit, offset delays it, roll retriggers inside the step,
     * duration caps the ring, pitch resamples synth pads. A pad
     * with an imported sample plays that sample (not the synth) —
     * the old code always played the synth from sequencer steps. */
    private fun triggerStepSample(index: Int, volume: Float, sourcePad: Int, cell: StepCell) {
        val pad = if (sourcePad in 0 until SampleBank.PAD_COUNT) sourcePad else index
        val vol = effectiveVolume(pad, volume * cell.velocity)
        if (vol <= 0f) return
        val imported = importedSampleFor(pad)
        val buffer = imported ?: pitchedSample(index, cell.pitchSemitones)
        val framesPerBeat = (SampleBank.SAMPLE_RATE * 60.0 / bpm).toInt().coerceAtLeast(1)
        val stepFrames = framesPerBeat / 4
        val hitFrames = (cell.durationBeats * framesPerBeat).toInt().coerceAtLeast(1)
        val roll = cell.roll.coerceIn(1, 4)
        for (k in 0 until roll) {
            // Offset applies to the first hit; roll hits subdivide
            // the step evenly after it.
            val delayBeats = cell.offsetBeats + if (roll > 1) k * (0.25f / roll) else 0f
            val delayFrames = (delayBeats * framesPerBeat).toInt().coerceAtLeast(0)
            val hitMax = if (roll > 1) (hitFrames / roll).coerceAtLeast(1) else hitFrames
            if (delayFrames <= 0) {
                pending.add(Voice(buffer, 0, vol, pad, maxFrames = hitMax))
            } else {
                delayedVoices.add(DelayedVoice(delayFrames, buffer, vol, pad, maxFrames = hitMax))
            }
        }
    }

    private fun mixLoop(at: AudioTrack) {
        val blockFrames = 512
        val outShorts = ShortArray(blockFrames * 2)
        // 4-second delay line (mono, shared by both channels).
        // 4 s covers the longest division (2 beats) even at 60 BPM.
        val delayBuf = FloatArray(SampleBank.SAMPLE_RATE * 4)
        var delayPos = 0
        val colSumL = FloatArray(8)
        val colSumR = FloatArray(8)

        while (running) {
            // Drain pending one-shots into the voice list.
            while (true) {
                val v = pending.poll() ?: break
                voices.add(v)
            }

            // Advance the transport clock and fire step events.
            // The clock ONLY advances while playing: pausing used to
            // keep totalBeats running, so a bar boundary computed
            // while paused was already in the past on resume, and
            // imported loops kept looping out of phase with the bar.
            if (isPlaying) {
                totalBeats += blockFrames.toDouble() / SampleBank.SAMPLE_RATE * (bpm / 60.0)
                // Fire any armed loops whose boundary this block crossed.
                val due = pendingLoops.filter { it.value <= totalBeats + 1e-9 }.keys.toList()
                for (pad in due) {
                    pendingLoops.remove(pad)
                    startLoopPad(pad, importedSampleFor(pad), userInitiated = true)
                }
                val step = ((totalBeats * 4).toInt() % 16 + 16) % 16
                if (step != lastStep) {
                    val wrapped = lastStep == 15 && step == 0
                    if (wrapped && sequenceStore.selected()?.loop == false) {
                        // One-shot sequence: the bar finished, stop
                        // the transport instead of looping it.
                        lastStep = -1
                        isPlaying = false
                        onStepChanged?.invoke(-1)
                        onTransportPositionChanged?.invoke(1, 1, 0f)
                    } else {
                    lastStep = step
                    onStepChanged?.invoke(step)
                    // Circular beat counter (2026-11-02): the baseline
                    // starts at Bar 1 Beat 1 when Play is tapped or
                    // the first loop starts (totalBeats = 0 after
                    // Stop), and advances from there while playing.
                    val bar = (totalBeats / 4).toInt() + 1
                    val beatInBar = (totalBeats % 4).toInt() + 1
                    val progress = ((totalBeats % 4) / 4).toFloat()
                    onTransportPositionChanged?.invoke(bar, beatInBar, progress)
                    val seqGain = sequenceStore.selected()?.gain ?: 1f
                    for (trk in 0 until SampleBank.PAD_COUNT) {
                        val cell = getStepCell(trk, step)
                        if (cell.on) triggerStepSample(trk, 0.9f * seqGain, trk, cell)
                    }
                    for (loopPad in activeLoops.toList()) {
                        if (importedPads.containsKey(loopPad)) continue
                        for ((s, sampleIdx) in SampleBank.loopPattern(loopPad)) {
                            if (s == step) triggerSample(sampleIdx, 0.85f, loopPad)
                        }
                    }
                    // Drain voices triggered by this step immediately.
                    while (true) {
                        val v = pending.poll() ?: break
                        voices.add(v)
                    }
                    }
                }
                // Offset / roll hits wait out their delay on the
                // sample clock, frozen while paused.
                val delayIter = delayedVoices.iterator()
                while (delayIter.hasNext()) {
                    val d = delayIter.next()
                    d.framesLeft -= blockFrames
                    if (d.framesLeft <= 0) {
                        delayIter.remove()
                        voices.add(Voice(d.sample, 0, d.volume, d.sourcePad, maxFrames = d.maxFrames))
                    }
                }
                // SONG timeline: on every bar line, start/stop the
                // clips covering the new bar.
                if (songEnabled) {
                    val barNow = (totalBeats / 4).toInt() + 1
                    if (barNow != lastSongBar) {
                        lastSongBar = barNow
                        updateSongClips(barNow)
                    }
                }
            }
            // Live re-render swap: if BPM, key, Detect, or any
            // EDIT change re-rendered an imported loop that is
            // already sounding, swap its voice to the new buffer
            // HERE, in the mixer thread, at the transport-aligned
            // position — not at the next trigger, and not at its
            // old (wrong-tempo) position. Paused voices get the
            // same swap; they stay frozen (transportLocked) until
            // resume, so they resume at the new tempo in phase.
            // One-shot voices are never swapped: a one-shot that
            // already fired is an event, not a loop.
            if (voiceSwapRequested) {
                voiceSwapRequested = false
                val swapped = mutableListOf<Voice>()
                val swapIter = voices.iterator()
                while (swapIter.hasNext()) {
                    val v = swapIter.next()
                    if (v.transportLocked && v.sourcePad in activeLoops) {
                        val rendered = importedPads[v.sourcePad]?.rendered
                        if (rendered != null && rendered.isNotEmpty() && rendered !== v.sample) {
                            swapIter.remove()
                            swapped.add(
                                Voice(
                                    rendered,
                                    transportAlignedPos(rendered.size, v.sample, v.pos),
                                    v.volume, v.sourcePad, transportLocked = true,
                                )
                            )
                        }
                    }
                }
                voices.addAll(swapped)
            }
            for (frame in 0 until blockFrames) {
                // Sum voices per column, stereo: the per-column
                // filter and the delay send both work on column
                // sums, and a pad's pan (PadSample.pan, resolved
                // here so a pan edit reaches a voice already
                // sounding) splits its signal left/right first.
                java.util.Arrays.fill(colSumL, 0f)
                java.util.Arrays.fill(colSumR, 0f)
                val iter = voices.iterator()
                while (iter.hasNext()) {
                    val v = iter.next()
                    // Transport-locked (imported loop) voices freeze
                    // while paused: they make no sound and do not
                    // advance, so they resume exactly on the bar
                    // instead of looping out of phase under a pause.
                    // One-shot voices still play out while paused.
                    if (!isPlaying && v.transportLocked) continue
                    // Per-step Duration: cut the voice short.
                    if (v.maxFrames > 0 && v.pos >= v.maxFrames) {
                        iter.remove()
                        continue
                    }
                    if (v.pos >= v.sample.size) {
                        // Imported loop voices loop until the pad is
                        // toggled off; synth voices end at the buffer.
                        if (v.sourcePad in activeLoops && importedPads.containsKey(v.sourcePad)) {
                            v.pos = 0
                        } else {
                            iter.remove()
                            continue
                        }
                    }
                    val s = v.sample[v.pos] * v.volume
                    val (panL, panR) = FxMath.panGains(
                        importedPads[v.sourcePad]?.assignment?.pan ?: 0f,
                    )
                    val col = SampleBank.columnOf(v.sourcePad)
                    colSumL[col] += s * panL
                    colSumR[col] += s * panR
                    v.pos++
                }
                // Per-column one-pole low-pass (Mixer "Eq Filter" tab),
                // then the master sum; the delay eats a per-column
                // send of the filtered signal (mono sum of the sides).
                var dryL = 0f
                var dryR = 0f
                var delayInput = 0f
                for (c in 0..7) {
                    val cutoff = fxState.columnFilterCutoffHz[c]
                    val (filteredL, filteredR) = if (cutoff <= 0f || cutoff >= 18000f) {
                        colSumL[c] to colSumR[c]
                    } else {
                        val alpha = FxMath.onePoleAlpha(cutoff, SampleBank.SAMPLE_RATE)
                        columnFilterYL[c] = FxMath.onePole(colSumL[c], columnFilterYL[c], alpha)
                        columnFilterYR[c] = FxMath.onePole(colSumR[c], columnFilterYR[c], alpha)
                        columnFilterYL[c] to columnFilterYR[c]
                    }
                    dryL += filteredL
                    dryR += filteredR
                    delayInput += (filteredL + filteredR) * 0.5f * fxState.columnSends[c]
                }
                dryL *= masterVolume
                dryR *= masterVolume
                // Master filter (XY pad X): one-pole low-pass on the mix.
                val masterCutoff = fxState.masterFilterCutoffHz
                if (masterCutoff > 0f && masterCutoff < 18000f) {
                    val alpha = FxMath.onePoleAlpha(masterCutoff, SampleBank.SAMPLE_RATE)
                    masterFilterYL = FxMath.onePole(dryL, masterFilterYL, alpha)
                    masterFilterYR = FxMath.onePole(dryR, masterFilterYR, alpha)
                    dryL = masterFilterYL
                    dryR = masterFilterYR
                }
                // Beat repeat: the dry mix (mono) is always recorded
                // into the ring; while active, the output stutters
                // the slice frozen at enable time, on both sides.
                val dryMono = (dryL + dryR) * 0.5f
                repeatBuf[repeatWritePos] = dryMono
                repeatWritePos = (repeatWritePos + 1) % repeatBuf.size
                if (repeatActive) {
                    val readPos = FxMath.repeatReadPos(
                        repeatWritePos, repeatStartPos, repeatLenFrames, repeatBuf.size,
                    )
                    val repeated = repeatBuf[readPos]
                    dryL = dryL * (1f - fxState.beatRepeatMix) + repeated * fxState.beatRepeatMix
                    dryR = dryR * (1f - fxState.beatRepeatMix) + repeated * fxState.beatRepeatMix
                }
                // Tempo-synced feedback delay on the master mix.
                if (fxState.delayEnabled) {
                    val delaySamples = FxMath.divisionFrames(
                        fxState.delayDivisionBeats, bpm, SampleBank.SAMPLE_RATE,
                    ).coerceIn(1, delayBuf.size - 1)
                    val delayed = delayBuf[delayPos]
                    dryL += delayed * fxState.delayMix
                    dryR += delayed * fxState.delayMix
                    delayBuf[delayPos] =
                        delayInput * masterVolume + delayed * fxState.delayFeedback
                    delayPos = (delayPos + 1) % delaySamples
                    // Keep delayPos within the active delay window.
                    if (delayPos >= delaySamples) delayPos = 0
                }
                // Soft clip (tanh) instead of a hard clamp: several
                // loops + one-shots summing past 1.0 used to square
                // off into harsh distortion. tanh rounds the top off.
                val clampedL = kotlin.math.tanh(dryL.toDouble()).toFloat()
                val clampedR = kotlin.math.tanh(dryR.toDouble()).toFloat()
                // Master Audio recording: capture exactly what the
                // speakers get, mono. Capped at 5 minutes; the cap
                // stops the capture.
                if (masterRecording) {
                    val mono = (clampedL + clampedR) * 0.5f
                    synchronized(captureLock) {
                        if (masterCapture.size < maxMasterFrames) {
                            masterCapture.add(mono)
                        } else {
                            masterRecording = false
                            masterCaptureFull = true
                        }
                    }
                }
                val shortL = (clampedL * 32767).toInt().toShort()
                val shortR = (clampedR * 32767).toInt().toShort()
                outShorts[frame * 2] = shortL
                outShorts[frame * 2 + 1] = shortR
            }
            at.write(outShorts, 0, outShorts.size)
        }
    }
}
