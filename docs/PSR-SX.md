# Yamaha PSR-SX support

Target family: PSR-SX600, PSR-SX700, PSR-SX900. The owner's own keyboard is an SX700; the SX600 is the compatibility floor because it has no 5-pin MIDI DIN ports.

## Connection (v1: USB only)

- Cable: USB-A to USB-B (printer-style) from the keyboard's **USB TO HOST** port, plus a USB OTG adapter on the phone/tablet side. The phone is the USB host.
- Why USB, not DIN: SX700/900 have MIDI IN/OUT DIN, SX600 does not. USB TO HOST works on all three and carries both MIDI and (on SX600/700/900) stereo audio.
- Android permission: the app must request USB device permission on first attach. The manifest's `USB_DEVICE_ATTACHED` filter lets Android offer PlayRX when the keyboard is plugged in.
- Bluetooth MIDI is **not** in v1. SX900 has Bluetooth audio on some variants; Bluetooth MIDI availability varies by batch/region. It lands in Phase 3, after USB is proven.

## Parallel backing — what "in parallel" means here

The keyboard is **not** muted, replaced, or turned into a dumb controller:

1. The keyboard keeps playing its own style/accompaniment and voices, exactly as the player set it up.
2. PlayRX listens to the notes the player is actually holding and detects the chord (see `ChordDetector.kt`).
3. PlayRX transposes its own loops to that chord and switches pad banks to match, so app loops and keyboard backing stay in the same harmony.
4. Tempo is manual in v1: set the same BPM in both the keyboard style and the app. MIDI Clock sync is a Phase 2 item — do not promise it in v1.

## The hard problem to solve in testing

A PSR-SX sends **everything** over USB MIDI by default: right-hand melody notes, left-hand chord notes, and (depending on settings) style parts. If PlayRX naively chord-detects every note, a melody note will constantly "change the chord."

v1 mitigations, in order:

1. **Chord zone setting in the app:** let the player set a split point / note range for chord detection (e.g. only notes below middle C count), matching how they already split the keyboard for left-hand chords.
2. **Channel filter:** if the keyboard is set to send the left/chord part on its own channel, let the player pick that channel in the app.
3. **Hold-time gate:** only treat a note set as a chord change if it is held past a short threshold, so passing melody notes don't retrigger banks.
4. **Manual chord lock:** a button / pedal that freezes the current chord until the player unlocks it.

These need real SX700 hardware testing — emulator MIDI cannot reproduce style-part traffic.

## What NOT to do

- Do not send MIDI back into the keyboard to change its style/voices in v1. One-way listening first; two-way control only after one-way is stable.
- Do not bundle or redistribute Yamaha styles, voices, or expansion packs. This app ships its own CC0 loops only.
