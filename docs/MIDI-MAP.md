# MIDI map (v1 draft — MIDI-learn replaces fixed mappings)

## Transport

Transport must be **explicitly learned** by the player, not guessed from a pedal or CC. The flow is: tap "Learn" on a transport action in the app, then press the key / pedal / button on the keyboard. That binding is saved.

Actions to learn:
- Play / Pause toggle
- Stop (all pads off)
- Record toggle (sequencer overdub)
- Tap tempo
- Next / previous pad bank
- Chord lock on/off (freeze the detected chord)

## Chord input

- Default: all channels, all notes, chord-detected from the held-note set.
- Recommended SX setup: set a left-hand split on the keyboard and set the app's chord zone to the same range (see `docs/PSR-SX.md`).
- Chord changes are quantized: a new chord takes effect at the next bar boundary by default, so loops don't jump mid-bar. (Setting: next beat vs next bar.)

## Pad triggering from the keyboard

- Drum mode: consecutive notes map to pads in order (C = pad 1, C# = pad 2, ...).
- Chromatic mode: notes transpose the selected pad's sample.
- Chord mode: a single chord (or a single root note, with the app filling the chord quality) triggers the chord pad bank for that root.

## Controls (Phase 2)

- Mod wheel -> FX amount on the selected channel.
- Sustain pedal -> pad hold (gate mode) for held pads.
- Assignable knobs (SX Live Control knobs send CCs) -> filter cutoff / FX depth, via MIDI-learn.

## Out of scope in v1

- Bluetooth MIDI (Phase 3).
- MIDI Clock send/receive (Phase 2).
- Sending notes/styles back to the keyboard (after one-way listening is stable).
