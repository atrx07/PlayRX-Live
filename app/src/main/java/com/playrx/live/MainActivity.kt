package com.playrx.live

import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import com.playrx.live.audio.PlayMode
import com.playrx.live.audio.PlaybackEngine
import com.playrx.live.audio.PadSample
import com.playrx.live.audio.SampleBank
import com.playrx.live.audio.SampleInfo
import com.playrx.live.audio.SampleStore
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

    private val importPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread {
            val info = SampleStore.importUri(
                this, uri, bpm, projectKeyMidi,
                SampleStore.categoryForColumn(SampleBank.columnOf(selectedPad)),
            )
            mainHandler.post {
                if (info == null) {
                    sampleImportError = "Could not decode that file. Try a WAV, OGG, MP3 or AAC you own."
                } else {
                    sampleImportError = null
                    sampleList = SampleStore.loadManifest(this)
                    sampleVersion++
                }
            }
        }.start()
    }

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
    private var armedLoops by mutableStateOf(setOf<Int>())
    private var selectedPad by mutableStateOf(0)
    private var editPattern by mutableStateOf(false)
    private var patternVersion by mutableStateOf(0)
    private var mixerVersion by mutableStateOf(0)
    private var selectedTab by mutableStateOf("LOOP")
    private var selectedPanel by mutableStateOf("MIXER")
    private var projectKeyMidi by mutableStateOf(0)
    private var sampleList by mutableStateOf(listOf<SampleInfo>())
    private var sampleImportError by mutableStateOf<String?>(null)
    private var sampleVersion by mutableStateOf(0)
    private var transportBar by mutableStateOf(1)
    private var transportBeat by mutableStateOf(1)
    private var barProgress by mutableStateOf(0f)
    private var showProjectSettings by mutableStateOf(false)
    private var defaultQuantizeBeats by mutableStateOf(4f)
    private var defaultSnapToTempo by mutableStateOf(true)
    private var sampleTempoDetectResult by mutableStateOf<String?>(null)
    private var selectedStep by mutableStateOf(-1)
    private var isRecording by mutableStateOf(false)
    private var scaleName by mutableStateOf("Major")
    private var scaleOctave by mutableStateOf(4)
    private var selectedClipId by mutableStateOf<String?>(null)
    private var songVisibleBars by mutableStateOf(8)
    private var songVersion by mutableStateOf(0)

    private fun currentState(): PlayRxState {
        // Read the version counters so pad/column/step edits recompose.
        @Suppress("UNUSED_VARIABLE")
        val pattern = patternVersion
        @Suppress("UNUSED_VARIABLE")
        val mixer = mixerVersion
        @Suppress("UNUSED_VARIABLE")
        val sampleV = sampleVersion
        @Suppress("UNUSED_VARIABLE")
        val songV = songVersion
        val selectedSeq = engine.selectedSequence()
        val stepCell = if (selectedStep in 0..15) engine.getStepCell(selectedPad, selectedStep)
            else com.playrx.live.audio.StepCell()
        return PlayRxState(
            chordText = chordText,
            midiStatus = midiStatus,
            isPlaying = isPlaying,
            bpm = bpm,
            masterVolume = masterVolume,
            delayOn = delayOn,
            currentStep = currentStep,
            transportBar = transportBar,
            transportBeat = transportBeat,
            barProgress = barProgress,
            showProjectSettings = showProjectSettings,
            defaultQuantizeBeats = defaultQuantizeBeats,
            defaultSnapToTempo = defaultSnapToTempo,
            sampleTempoDetectResult = sampleTempoDetectResult,
            sequences = engine.sequences().map { Triple(it.id, it.name, it.stepCount()) },
            selectedSequenceId = selectedSeq?.id ?: "seq-1",
            selectedSequenceLoop = selectedSeq?.loop ?: true,
            selectedSequenceGain = selectedSeq?.gain ?: 1f,
            selectedStep = selectedStep,
            stepVelocity = stepCell.velocity,
            stepOffset = stepCell.offsetBeats,
            stepDuration = stepCell.durationBeats,
            stepRoll = stepCell.roll,
            stepPitch = stepCell.pitchSemitones,
            isRecording = isRecording,
            scaleName = scaleName,
            scaleOctave = scaleOctave,
            songClips = engine.songTimeline.list(),
            selectedClipId = selectedClipId,
            songLengthBars = engine.songTimeline.lengthBars,
            songVisibleBars = songVisibleBars,
            songBar = ((transportBar - 1) % engine.songTimeline.lengthBars.coerceAtLeast(1)) + 1,
            activeLoops = activeLoops,
            armedLoops = armedLoops,
            selectedPad = selectedPad,
            editPattern = editPattern,
            selectedTab = selectedTab,
            selectedPanel = selectedPanel,
            projectKeyMidi = projectKeyMidi,
            samples = sampleList.map { it.name },
            selectedPadSampleName = engine.importedSampleName(selectedPad),
            samplePlayMode = (engine.importedAssignment(selectedPad)?.playMode?.name ?: "LOOP"),
            sampleTranspose = engine.importedAssignment(selectedPad)?.transposeSemitones ?: 0,
            sampleSnapToTempo = engine.importedAssignment(selectedPad)?.snapToTempo ?: true,
            sampleCutToGrid = engine.importedAssignment(selectedPad)?.cutToGrid ?: false,
            sampleReverse = engine.importedAssignment(selectedPad)?.reverse ?: false,
            sampleOriginalBpm = engine.importedSampleInfo(selectedPad)?.originalBpm,
            sampleOriginalKey = engine.importedSampleInfo(selectedPad)?.originalKeyMidi?.let { ((it % 12) + 12) % 12 } ?: projectKeyMidi,
            sampleQuantizeBeats = engine.importedAssignment(selectedPad)?.quantizeBeats ?: 4f,
            sampleImportError = sampleImportError,
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
                activeLoops = engine.activeLoopPads()
                armedLoops = engine.armedLoopPads()
                isPlaying = engine.isPlaying
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
            armedLoops = emptySet()
            transportBar = 1
            transportBeat = 1
            barProgress = 0f
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
            engine.refreshImportedSamples()
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
        onTabSelect = {
            selectedTab = it
            engine.setSongEnabled(it == "SONG")
        },
        onPanelSelect = { selectedPanel = it },
        onProjectKeyChange = { key ->
            projectKeyMidi = key
            engine.setProjectKey(key)
        },
        onImportSample = { importPicker.launch("audio/*") },
        onAssignSample = { name ->
            val info = sampleList.firstOrNull { it.name == name }
            val decoded = if (info == null) null else SampleStore.decodeStored(this, info)
            if (info == null) {
                sampleImportError = "Sample not found: $name"
            } else if (decoded == null) {
                sampleImportError = "Could not decode ${info.name} again."
            } else {
            val mode = if (SampleBank.isLoopPad(selectedPad)) PlayMode.LOOP else PlayMode.ONE_SHOT
            // Key dynamics read the category from SampleInfo. The
            // category follows the *target* pad's column, not the
            // pad that happened to be selected at import time, so
            // a sample assigned to a drum column stays exempt and
            // one assigned to a melodic column follows the key.
            val targetInfo = info.copy(
                category = SampleStore.categoryForColumn(SampleBank.columnOf(selectedPad)),
            )
            engine.assignImportedSample(
                selectedPad,
                decoded.frames,
                PadSample(pad = selectedPad, sampleId = info.id, playMode = mode, quantizeBeats = if (mode == PlayMode.LOOP) defaultQuantizeBeats else 0f, snapToTempo = defaultSnapToTempo),
                targetInfo,
            )
            sampleTempoDetectResult = null
            sampleVersion++
            }
        },
        onSamplePlayMode = { mode ->
            val cur = engine.importedAssignment(selectedPad)
            val parsed = if (cur == null) null else runCatching { PlayMode.valueOf(mode) }.getOrNull()
            if (cur != null && parsed != null) {
                engine.updateImportedAssignment(selectedPad, cur.copy(playMode = parsed))
                sampleVersion++
            }
        },
        onSampleTranspose = { semitones ->
            val cur = engine.importedAssignment(selectedPad)
            if (cur != null) {
                engine.updateImportedAssignment(selectedPad, cur.copy(transposeSemitones = semitones))
                sampleVersion++
            }
        },
        onSampleSnapToggle = {
            val cur = engine.importedAssignment(selectedPad)
            if (cur != null) {
                engine.updateImportedAssignment(selectedPad, cur.copy(snapToTempo = !cur.snapToTempo))
                sampleVersion++
            }
        },
        onSampleCutToggle = {
            val cur = engine.importedAssignment(selectedPad)
            if (cur != null) {
                engine.updateImportedAssignment(selectedPad, cur.copy(cutToGrid = !cur.cutToGrid))
                sampleVersion++
            }
        },
        onSampleReverseToggle = {
            val cur = engine.importedAssignment(selectedPad)
            if (cur != null) {
                engine.updateImportedAssignment(selectedPad, cur.copy(reverse = !cur.reverse))
                sampleVersion++
            }
        },
        onSampleOriginalBpm = { bpmValue ->
            val info = engine.importedSampleInfo(selectedPad)
            if (info != null) {
                val updated = info.copy(originalBpm = bpmValue.coerceIn(30f, 260f))
                engine.updateImportedSampleInfo(info.id, updated)
                sampleList = sampleList.map { if (it.id == info.id) updated else it }
                SampleStore.updateSampleInfo(this, updated)
                sampleVersion++
            }
        },
        onSampleOriginalKey = { key ->
            val info = engine.importedSampleInfo(selectedPad)
            if (info != null) {
                val updated = info.copy(originalKeyMidi = key)
                engine.updateImportedSampleInfo(info.id, updated)
                sampleList = sampleList.map { if (it.id == info.id) updated else it }
                SampleStore.updateSampleInfo(this, updated)
                sampleVersion++
            }
        },
        onSampleQuantize = { q ->
            val cur = engine.importedAssignment(selectedPad)
            if (cur != null) {
                engine.updateImportedAssignment(selectedPad, cur.copy(quantizeBeats = q))
                sampleVersion++
            }
        },
        onSampleDetectTempo = {
            val detected = engine.detectImportedTempo(selectedPad)
            if (detected != null) {
                val info = engine.importedSampleInfo(selectedPad)
                if (info != null) {
                    sampleList = sampleList.map { if (it.id == info.id) info else it }
                    SampleStore.updateSampleInfo(this, info)
                }
                sampleTempoDetectResult = "Detected ${detected.toInt()} BPM"
            } else {
                sampleTempoDetectResult = "No clear tempo found — set it manually."
            }
            sampleVersion++
        },
        onProjectSettingsToggle = { showProjectSettings = !showProjectSettings },
        onDeleteSample = { name ->
            val info = sampleList.firstOrNull { it.name == name }
            if (info != null) {
                engine.clearImportedSamplesById(info.id)
                SampleStore.deleteSample(this, info)
                sampleList = sampleList.filter { it.id != info.id }
                activeLoops = engine.activeLoopPads()
                armedLoops = engine.armedLoopPads()
                if (engine.importedSampleName(selectedPad) == null) {
                    sampleTempoDetectResult = null
                }
                sampleVersion++
            }
        },
        onDefaultQuantize = { q -> defaultQuantizeBeats = q },
        onDefaultSnapToggle = { defaultSnapToTempo = !defaultSnapToTempo },
        onPadSelect = { pad ->
            selectedPad = pad
            selectedStep = -1
        },
        onSequenceSelect = { id ->
            engine.selectSequence(id)
            selectedStep = -1
            patternVersion++
        },
        onSequenceNew = {
            engine.createSequence()
            selectedStep = -1
            patternVersion++
        },
        onSequenceDelete = { id ->
            engine.deleteSequence(id)
            selectedStep = -1
            patternVersion++
        },
        onSequenceLoopToggle = {
            val cur = engine.selectedSequence()
            if (cur != null) {
                engine.setSequenceLoop(!cur.loop)
                patternVersion++
            }
        },
        onSequenceGain = { gain ->
            engine.setSequenceGain(gain)
            patternVersion++
        },
        onStepSelect = { step ->
            selectedStep = step
        },
        onStepCellVelocity = { v ->
            if (selectedStep in 0..15) {
                val cur = engine.getStepCell(selectedPad, selectedStep)
                engine.setStepCell(selectedPad, selectedStep, cur.copy(on = true, velocity = v))
                patternVersion++
            }
        },
        onStepCellOffset = { v ->
            if (selectedStep in 0..15) {
                val cur = engine.getStepCell(selectedPad, selectedStep)
                engine.setStepCell(selectedPad, selectedStep, cur.copy(on = true, offsetBeats = v))
                patternVersion++
            }
        },
        onStepCellDuration = { v ->
            if (selectedStep in 0..15) {
                val cur = engine.getStepCell(selectedPad, selectedStep)
                engine.setStepCell(selectedPad, selectedStep, cur.copy(on = true, durationBeats = v))
                patternVersion++
            }
        },
        onStepCellRoll = { roll ->
            if (selectedStep in 0..15) {
                val cur = engine.getStepCell(selectedPad, selectedStep)
                engine.setStepCell(selectedPad, selectedStep, cur.copy(on = true, roll = roll))
                patternVersion++
            }
        },
        onStepCellPitch = { pitch ->
            if (selectedStep in 0..15) {
                val cur = engine.getStepCell(selectedPad, selectedStep)
                engine.setStepCell(selectedPad, selectedStep, cur.copy(on = true, pitchSemitones = pitch))
                patternVersion++
            }
        },
        onRecordingToggle = {
            isRecording = !isRecording
            engine.isRecording = isRecording
        },
        onDrumPadClick = { pad ->
            engine.triggerOneShot(pad)
            selectedPad = pad
            activeLoops = engine.activeLoopPads()
            armedLoops = engine.armedLoopPads()
            isPlaying = engine.isPlaying
        },
        onScaleSelect = { scale ->
            if (scale in com.playrx.live.audio.ScaleKeyboard.SCALES) scaleName = scale
        },
        onScaleOctave = { delta ->
            scaleOctave = (scaleOctave + delta).coerceIn(2, 6)
        },
        onScaleNote = { midi ->
            engine.playNote(midi, pad = selectedPad)
        },
        onSongClipPlace = { track, bar ->
            val clip = engine.songTimeline.addClip(
                track, bar, 1, selectedPad, SampleBank.PAD_NAMES[selectedPad],
            )
            selectedClipId = clip?.id
            songVersion++
        },
        onSongClipSelect = { id ->
            selectedClipId = id
        },
        onSongClipMove = { delta ->
            val clip = engine.songTimeline.list().firstOrNull { it.id == selectedClipId }
            if (clip != null) {
                engine.songTimeline.moveClip(clip.id, clip.track, clip.startBar + delta)
                songVersion++
            }
        },
        onSongClipLength = { delta ->
            val clip = engine.songTimeline.list().firstOrNull { it.id == selectedClipId }
            if (clip != null) {
                engine.songTimeline.resizeClip(clip.id, clip.lengthBars + delta)
                songVersion++
            }
        },
        onSongClipDuplicate = {
            val clip = engine.songTimeline.list().firstOrNull { it.id == selectedClipId }
            if (clip != null) {
                // First free slot of the same length at/after the clip end.
                var bar = clip.endBar + 1
                var dup: com.playrx.live.audio.SongClip? = null
                while (bar + clip.lengthBars - 1 <= engine.songTimeline.lengthBars && dup == null) {
                    dup = engine.songTimeline.duplicateClip(clip.id, bar)
                    bar++
                }
                selectedClipId = dup?.id ?: selectedClipId
                songVersion++
            }
        },
        onSongClipSplit = {
            val clip = engine.songTimeline.list().firstOrNull { it.id == selectedClipId }
            if (clip != null && clip.lengthBars >= 2) {
                engine.songTimeline.splitClip(clip.id, clip.startBar + clip.lengthBars / 2)
                songVersion++
            }
        },
        onSongClipDelete = {
            if (selectedClipId != null) {
                engine.songTimeline.removeClip(selectedClipId!!)
                selectedClipId = null
                songVersion++
            }
        },
        onSongZoom = { bars ->
            songVisibleBars = bars
        },
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
            // SEQ covers every sound, not just drum pads: a sequence
            // row can hold a bass note or a synth stab as well as a kick.
            engine.setStep(pad, step, !engine.getStep(pad, step))
            patternVersion++
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        engine = PlaybackEngine()
        engine.setProjectKey(projectKeyMidi)
        sampleList = SampleStore.loadManifest(this)
        engine.bpm = bpm
        engine.masterVolume = masterVolume
        engine.onStepChanged = { step ->
            mainHandler.post { currentStep = step }
        }
        engine.onTransportPositionChanged = { bar, beat, progress ->
            mainHandler.post {
                transportBar = bar
                transportBeat = beat
                barProgress = progress
            }
        }
        engine.onLoopsChanged = { loops ->
            mainHandler.post {
                activeLoops = loops
                armedLoops = engine.armedLoopPads()
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
