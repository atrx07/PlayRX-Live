package com.playrx.live.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
 * Layout (Tutorial Tour restructure, 2026-10-31 — see
 * docs/UI-STRUCTURE.md) + full dark UI (2026-11-01, from the
 * Tutorial Tour look and the user's dark spec):
 * - Dark overall: near-black background, dark panels, light text.
 * - Pads, the Remixlive read: UNSELECTED = pale column colour
 *   (solid colour at ~22% alpha over the dark background) with a
 *   DIM outline (same colour at ~45% alpha, 1dp). PLAYING =
 *   FILLED solid column colour. PLAYING + SELECTED = filled
 *   solid colour + DARK outline (black, 3dp) — that dark outline
 *   is what marks the selected playing pad when a side panel
 *   (EDIT / MIXER / FX / SAMPLES) is open. Selected but not
 *   playing = pale fill + STRONG column-colour outline (2dp),
 *   so selection is visible without pretending it is playing.
 * - Top tabs only: LOOP, SEQ, DRUM, SONG. Edit and Mixer are NOT
 *   top tabs and NOT a side column inside LOOP.
 * - Below / beside the main view, a switchable panel, chosen by
 *   the EDIT / MIXER / FX / SAMPLES buttons: EDIT = selected pad,
 *   MIXER = VOLUMES (per-column faders + M/S, plus tempo/master),
 *   FX = delay for now (beat-repeat / XY pad next), SAMPLES =
 *   collection / import placeholder (own manifest, next).
 * - Landscape (primary, music-rest use): top bar, 8-column grid
 *   (rounded-rectangle pads, height fitted so all 6 rows show)
 *   with a switchable panel on the right (EDIT / MIXER / FX /
 *   SAMPLES, one at a time — NOT all stacked). A bottom panel
 *   in phone landscape collapsed the grid to ~0 height (Paparazzi
 *   panel run, 2026-10-31): phone landscape is only ~412dp tall,
 *   and a fixed 170dp panel + header + tabs left the grid ~40dp.
 *   The right-side panel keeps the grid's full height.
 * - Portrait (handheld secondary): stacked, scrollable column, grid
 *   wrapped to 4 columns via non-lazy rows (a lazy grid inside a
 *   scrollable Column crashes: infinite-height measurement).
 */
// Dark palette, shared by every composable below.
private val DarkBg = Color(0xFF121218)
private val DarkPanel = Color(0xFF1C1C26)
private val DarkTab = Color(0xFF262633)
private val TextMain = Color(0xFFF2F2F7)
private val TextDim = Color(0xFF9A9AAA)
private val AccentAmber = Color(0xFFFFC107)
private val PlayingTeal = Color(0xFF00BFA5)

@Composable
fun PlayRxScreen(
    state: PlayRxState,
    actions: PlayRxActions = PlayRxActions(),
    isLandscape: Boolean,
) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = DarkBg,
            surface = DarkBg,
            surfaceVariant = DarkPanel,
            primary = AccentAmber,
            onBackground = TextMain,
            onSurface = TextMain,
            onSurfaceVariant = TextDim,
        )
    ) {
        Surface(color = DarkBg, modifier = Modifier.fillMaxSize()) {
            if (isLandscape) {
                LandscapeContent(state, actions)
            } else {
                PortraitContent(state, actions)
            }
        }
    }
}

private val PitchNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private fun pitchName(pc: Int): String = PitchNames[((pc % 12) + 12) % 12]

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
    val isArmed = state.armedLoops.contains(pad)
    val isSelected = state.selectedPad == pad
    val col = columnColor(SampleBank.columnOf(pad))
    // Remixlive pad read, dark version:
    // - unselected, not playing: pale fill + dim outline
    // - playing: filled solid colour
    // - playing + selected: filled solid + dark (black) outline
    // - selected, not playing: pale fill + strong colour outline
    // The old code made every selected pad amber and every active
    // loop teal, which hid the pad's own column colour entirely.
    val container = when {
        isActiveLoop -> col
        isArmed -> col.copy(alpha = 0.35f)
        isSelected -> col.copy(alpha = 0.35f)
        else -> col.copy(alpha = 0.22f)
    }
    val border = when {
        isActiveLoop && isSelected -> BorderStroke(3.dp, Color.Black)
        isActiveLoop -> BorderStroke(1.dp, Color.Black.copy(alpha = 0.35f))
        // Armed (waiting for the next bar) reads as a white ring:
        // it is NOT playing yet, so it must not use the filled
        // playing colour, and it must not look merely selected.
        isArmed -> BorderStroke(2.dp, Color.White)
        isSelected -> BorderStroke(2.dp, col)
        else -> BorderStroke(1.dp, col.copy(alpha = 0.45f))
    }
    val content = when {
        isActiveLoop -> if (col == columnColor(2)) Color.Black else Color.White
        isSelected -> Color.White
        else -> col
    }
    Button(
        onClick = { actions.onPadClick(pad) },
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(2.dp),
        border = border,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
        ),
        modifier = modifier,
    ) {
        Text(SampleBank.PAD_NAMES[pad], fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
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
                        PadButton(state, actions, pad, Modifier.weight(1f).aspectRatio(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun PadGrid(
    state: PlayRxState,
    actions: PlayRxActions,
    columns: Int,
    padHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        items(SampleBank.PAD_COUNT) { pad ->
            PadButton(state, actions, pad, Modifier.height(padHeight))
        }
    }
}

@Composable
private fun TopTabs(state: PlayRxState, actions: PlayRxActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (tab in listOf("LOOP", "SEQ", "DRUM", "SONG")) {
            val selected = state.selectedTab == tab
            Button(
                onClick = { actions.onTabSelect(tab) },
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) Color.White else DarkTab,
                    contentColor = if (selected) Color.Black else TextDim,
                ),
            ) {
                Text(tab, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun ColumnMixer(state: PlayRxState, actions: PlayRxActions) {
    Text("Mixer — one strip per instrument column", style = MaterialTheme.typography.bodySmall, color = TextDim)
    for (col in 0 until SampleBank.COLUMNS) {
        val colColor = columnColor(col)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(SampleBank.COLUMN_NAMES[col], modifier = Modifier.width(52.dp), color = TextMain)
            Slider(
                value = state.columnVolumes[col],
                onValueChange = { actions.onColumnVolume(col, it) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = colColor,
                    activeTrackColor = colColor,
                    inactiveTrackColor = colColor.copy(alpha = 0.25f),
                ),
                modifier = Modifier.weight(1f),
            )
            val muted = state.columnMuted[col]
            Button(
                onClick = { actions.onColumnMuteToggle(col) },
                contentPadding = PaddingValues(4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (muted) Color.White else DarkTab,
                    contentColor = if (muted) Color.Black else TextDim,
                ),
            ) {
                Text(if (muted) "M" else "m")
            }
            val solo = state.columnSolo[col]
            Button(
                onClick = { actions.onColumnSoloToggle(col) },
                contentPadding = PaddingValues(4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (solo) AccentAmber else DarkTab,
                    contentColor = if (solo) Color.Black else TextDim,
                ),
            ) {
                Text(if (solo) "S" else "s")
            }
        }
    }
}

@Composable
private fun TransportButtons(state: PlayRxState, actions: PlayRxActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = actions.onPlayToggle,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.isPlaying) PlayingTeal else DarkTab,
                contentColor = if (state.isPlaying) Color.Black else TextMain,
            ),
        ) {
            Text(if (state.isPlaying) "Pause" else "Play")
        }
        Button(
            onClick = actions.onStop,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
        ) {
            Text("Stop")
        }
        Button(
            onClick = actions.onConnectMidi,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
        ) {
            Text("Connect MIDI")
        }
    }
}

@Composable
private fun TempoControls(state: PlayRxState, actions: PlayRxActions) {
    Text("BPM: ${state.bpm.toInt()}", color = TextMain)
    Slider(
        value = state.bpm, onValueChange = actions.onBpmChange, valueRange = 60f..180f,
        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
    )
    Text("Master volume: ${(state.masterVolume * 100).toInt()}%", color = TextMain)
    Slider(
        value = state.masterVolume, onValueChange = actions.onVolumeChange, valueRange = 0f..1f,
        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
    )
}

@Composable
private fun PanelSwitchButton(state: PlayRxState, actions: PlayRxActions, panel: String, modifier: Modifier = Modifier) {
    Button(
        onClick = { actions.onPanelSelect(panel) },
        contentPadding = PaddingValues(4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (state.selectedPanel == panel) AccentAmber else DarkTab,
            contentColor = if (state.selectedPanel == panel) Color.Black else TextDim,
        ),
        modifier = modifier,
    ) {
        Text(panel, fontSize = 11.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun PanelSwitch(state: PlayRxState, actions: PlayRxActions) {
    // 2x2, not a single Row: four full-size buttons do not fit a
    // 260dp landscape panel column, and the 4th (SAMPLES) was cut
    // off the right edge in the panel2 screenshots (2026-10-31).
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PanelSwitchButton(state, actions, "EDIT", Modifier.weight(1f))
            PanelSwitchButton(state, actions, "MIXER", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PanelSwitchButton(state, actions, "FX", Modifier.weight(1f))
            PanelSwitchButton(state, actions, "SAMPLES", Modifier.weight(1f))
        }
    }
}

@Composable
private fun BottomPanel(state: PlayRxState, actions: PlayRxActions, modifier: Modifier = Modifier) {
    // The side panel itself is a dark card, so the panel area reads
    // as one Remixlive-style lower panel, not as loose controls on
    // the background.
    Column(modifier = modifier.background(DarkPanel, RoundedCornerShape(16.dp)).padding(12.dp)) {
        when (state.selectedPanel) {
            "EDIT" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = actions.onEditToggle,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.editPattern) AccentAmber else DarkTab,
                            contentColor = if (state.editPattern) Color.Black else TextMain,
                        ),
                    ) {
                        Text(if (state.editPattern) "Edit: On — tap a pad to select" else "Edit pattern")
                    }
                }
                PadEditPanel(state, actions)
                // Advanced sample engine (2026-11-01): these controls
                // only change the sound when a sample is assigned to
                // the selected pad; with the built-in synth they are
                // shown for the pad's future sample, not faked as live.
                Text(
                    "Sample: ${state.selectedPadSampleName ?: "Built-in synth (no sample assigned)"}",
                    style = MaterialTheme.typography.bodySmall, color = TextDim,
                )
                Text("Play mode", color = TextMain)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (mode in listOf("LOOP", "ONE_SHOT", "GATE")) {
                        val sel = state.samplePlayMode == mode
                        Button(
                            onClick = { actions.onSamplePlayMode(mode) },
                            contentPadding = PaddingValues(4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (sel) AccentAmber else DarkTab,
                                contentColor = if (sel) Color.Black else TextDim,
                            ),
                        ) {
                            Text(mode, fontSize = 10.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
                Text("Transpose: ${state.sampleTranspose} semitones", color = TextMain)
                Slider(
                    value = state.sampleTranspose.toFloat(),
                    onValueChange = { actions.onSampleTranspose(it.toInt()) },
                    valueRange = -12f..12f, steps = 23,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val snap = state.sampleSnapToTempo
                    Button(
                        onClick = actions.onSampleSnapToggle,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (snap) AccentAmber else DarkTab,
                            contentColor = if (snap) Color.Black else TextMain,
                        ),
                    ) { Text(if (snap) "Snap: On" else "Snap: Off") }
                    val cut = state.sampleCutToGrid
                    Button(
                        onClick = actions.onSampleCutToggle,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (cut) AccentAmber else DarkTab,
                            contentColor = if (cut) Color.Black else TextMain,
                        ),
                    ) { Text(if (cut) "Cut: On" else "Cut: Off") }
                    val rev = state.sampleReverse
                    Button(
                        onClick = actions.onSampleReverseToggle,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (rev) AccentAmber else DarkTab,
                            contentColor = if (rev) Color.Black else TextMain,
                        ),
                    ) { Text(if (rev) "Reverse: On" else "Reverse") }
                }
                if (state.selectedPadSampleName != null) {
                    Text(
                        "Original BPM: ${state.sampleOriginalBpm?.toInt() ?: 0} — set this to the loop's real BPM and Snap stretches it to the project BPM. If it is left at the project BPM, Snap has nothing to stretch.",
                        color = TextMain,
                    )
                    Slider(
                        value = state.sampleOriginalBpm ?: state.bpm,
                        onValueChange = { actions.onSampleOriginalBpm(it) },
                        valueRange = 60f..180f,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
                    )
                    Text("Original key: ${pitchName(state.sampleOriginalKey)} — melodic samples transpose from here to the project key.", color = TextMain)
                    Slider(
                        value = state.sampleOriginalKey.toFloat(),
                        onValueChange = { actions.onSampleOriginalKey(it.toInt()) },
                        valueRange = 0f..11f, steps = 10,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
                    )
                    Text("Loop start: ${if (state.sampleQuantizeBeats == 0f) "Immediate" else if (state.sampleQuantizeBeats == 1f) "Next beat" else "Next bar"} — a loop tapped mid-bar waits for this boundary, so you never have to time the tap.", color = TextMain)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((label, q) in listOf("Off" to 0f, "Beat" to 1f, "Bar" to 4f)) {
                            val sel = state.sampleQuantizeBeats == q
                            Button(
                                onClick = { actions.onSampleQuantize(q) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (sel) AccentAmber else DarkTab,
                                    contentColor = if (sel) Color.Black else TextMain,
                                ),
                            ) { Text(label) }
                        }
                    }
                }
                Text(
                    "Snap = tempo-stretch to project BPM, pitch kept. " +
                        "Cut = start/end snap to the 16th grid at project BPM. " +
                        "Project key (in Mixer) transposes melodic samples; drums do not follow it.",
                    style = MaterialTheme.typography.bodySmall, color = TextDim,
                )
            }
            "FX" -> {
                Text("FX — Delay for now; beat-repeat and XY pad come next.", style = MaterialTheme.typography.bodySmall, color = TextDim)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Delay FX")
                    Switch(checked = state.delayOn, onCheckedChange = actions.onDelayChange)
                }
            }
            "SAMPLES" -> {
                Text(
                    "Samples / Collection — your own files only. Import a " +
                        "WAV, OGG, MP3 or AAC you own; it is copied into " +
                        "app-private storage and saved in PlayRX's own manifest. " +
                        "No copied packs, ever.",
                    style = MaterialTheme.typography.bodySmall, color = TextDim,
                )
                Button(
                    onClick = actions.onImportSample,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = Color.Black),
                ) {
                    Text("Import sample…")
                }
                if (state.sampleImportError != null) {
                    Text(state.sampleImportError, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF6B6B))
                }
                if (state.samples.isEmpty()) {
                    Text("No samples yet.", style = MaterialTheme.typography.bodySmall, color = TextDim)
                } else {
                    for (name in state.samples) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(name, modifier = Modifier.weight(1f), color = TextMain, maxLines = 1)
                            Button(
                                onClick = { actions.onAssignSample(name) },
                                contentPadding = PaddingValues(4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
                            ) {
                                Text("Assign")
                            }
                        }
                    }
                }
            }
            else -> {
                Text("Mixer — VOLUMES", style = MaterialTheme.typography.bodySmall, color = TextDim)
                TempoControls(state, actions)
                Text("Project key: ${pitchName(state.projectKeyMidi)} — melodic samples follow it, drums do not.", color = TextMain)
                Slider(
                    value = state.projectKeyMidi.toFloat(),
                    onValueChange = { actions.onProjectKeyChange(it.toInt()) },
                    valueRange = 0f..11f, steps = 10,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
                )
                ColumnMixer(state, actions)
            }
        }
    }
}

@Composable
private fun PadEditPanel(state: PlayRxState, actions: PlayRxActions) {
    val pad = state.selectedPad
    val type = if (SampleBank.isLoopPad(pad)) "Loop" else "One-shot"
    val col = columnColor(SampleBank.columnOf(pad))
    Column {
        Text(
            "Pad: ${SampleBank.PAD_NAMES[pad]} ($type) — tap Edit pattern, then a pad, to select without playing",
            style = MaterialTheme.typography.bodySmall, color = TextDim,
        )
        Text("Pad volume: ${(state.padVolumes[pad] * 100).toInt()}%", color = TextMain)
        Slider(
            value = state.padVolumes[pad],
            onValueChange = { actions.onPadVolume(pad, it) },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(thumbColor = col, activeTrackColor = col, inactiveTrackColor = col.copy(alpha = 0.25f)),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val muted = state.padMuted[pad]
            Button(
                onClick = { actions.onPadMuteToggle(pad) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (muted) Color.White else DarkTab,
                    contentColor = if (muted) Color.Black else TextMain,
                ),
            ) {
                Text(if (muted) "Muted" else "Mute")
            }
            val solo = state.padSolo[pad]
            Button(
                onClick = { actions.onPadSoloToggle(pad) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (solo) AccentAmber else DarkTab,
                    contentColor = if (solo) Color.Black else TextMain,
                ),
            ) {
                Text(if (solo) "Soloed" else "Solo")
            }
        }
    }
}

@Composable
private fun SequencerRow(state: PlayRxState, actions: PlayRxActions) {
    Text(
        "Sequencer — ${SampleBank.PAD_NAMES[state.selectedPad]} (tap Edit pattern, pick a drum pad, then toggle steps)",
        style = MaterialTheme.typography.bodySmall, color = TextDim,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        for (step in 0 until 16) {
            val on = state.steps[state.selectedPad][step]
            val isCurrent = state.currentStep == step && state.isPlaying
            // Same pad read as the grid: off = pale/dim, on = filled,
            // current + on = filled + dark outline.
            Button(
                onClick = { actions.onStepToggle(state.selectedPad, step) },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(0.dp),
                border = when {
                    isCurrent && on -> BorderStroke(2.dp, Color.Black)
                    isCurrent -> BorderStroke(2.dp, Color.White)
                    on -> BorderStroke(1.dp, Color.Black.copy(alpha = 0.35f))
                    else -> BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        on -> PlayingTeal
                        else -> DarkTab
                    },
                    contentColor = if (on) Color.Black else TextDim,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text((step + 1).toString(), fontSize = 9.sp, maxLines = 1, softWrap = false)
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
        style = MaterialTheme.typography.bodySmall, color = TextDim,
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
        Text("PlayRX Live", style = MaterialTheme.typography.headlineMedium, color = TextMain)
        Text(state.midiStatus, style = MaterialTheme.typography.bodySmall, color = TextDim)
        Text("Chord: ${state.chordText}", style = MaterialTheme.typography.titleMedium, color = TextMain)
        Row(modifier = Modifier.padding(vertical = 8.dp)) {
            TransportButtons(state, actions)
        }
        TopTabs(state, actions)
        if (state.selectedTab == "LOOP") {
            PadGridRows(state, actions, columns = 4, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        } else if (state.selectedTab == "DRUM") {
            SequencerRow(state, actions)
        } else {
            PlaceholderTab(state)
        }
        // Switchable bottom panel, NOT stacked inside LOOP: Edit and
        // Mixer are their own panels (Tutorial Tour structure).
        PanelSwitch(state, actions)
        BottomPanel(state, actions, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        Text(
            "48 pads, 8 instrument columns x 6 variations. All sounds are synthesized " +
                "in the app — no sample packs needed. Pad and FX columns are loop pads " +
                "that repeat every bar. MIDI chord readout works when a keyboard " +
                "is connected; chord-following of loops comes with MIDI testing later.",
            style = MaterialTheme.typography.bodySmall, color = TextDim,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun LandscapeContent(state: PlayRxState, actions: PlayRxActions) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("PlayRX Live", style = MaterialTheme.typography.titleLarge, color = TextMain)
                Text(state.midiStatus, style = MaterialTheme.typography.bodySmall, color = TextDim)
                Text("Chord: ${state.chordText}", style = MaterialTheme.typography.titleSmall, color = TextMain)
            }
            TransportButtons(state, actions)
        }
        TopTabs(state, actions)
        if (state.selectedTab == "LOOP") {
            // Grid left, ONE switchable panel right. The old layout
            // stacked BPM + Edit + Mixer all at once in the side
            // column; now only the selected panel shows, switched
            // by EDIT / MIXER / FX / SAMPLES above it.
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 12.dp)) {
                    val rows = 6
                    val spacing = 6.dp
                    val padHeight = (maxHeight - spacing * (rows - 1)) / rows
                    PadGrid(
                        state, actions, columns = 8, padHeight = padHeight,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(
                    modifier = Modifier.width(260.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                ) {
                    PanelSwitch(state, actions)
                    BottomPanel(state, actions, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
            }
            Text(
                "8 columns x 6 variations = 48 pads. Pad + FX columns loop.",
                style = MaterialTheme.typography.bodySmall, color = TextDim,
            )
        } else if (state.selectedTab == "DRUM") {
            SequencerRow(state, actions)
            PanelSwitch(state, actions)
            BottomPanel(
                state, actions,
                modifier = Modifier.fillMaxWidth().height(170.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            )
        } else {
            PlaceholderTab(state)
            PanelSwitch(state, actions)
            BottomPanel(
                state, actions,
                modifier = Modifier.fillMaxWidth().height(170.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            )
        }
    }
}
