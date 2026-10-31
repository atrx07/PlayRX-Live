package com.playrx.live

import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playrx.live.audio.PlaybackEngine
import com.playrx.live.audio.SampleBank
import com.playrx.live.midi.MidiEngine

/**
 * PlayRX Live v1 + landscape-first UI pass (from the Remixlive 8.6.2 study).
 *
 * - 24 pads: 0-15 one-shot drums/bass, 16-23 repeating loop patterns.
 * - Transport: play/pause, stop, BPM 60-180, master volume, delay FX.
 * - Step sequencer: pick a drum pad, toggle its 16 steps. Steps play on
 *   the transport clock alongside any active loop pads.
 * - Full pad: per-pad volume, mute, and solo (applies to one-shots,
 *   sequencer steps, and loops for that pad), pad colours by type
 *   (drums teal, bass blue, loops purple, selected amber, active
 *   loop bright teal), and a pad edit panel. Edit mode selects a pad
 *   without playing it.
 * - MIDI (existing): Connect MIDI shows the keyboard, chord readout updates
 *   from held notes. Chord-following of loops is NOT wired yet — that is
 *   the MIDI hardware test phase, after this audio core is proven.
 *
 * Layout:
 * - Landscape (primary, music-rest use): top bar with title / MIDI /
 *   chord / transport, a wide 8-column x 3-row pad grid on the left,
 *   transport sliders in a narrow side panel on the right (so the USB
 *   cable exits sideways, not over the keys), and the 16-step sequencer
 *   across the bottom.
 * - Portrait (handheld secondary): the original stacked column, now
 *   scrollable so the sequencer and footer are reachable.
 * Rotation does not restart the audio engine: the manifest declares
 * configChanges for orientation/screenSize, so the activity is kept
 * and only the layout recomposes.
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
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val isLandscape =
                        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                    if (isLandscape) {
                        LandscapeContent()
                    } else {
                        PortraitContent()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        midiEngine.close()
        engine.stop()
        super.onDestroy()
    }

    @Composable
    private fun PadButton(pad: Int, modifier: Modifier = Modifier) {
        val isActiveLoop = activeLoops.contains(pad)
        val isSelected = selectedPad == pad
        Button(
            onClick = {
                if (editPattern) {
                    // Edit mode: select the pad without playing it, so its
                    // volume/mute/solo (and, for drum pads, its steps)
                    // can be edited.
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
            colors = ButtonDefaults.buttonColors(
                containerColor = when {
                    isSelected -> Color(0xFFFFC107)
                    isActiveLoop -> Color(0xFF00BFA5)
                    SampleBank.isLoopPad(pad) -> Color(0xFF6A1B9A)
                    pad in 8..15 -> Color(0xFF1565C0)
                    else -> Color(0xFF2E7D6F)
                }
            ),
            modifier = modifier.aspectRatio(1f),
        ) {
            Text(
                SampleBank.PAD_NAMES[pad],
                fontSize = 9.sp,
                maxLines = 2,
            )
        }
    }

    /**
     * Non-lazy pad grid for portrait: a lazy grid inside a scrollable
     * Column is measured with infinite height and crashes on launch
     * ("vertically scrollable component was measured with an infinite
     * maximum height"). Rows of buttons have no such restriction, so
     * portrait uses this and keeps its scroll; landscape keeps the
     * lazy grid, where the height is finite.
     */
    @Composable
    private fun PadGridRows(columns: Int, modifier: Modifier = Modifier) {
        val rows = (24 + columns - 1) / columns
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = modifier,
        ) {
            for (row in 0 until rows) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    for (col in 0 until columns) {
                        val pad = row * columns + col
                        if (pad < 24) {
                            PadButton(pad, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PadGrid(columns: Int, modifier: Modifier = Modifier) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = modifier,
        ) {
            items(24) { pad ->
                PadButton(pad)
            }
        }
    }

    @Composable
    private fun TransportButtons() {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = {
                val next = !isPlaying
                engine.setPlaying(next)
                isPlaying = next
            }) {
                Text(if (isPlaying) "Pause" else "Play")
            }
            Button(onClick = {
                engine.stopAll()
                isPlaying = false
                activeLoops = emptySet()
                currentStep = -1
            }) {
                Text("Stop")
            }
            Button(onClick = {
                val devices = midiEngine.listDevices()
                if (devices.isNotEmpty()) {
                    midiEngine.open(devices.first())
                } else {
                    midiStatus = "No USB MIDI device found — connect the keyboard via USB OTG"
                }
            }) {
                Text("Connect MIDI")
            }
        }
    }

    @Composable
    private fun MixControls() {
        Text("BPM: ${bpm.toInt()}")
        Slider(
            value = bpm,
            onValueChange = {
                bpm = it
                engine.bpm = it
            },
            valueRange = 60f..180f,
        )
        Text("Volume: ${(masterVolume * 100).toInt()}%")
        Slider(
            value = masterVolume,
            onValueChange = {
                masterVolume = it
                engine.masterVolume = it
            },
            valueRange = 0f..1f,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Delay FX")
            Switch(
                checked = delayOn,
                onCheckedChange = {
                    delayOn = it
                    engine.delayEnabled = it
                },
            )
            Button(onClick = { editPattern = !editPattern }) {
                Text(if (editPattern) "Edit: On" else "Edit pattern")
            }
        }
    }

    @Composable
    private fun PadEditPanel() {
        // Read mixerVersion so volume/mute/solo changes recompose this panel.
        @Suppress("UNUSED_VARIABLE")
        val version = mixerVersion
        val pad = selectedPad
        val type = when {
            SampleBank.isLoopPad(pad) -> "Loop"
            SampleBank.isDrumPad(pad) -> "Drum"
            else -> "Bass"
        }
        Column {
            Text(
                "Pad: ${SampleBank.PAD_NAMES[pad]} ($type) — tap Edit pattern, then a pad, to select without playing",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Pad volume: ${(engine.getPadVolume(pad) * 100).toInt()}%")
            Slider(
                value = engine.getPadVolume(pad),
                onValueChange = {
                    engine.setPadVolume(pad, it)
                    mixerVersion++
                },
                valueRange = 0f..1f,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = {
                    engine.setPadMuted(pad, !engine.isPadMuted(pad))
                    mixerVersion++
                }) {
                    Text(if (engine.isPadMuted(pad)) "Muted" else "Mute")
                }
                Button(onClick = {
                    engine.setPadSolo(pad, !engine.isPadSolo(pad))
                    mixerVersion++
                }) {
                    Text(if (engine.isPadSolo(pad)) "Soloed" else "Solo")
                }
            }
        }
    }

    @Composable
    private fun SequencerRow() {
        Text(
            "Sequencer — ${SampleBank.PAD_NAMES[selectedPad]} (tap Edit pattern, pick a drum pad, then toggle steps)",
            style = MaterialTheme.typography.bodySmall,
        )
        // Read patternVersion so toggling a step recomposes this row.
        @Suppress("UNUSED_VARIABLE")
        val version = patternVersion
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            for (step in 0 until 16) {
                val on = engine.getStep(selectedPad, step)
                val isCurrent = currentStep == step && isPlaying
                Button(
                    onClick = {
                        if (SampleBank.isDrumPad(selectedPad)) {
                            engine.setStep(selectedPad, step, !on)
                            patternVersion++
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when {
                            isCurrent && on -> Color(0xFFFFC107)
                            on -> Color(0xFF00BFA5)
                            isCurrent -> Color(0xFF616161)
                            else -> Color(0xFFE0E0E0)
                        }
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text((step + 1).toString(), fontSize = 9.sp, maxLines = 1)
                }
            }
        }
    }

    @Composable
    private fun PortraitContent() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("PlayRX Live", style = MaterialTheme.typography.headlineMedium)
            Text(midiStatus, style = MaterialTheme.typography.bodySmall)
            Text("Chord: $chordText", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.padding(vertical = 8.dp)) {
                TransportButtons()
            }
            MixControls()
            PadGridRows(
                columns = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )
            PadEditPanel()
            SequencerRow()
            Text(
                "All sounds are synthesized in the app — no sample packs needed. " +
                    "Loop pads repeat every bar. MIDI chord readout works when a keyboard " +
                    "is connected; chord-following of loops comes with MIDI testing later.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }

    @Composable
    private fun LandscapeContent() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
        ) {
            // Top bar: title / MIDI / chord on the left, transport on the right.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("PlayRX Live", style = MaterialTheme.typography.titleLarge)
                    Text(midiStatus, style = MaterialTheme.typography.bodySmall)
                    Text("Chord: $chordText", style = MaterialTheme.typography.titleSmall)
                }
                TransportButtons()
            }
            // Middle: pad grid dominant, side panel for mix controls.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                PadGrid(
                    columns = 8,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(end = 12.dp),
                )
                Column(
                    modifier = Modifier
                        .width(240.dp)
                        .fillMaxHeight(),
                ) {
                    MixControls()
                    Text(
                        "Pads 0–15: one-shots. Pads 16–23: loops.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    PadEditPanel()
                }
            }
            // Bottom: sequencer across the full width.
            SequencerRow()
        }
    }
}
