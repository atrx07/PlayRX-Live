package com.playrx.live.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playrx.live.audio.SampleBank

/**
 * The PlayRX screen as standalone composables, driven only by
 * [PlayRxState] + [PlayRxActions]. This is the exact UI MainActivity
 * used to own; moving it here lets Paparazzi render it on the JVM
 * with no Activity, engine, or MIDI device, and lets the app and the
 * screenshot tests share one implementation (no drift between them).
 *
 * Layout (unchanged from the landscape-first UI pass):
 * - Landscape (primary, music-rest use): top bar, 8-column pad grid
 *   left, 240dp side panel right, sequencer across the bottom.
 * - Portrait (handheld secondary): stacked, scrollable column, grid
 *   wrapped to 4 columns via non-lazy rows (a lazy grid inside a
 *   scrollable Column crashes: infinite-height measurement).
 */
@Composable
fun PlayRxScreen(
    state: PlayRxState,
    actions: PlayRxActions = PlayRxActions(),
    isLandscape: Boolean,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (isLandscape) {
                LandscapeContent(state, actions)
            } else {
                PortraitContent(state, actions)
            }
        }
    }
}

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

@Composable
private fun PadButton(state: PlayRxState, actions: PlayRxActions, pad: Int, modifier: Modifier = Modifier) {
    val isActiveLoop = state.activeLoops.contains(pad)
    val isSelected = state.selectedPad == pad
    Button(
        onClick = { actions.onPadClick(pad) },
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                isSelected -> Color(0xFFFFC107)
                isActiveLoop -> Color(0xFF00BFA5)
                else -> columnColor(SampleBank.columnOf(pad))
            }
        ),
        modifier = modifier.aspectRatio(1f),
    ) {
        Text(SampleBank.PAD_NAMES[pad], fontSize = 9.sp, maxLines = 2)
    }
}

@Composable
private fun PadGridRows(state: PlayRxState, actions: PlayRxActions, columns: Int, modifier: Modifier = Modifier) {
    val rows = (SampleBank.PAD_COUNT + columns - 1) / columns
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        for (row in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until columns) {
                    val pad = row * columns + col
                    if (pad < SampleBank.PAD_COUNT) {
                        PadButton(state, actions, pad, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun PadGrid(state: PlayRxState, actions: PlayRxActions, columns: Int, modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        items(SampleBank.PAD_COUNT) { pad ->
            PadButton(state, actions, pad)
        }
    }
}

@Composable
private fun TopTabs(state: PlayRxState, actions: PlayRxActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (tab in listOf("LOOP", "SEQ", "DRUM", "SONG")) {
            Button(onClick = { actions.onTabSelect(tab) }) {
                Text(tab)
            }
        }
    }
}

@Composable
private fun ColumnMixer(state: PlayRxState, actions: PlayRxActions) {
    Text("Mixer — one strip per instrument column", style = MaterialTheme.typography.bodySmall)
    for (col in 0 until SampleBank.COLUMNS) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(SampleBank.COLUMN_NAMES[col], modifier = Modifier.width(52.dp))
            Slider(
                value = state.columnVolumes[col],
                onValueChange = { actions.onColumnVolume(col, it) },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { actions.onColumnMuteToggle(col) }) {
                Text(if (state.columnMuted[col]) "M" else "m")
            }
            Button(onClick = { actions.onColumnSoloToggle(col) }) {
                Text(if (state.columnSolo[col]) "S" else "s")
            }
        }
    }
}

@Composable
private fun TransportButtons(state: PlayRxState, actions: PlayRxActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = actions.onPlayToggle) {
            Text(if (state.isPlaying) "Pause" else "Play")
        }
        Button(onClick = actions.onStop) {
            Text("Stop")
        }
        Button(onClick = actions.onConnectMidi) {
            Text("Connect MIDI")
        }
    }
}

@Composable
private fun MixControls(state: PlayRxState, actions: PlayRxActions) {
    Text("BPM: ${state.bpm.toInt()}")
    Slider(value = state.bpm, onValueChange = actions.onBpmChange, valueRange = 60f..180f)
    Text("Volume: ${(state.masterVolume * 100).toInt()}%")
    Slider(value = state.masterVolume, onValueChange = actions.onVolumeChange, valueRange = 0f..1f)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Delay FX")
        Switch(checked = state.delayOn, onCheckedChange = actions.onDelayChange)
        Button(onClick = actions.onEditToggle) {
            Text(if (state.editPattern) "Edit: On" else "Edit pattern")
        }
    }
}

@Composable
private fun PadEditPanel(state: PlayRxState, actions: PlayRxActions) {
    val pad = state.selectedPad
    val type = if (SampleBank.isLoopPad(pad)) "Loop" else "One-shot"
    Column {
        Text(
            "Pad: ${SampleBank.PAD_NAMES[pad]} ($type) — tap Edit pattern, then a pad, to select without playing",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Pad volume: ${(state.padVolumes[pad] * 100).toInt()}%")
        Slider(
            value = state.padVolumes[pad],
            onValueChange = { actions.onPadVolume(pad, it) },
            valueRange = 0f..1f,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { actions.onPadMuteToggle(pad) }) {
                Text(if (state.padMuted[pad]) "Muted" else "Mute")
            }
            Button(onClick = { actions.onPadSoloToggle(pad) }) {
                Text(if (state.padSolo[pad]) "Soloed" else "Solo")
            }
        }
    }
}

@Composable
private fun SequencerRow(state: PlayRxState, actions: PlayRxActions) {
    Text(
        "Sequencer — ${SampleBank.PAD_NAMES[state.selectedPad]} (tap Edit pattern, pick a drum pad, then toggle steps)",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        for (step in 0 until 16) {
            val on = state.steps[state.selectedPad][step]
            val isCurrent = state.currentStep == step && state.isPlaying
            Button(
                onClick = { actions.onStepToggle(state.selectedPad, step) },
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
private fun PlaceholderTab(state: PlayRxState) {
    Text(
        "${state.selectedTab} view is next: projects, recording, and the song arranger " +
            "come after the pad and mixer are proven. PlayRX's edge over a " +
            "closed pad app is USB MIDI chord-following with a Yamaha keyboard, " +
            "open source, and no paywall.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun PortraitContent(state: PlayRxState, actions: PlayRxActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("PlayRX Live", style = MaterialTheme.typography.headlineMedium)
        Text(state.midiStatus, style = MaterialTheme.typography.bodySmall)
        Text("Chord: ${state.chordText}", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.padding(vertical = 8.dp)) {
            TransportButtons(state, actions)
        }
        TopTabs(state, actions)
        MixControls(state, actions)
        if (state.selectedTab == "LOOP") {
            PadGridRows(state, actions, columns = 4, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            PadEditPanel(state, actions)
            ColumnMixer(state, actions)
        } else if (state.selectedTab == "DRUM") {
            SequencerRow(state, actions)
        } else {
            PlaceholderTab(state)
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
private fun LandscapeContent(state: PlayRxState, actions: PlayRxActions) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("PlayRX Live", style = MaterialTheme.typography.titleLarge)
                Text(state.midiStatus, style = MaterialTheme.typography.bodySmall)
                Text("Chord: ${state.chordText}", style = MaterialTheme.typography.titleSmall)
            }
            TransportButtons(state, actions)
        }
        TopTabs(state, actions)
        if (state.selectedTab == "LOOP") {
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
                PadGrid(
                    state, actions, columns = 8,
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 12.dp),
                )
                Column(
                    modifier = Modifier.width(240.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                ) {
                    MixControls(state, actions)
                    Text(
                        "8 columns x 6 variations = 48 pads. Pad + FX columns loop.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    PadEditPanel(state, actions)
                    ColumnMixer(state, actions)
                }
            }
            SequencerRow(state, actions)
        } else if (state.selectedTab == "DRUM") {
            SequencerRow(state, actions)
        } else {
            PlaceholderTab(state)
        }
    }
}
