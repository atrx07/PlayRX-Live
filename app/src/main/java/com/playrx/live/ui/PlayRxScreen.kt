package com.playrx.live.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playrx.live.audio.BeatRepeat
import com.playrx.live.audio.DelayDivision
import com.playrx.live.audio.FxMath
import com.playrx.live.audio.SampleBank
import com.playrx.live.audio.ScaleKeyboard
import kotlin.math.ln

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
            if (state.showProjectSettings) {
                ProjectSettingsOverlay(state, actions)
            }
            if (state.showProjects) {
                ProjectsOverlay(state, actions)
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
private fun PadButton(state: PlayRxState, actions: PlayRxActions, pad: Int, modifier: Modifier = Modifier, onPadClick: ((Int) -> Unit)? = null) {
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
    // GATE pads (2026-11-08): hold to play. A press starts the
    // voice, the release stops it — a tap never latches. The
    // hold does not record into the sequence (a held note has no
    // step duration yet); sequenced GATE hits fire once via the
    // normal tap path.
    if (state.gatePads.contains(pad)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(container)
                .border(border, RoundedCornerShape(12.dp))
                .pointerInput(pad) {
                    detectTapGestures(
                        onPress = {
                            actions.onPadPress(pad)
                            tryAwaitRelease()
                            actions.onPadRelease(pad)
                        },
                    )
                },
        ) {
            Text(SampleBank.PAD_NAMES[pad], fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, color = content)
        }
        return
    }
    Button(
        onClick = { (onPadClick ?: actions.onPadClick)(pad) },
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
private fun PadGridRows(state: PlayRxState, actions: PlayRxActions, columns: Int, modifier: Modifier = Modifier, onPadClick: ((Int) -> Unit)? = null) {
    val rows = (SampleBank.PAD_COUNT + columns - 1) / columns
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        for (row in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until columns) {
                    val pad = row * columns + col
                    if (pad < SampleBank.PAD_COUNT) {
                        PadButton(state, actions, pad, Modifier.weight(1f).aspectRatio(1f), onPadClick)
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
    onPadClick: ((Int) -> Unit)? = null,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        items(SampleBank.PAD_COUNT) { pad ->
            PadButton(state, actions, pad, Modifier.height(padHeight), onPadClick)
        }
    }
}


@Composable
private fun BeatCounter(state: PlayRxState, modifier: Modifier = Modifier) {
    // Circular beat counter (2026-11-02): a ring that fills through
    // the current bar, with the beat number in the centre and the
    // bar number below it. The baseline starts at Bar 1 Beat 1 when
    // Play is tapped or the first loop starts, and advances from
    // there while the transport runs; Stop resets it to Bar 1.
    Box(modifier = modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            drawArc(
                color = DarkTab,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke),
            )
            drawArc(
                color = AccentAmber,
                startAngle = -90f,
                sweepAngle = (state.barProgress.coerceIn(0f, 1f) * 360f).coerceAtLeast(2f),
                useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                state.transportBeat.toString(),
                color = if (state.isPlaying) TextMain else TextDim,
                fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            )
            Text("BAR ${state.transportBar}", color = TextDim, fontSize = 8.sp, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun ProjectSettingsOverlay(state: PlayRxState, actions: PlayRxActions) {
    // Project settings menu (2026-11-02): everything project-wide
    // lives here, not scattered across MIXER/FX. MIXER is now only
    // the per-column strips; tempo, key, master volume and the
    // defaults for newly assigned loops are here.
    Surface(color = DarkBg, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Project settings", style = MaterialTheme.typography.titleLarge, color = TextMain, modifier = Modifier.weight(1f))
                Button(
                    onClick = actions.onProjectSettingsToggle,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = Color.Black),
                ) { Text("Done") }
            }
            Text("Tempo", color = TextMain)
            Text("BPM: ${state.bpm.toInt()}", color = TextDim)
            Slider(
                value = state.bpm, onValueChange = actions.onBpmChange, valueRange = 60f..180f,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
            )
            Text("Project key: ${pitchName(state.projectKeyMidi)} — melodic samples follow it, drums do not.", color = TextMain)
            Slider(
                value = state.projectKeyMidi.toFloat(),
                onValueChange = { actions.onProjectKeyChange(it.toInt()) },
                valueRange = 0f..11f, steps = 10,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
            )
            Text("Master volume: ${(state.masterVolume * 100).toInt()}%", color = TextMain)
            Slider(
                value = state.masterVolume, onValueChange = actions.onVolumeChange, valueRange = 0f..1f,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
            )
            Text("Default loop start for newly assigned loops", color = TextMain)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, q) in listOf("Off" to 0f, "Beat" to 1f, "Bar" to 4f)) {
                    val sel = state.defaultQuantizeBeats == q
                    Button(
                        onClick = { actions.onDefaultQuantize(q) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (sel) AccentAmber else DarkTab,
                            contentColor = if (sel) Color.Black else TextMain,
                        ),
                    ) { Text(label) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Snap new samples to project tempo by default", color = TextMain, modifier = Modifier.weight(1f))
                Switch(checked = state.defaultSnapToTempo, onCheckedChange = { actions.onDefaultSnapToggle() })
            }
            Text(
                "Per-pad tempo/key/quantize for a sample already assigned are still in EDIT. " +
                    "These project settings are the defaults a new assignment starts from.",
                style = MaterialTheme.typography.bodySmall, color = TextDim,
            )
        }
    }
}

@Composable
private fun RecMenuButton(state: PlayRxState, actions: PlayRxActions) {
    // Top-right Rec dropdown (2026-11-08): Song (loops/sequences
    // to the timeline), Drums & Sounds (to a sequence), Master
    // Audio (the live mix to a WAV). Off stops the mode; leaving
    // Master Audio saves the WAV.
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(
            onClick = { expanded = true },
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.recordMode == "OFF") DarkTab else Color(0xFFD32F2F),
                contentColor = if (state.recordMode == "OFF") TextMain else Color.White,
            ),
        ) {
            Text(if (state.recordMode == "OFF") "● Rec" else "● ${state.recordMode}", fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((label, mode) in listOf(
                "Off" to "OFF",
                "Song — loops to the timeline" to "SONG",
                "Drums & Sounds — to a sequence" to "DRUMS",
                "Master Audio — mix to WAV" to "MASTER",
            )) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        actions.onRecordMode(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun ProjectsOverlay(state: PlayRxState, actions: PlayRxActions) {
    // Projects + Live Set (2026-11-08): the home-screen set of
    // projects, newest first, plus the Master Audio recordings.
    Surface(color = DarkBg, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Projects — Live Set", style = MaterialTheme.typography.titleLarge, color = TextMain, modifier = Modifier.weight(1f))
                Button(
                    onClick = actions.onProjectsToggle,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = Color.Black),
                ) { Text("Done") }
            }
            Text(
                "Save the whole project: tempo, key, sequences, song timeline, sample assignments. " +
                    "Sample files stay in the library and are looked up by name on load.",
                style = MaterialTheme.typography.bodySmall, color = TextDim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = state.newProjectName,
                    onValueChange = actions.onNewProjectName,
                    label = { Text("Project name") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = DarkTab,
                        unfocusedContainerColor = DarkTab,
                        focusedTextColor = TextMain,
                        unfocusedTextColor = TextMain,
                        focusedLabelColor = TextDim,
                        unfocusedLabelColor = TextDim,
                        cursorColor = AccentAmber,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = actions.onProjectSave,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = Color.Black),
                ) { Text("Save") }
            }
            if (state.projectMessage != null) {
                Text(state.projectMessage, style = MaterialTheme.typography.bodySmall, color = AccentAmber)
            }
            if (state.projects.isEmpty()) {
                Text("No saved projects yet.", style = MaterialTheme.typography.bodySmall, color = TextDim)
            } else {
                for ((name, _) in state.projects) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(name, color = TextMain, modifier = Modifier.weight(1f), maxLines = 1)
                        Button(
                            onClick = { actions.onProjectLoad(name) },
                            contentPadding = PaddingValues(4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = Color.Black),
                        ) { Text("Load") }
                        Button(
                            onClick = { actions.onProjectDelete(name) },
                            contentPadding = PaddingValues(4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = Color(0xFFFF6B6B)),
                        ) { Text("Delete") }
                    }
                }
            }
            Text("Recordings — Master Audio WAVs", style = MaterialTheme.typography.titleMedium, color = TextMain)
            if (state.masterCaptureFull) {
                Text(
                    "Master capture hit its 5-minute cap and stopped itself.",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF6B6B),
                )
            }
            if (state.recordings.isEmpty()) {
                Text(
                    "No recordings yet. Rec → Master Audio, play, then switch Rec off to save a WAV.",
                    style = MaterialTheme.typography.bodySmall, color = TextDim,
                )
            } else {
                for (file in state.recordings) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(file, color = TextMain, modifier = Modifier.weight(1f), maxLines = 1)
                        Button(
                            onClick = { actions.onRecordingShare(file) },
                            contentPadding = PaddingValues(4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
                        ) { Text("Share") }
                    }
                }
            }
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
private fun FxPanel(state: PlayRxState, actions: PlayRxActions) {
    // FX panel (2026-11-08): tempo-synced delay, beat repeat, XY
    // pad (X = master filter, Y = delay mix), per-column sends.
    Text("FX — Delay", style = MaterialTheme.typography.titleMedium, color = TextMain)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Delay", color = TextMain, modifier = Modifier.weight(1f))
        Switch(checked = state.delayOn, onCheckedChange = actions.onDelayChange)
    }
    if (state.delayOn) {
        Text("Division", color = TextMain)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (div in DelayDivision.values()) {
                val sel = state.delayDivisionBeats == div.beats
                Button(
                    onClick = { actions.onDelayDivision(div.beats) },
                    contentPadding = PaddingValues(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sel) AccentAmber else DarkTab,
                        contentColor = if (sel) Color.Black else TextDim,
                    ),
                ) { Text(div.label, fontSize = 10.sp, maxLines = 1, softWrap = false) }
            }
        }
        Text("Mix: ${(state.delayMix * 100).toInt()}%", color = TextMain)
        Slider(
            value = state.delayMix,
            onValueChange = actions.onDelayMix,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
        )
        Text("Feedback: ${(state.delayFeedback * 100).toInt()}%", color = TextMain)
        Slider(
            value = state.delayFeedback,
            onValueChange = actions.onDelayFeedback,
            valueRange = 0f..0.95f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
        )
    }
    Text("Beat repeat", style = MaterialTheme.typography.titleMedium, color = TextMain)
    Text(
        "Freezes the slice you just played and stutters it until Off.",
        style = MaterialTheme.typography.bodySmall, color = TextDim,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (rep in BeatRepeat.values()) {
            val sel = state.beatRepeat == rep.label
            Button(
                onClick = { actions.onBeatRepeat(rep.label) },
                contentPadding = PaddingValues(4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (sel) AccentAmber else DarkTab,
                    contentColor = if (sel) Color.Black else TextDim,
                ),
            ) { Text(rep.label, fontSize = 10.sp, maxLines = 1, softWrap = false) }
        }
    }
    if (state.beatRepeat != "Off") {
        Text("Repeat mix: ${(state.beatRepeatMix * 100).toInt()}%", color = TextMain)
        Slider(
            value = state.beatRepeatMix,
            onValueChange = actions.onBeatRepeatMix,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
        )
    }
    Text("XY — X: master filter, Y: delay mix", style = MaterialTheme.typography.titleMedium, color = TextMain)
    XyPad(state, actions)
    Text(
        "Filter: ${formatCutoff(state.masterFilterCutoffHz)} · Delay mix: ${(state.delayMix * 100).toInt()}%",
        style = MaterialTheme.typography.bodySmall, color = TextDim,
    )
    Text("Routing — delay send per column", style = MaterialTheme.typography.titleMedium, color = TextMain)
    for (col in 0 until SampleBank.COLUMNS) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(SampleBank.COLUMN_NAMES[col], modifier = Modifier.width(52.dp), color = TextMain)
            Slider(
                value = state.columnSends[col],
                onValueChange = { actions.onColumnSend(col, it) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = columnColor(col),
                    activeTrackColor = columnColor(col),
                    inactiveTrackColor = columnColor(col).copy(alpha = 0.25f),
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun formatCutoff(hz: Float): String = when {
    hz <= 0f || hz >= 20000f -> "Off"
    hz >= 1000f -> "%.1f kHz".format(hz / 1000f)
    else -> "${hz.toInt()} Hz"
}

/** Slider 0..1 <-> cutoff Hz (log 200..18000); 0 = bypass. */
private fun filterSliderToHz(t: Float): Float =
    if (t <= 0.02f) 0f else (200f * kotlin.math.exp(t * ln(90f)))

private fun filterHzToSlider(hz: Float): Float =
    if (hz <= 0f) 0f else (ln(hz / 200f) / ln(90f)).coerceIn(0f, 1f)

/** EDIT filter slider: same log curve, but the top is 20000 Hz =
 * fully open (PadSample's default), shown as Off. */
private fun editFilterSliderToHz(t: Float): Float =
    if (t >= 0.98f) 20000f else (200f * kotlin.math.exp(t * ln(90f)))

private fun editFilterHzToSlider(hz: Float): Float =
    if (hz >= 20000f) 1f else (ln(hz / 200f) / ln(90f)).coerceIn(0f, 1f)

@Composable
private fun XyPad(state: PlayRxState, actions: PlayRxActions) {
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    val x = FxMath.cutoffToXy(state.masterFilterCutoffHz)
    val y = state.delayMix
    Canvas(
        modifier = Modifier.size(140.dp)
            .onSizeChanged { padSize = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        if (padSize.width > 0 && padSize.height > 0) {
                            actions.onXy(
                                (offset.x / padSize.width).coerceIn(0f, 1f),
                                1f - (offset.y / padSize.height).coerceIn(0f, 1f),
                            )
                        }
                    },
                    onDrag = { change, _ ->
                        if (padSize.width > 0 && padSize.height > 0) {
                            actions.onXy(
                                (change.position.x / padSize.width).coerceIn(0f, 1f),
                                1f - (change.position.y / padSize.height).coerceIn(0f, 1f),
                            )
                        }
                    },
                )
            },
    ) {
        drawRect(color = DarkTab, size = size)
        val px = x * size.width
        val py = (1f - y) * size.height
        drawLine(AccentAmber, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2f)
        drawLine(AccentAmber, Offset(0f, py), Offset(size.width, py), strokeWidth = 2f)
        drawCircle(color = AccentAmber, radius = 14f, center = Offset(px, py))
        drawCircle(color = Color.Black, radius = 6f, center = Offset(px, py))
    }
}

@Composable
private fun WaveformView(state: PlayRxState) {
    // EDIT waveform (2026-11-08): peak bars from the assigned
    // sample's raw frames, with white trim markers. Built-in synth
    // pads have no waveform to show.
    val peaks = state.samplePeaks
    Canvas(modifier = Modifier.fillMaxWidth().height(72.dp)) {
        drawRect(color = DarkTab, size = size)
        if (peaks.isNotEmpty()) {
            val barW = size.width / peaks.size
            for (i in peaks.indices) {
                val h = (peaks[i] * size.height).coerceAtLeast(2f)
                val top = (size.height - h) / 2f
                drawRect(
                    color = AccentAmber,
                    topLeft = Offset(i * barW + 1f, top),
                    size = Size((barW - 2f).coerceAtLeast(1f), h),
                )
            }
            val sx = state.sampleTrimStart * size.width
            val ex = state.sampleTrimEnd * size.width
            drawLine(Color.White, Offset(sx, 0f), Offset(sx, size.height), strokeWidth = 2f)
            drawLine(Color.White, Offset(ex, 0f), Offset(ex, size.height), strokeWidth = 2f)
        }
    }
    if (peaks.isEmpty()) {
        Text(
            "No waveform — built-in synth, nothing imported on this pad.",
            style = MaterialTheme.typography.bodySmall, color = TextDim,
        )
    }
}

@Composable
private fun ColumnFilterPanel(state: PlayRxState, actions: PlayRxActions) {
    Text(
        "One-pole low-pass per column. Slider left = bypass; right = 18 kHz.",
        style = MaterialTheme.typography.bodySmall, color = TextDim,
    )
    for (col in 0 until SampleBank.COLUMNS) {
        val hz = state.columnFilters[col]
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(SampleBank.COLUMN_NAMES[col], modifier = Modifier.width(52.dp), color = TextMain)
            Slider(
                value = filterHzToSlider(hz),
                onValueChange = { actions.onColumnFilter(col, filterSliderToHz(it)) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = columnColor(col),
                    activeTrackColor = columnColor(col),
                    inactiveTrackColor = columnColor(col).copy(alpha = 0.25f),
                ),
                modifier = Modifier.weight(1f),
            )
            Text(formatCutoff(hz), modifier = Modifier.width(64.dp), color = TextDim, fontSize = 11.sp)
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
                        value = (state.sampleOriginalBpm ?: state.bpm).coerceIn(30f, 260f),
                        onValueChange = { actions.onSampleOriginalBpm(it) },
                        valueRange = 30f..260f,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = actions.onSampleDetectTempo,
                            colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
                        ) { Text("Detect tempo") }
                        // One-tap octave correction: tempo detection
                        // is inherently ambiguous for half-time /
                        // double-time feels, so a wrong octave must
                        // not mean dragging the slider across the range.
                        Button(
                            onClick = { actions.onSampleOriginalBpm((state.sampleOriginalBpm ?: state.bpm) * 2f) },
                            colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
                        ) { Text("×2") }
                        Button(
                            onClick = { actions.onSampleOriginalBpm((state.sampleOriginalBpm ?: state.bpm) / 2f) },
                            colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
                        ) { Text("÷2") }
                        if (state.sampleTempoDetectResult != null) {
                            Text(state.sampleTempoDetectResult, style = MaterialTheme.typography.bodySmall, color = TextDim, modifier = Modifier.weight(1f))
                        }
                    }
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
                    // Waveform + Gain / Pan / Filter / Repeat / Trim
                    // (2026-11-08): the EDIT completeness slice. All
                    // of these re-render the pad's sample through the
                    // existing update path, so a change lands live on
                    // a playing loop via the voice swap.
                    Text("Waveform", color = TextMain)
                    WaveformView(state)
                    Text("Gain: ${(state.sampleGain * 100).toInt()}%", color = TextMain)
                    Slider(
                        value = state.sampleGain,
                        onValueChange = actions.onSampleGain,
                        valueRange = 0f..2f,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
                    )
                    Text("Pan: ${if (state.samplePan == 0f) "Centre" else if (state.samplePan < 0f) "Left ${(-state.samplePan * 100).toInt()}%" else "Right ${(state.samplePan * 100).toInt()}%"}", color = TextMain)
                    Slider(
                        value = state.samplePan,
                        onValueChange = actions.onSamplePan,
                        valueRange = -1f..1f,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
                    )
                    Text("Filter: ${formatCutoff(state.sampleFilterHz)}", color = TextMain)
                    Slider(
                        value = editFilterHzToSlider(state.sampleFilterHz),
                        onValueChange = { actions.onSampleFilter(editFilterSliderToHz(it)) },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
                    )
                    if (state.samplePlayMode != "LOOP") {
                        Text("Repeat: ${state.sampleRepeat}x", color = TextMain)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { actions.onSampleRepeat((state.sampleRepeat - 1).coerceAtLeast(1)) },
                                colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
                            ) { Text("−") }
                            Text("${state.sampleRepeat}x", color = TextMain, modifier = Modifier.weight(1f))
                            Button(
                                onClick = { actions.onSampleRepeat((state.sampleRepeat + 1).coerceAtMost(8)) },
                                colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
                            ) { Text("+") }
                        }
                    }
                    Text("Trim: ${(state.sampleTrimStart * 100).toInt()}% – ${(state.sampleTrimEnd * 100).toInt()}%", color = TextMain)
                    Slider(
                        value = state.sampleTrimStart,
                        onValueChange = { actions.onSampleTrimStart(it.coerceIn(0f, state.sampleTrimEnd - 0.01f)) },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
                    )
                    Slider(
                        value = state.sampleTrimEnd,
                        onValueChange = { actions.onSampleTrimEnd(it.coerceIn(state.sampleTrimStart + 0.01f, 1f)) },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = actions.onSampleUnload,
                            colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = Color(0xFFFF6B6B)),
                        ) { Text("Unload") }
                        Button(
                            onClick = actions.onSampleReplace,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = Color.Black),
                        ) { Text("Replace…") }
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
                FxPanel(state, actions)
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
                // Search + category filter (2026-11-08): the
                // collection grows past a flat list quickly.
                TextField(
                    value = state.samplesSearch,
                    onValueChange = actions.onSamplesSearch,
                    label = { Text("Search samples") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = DarkTab,
                        unfocusedContainerColor = DarkTab,
                        focusedTextColor = TextMain,
                        unfocusedTextColor = TextMain,
                        focusedLabelColor = TextDim,
                        unfocusedLabelColor = TextDim,
                        cursorColor = AccentAmber,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    for (cat in listOf("All", "DRUM", "BASS", "MELODY", "PAD", "VOX", "FX")) {
                        val sel = state.samplesCategory == cat
                        Button(
                            onClick = { actions.onSamplesCategory(cat) },
                            contentPadding = PaddingValues(4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (sel) AccentAmber else DarkTab,
                                contentColor = if (sel) Color.Black else TextDim,
                            ),
                        ) { Text(cat, fontSize = 10.sp, maxLines = 1, softWrap = false) }
                    }
                }
                val categoryOf = { name: String ->
                    state.sampleCategories.firstOrNull { it.first == name }?.second ?: "OTHER"
                }
                val query = state.samplesSearch.trim()
                val filtered = state.samples.filter { name ->
                    (query.isEmpty() || name.contains(query, ignoreCase = true)) &&
                        (state.samplesCategory == "All" || categoryOf(name) == state.samplesCategory)
                }
                // Suggested for the selected pad: same-category
                // samples first — the closest thing to Remixlive's
                // "replace this pad" recommendation without an AI
                // model. Only shown when the pad has no sample yet
                // or the list is unfiltered.
                val padCategory = when (SampleBank.columnOf(state.selectedPad)) {
                    0, 1, 2 -> "DRUM"
                    3 -> "BASS"
                    4 -> "MELODY"
                    5 -> "PAD"
                    6 -> "VOX"
                    else -> "FX"
                }
                val suggested = filtered.filter { categoryOf(it) == padCategory }.take(3)
                if (suggested.isNotEmpty() && query.isEmpty()) {
                    Text(
                        "Suggested for ${SampleBank.PAD_NAMES[state.selectedPad]} ($padCategory):",
                        style = MaterialTheme.typography.bodySmall, color = TextDim,
                    )
                    for (name in suggested) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(name, modifier = Modifier.weight(1f), color = TextMain, maxLines = 1)
                            Button(
                                onClick = { actions.onAssignSample(name) },
                                contentPadding = PaddingValues(4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = Color.Black),
                            ) {
                                Text("Assign")
                            }
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    Text(
                        if (state.samples.isEmpty()) "No samples yet." else "Nothing matches.",
                        style = MaterialTheme.typography.bodySmall, color = TextDim,
                    )
                } else {
                    for (name in filtered) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(name, modifier = Modifier.weight(1f), color = TextMain, maxLines = 1)
                            Button(
                                onClick = { actions.onAssignSample(name) },
                                contentPadding = PaddingValues(4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
                            ) {
                                Text("Assign")
                            }
                            Button(
                                onClick = { actions.onDeleteSample(name) },
                                contentPadding = PaddingValues(4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = Color(0xFFFF6B6B)),
                            ) {
                                Text("Delete")
                            }
                        }
                    }
                    Text(
                        "Delete removes a sample from PlayRX's library only — your original file on the device is untouched. Any pad using it goes back to the built-in synth.",
                        style = MaterialTheme.typography.bodySmall, color = TextDim,
                    )
                }
            }
            else -> {
                // MIXER: inner tabs Volumes / Eq Filter (2026-11-02
                // Volumes; 2026-11-08 Eq Filter = per-column one-pole
                // low-pass). Tempo, project key and master volume live
                // in the Project settings menu (top bar), not here.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (tab in listOf("Volumes", "Eq Filter")) {
                        val sel = state.mixerTab == tab
                        Button(
                            onClick = { actions.onMixerTab(tab) },
                            contentPadding = PaddingValues(4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (sel) AccentAmber else DarkTab,
                                contentColor = if (sel) Color.Black else TextDim,
                            ),
                        ) { Text(tab, fontSize = 11.sp, maxLines = 1, softWrap = false) }
                    }
                }
                if (state.mixerTab == "Eq Filter") {
                    Text("Mixer — EQ FILTER (one-pole low-pass per column)", style = MaterialTheme.typography.bodySmall, color = TextDim)
                    ColumnFilterPanel(state, actions)
                } else {
                    Text("Mixer — VOLUMES", style = MaterialTheme.typography.bodySmall, color = TextDim)
                    ColumnMixer(state, actions)
                }
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
private fun ScaleKeyboardView(state: PlayRxState, actions: PlayRxActions, modifier: Modifier = Modifier) {
    // Scale Keyboard (2026-11-08): the expandable keyboard at the
    // bottom of Drum View. Root follows the project key, so the keys
    // are always in key; scale flavour and octave are the player's
    // choice. Notes play live over the loops, and with ● Rec on in
    // SEQ they quantize into the sequence with their pitch.
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Scale keyboard — root ${pitchName(state.projectKeyMidi)} (project key)",
            color = TextMain,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            for (scale in ScaleKeyboard.SCALES) {
                val sel = state.scaleName == scale
                Button(
                    onClick = { actions.onScaleSelect(scale) },
                    contentPadding = PaddingValues(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sel) AccentAmber else DarkTab,
                        contentColor = if (sel) Color.Black else TextMain,
                    ),
                ) { Text(scale, fontSize = 11.sp, maxLines = 1, softWrap = false) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { actions.onScaleOctave(-1) },
                colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
            ) { Text("Oct −") }
            Text(
                "Octave ${state.scaleOctave}", color = TextMain,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { actions.onScaleOctave(1) },
                colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
            ) { Text("Oct +") }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            for ((midi, name) in ScaleKeyboard.notes(state.projectKeyMidi, state.scaleName, state.scaleOctave)) {
                Button(
                    onClick = { actions.onScaleNote(midi) },
                    contentPadding = PaddingValues(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) { Text(name, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
            }
        }
        Text(
            "Play melodies live over the loops. With ● Rec on (SEQ tab), taps quantize into the sequence.",
            style = MaterialTheme.typography.bodySmall, color = TextDim,
        )
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
private fun SeqView(state: PlayRxState, actions: PlayRxActions) {
    // SEQ for real (2026-11-08): the sequence list + step editor.
    // List on top (tap to select, Delete removes), general controls
    // (Loop / One shot, Gain), pad chips for the sounds in the
    // sequence, the 16-step row for the selected pad, and the
    // per-step detail strip (Velocity / Offset / Duration / Roll /
    // Pitch) for the tapped step.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Sequences", style = MaterialTheme.typography.titleMedium, color = TextMain,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = actions.onRecordingToggle,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isRecording) Color(0xFFD32F2F) else DarkTab,
                    contentColor = if (state.isRecording) Color.White else TextMain,
                ),
            ) { Text(if (state.isRecording) "● Recording" else "● Rec") }
            Button(
                onClick = actions.onSequenceNew,
                colors = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = Color.Black),
            ) { Text("+ New") }
        }
        Text(
            "With ● Rec on, pad taps in LOOP / DRUM quantize into the selected sequence. " +
                "Tap a step to toggle it and edit its detail below.",
            style = MaterialTheme.typography.bodySmall, color = TextDim,
        )
        for ((id, name, count) in state.sequences) {
            val sel = id == state.selectedSequenceId
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { actions.onSequenceSelect(id) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sel) AccentAmber else DarkTab,
                        contentColor = if (sel) Color.Black else TextMain,
                    ),
                    modifier = Modifier.weight(1f),
                ) { Text("$name  •  $count steps", maxLines = 1) }
                Button(
                    onClick = { actions.onSequenceDelete(id) },
                    contentPadding = PaddingValues(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = Color(0xFFFF6B6B)),
                ) { Text("Delete") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = actions.onSequenceLoopToggle,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.selectedSequenceLoop) AccentAmber else DarkTab,
                    contentColor = if (state.selectedSequenceLoop) Color.Black else TextMain,
                ),
            ) { Text(if (state.selectedSequenceLoop) "Loop" else "One shot") }
            Text(
                "Gain: ${(state.selectedSequenceGain * 100).toInt()}%",
                color = TextMain, modifier = Modifier.weight(1f),
            )
            Slider(
                value = state.selectedSequenceGain,
                onValueChange = actions.onSequenceGain,
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "Sound: ${SampleBank.PAD_NAMES[state.selectedPad]} — tap Edit pattern, pick a pad, then toggle steps",
            style = MaterialTheme.typography.bodySmall, color = TextDim,
        )
        val padsWithSteps = (listOf(state.selectedPad) +
            state.steps.indices.filter { pad -> state.steps[pad].any { it } })
            .distinct().sorted()
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            for (pad in padsWithSteps) {
                val sel = pad == state.selectedPad
                Button(
                    onClick = { actions.onPadSelect(pad) },
                    contentPadding = PaddingValues(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sel) Color.White else DarkTab,
                        contentColor = if (sel) Color.Black else TextDim,
                    ),
                ) { Text(SampleBank.PAD_NAMES[pad], fontSize = 10.sp, maxLines = 1, softWrap = false) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
            for (step in 0 until 16) {
                val on = state.steps[state.selectedPad][step]
                val isCurrent = state.currentStep == step && state.isPlaying
                val isDetail = state.selectedStep == step
                Button(
                    onClick = {
                        actions.onStepToggle(state.selectedPad, step)
                        actions.onStepSelect(step)
                    },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp),
                    border = when {
                        isDetail -> BorderStroke(2.dp, AccentAmber)
                        isCurrent && on -> BorderStroke(2.dp, Color.Black)
                        isCurrent -> BorderStroke(2.dp, Color.White)
                        on -> BorderStroke(1.dp, Color.Black.copy(alpha = 0.35f))
                        else -> BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (on) PlayingTeal else DarkTab,
                        contentColor = if (on) Color.Black else TextDim,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text((step + 1).toString(), fontSize = 9.sp, maxLines = 1, softWrap = false)
                }
            }
        }
        if (state.selectedStep >= 0) {
            Text(
                "Step ${state.selectedStep + 1} — ${SampleBank.PAD_NAMES[state.selectedPad]}",
                color = TextMain,
            )
            Text("Velocity: ${(state.stepVelocity * 100).toInt()}%", color = TextMain)
            Slider(
                value = state.stepVelocity, onValueChange = actions.onStepCellVelocity, valueRange = 0.05f..1f,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
            )
            Text("Offset: ${"%.2f".format(state.stepOffset)} beats (early ← → late)", color = TextMain)
            Slider(
                value = state.stepOffset, onValueChange = actions.onStepCellOffset, valueRange = -0.25f..0.25f,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
            )
            Text("Duration: ${"%.2f".format(state.stepDuration)} beats", color = TextMain)
            Slider(
                value = state.stepDuration, onValueChange = actions.onStepCellDuration, valueRange = 0.0625f..1f,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Roll:", color = TextMain)
                for (roll in listOf(1, 2, 4)) {
                    val sel = state.stepRoll == roll
                    Button(
                        onClick = { actions.onStepCellRoll(roll) },
                        contentPadding = PaddingValues(4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (sel) AccentAmber else DarkTab,
                            contentColor = if (sel) Color.Black else TextMain,
                        ),
                    ) { Text("x$roll") }
                }
            }
            Text("Pitch: ${state.stepPitch} semitones (synth pads; imported samples keep their own transpose)", color = TextMain)
            Slider(
                value = state.stepPitch.toFloat(), onValueChange = { actions.onStepCellPitch(it.toInt()) },
                valueRange = -12f..12f, steps = 23,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentAmber, inactiveTrackColor = DarkTab),
            )
        } else {
            Text(
                "Tap a step to edit its velocity, timing, duration, roll and pitch.",
                style = MaterialTheme.typography.bodySmall, color = TextDim,
            )
        }
    }
}

@Composable
private fun SongView(state: PlayRxState, actions: PlayRxActions) {
    // SONG for real (2026-11-08): a timeline, not a grid. Bars
    // across the top, the 8 instrument columns as tracks down the
    // left, clips placed on it. Tap an empty cell to place the
    // selected pad as a 1-bar clip; tap a clip to edit it (move,
    // grow/shrink, duplicate, split, delete). The amber outline is
    // the playhead bar while the transport runs in SONG.
    val cellWidth = when (state.songVisibleBars) {
        4 -> 72.dp
        8 -> 48.dp
        else -> 28.dp
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Song — timeline", style = MaterialTheme.typography.titleMedium, color = TextMain,
                modifier = Modifier.weight(1f),
            )
            for (zoom in listOf(4, 8, 16)) {
                val sel = state.songVisibleBars == zoom
                Button(
                    onClick = { actions.onSongZoom(zoom) },
                    contentPadding = PaddingValues(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sel) AccentAmber else DarkTab,
                        contentColor = if (sel) Color.Black else TextMain,
                    ),
                ) { Text("$zoom bars", fontSize = 10.sp, maxLines = 1, softWrap = false) }
            }
        }
        Text(
            "Tap an empty cell to place ${SampleBank.PAD_NAMES[state.selectedPad]} as a 1-bar clip. " +
                "Tap a clip to edit it. Clips play when the transport runs in SONG.",
            style = MaterialTheme.typography.bodySmall, color = TextDim,
        )
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(modifier = Modifier.width(52.dp))
                for (bar in 1..state.songLengthBars) {
                    Box(
                        modifier = Modifier.width(cellWidth),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$bar",
                            color = if (bar == state.songBar && state.isPlaying) AccentAmber else TextDim,
                            fontSize = 10.sp, fontWeight = if (bar == state.songBar && state.isPlaying) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
            for (track in 0 until SampleBank.COLUMNS) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        SampleBank.COLUMN_NAMES[track],
                        modifier = Modifier.width(52.dp),
                        color = columnColor(track), fontSize = 11.sp, maxLines = 1,
                    )
                    var bar = 1
                    while (bar <= state.songLengthBars) {
                        val clip = state.songClips.firstOrNull { it.track == track && it.startBar == bar }
                        if (clip != null) {
                            val sel = clip.id == state.selectedClipId
                            Button(
                                onClick = { actions.onSongClipSelect(clip.id) },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(2.dp),
                                border = if (sel) BorderStroke(2.dp, AccentAmber) else BorderStroke(1.dp, Color.Black.copy(alpha = 0.35f)),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = columnColor(track),
                                    contentColor = if (track == 2) Color.Black else Color.White,
                                ),
                                modifier = Modifier.width(cellWidth * clip.lengthBars).height(36.dp),
                            ) {
                                Text(SampleBank.PAD_NAMES[clip.pad], fontSize = 9.sp, maxLines = 1, softWrap = false)
                            }
                            bar += clip.lengthBars
                        } else {
                            val isPlayhead = bar == state.songBar && state.isPlaying
                            Button(
                                onClick = { actions.onSongClipPlace(track, bar) },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(0.dp),
                                border = if (isPlayhead) BorderStroke(2.dp, AccentAmber) else BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextDim),
                                modifier = Modifier.width(cellWidth).height(36.dp),
                            ) { Text("", fontSize = 9.sp) }
                            bar += 1
                        }
                    }
                }
            }
        }
        val clip = state.songClips.firstOrNull { it.id == state.selectedClipId }
        if (clip != null) {
            Text(
                "Clip: ${SampleBank.PAD_NAMES[clip.pad]} — ${SampleBank.COLUMN_NAMES[clip.track]}, " +
                    "bar ${clip.startBar}, ${clip.lengthBars} bar(s)",
                color = TextMain,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { actions.onSongClipMove(-1) },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
                ) { Text("← Move") }
                Button(
                    onClick = { actions.onSongClipMove(1) },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
                ) { Text("Move →") }
                Button(
                    onClick = { actions.onSongClipLength(-1) },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
                ) { Text("− Bar") }
                Button(
                    onClick = { actions.onSongClipLength(1) },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
                ) { Text("+ Bar") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = actions.onSongClipDuplicate,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
                ) { Text("Duplicate") }
                Button(
                    onClick = actions.onSongClipSplit,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
                ) { Text("Split") }
                Button(
                    onClick = actions.onSongClipDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = Color(0xFFFF6B6B)),
                ) { Text("Delete") }
            }
        } else {
            Text(
                "No clip selected.",
                style = MaterialTheme.typography.bodySmall, color = TextDim,
            )
        }
    }
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
        Row(modifier = Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BeatCounter(state)
            Button(
                onClick = actions.onProjectSettingsToggle,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
            ) { Text("Project") }
            RecMenuButton(state, actions)
            Button(
                onClick = actions.onProjectsToggle,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
            ) { Text("Live Set") }
            TransportButtons(state, actions)
        }
        TopTabs(state, actions)
        if (state.selectedTab == "LOOP") {
            PadGridRows(state, actions, columns = 4, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        } else if (state.selectedTab == "SEQ") {
            SeqView(state, actions)
        } else if (state.selectedTab == "DRUM") {
            Text(
                "Drum — one-shots over the loops. Loops keep playing when you switch here.",
                style = MaterialTheme.typography.bodySmall, color = TextDim,
            )
            PadGridRows(
                state, actions, columns = 4,
                onPadClick = actions.onDrumPadClick,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            ScaleKeyboardView(state, actions, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        } else {
            SongView(state, actions)
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
            BeatCounter(state)
            Button(
                onClick = actions.onProjectSettingsToggle,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
            ) { Text("Project") }
            RecMenuButton(state, actions)
            Button(
                onClick = actions.onProjectsToggle,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DarkTab, contentColor = TextMain),
            ) { Text("Live Set") }
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
            // Drum grid left (one-shots, even on loop columns),
            // Scale Keyboard below it, ONE switchable panel right —
            // the same right-panel layout as LOOP.
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 12.dp)) {
                    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val rows = 6
                        val spacing = 6.dp
                        val padHeight = (maxHeight - spacing * (rows - 1)) / rows
                        PadGrid(
                            state, actions, columns = 8, padHeight = padHeight,
                            modifier = Modifier.fillMaxSize(),
                            onPadClick = actions.onDrumPadClick,
                        )
                    }
                    ScaleKeyboardView(state, actions, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                Column(
                    modifier = Modifier.width(260.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                ) {
                    PanelSwitch(state, actions)
                    BottomPanel(state, actions, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
            }
            Text(
                "Drum — one-shots over the loops. Loops keep playing when you switch here.",
                style = MaterialTheme.typography.bodySmall, color = TextDim,
            )
        } else if (state.selectedTab == "SEQ") {
            // Same right-panel layout as LOOP/DRUM: content left,
            // ONE switchable panel right. The earlier layout stacked
            // the panel switch + a 170dp panel below the content,
            // which left the SEQ editor ~0dp tall in phone landscape
            // (verified in the dc195a5 screenshots: panel only, no
            // Sequences list visible).
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 12.dp),
                ) {
                    SeqView(state, actions)
                }
                Column(
                    modifier = Modifier.width(260.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                ) {
                    PanelSwitch(state, actions)
                    BottomPanel(state, actions, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
            }
        } else {
            // SONG: same right-panel layout as SEQ above.
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 12.dp),
                ) {
                    SongView(state, actions)
                }
                Column(
                    modifier = Modifier.width(260.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                ) {
                    PanelSwitch(state, actions)
                    BottomPanel(state, actions, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
            }
        }
    }
}
