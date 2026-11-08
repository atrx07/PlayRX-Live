package com.playrx.live.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test

/**
 * Visual QA for PlayRX Live, rendered by Paparazzi on the JVM — no
 * emulator, no device, no KVM needed. GitHub Actions runs
 * `gradle recordPaparazziDebug` (manual screenshots job in android.yml)
 * and uploads app/build/paparazzi/ as an artifact for review.
 *
 * Four viewports, four states each = 16 screenshots:
 * - Phone portrait: Pixel 6, portrait (handheld use)
 * - Phone landscape: Pixel 6, landscape (the music-rest use — primary)
 * - Tablet portrait: Pixel C, portrait
 * - Tablet landscape: Pixel C, landscape
 *
 * Record writes PNGs to app/src/test/snapshots/ — that is the path
 * the screenshots job in android.yml uploads as an artifact.
 *
 * States:
 * - loopDefault: fresh LOOP tab, nothing playing
 * - loopPlaying: LOOP tab, two loop pads active, step 3 playing, chord shown
 * - drumSequencer: DRUM tab, Kick pad with a 4-on-the-floor pattern
 * - loopEditMixer: LOOP tab, edit mode on, one pad muted, one column soloed
 *
 * To record locally / in CI: ./gradlew recordPaparazziDebug
 * To verify against goldens: ./gradlew verifyPaparazziDebug
 * (Goldens are binary PNGs and are NOT committed via the agent's
 * text-only push tooling — see docs; record/review mode is the loop
 * until approved goldens are committed via web upload / direct access.)
 */
private fun defaultState(): PlayRxState = PlayRxState()

private fun playingState(): PlayRxState = PlayRxState(
    chordText = "C major",
    midiStatus = "PSR-SX700 connected via USB",
    isPlaying = true,
    bpm = 127f,
    masterVolume = 0.8f,
    currentStep = 3,
    // Loop pads are columns 5 (Pad) and 7 (FX): pad 5 and pad 15.
    activeLoops = setOf(5, 15),
    selectedPad = 5,
    projectKeyMidi = 5,
    transportBar = 2,
    transportBeat = 3,
    barProgress = 0.5f,
)

private fun drumState(): PlayRxState {
    // DRUM tab: one-shot grid + Scale Keyboard (F minor, octave 4),
    // loops still playing underneath.
    val steps = List(48) { List(16) { false } }.toMutableList()
    steps[0] = listOf(true, false, false, false, true, false, false, false, true, false, false, false, true, false, false, false)
    return PlayRxState(
        chordText = "C major",
        midiStatus = "PSR-SX700 connected via USB",
        isPlaying = true,
        bpm = 127f,
        currentStep = 4,
        selectedPad = 0,
        selectedTab = "DRUM",
        selectedPanel = "MIXER",
        projectKeyMidi = 5,
        scaleName = "Minor",
        scaleOctave = 4,
        activeLoops = setOf(5),
        samples = listOf("my-kick.wav", "bass-loop.wav"),
        steps = steps,
    )
}

private fun seqState(): PlayRxState {
    val steps = List(48) { List(16) { false } }.toMutableList()
    // Kick 1 (pad 0): 4-on-the-floor; Snare 1 (pad 1): backbeat.
    steps[0] = listOf(true, false, false, false, true, false, false, false, true, false, false, false, true, false, false, false)
    steps[1] = listOf(false, false, false, false, true, false, false, false, false, false, false, false, true, false, false, false)
    return PlayRxState(
        chordText = "C major",
        midiStatus = "PSR-SX700 connected via USB",
        isPlaying = true,
        bpm = 127f,
        currentStep = 4,
        selectedPad = 0,
        selectedTab = "SEQ",
        selectedPanel = "MIXER",
        sequences = listOf(Triple("seq-1", "Seq 1", 5), Triple("seq-2", "Chorus", 0)),
        selectedSequenceId = "seq-1",
        selectedSequenceLoop = true,
        selectedSequenceGain = 0.8f,
        selectedStep = 4,
        stepVelocity = 0.8f,
        stepOffset = 0f,
        stepDuration = 0.25f,
        stepRoll = 2,
        stepPitch = 0,
        steps = steps,
    )
}

private fun fxState(): PlayRxState {
    return PlayRxState(
        chordText = "C major",
        midiStatus = "PSR-SX700 connected via USB",
        selectedPanel = "FX",
        delayOn = true,
        delayDivisionBeats = 0.75f,
        delayMix = 0.4f,
        delayFeedback = 0.5f,
        beatRepeat = "1/8",
        beatRepeatMix = 0.7f,
        masterFilterCutoffHz = 2400f,
        columnSends = listOf(0.8f, 0.5f, 0.5f, 0.3f, 0.5f, 0.6f, 0.5f, 0.5f),
    )
}

private fun mixerEqState(): PlayRxState {
    return PlayRxState(
        chordText = "C major",
        midiStatus = "PSR-SX700 connected via USB",
        selectedPanel = "MIXER",
        mixerTab = "Eq Filter",
        columnFilters = listOf(0f, 0f, 800f, 0f, 2400f, 0f, 0f, 12000f),
    )
}

private fun projectsState(): PlayRxState {
    return PlayRxState(
        chordText = "C major",
        midiStatus = "PSR-SX700 connected via USB",
        showProjects = true,
        projects = listOf("My Song" to 1762620000000L, "Verse idea" to 1762533600000L),
        newProjectName = "Chorus",
        recordMode = "OFF",
        recordings = listOf("master-20261108-203000.wav"),
        projectMessage = "Saved My Song",
    )
}

private fun songState(): PlayRxState {
    val clips = listOf(
        com.playrx.live.audio.SongClip("clip-1", 5, 1, 4, 5, "Pad 1"),
        com.playrx.live.audio.SongClip("clip-2", 0, 1, 2, 0, "Kick 1"),
        com.playrx.live.audio.SongClip("clip-3", 0, 5, 4, 0, "Kick 1"),
        com.playrx.live.audio.SongClip("clip-4", 3, 9, 4, 27, "Bass 4"),
    )
    return PlayRxState(
        chordText = "C major",
        midiStatus = "PSR-SX700 connected via USB",
        isPlaying = true,
        bpm = 127f,
        selectedPad = 5,
        selectedTab = "SONG",
        selectedPanel = "MIXER",
        songClips = clips,
        selectedClipId = "clip-1",
        songLengthBars = 16,
        songVisibleBars = 8,
        songBar = 2,
        transportBar = 2,
        transportBeat = 1,
        activeLoops = setOf(5),
    )
}

private fun editMixerState(): PlayRxState {
    val padVolumes = List(48) { 1f }.toMutableList().apply { this[5] = 0.5f }
    val padMuted = List(48) { false }.toMutableList().apply { this[0] = true }
    val columnSolo = List(8) { false }.toMutableList().apply { this[3] = true }
    return PlayRxState(
        chordText = "C major",
        midiStatus = "PSR-SX700 connected via USB",
        editPattern = true,
        selectedPad = 5,
        // Bottom panel on EDIT: proves Edit is its own switchable
        // panel below the grid, not a side column in LOOP.
        selectedPanel = "EDIT",
        samples = listOf("my-kick.wav", "bass-loop.wav"),
        selectedPadSampleName = "bass-loop.wav",
        samplePlayMode = "LOOP",
        sampleTranspose = 3,
        sampleSnapToTempo = true,
        sampleCutToGrid = false,
        sampleReverse = false,
        sampleOriginalBpm = 100f,
        sampleOriginalKey = 0,
        sampleQuantizeBeats = 4f,
        padVolumes = padVolumes,
        padMuted = padMuted,
        columnSolo = columnSolo,
    )
}

class PhonePortraitScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.Light.NoActionBar",
    )

    @Test fun loopDefault() {
        paparazzi.snapshot { PlayRxScreen(defaultState(), isLandscape = false) }
    }

    @Test fun loopPlaying() {
        paparazzi.snapshot { PlayRxScreen(playingState(), isLandscape = false) }
    }

    @Test fun drumScale() {
        paparazzi.snapshot { PlayRxScreen(drumState(), isLandscape = false) }
    }

    @Test fun loopEditMixer() {
        paparazzi.snapshot { PlayRxScreen(editMixerState(), isLandscape = false) }
    }

    @Test fun projectSettings() {
        paparazzi.snapshot { PlayRxScreen(playingState().copy(showProjectSettings = true), isLandscape = false) }
    }

    @Test fun seqEditor() {
        paparazzi.snapshot { PlayRxScreen(seqState(), isLandscape = false) }
    }

    @Test fun songTimeline() {
        paparazzi.snapshot { PlayRxScreen(songState(), isLandscape = false) }
    }

    @Test fun projectsOverlay() {
        paparazzi.snapshot { PlayRxScreen(projectsState(), isLandscape = false) }
    }

    @Test fun fxPanel() {
        paparazzi.snapshot { PlayRxScreen(fxState(), isLandscape = false) }
    }

    @Test fun mixerEq() {
        paparazzi.snapshot { PlayRxScreen(mixerEqState(), isLandscape = false) }
    }
}

class PhoneLandscapeScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(orientation = ScreenOrientation.LANDSCAPE),
        theme = "android:Theme.Material.Light.NoActionBar",
    )

    @Test fun loopDefault() {
        paparazzi.snapshot { PlayRxScreen(defaultState(), isLandscape = true) }
    }

    @Test fun loopPlaying() {
        paparazzi.snapshot { PlayRxScreen(playingState(), isLandscape = true) }
    }

    @Test fun drumScale() {
        paparazzi.snapshot { PlayRxScreen(drumState(), isLandscape = true) }
    }

    @Test fun loopEditMixer() {
        paparazzi.snapshot { PlayRxScreen(editMixerState(), isLandscape = true) }
    }

    @Test fun projectSettings() {
        paparazzi.snapshot { PlayRxScreen(playingState().copy(showProjectSettings = true), isLandscape = true) }
    }

    @Test fun seqEditor() {
        paparazzi.snapshot { PlayRxScreen(seqState(), isLandscape = true) }
    }

    @Test fun songTimeline() {
        paparazzi.snapshot { PlayRxScreen(songState(), isLandscape = true) }
    }

    @Test fun projectsOverlay() {
        paparazzi.snapshot { PlayRxScreen(projectsState(), isLandscape = true) }
    }

    @Test fun fxPanel() {
        paparazzi.snapshot { PlayRxScreen(fxState(), isLandscape = true) }
    }

    @Test fun mixerEq() {
        paparazzi.snapshot { PlayRxScreen(mixerEqState(), isLandscape = true) }
    }
}

class TabletPortraitScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_C.copy(orientation = ScreenOrientation.PORTRAIT),
        theme = "android:Theme.Material.Light.NoActionBar",
    )

    @Test fun loopDefault() {
        paparazzi.snapshot { PlayRxScreen(defaultState(), isLandscape = false) }
    }

    @Test fun loopPlaying() {
        paparazzi.snapshot { PlayRxScreen(playingState(), isLandscape = false) }
    }

    @Test fun drumScale() {
        paparazzi.snapshot { PlayRxScreen(drumState(), isLandscape = false) }
    }

    @Test fun loopEditMixer() {
        paparazzi.snapshot { PlayRxScreen(editMixerState(), isLandscape = false) }
    }

    @Test fun projectSettings() {
        paparazzi.snapshot { PlayRxScreen(playingState().copy(showProjectSettings = true), isLandscape = false) }
    }

    @Test fun seqEditor() {
        paparazzi.snapshot { PlayRxScreen(seqState(), isLandscape = false) }
    }

    @Test fun songTimeline() {
        paparazzi.snapshot { PlayRxScreen(songState(), isLandscape = false) }
    }

    @Test fun projectsOverlay() {
        paparazzi.snapshot { PlayRxScreen(projectsState(), isLandscape = false) }
    }

    @Test fun fxPanel() {
        paparazzi.snapshot { PlayRxScreen(fxState(), isLandscape = false) }
    }

    @Test fun mixerEq() {
        paparazzi.snapshot { PlayRxScreen(mixerEqState(), isLandscape = false) }
    }
}

class TabletLandscapeScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_C,
        theme = "android:Theme.Material.Light.NoActionBar",
    )

    @Test fun loopDefault() {
        paparazzi.snapshot { PlayRxScreen(defaultState(), isLandscape = true) }
    }

    @Test fun loopPlaying() {
        paparazzi.snapshot { PlayRxScreen(playingState(), isLandscape = true) }
    }

    @Test fun drumScale() {
        paparazzi.snapshot { PlayRxScreen(drumState(), isLandscape = true) }
    }

    @Test fun loopEditMixer() {
        paparazzi.snapshot { PlayRxScreen(editMixerState(), isLandscape = true) }
    }

    @Test fun projectSettings() {
        paparazzi.snapshot { PlayRxScreen(playingState().copy(showProjectSettings = true), isLandscape = true) }
    }

    @Test fun seqEditor() {
        paparazzi.snapshot { PlayRxScreen(seqState(), isLandscape = true) }
    }

    @Test fun songTimeline() {
        paparazzi.snapshot { PlayRxScreen(songState(), isLandscape = true) }
    }

    @Test fun projectsOverlay() {
        paparazzi.snapshot { PlayRxScreen(projectsState(), isLandscape = true) }
    }

    @Test fun fxPanel() {
        paparazzi.snapshot { PlayRxScreen(fxState(), isLandscape = true) }
    }

    @Test fun mixerEq() {
        paparazzi.snapshot { PlayRxScreen(mixerEqState(), isLandscape = true) }
    }
}
