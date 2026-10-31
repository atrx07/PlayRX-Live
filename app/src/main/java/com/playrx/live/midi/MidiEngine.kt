package com.playrx.live.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

/**
 * USB MIDI input (Phase 1 scope). Bluetooth MIDI is explicitly out of scope
 * until Phase 3 — see docs/ROADMAP.md.
 *
 * Listens for Note On/Off and Control Change on every channel, keeps the set
 * of currently held notes, and reports chord changes via [onChordChanged]
 * and transport events via [onTransport]. The chord zone is configurable:
 * by default every channel is listened to, and the app should later let the
 * user restrict detection to the keyboard's left/chord zone (see docs/PSR-SX.md).
 */
class MidiEngine(private val context: Context) {

    var onChordChanged: ((ChordDetector.Chord?) -> Unit)? = null
    var onTransport: ((Transport) -> Unit)? = null
    var onDeviceStatus: ((String) -> Unit)? = null

    enum class Transport { PLAY, PAUSE, STOP, RECORD_TOGGLE }

    private val midiManager: MidiManager? =
        context.getSystemService(Context.MIDI_SERVICE) as? MidiManager

    private val heldNotes = mutableSetOf<Int>()
    private var openDevice: MidiDevice? = null
    private var openPort: android.media.midi.MidiInputPort? = null

    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            var i = offset
            val end = offset + count
            while (i < end) {
                val status = msg[i].toInt() and 0xFF
                val type = status and 0xF0
                when (type) {
                    0x90 -> { // Note On (velocity 0 == Note Off)
                        if (i + 2 < end) {
                            val note = msg[i + 1].toInt() and 0xFF
                            val velocity = msg[i + 2].toInt() and 0xFF
                            if (velocity == 0) noteOff(note) else noteOn(note)
                            i += 3
                        } else i = end
                    }
                    0x80 -> { // Note Off
                        if (i + 2 < end) {
                            noteOff(msg[i + 1].toInt() and 0xFF)
                            i += 3
                        } else i = end
                    }
                    0xB0 -> { // Control Change — transport is MIDI-learned later
                        if (i + 2 < end) {
                            handleControlChange(
                                msg[i + 1].toInt() and 0xFF,
                                msg[i + 2].toInt() and 0xFF,
                            )
                            i += 3
                        } else i = end
                    }
                    else -> i += 1 // Skip unsupported / running-status bytes for now
                }
            }
        }
    }

    fun listDevices(): List<MidiDeviceInfo> =
        midiManager?.devices?.toList().orEmpty()

    fun open(deviceInfo: MidiDeviceInfo) {
        midiManager?.openDevice(deviceInfo, { device ->
            openDevice = device
            openPort = device?.openInputPort(0)
            openPort?.connect(receiver)
            Handler(Looper.getMainLooper()).post {
                onDeviceStatus?.invoke("Connected: ${deviceInfo.properties.getString(MidiDeviceInfo.PROPERTY_NAME)}")
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun close() {
        runCatching { openPort?.close() }
        runCatching { openDevice?.close() }
        openPort = null
        openDevice = null
        heldNotes.clear()
    }

    private fun noteOn(note: Int) {
        heldNotes.add(note)
        emitChord()
    }

    private fun noteOff(note: Int) {
        heldNotes.remove(note)
        emitChord()
    }

    private fun emitChord() {
        val chord = ChordDetector.detect(heldNotes)
        Handler(Looper.getMainLooper()).post { onChordChanged?.invoke(chord) }
    }

    private fun handleControlChange(controller: Int, value: Int) {
        // Placeholder mapping — real MIDI-learn replaces this in Phase 2.
        // CC 64 (sustain pedal) down = play, up = pause is NOT assumed here;
        // transport must be explicitly learned by the user. See docs/MIDI-MAP.md.
        if (value == 0) return
        when (controller) {
            0x7A -> onTransport?.invoke(Transport.PLAY) // placeholder only
        }
    }
}
