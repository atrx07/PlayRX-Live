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
    val activeLoops: Set<Int> = emptySet(),
    val selectedPad: Int = 0,
    val editPattern: Boolean = false,
    val selectedTab: String = "LOOP",
    val padVolumes: List<Float> = List(SampleBank.PAD_COUNT) { 1f },
    val padMuted: List<Boolean> = List(SampleBank.PAD_COUNT) { false },
    val padSolo: List<Boolean> = List(SampleBank.PAD_COUNT) { false },
    val columnVolumes: List<Float> = List(SampleBank.COLUMNS) { 1f },
    val columnMuted: List<Boolean> = List(SampleBank.COLUMNS) { false },
    val columnSolo: List<Boolean> = List(SampleBank.COLUMNS) { false },
    /** steps[pad][step], 48 x 16. Only the selected pad's row is drawn. */
    val steps: List<List<Boolean>> = List(SampleBank.PAD_COUNT) { List(16) { false } },
)

/** Every user action the screen can trigger. No-ops by default for screenshots. */
data class PlayRxActions(
    val onPadClick: (Int) -> Unit = {},
    val onPlayToggle: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onConnectMidi: () -> Unit = {},
    val onBpmChange: (Float) -> Unit = {},
    val onVolumeChange: (Float) -> Unit = {},
    val onDelayChange: (Boolean) -> Unit = {},
    val onEditToggle: () -> Unit = {},
    val onTabSelect: (String) -> Unit = {},
    val onPadVolume: (Int, Float) -> Unit = { _, _ -> },
    val onPadMuteToggle: (Int) -> Unit = {},
    val onPadSoloToggle: (Int) -> Unit = {},
    val onColumnVolume: (Int, Float) -> Unit = { _, _ -> },
    val onColumnMuteToggle: (Int) -> Unit = {},
    val onColumnSoloToggle: (Int) -> Unit = {},
    val onStepToggle: (Int, Int) -> Unit = { _, _ -> },
)
