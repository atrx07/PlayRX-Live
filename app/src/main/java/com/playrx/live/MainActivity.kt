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
 * - 48 pads, 8x6: columns Kick/Snare/Tops/Bass/Synth/Pad/Vox/FX, rows
 *   are variations 1..6. Pad + FX columns are repeating loop pads;
 *   the rest are one-shots. This grid shape is familiar to Remixlive
 *   users; the sounds, names, branding, and code are PlayRX's own.
 * - Transport: play/pause, stop, BPM 60-180, master volume, delay FX.
 * - Step sequencer: pick a drum pad, toggle its 16 steps. Steps play on
 *   the transport clock alongside any active loop pads.
 * - Full pad: per-pad volume, mute, and solo, plus a per-column mixer
 *   (one strip per instrument column), and a pad edit panel. Edit mode
 *   selects a pad without playing it.
 * - Tabs: LOOP (pad grid), DRUM (sequencer), SEQ/SONG (placeholder —
 *   projects, recording, and arranger are the next phase).
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
    private var selectedTab by mutableStateOf("LOOP")

    private fun columnColor(col: Int): Color = when (col) {
        0 -> Color(0xFFD32F2F) // Kick red
        1 -> Color(0xFFEF6C00) // Snare orange
        2 -> Color(0xFFF9A825) // Tops yellow
        3 -> Color(0xFF43A047) // Bass green
        4 -> Color(0xFF00ACC1) // Synth cyan
        5 -> Color(0xFFEC407A) // Pad pink
        6 -> Color(0xFF7E57C2) // Vox purple
        else -> Color(0xFF42A5F5) // FX blue
    }

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
                    else -> columnColor(SampleBank.columnOf(pad))
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
        // For portrait, columns is the display width, not the 8 instrument
        // columns: pads are still row-major in instrument order, just wrapped.
        val rows = (SampleBank.PAD_COUNT + columns - 1) / columns
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
                        if (pad < SampleBank.PAD_COUNT) {
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
            items(SampleBank.PAD_COUNT) { pad ->
                PadButton(pad)
            }
        }
    }

    @Composable
    private fun TopTabs() {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (tab in listOf("LOOP", "SEQ", "DRUM", "SONG")) {
                Button(onClick = { selectedTab = tab }) {
                    Text(tab)
                }
            }
        }
    }

    @Composable
    private fun ColumnMixer() {
        @Suppress("UNUSED_VARIABLE")
        val version = mixerVersion
        Text("Mixer — one strip per instrument column", style = MaterialTheme.typography.bodySmall)
        for (col in 0 until SampleBank.COLUMNS) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(SampleBank.COLUMN_NAMES[col], modifier = Modifier.width(52.dp))
                Slider(
                    value = engine.getColumnVolume(col),
                    onValueChange = {
                        engine.setColumnVolume(col, it)
                        mixerVersion++
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = {
                    engine.setColumnMuted(col, !engine.isColumnMuted(col))
                    mixerVersion++
                }) {
                    Text(if (engine.isColumnMuted(col)) "M" else "m")
                }
                Button(onClick = {
                    engine.setColumnSolo(col, !engine.isColumnSolo(col))
                    mixerVersion++
                }) {
                    Text(if (engine.isColumnSolo(col)) "S" else "s")
                }
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
        val type = if (SampleBank.isLoopPad(pad)) "Loop" else "One-shot"
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
            TopTabs()
            MixControls()
            if (selectedTab == "LOOP") {
                PadGridRows(
                    columns = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
                PadEditPanel()
                ColumnMixer()
            } else if (selectedTab == "DRUM") {
                SequencerRow()
            } else {
                Text(
                    "$selectedTab view is next: projects, recording, and the song arranger " +
                        "come after the pad and mixer are proven. PlayRX's edge over a " +
                        "closed pad app is USB MIDI chord-following with a Yamaha keyboard, " +
                        "open source, and no paywall.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            Text(
                "48 pads, 8 instrument columns x 6 variations. All sounds are synthesized " +
                    "in the app — no sample packs needed. Pad and FX columns are loop pads " +
                    "that repeat every bar. MIDI chord readout works when a keyboard " +
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
            TopTabs()
            if (selectedTab == "LOOP") {
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
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        MixControls()
                        Text(
                            "8 columns x 6 variations = 48 pads. Pad + FX columns loop.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        PadEditPanel()
                        ColumnMixer()
                    }
                }
                // Bottom: sequencer across the full width.
                SequencerRow()
            } else if (selectedTab == "DRUM") {
                SequencerRow()
            } else {
                Text(
                    "$selectedTab view is next: projects, recording, and the song arranger " +
                        "come after the pad and mixer are proven. PlayRX's edge over a " +
                        "closed pad app is USB MIDI chord-following with a Yamaha keyboard, " +
                        "open source, and no paywall.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }
}
