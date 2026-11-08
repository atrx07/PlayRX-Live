package com.playrx.live.ui

import com.playrx.live.audio.SampleBank

/**
 * All state the PlayRX screen renders, as plain data.
 *
 * MainActivity builds this from the live PlaybackEngine + MIDI state.
 * Paparazzi screenshot tests build it directly with canned values, so
 * the screen can be rendered on the JVM with no Activity, no engine,
 * and no MIDI device.
 */
data class PlayRxState(
    val chordText: String = "No chord — play the keyboard",
    val midiStatus: String = "No MIDI device connected",
    val isPlaying: Boolean = false,
    val bpm: Float = 120f,
    val masterVolume: Float = 0.8f,
    val delayOn: Boolean = false,
    val currentStep: Int = -1,
    /** Circular beat counter: bar (1-based), beat in bar (1..4), progress through the bar 0..1. */
    val transportBar: Int = 1,
    val transportBeat: Int = 1,
    val barProgress: Float = 0f,
    /** Project settings overlay (gear menu) open or not. */
    val showProjectSettings: Boolean = false,
    /** Project defaults for newly assigned loops. */
    val defaultQuantizeBeats: Float = 4f,
    val defaultSnapToTempo: Boolean = true,
    val activeLoops: Set<Int> = emptySet(),
    /** Loop pads tapped while playing, waiting for the next bar/beat. */
    val armedLoops: Set<Int> = emptySet(),
    val selectedPad: Int = 0,
    val editPattern: Boolean = false,
    val selectedTab: String = "LOOP",
    /** Lower panel switched by the right-edge icons in Remixlive's
     * structure: EDIT, MIXER, FX, or SAMPLES. This is NOT a top tab
     * and NOT part of LOOP's side column — it is a switchable panel
     * below the main view (see docs/UI-STRUCTURE.md, from the
     * 2026-10-31 Tutorial Tour watch-through). */
    val selectedPanel: String = "MIXER",
    val padVolumes: List<Float> = List(SampleBank.PAD_COUNT) { 1f },
    val padMuted: List<Boolean> = List(SampleBank.PAD_COUNT) { false },
    val padSolo: List<Boolean> = List(SampleBank.PAD_COUNT) { false },
    val columnVolumes: List<Float> = List(SampleBank.COLUMNS) { 1f },
    val columnMuted: List<Boolean> = List(SampleBank.COLUMNS) { false },
    val columnSolo: List<Boolean> = List(SampleBank.COLUMNS) { false },
    /** steps[pad][step], 48 x 16. Only the selected pad's row is drawn. */
    val steps: List<List<Boolean>> = List(SampleBank.PAD_COUNT) { List(16) { false } },
    // ---- Sample import / advanced engine (2026-11-01) ----
    /** Project key as a pitch class 0..11 (C=0). Drives key dynamics. */
    val projectKeyMidi: Int = 0,
    /** Names in the SAMPLES collection, in manifest order. */
    val samples: List<String> = emptyList(),
    /** Sample assigned to the selected pad, or null = built-in synth. */
    val selectedPadSampleName: String? = null,
    val samplePlayMode: String = "LOOP",
    val sampleTranspose: Int = 0,
    val sampleSnapToTempo: Boolean = true,
    val sampleCutToGrid: Boolean = false,
    val sampleReverse: Boolean = false,
    /** Original BPM of the selected pad's sample, or null = unknown. */
    val sampleOriginalBpm: Float? = null,
    /** Original key (pitch class) of the selected pad's sample. */
    val sampleOriginalKey: Int = 0,
    /** Quantize launch for the selected pad's sample: 0 = off, 1 = beat, 4 = bar. */
    val sampleQuantizeBeats: Float = 4f,
    /** Result of the last Detect-tempo run, shown in EDIT. */
    val sampleTempoDetectResult: String? = null,
    val sampleImportError: String? = null,
    // ---- SEQ for real (2026-11-08) ----
    /** Sequence list: (id, name, step count). */
    val sequences: List<Triple<String, String, Int>> = listOf(Triple("seq-1", "Seq 1", 0)),
    val selectedSequenceId: String = "seq-1",
    val selectedSequenceLoop: Boolean = true,
    val selectedSequenceGain: Float = 1f,
    /** Step selected in the SEQ editor for per-step detail, -1 = none. */
    val selectedStep: Int = -1,
    /** Per-step parameters of the selected step (defaults when off). */
    val stepVelocity: Float = 1f,
    val stepOffset: Float = 0f,
    val stepDuration: Float = 0.25f,
    val stepRoll: Int = 1,
    val stepPitch: Int = 0,
    /** Drums & Sounds recording: pad taps quantize into the sequence. */
    val isRecording: Boolean = false,
    // ---- DRUM Scale Keyboard (2026-11-08) ----
    /** Scale flavour; root follows the project key. */
    val scaleName: String = "Major",
    /** MIDI octave of the keyboard's first note (2..6, C4 = 4). */
    val scaleOctave: Int = 4,
    // ---- SONG timeline (2026-11-08) ----
    val songClips: List<com.playrx.live.audio.SongClip> = emptyList(),
    val selectedClipId: String? = null,
    val songLengthBars: Int = 16,
    /** Bars visible at once: 4 (wide cells), 8, or 16. */
    val songVisibleBars: Int = 8,
    /** Current bar inside the song loop (1..lengthBars). */
    val songBar: Int = 1,
    // ---- Projects + Live Set + recording (2026-11-08) ----
    val showProjects: Boolean = false,
    /** (name, lastModified) newest first — the Live Set. */
    val projects: List<Pair<String, Long>> = emptyList(),
    val newProjectName: String = "",
    /** OFF / SONG / DRUMS / MASTER. */
    val recordMode: String = "OFF",
    val masterRecording: Boolean = false,
    val masterCaptureFull: Boolean = false,
    /** WAV files in filesDir/recordings, newest first. */
    val recordings: List<String> = emptyList(),
    val projectMessage: String? = null,
)

/** Every user action the screen can trigger. No-ops by default for screenshots. */
data class PlayRxActions(
    val onPadClick: (Int) -> Unit = {},
    val onPadSelect: (Int) -> Unit = {},
    val onPlayToggle: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onConnectMidi: () -> Unit = {},
    val onBpmChange: (Float) -> Unit = {},
    val onVolumeChange: (Float) -> Unit = {},
    val onDelayChange: (Boolean) -> Unit = {},
    val onEditToggle: () -> Unit = {},
    val onTabSelect: (String) -> Unit = {},
    val onPanelSelect: (String) -> Unit = {},
    val onPadVolume: (Int, Float) -> Unit = { _, _ -> },
    val onPadMuteToggle: (Int) -> Unit = {},
    val onPadSoloToggle: (Int) -> Unit = {},
    val onColumnVolume: (Int, Float) -> Unit = { _, _ -> },
    val onColumnMuteToggle: (Int) -> Unit = {},
    val onColumnSoloToggle: (Int) -> Unit = {},
    val onStepToggle: (Int, Int) -> Unit = { _, _ -> },
    val onProjectKeyChange: (Int) -> Unit = {},
    val onImportSample: () -> Unit = {},
    val onDeleteSample: (String) -> Unit = {},
    val onAssignSample: (String) -> Unit = {},
    val onSamplePlayMode: (String) -> Unit = {},
    val onSampleTranspose: (Int) -> Unit = {},
    val onSampleSnapToggle: () -> Unit = {},
    val onSampleCutToggle: () -> Unit = {},
    val onSampleReverseToggle: () -> Unit = {},
    val onSampleOriginalBpm: (Float) -> Unit = {},
    val onSampleOriginalKey: (Int) -> Unit = {},
    val onSampleQuantize: (Float) -> Unit = {},
    val onSampleDetectTempo: () -> Unit = {},
    val onProjectSettingsToggle: () -> Unit = {},
    val onDefaultQuantize: (Float) -> Unit = {},
    val onDefaultSnapToggle: () -> Unit = {},
    val onSequenceSelect: (String) -> Unit = {},
    val onSequenceNew: () -> Unit = {},
    val onSequenceDelete: (String) -> Unit = {},
    val onSequenceLoopToggle: () -> Unit = {},
    val onSequenceGain: (Float) -> Unit = {},
    val onStepSelect: (Int) -> Unit = {},
    val onStepCellVelocity: (Float) -> Unit = {},
    val onStepCellOffset: (Float) -> Unit = {},
    val onStepCellDuration: (Float) -> Unit = {},
    val onStepCellRoll: (Int) -> Unit = {},
    val onStepCellPitch: (Int) -> Unit = {},
    val onRecordingToggle: () -> Unit = {},
    val onDrumPadClick: (Int) -> Unit = {},
    val onScaleSelect: (String) -> Unit = {},
    val onScaleOctave: (Int) -> Unit = {},
    val onScaleNote: (Int) -> Unit = {},
    val onSongClipPlace: (Int, Int) -> Unit = { _, _ -> },
    val onSongClipSelect: (String?) -> Unit = {},
    val onSongClipMove: (Int) -> Unit = {},
    val onSongClipLength: (Int) -> Unit = {},
    val onSongClipDuplicate: () -> Unit = {},
    val onSongClipSplit: () -> Unit = {},
    val onSongClipDelete: () -> Unit = {},
    val onSongZoom: (Int) -> Unit = {},
    val onProjectsToggle: () -> Unit = {},
    val onNewProjectName: (String) -> Unit = {},
    val onProjectSave: () -> Unit = {},
    val onProjectLoad: (String) -> Unit = {},
    val onProjectDelete: (String) -> Unit = {},
    val onRecordMode: (String) -> Unit = {},
    val onRecordingShare: (String) -> Unit = {},
)
