package com.playrx.live

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.playrx.live.midi.MidiEngine

/**
 * Phase 0 UI: a 6x4 pad grid (24 pads, phone layout), transport controls,
 * and a live chord readout from the connected MIDI keyboard.
 * Pads are visual placeholders until the Oboe audio engine lands in Phase 1.
 */
class MainActivity : ComponentActivity() {

    private lateinit var midiEngine: MidiEngine
    private var chordText by mutableStateOf("No chord — play the keyboard")
    private var midiStatus by mutableStateOf("No MIDI device connected")
    private var isPlaying by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        midiEngine = MidiEngine(this)
        midiEngine.onChordChanged = { chord ->
            chordText = chord?.name ?: "No chord — play the keyboard"
        }
        midiEngine.onDeviceStatus = { midiStatus = it }
        midiEngine.onTransport = { transport ->
            if (transport == MidiEngine.Transport.PLAY) isPlaying = true
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("PlayRX Live", style = MaterialTheme.typography.headlineMedium)
                        Text(midiStatus, style = MaterialTheme.typography.bodySmall)
                        Text("Chord: $chordText", style = MaterialTheme.typography.titleMedium)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 12.dp),
                        ) {
                            Button(onClick = { isPlaying = !isPlaying }) {
                                Text(if (isPlaying) "Pause" else "Play")
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
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(6),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items((0 until 24).toList()) { pad ->
                                Surface(
                                    modifier = Modifier.aspectRatio(1f),
                                    color = Color(0xFF2E7D6F),
                                    shape = MaterialTheme.shapes.medium,
                                ) {
                                    // Pad tap -> AudioEngine.triggerPad(pad) in Phase 1.
                                }
                            }
                        }
                        Text(
                            "Pads are placeholders until the Oboe engine lands. " +
                                "MIDI chord detection works as soon as a keyboard is connected.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        midiEngine.close()
        super.onDestroy()
    }
}
