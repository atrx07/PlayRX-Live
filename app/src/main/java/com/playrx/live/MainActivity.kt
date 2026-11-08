package com.playrx.live

import android.content.Intent
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
import androidx.core.content.FileProvider
import com.playrx.live.audio.PlayMode
import com.playrx.live.audio.PlaybackEngine
import com.playrx.live.audio.PadSample
import com.playrx.live.audio.ProjectData
import com.playrx.live.audio.ProjectFile
import com.playrx.live.audio.ProjectStore
import com.playrx.live.audio.RecordMode
import com.playrx.live.audio.SampleBank
import com.playrx.live.audio.SampleInfo
import com.playrx.live.audio.SampleStore
import com.playrx.live.audio.SongTimeline
import com.playrx.live.audio.WavWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        // Replace… (EDIT): the import goes straight onto the pad
        // it was launched from; a plain import just joins the
        // library and waits for Assign.
        val replacePad = pendingReplacePad
        pendingReplacePad = null
        val targetPad = replacePad ?: selectedPad
        Thread {
            val info = SampleStore.importUri(
                this, uri, bpm, projectKeyMidi,
                SampleStore.categoryForColumn(SampleBank.columnOf(targetPad)),
            )
            mainHandler.post {
                if (info == null) {
                    sampleImportError = "Could not decode that file. Try a WAV, OGG, MP3 or AAC you own."
                } else {
                    sampleImportError = null
                    sampleList = SampleStore.loadManifest(this)
                    if (replacePad != null) {
                        val decoded = SampleStore.decodeStored(this, info)
                        if (decoded != null) {
                            val mode = if (SampleBank.isLoopPad(replacePad)) PlayMode.LOOP else PlayMode.ONE_SHOT
                            val targetInfo = info.copy(
                                category = SampleStore.categoryForColumn(SampleBank.columnOf(replacePad)),
                            )
                            engine.assignImportedSample(
                                replacePad,
                                decoded.frames,
                                PadSample(pad = replacePad, sampleId = info.id, playMode = mode, quantizeBeats = if (mode == PlayMode.LOOP) defaultQuantizeBeats else 0f, snapToTempo = defaultSnapToTempo),
                                targetInfo,
                            )
                            sampleTempoDetectResult = null
                        }
                    }
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
    private var showProjects by mutableStateOf(false)
    private var newProjectName by mutableStateOf("")
    private var projects by mutableStateOf(listOf<Pair<String, Long>>())
    private var recordings by mutableStateOf(listOf<String>())
    private var projectMessage by mutableStateOf<String?>(null)
    private var recordMode by mutableStateOf("OFF")
    private var delayDivisionBeats by mutableStateOf(0.75f)
    private var delayMix by mutableStateOf(0.25f)
    private var delayFeedback by mutableStateOf(0.35f)
    private var beatRepeat by mutableStateOf("Off")
    private var beatRepeatMix by mutableStateOf(0.7f)
    private var masterFilterCutoffHz by mutableStateOf(0f)
    private var columnSends by mutableStateOf(List(8) { 0.5f })
    private var columnFilters by mutableStateOf(List(8) { 0f })
    private var mixerTab by mutableStateOf("Volumes")
    private var samplesSearch by mutableStateOf("")
    private var samplesCategory by mutableStateOf("All")
    /** EDIT Replace…: the pad the next import lands on, if any. */
    private var pendingReplacePad: Int? = null

    private fun refreshProjectLists() {
        projects = ProjectStore.list(this).map { it.name to it.lastModified }
        recordings = ProjectStore.recordingsDir(this).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".wav") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name } ?: emptyList()
    }

    private fun buildProjectData(name: String): ProjectData {
        val assignments = engine.importedPadAssignments().entries.joinToString(
            separator = ",", prefix = "[", postfix = "]",
        ) { (pad, pair) -> ProjectFile.padAssignmentToJson(pad, pair.first, pair.second) }
        return ProjectData(
            name = name,
            bpm = bpm,
            projectKeyMidi = projectKeyMidi,
            masterVolume = masterVolume,
            defaultQuantizeBeats = defaultQuantizeBeats,
            defaultSnapToTempo = defaultSnapToTempo,
            sequencesJson = engine.sequenceStore.toJson(),
            songJson = engine.songTimeline.toJson(),
            assignmentsJson = assignments,
        )
    }

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
            delayDivisionBeats = delayDivisionBeats,
            delayMix = delayMix,
            delayFeedback = delayFeedback,
            beatRepeat = beatRepeat,
            beatRepeatMix = beatRepeatMix,
            masterFilterCutoffHz = masterFilterCutoffHz,
            columnSends = columnSends,
            columnFilters = columnFilters,
            mixerTab = mixerTab,
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
            showProjects = showProjects,
            projects = projects,
            newProjectName = newProjectName,
            recordMode = recordMode,
            masterRecording = engine.masterRecording,
            masterCaptureFull = engine.masterCaptureFull,
            recordings = recordings,
            projectMessage = projectMessage,
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
            sampleGain = engine.importedAssignment(selectedPad)?.gain ?: 1f,
            samplePan = engine.importedAssignment(selectedPad)?.pan ?: 0f,
            sampleFilterHz = engine.importedAssignment(selectedPad)?.filterCutoffHz ?: 20000f,
            sampleRepeat = engine.importedAssignment(selectedPad)?.repeatCount ?: 1,
            sampleTrimStart = run {
                val a = engine.importedAssignment(selectedPad)
                val info = engine.importedSampleInfo(selectedPad)
                if (a == null || info == null || a.startFrame < 0 || info.frames <= 0) 0f
                else (a.startFrame.toFloat() / info.frames).coerceIn(0f, 1f)
            },
            sampleTrimEnd = run {
                val a = engine.importedAssignment(selectedPad)
                val info = engine.importedSampleInfo(selectedPad)
                if (a == null || info == null || a.endFrame < 0 || info.frames <= 0) 1f
                else (a.endFrame.toFloat() / info.frames).coerceIn(0f, 1f)
            },
            samplePeaks = engine.samplePeaks(selectedPad).toList(),
            gatePads = engine.importedPadIds().filter {
                engine.importedAssignment(it)?.playMode == PlayMode.GATE
            }.toSet(),
            samplesSearch = samplesSearch,
            samplesCategory = samplesCategory,
            sampleCategories = sampleList.map { it.name to it.category.name },
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
            engine.setDelayEnabled(it)
        },
        onDelayDivision = {
            delayDivisionBeats = it
            engine.setDelayDivision(it)
        },
        onDelayMix = {
            delayMix = it
            engine.setDelayMix(it)
        },
        onDelayFeedback = {
            delayFeedback = it
            engine.setDelayFeedback(it)
        },
        onBeatRepeat = {
            beatRepeat = it
            engine.setBeatRepeat(com.playrx.live.audio.BeatRepeat.fromLabel(it))
        },
        onBeatRepeatMix = {
            beatRepeatMix = it
            engine.setBeatRepeatMix(it)
        },
        onMasterFilterCutoff = {
            masterFilterCutoffHz = it
            engine.setMasterFilterCutoff(it)
        },
        onColumnSend = { col, value ->
            columnSends = columnSends.mapIndexed { i, v -> if (i == col) value else v }
            engine.setColumnSend(col, value)
        },
        onColumnFilter = { col, hz ->
            columnFilters = columnFilters.mapIndexed { i, v -> if (i == col) hz else v }
            engine.setColumnFilter(col, hz)
        },
        onMixerTab = { mixerTab = it },
        onXy = { x, y ->
            engine.setXy(x, y)
            masterFilterCutoffHz = engine.fxState.masterFilterCutoffHz
            delayMix = engine.fxState.delayMix
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
        onSampleGain = { gain ->
            val cur = engine.importedAssignment(selectedPad)
            if (cur != null) {
                engine.updateImportedAssignment(selectedPad, cur.copy(gain = gain.coerceIn(0f, 2f)))
                sampleVersion++
            }
        },
        onSamplePan = { pan ->
            val cur = engine.importedAssignment(selectedPad)
            if (cur != null) {
                engine.updateImportedAssignment(selectedPad, cur.copy(pan = pan.coerceIn(-1f, 1f)))
                sampleVersion++
            }
        },
        onSampleFilter = { hz ->
            val cur = engine.importedAssignment(selectedPad)
            if (cur != null) {
                engine.updateImportedAssignment(selectedPad, cur.copy(filterCutoffHz = hz.coerceIn(200f, 20000f)))
                sampleVersion++
            }
        },
        onSampleRepeat = { count ->
            val cur = engine.importedAssignment(selectedPad)
            if (cur != null) {
                engine.updateImportedAssignment(selectedPad, cur.copy(repeatCount = count.coerceIn(1, 8)))
                sampleVersion++
            }
        },
        onSampleTrimStart = { fraction ->
            val cur = engine.importedAssignment(selectedPad)
            val info = engine.importedSampleInfo(selectedPad)
            if (cur != null && info != null && info.frames > 0) {
                val start = (fraction * info.frames).toInt().coerceIn(0, info.frames - 1)
                engine.updateImportedAssignment(selectedPad, cur.copy(startFrame = start))
                sampleVersion++
            }
        },
        onSampleTrimEnd = { fraction ->
            val cur = engine.importedAssignment(selectedPad)
            val info = engine.importedSampleInfo(selectedPad)
            if (cur != null && info != null && info.frames > 0) {
                val end = (fraction * info.frames).toInt().coerceIn(1, info.frames)
                engine.updateImportedAssignment(selectedPad, cur.copy(endFrame = end))
                sampleVersion++
            }
        },
        onSampleUnload = {
            engine.clearImportedSample(selectedPad)
            activeLoops = engine.activeLoopPads()
            armedLoops = engine.armedLoopPads()
            sampleTempoDetectResult = null
            sampleVersion++
        },
        onSampleReplace = {
            pendingReplacePad = selectedPad
            importPicker.launch("audio/*")
        },
        onPadPress = { pad -> engine.pressGatePad(pad) },
        onPadRelease = { pad -> engine.releaseGatePad(pad) },
        onSamplesSearch = { samplesSearch = it },
        onSamplesCategory = { samplesCategory = it },
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
            // The ● Rec button in SEQ mirrors DRUMS mode in the Rec dropdown.
            val next = if (engine.recordMode == RecordMode.DRUMS) RecordMode.OFF else RecordMode.DRUMS
            engine.setRecordMode(next)
            isRecording = engine.isRecording
            recordMode = next.name
        },
        onProjectsToggle = {
            showProjects = !showProjects
            if (showProjects) refreshProjectLists()
        },
        onNewProjectName = { newProjectName = it },
        onProjectSave = {
            val name = ProjectStore.sanitizeName(
                newProjectName.ifBlank { "Project ${projects.size + 1}" },
            )
            val ok = ProjectStore.save(this, name, ProjectFile.toJson(buildProjectData(name)))
            projectMessage = if (ok) "Saved $name" else "Could not save $name"
            if (ok) {
                newProjectName = ""
                refreshProjectLists()
            }
        },
        onProjectLoad = { name ->
            val data = ProjectStore.load(this, name)?.let { ProjectFile.fromJson(it) }
            if (data == null) {
                projectMessage = "Could not open $name"
            } else {
                // A loaded project starts from bar 1.
                engine.stopAll()
                isPlaying = false
                activeLoops = emptySet()
                armedLoops = emptySet()
                transportBar = 1
                transportBeat = 1
                barProgress = 0f
                currentStep = -1
                bpm = data.bpm
                engine.bpm = data.bpm
                projectKeyMidi = data.projectKeyMidi
                engine.setProjectKey(data.projectKeyMidi)
                masterVolume = data.masterVolume
                engine.masterVolume = data.masterVolume
                defaultQuantizeBeats = data.defaultQuantizeBeats
                defaultSnapToTempo = data.defaultSnapToTempo
                engine.sequenceStore.loadJson(data.sequencesJson)
                SongTimeline.fromJson(data.songJson)?.let { engine.songTimeline.loadFrom(it) }
                selectedClipId = null
                // Re-assign samples by id; a sample deleted since the
                // save loads back to the built-in synth, never an error.
                for (obj in ProjectFile.splitAssignmentObjects(data.assignmentsJson)) {
                    val parsed = ProjectFile.padAssignmentFromJson(obj) ?: continue
                    val (pad, sampleId, assignment) = parsed
                    val info = sampleList.firstOrNull { it.id == sampleId } ?: continue
                    val decoded = SampleStore.decodeStored(this, info) ?: continue
                    val targetInfo = info.copy(
                        category = SampleStore.categoryForColumn(SampleBank.columnOf(pad)),
                    )
                    engine.assignImportedSample(pad, decoded.frames, assignment, targetInfo)
                }
                sampleVersion++
                songVersion++
                patternVersion++
                projectMessage = "Loaded $name"
                showProjects = false
            }
        },
        onProjectDelete = { name ->
            if (ProjectStore.delete(this, name)) {
                projectMessage = "Deleted $name"
                refreshProjectLists()
            } else {
                projectMessage = "Could not delete $name"
            }
        },
        onRecordMode = { mode ->
            val parsed = runCatching { RecordMode.valueOf(mode) }.getOrNull() ?: RecordMode.OFF
            // Leaving MASTER saves the WAV first.
            if (engine.recordMode == RecordMode.MASTER && parsed != RecordMode.MASTER) {
                val samples = engine.finishMasterRecording()
                if (samples != null) {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    val file = File(ProjectStore.recordingsDir(this), "master-$stamp.wav")
                    projectMessage = if (WavWriter.writeWav(file, samples, SampleBank.SAMPLE_RATE, 1)) {
                        "Saved ${file.name}"
                    } else {
                        "Could not save the recording"
                    }
                } else {
                    projectMessage = "Recording too short — nothing saved"
                }
                refreshProjectLists()
            }
            engine.setRecordMode(parsed)
            isRecording = engine.isRecording
            recordMode = parsed.name
        },
        onRecordingShare = { fileName ->
            val file = File(ProjectStore.recordingsDir(this), fileName)
            if (file.exists()) {
                val uri = FileProvider.getUriForFile(this, "com.playrx.live.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "audio/wav"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share recording"))
            } else {
                projectMessage = "Recording not found: $fileName"
            }
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
