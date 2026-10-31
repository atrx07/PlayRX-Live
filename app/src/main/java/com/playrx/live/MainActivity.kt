package com.playrx.live

import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import com.playrx.live.audio.PlaybackEngine
import com.playrx.live.audio.SampleBank
import com.playrx.live.midi.MidiEngine
import com.playrx.live.ui.PlayRxActions
import com.playrx.live.ui.PlayRxScreen
import com.playrx.live.ui.PlayRxState

/**
 * PlayRX Live v1 + landscape-first UI pass (from the Remixlive 8.6.2 study).
 *
 * The screen itself lives in ui/PlayRxScreen.kt as standalone composables
 * driven by PlayRxState + PlayRxActions, so Paparazzi can render it on the
 * JVM with no Activity/engine/MIDI. This activity only owns the live
 * engine + MIDI wiring and maps it to/from that state. Behaviour is
 * unchanged from the pre-refactor version:
 *
 * - 48 pads, 8x6: columns Kick/Snare/Tops/Bass/Synth/Pad/Vox/FX, rows
 *   are variations 1..6. Pad + FX columns are repeating loop pads;
 *   the rest are one-shots.
 * - Transport: play/pause, stop, BPM 60-180, master volume, delay FX.
 * - Step sequencer: pick a drum pad, toggle its 16 steps.
 * - Full pad: per-pad volume/mute/solo, per-column mixer, pad edit panel.
 *   Edit mode selects a pad without playing it.
 * - Tabs: LOOP (pad grid), DRUM (sequencer), SEQ/SONG (placeholder).
 * - MIDI: Connect MIDI shows the keyboard, chord readout updates from
 *   held notes. Chord-following of loops is NOT wired yet.
 * - Rotation does not restart the audio engine: the manifest declares
 *   configChanges for orientation/screenSize.
 */
class MainActivity : ComponentActivity() {

    private lateinit var midiEngine: MidiEngine
    private lateinit var engine: PlaybackEngine
    private val mainHandler = Handler(Looper.getMainLooper())

    private var chordText by mutableStateOf("No chord — play the keyboard")
    private var midiStatus by mutableStateOf("No MIDI device connected")
    private var isPlaying by mutableStateOf(false)
    private var bpm by mutableStateOf(120f)
    private var masterVolume by mutableStateOf(0.8f)
    private var delayOn by mutableStateOf(false)
    private var currentStep by mutableStateOf(-1)
    private var activeLoops by mutableStateOf(setOf<Int>())
    private var selectedPad by mutableStateOf(0)
    private var editPattern by mutableStateOf(false)
    private var patternVersion by mutableStateOf(0)
    private var mixerVersion by mutableStateOf(0)
    private var selectedTab by mutableStateOf("LOOP")

    private fun currentState(): PlayRxState {
        // Read the version counters so pad/column/step edits recompose.
        @Suppress("UNUSED_VARIABLE")
        val pattern = patternVersion
        @Suppress("UNUSED_VARIABLE")
        val mixer = mixerVersion
        return PlayRxState(
            chordText = chordText,
            midiStatus = midiStatus,
            isPlaying = isPlaying,
            bpm = bpm,
            masterVolume = masterVolume,
            delayOn = delayOn,
            currentStep = currentStep,
            activeLoops = activeLoops,
            selectedPad = selectedPad,
            editPattern = editPattern,
            selectedTab = selectedTab,
            padVolumes = List(SampleBank.PAD_COUNT) { engine.getPadVolume(it) },
            padMuted = List(SampleBank.PAD_COUNT) { engine.isPadMuted(it) },
            padSolo = List(SampleBank.PAD_COUNT) { engine.isPadSolo(it) },
            columnVolumes = List(SampleBank.COLUMNS) { engine.getColumnVolume(it) },
            columnMuted = List(SampleBank.COLUMNS) { engine.isColumnMuted(it) },
            columnSolo = List(SampleBank.COLUMNS) { engine.isColumnSolo(it) },
            steps = List(SampleBank.PAD_COUNT) { pad ->
                List(16) { step -> engine.getStep(pad, step) }
            },
        )
    }

    private fun actions(): PlayRxActions = PlayRxActions(
        onPadClick = { pad ->
            if (editPattern) {
                selectedPad = pad
            } else {
                engine.triggerPad(pad)
                selectedPad = pad
                if (SampleBank.isLoopPad(pad)) {
                    activeLoops = engine.activeLoopPads()
                    isPlaying = engine.isPlaying
                }
            }
        },
        onPlayToggle = {
            val next = !isPlaying
            engine.setPlaying(next)
            isPlaying = next
        },
        onStop = {
            engine.stopAll()
            isPlaying = false
            activeLoops = emptySet()
            currentStep = -1
        },
        onConnectMidi = {
            val devices = midiEngine.listDevices()
            if (devices.isNotEmpty()) {
                midiEngine.open(devices.first())
            } else {
                midiStatus = "No USB MIDI device found — connect the keyboard via USB OTG"
            }
        },
        onBpmChange = {
            bpm = it
            engine.bpm = it
        },
        onVolumeChange = {
            masterVolume = it
            engine.masterVolume = it
        },
        onDelayChange = {
            delayOn = it
            engine.delayEnabled = it
        },
        onEditToggle = { editPattern = !editPattern },
        onTabSelect = { selectedTab = it },
        onPadVolume = { pad, volume ->
            engine.setPadVolume(pad, volume)
            mixerVersion++
        },
        onPadMuteToggle = { pad ->
            engine.setPadMuted(pad, !engine.isPadMuted(pad))
            mixerVersion++
        },
        onPadSoloToggle = { pad ->
            engine.setPadSolo(pad, !engine.isPadSolo(pad))
            mixerVersion++
        },
        onColumnVolume = { col, volume ->
            engine.setColumnVolume(col, volume)
            mixerVersion++
        },
        onColumnMuteToggle = { col ->
            engine.setColumnMuted(col, !engine.isColumnMuted(col))
            mixerVersion++
        },
        onColumnSoloToggle = { col ->
            engine.setColumnSolo(col, !engine.isColumnSolo(col))
            mixerVersion++
        },
        onStepToggle = { pad, step ->
            if (SampleBank.isDrumPad(pad)) {
                engine.setStep(pad, step, !engine.getStep(pad, step))
                patternVersion++
            }
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        engine = PlaybackEngine()
        engine.bpm = bpm
        engine.masterVolume = masterVolume
        engine.onStepChanged = { step ->
            mainHandler.post { currentStep = step }
        }
        engine.onLoopsChanged = { loops ->
            mainHandler.post {
                activeLoops = loops
                isPlaying = engine.isPlaying
            }
        }
        engine.start()

        midiEngine = MidiEngine(this)
        midiEngine.onChordChanged = { chord ->
            mainHandler.post {
                chordText = chord?.name ?: "No chord — play the keyboard"
            }
        }
        midiEngine.onDeviceStatus = { status ->
            mainHandler.post { midiStatus = status }
        }
        midiEngine.onTransport = { transport ->
            if (transport == MidiEngine.Transport.PLAY) {
                mainHandler.post {
                    engine.setPlaying(true)
                    isPlaying = true
                }
            }
        }

        setContent {
            val isLandscape =
                LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            PlayRxScreen(state = currentState(), actions = actions(), isLandscape = isLandscape)
        }
    }

    override fun onDestroy() {
        midiEngine.close()
        engine.stop()
        super.onDestroy()
    }
}
