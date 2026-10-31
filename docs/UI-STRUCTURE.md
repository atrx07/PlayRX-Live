# PlayRX UI structure — from the Remixlive Tutorial Tour

Source: "Remixlive | Tutorial Tour with Nadia Struiwigh" (MIXVIBES,
10 videos), watched in full 2026-10-31. This file is the durable
record of that structure, so future PlayRX work does not drift back
to the old layout. It describes workflow and layout only — no
Remixlive code, samples, artwork, or branding is copied into PlayRX.

## The rule that matters most

- Top tabs are ONLY: Loop, Seq, Drum, Song (plus a Rec dropdown
  and a settings gear, top right).
- Edit and Mixer are NOT top tabs, and they are NOT a side column
  inside Loop. They are switchable panels below the main view,
  chosen by four right-edge icons, in order:
  1. Edit
  2. Mixer
  3. FX
  4. Samples / Collection

The old PlayRX layout (BPM, volume, delay, Edit, Mixer all stacked
in a 240dp side column inside LOOP) broke both halves: it squeezed
the grid and hid Edit/Mixer below the fold. Never restore it.

## Panels

- **Edit** — selected pad/sample: waveform/timeline, play modes
  Loop / One shot / Gate, Gain, Pan, Filter, Transposition,
  Transport, Quantize, Repeat, Reverse, Reset, Import / Replace.
  PlayRX v1 implements the honest subset: pad volume/mute/solo
  plus "Edit pattern" (select a pad without playing it).
  Waveform, Gate, Reverse, Transpose are NOT built yet — do not
  render them as if they work.
- **Mixer** — lower panel with two tabs inside it: Volume
  (VOLUMES: 8 faders, one per column, colour-coded, M and S under
  each) and Eq Filter. The same panel appears under Loop, under
  Live Set, and under the Song timeline. PlayRX v1: per-column
  faders + M/S, plus tempo (BPM) and master volume. EQ/Filter
  per channel is NOT built yet.
- **FX** — routing, Master + channels 1–8, beat-repeat divisions
  (1/16, 1/8, 1/4, 1/2, 1, 2), XY pad. PlayRX v1: Delay FX switch
  only; beat-repeat and XY pad are next, not faked.
- **Samples / Collection** — folders/tags/packs, AI recommendations
  for replacing a pad's sample. PlayRX v1: placeholder only.
  The planned route is sample import in PlayRX's own JSON
  manifest format, loading WAV/OGG the user legally owns.
  Never fetch or bundle a Remixlive pack, even for testing.

## Views

- **Loop (default)** — 8 columns x 6 rows. Columns are channels,
  colour-coded (PlayRX: Kick red, Snare orange, Tops yellow,
  Bass green, Synth cyan, Pad pink, Vox purple, FX blue). Rows
  are loop variations. Tapping a pad launches that loop.
  Loops keep playing when you switch to Drum, so you can play
  one-shots over them.
- **Drum** — same 8x6 area, one-shot pads for finger-drumming
  (MPC-style), icons show sound type. A Scale Keyboard expands
  at the bottom: drag to move octave/range, choose a scale, play
  melodies live over the loops. PlayRX v1: Drum tab currently
  shows only the 16-step sequencer for the selected drum pad;
  the one-shot pad grid + Scale Keyboard are still to build.
- **Seq** — list of sequences, tap one + Edit for the step
  editor: rows are sounds, columns are steps/bars, tap to add,
  tap again to remove. A second tab gives per-step Velocity,
  Offset (timing/swing), Duration, Roll, Pitch. General controls:
  Loop / One shot, Gain, Quantize, Replay, length in beats.
  Recording a sequence goes through Drum View, then back to Seq.
  PlayRX v1: Seq is a placeholder; the step editor lives on the
  Drum tab for now.
- **Song** — a timeline, NOT a grid: numbered bars across the
  top, tracks down the left, clips/loops placed on it, same
  Mixer panel below. Pinch to zoom, cut/copy/duplicate/shrink/
  remove clips, share as stems or full track. PlayRX v1:
  placeholder.
- **Live Set** — home-screen set of projects in chronological
  order; perform across Loop/Drum/Mixer/FX, move to the next
  project. Not built yet.
- **MIDI** — settings gear > Preferences > MIDI devices, then
  Mapping / Quick Map (tap left to right). PlayRX's edge here
  is USB MIDI chord-following with Yamaha keyboards
  (PSR-SX600 floor, SX700 test device) — see docs/PSR-SX.md
  and docs/MIDI-MAP.md. Tempo sync is manual in v1; MIDI Clock
  and Bluetooth MIDI come later.
- **Recording** — top-right Rec dropdown: Song (loops/sequences
  to the timeline), Drums & Sounds (to a sequence), Master
  Audio. Not built yet in PlayRX.

## PlayRX implementation mapping

- `PlayRxState.selectedTab`: "LOOP" | "SEQ" | "DRUM" | "SONG".
- `PlayRxState.selectedPanel`: "EDIT" | "MIXER" | "FX" | "SAMPLES".
- `PlayRxScreen`: header (title, MIDI status, chord, transport),
  top tabs, full-width main view, panel switch buttons, then
  the selected bottom panel. Landscape: grid height is fitted
  so all 6 rows show, bottom panel is a fixed-height scroll
  area. Portrait: stacked scroll, grid wrapped to 4 columns.
- Screenshot states in `PlayRxScreenshotTest`: loopDefault and
  loopPlaying use the default MIXER panel; loopEditMixer uses
  the EDIT panel — so both panels are visually proven every run.
