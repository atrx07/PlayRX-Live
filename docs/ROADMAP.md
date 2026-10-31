# Roadmap

## Phase 0 — Scaffold (this commit)
Repo, MIT license, Gradle/Compose skeleton, USB MIDI plumbing, pure-Kotlin chord detector, PSR-SX + MIDI-map specs. No working audio yet.

## Phase 1 — Core engine
- Oboe native audio engine (LowLatency + Exclusive, native sample rate, lock-free callback).
- 24-pad grid (phone) / 48-pad (tablet), tempo/key-synced loops, per-column mute/solo/EQ.
- One CC0 demo pack, WAV export of a performance.
- Proof point: measured touch-to-sound latency on a real device + SX700 USB test.

## Phase 2 — Production
- Step sequencer with overdub, song/timeline mode.
- Sample import (MP3/WAV/AAC) and editing (trim, gain, reverse, transpose).
- FX: delay, filter, reverb, beat-repeat. MIDI Clock sync. MIDI-learn for transport.

## Phase 3 — Advanced MIDI
- Bluetooth MIDI (in addition to USB, not instead of it).
- Chord-zone + channel filtering, hold-time gate, chord lock (see `docs/PSR-SX.md`).
- Chord-mode pad banks, chromatic mode, mod wheel / sustain / Live Control knob mapping.
- Ableton Link (optional, if there is demand).

## Phase 4 — Ship
- README polish, demo video, GitHub releases, F-Droid submission, Play listing (free, no IAP).
- Community packs: a documented CC0 pack format so contributors can ship packs without code changes.
