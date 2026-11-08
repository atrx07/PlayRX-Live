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
  Live Set, and under the Song timeline. PlayRX: per-column
  faders + M/S, plus tempo (BPM) and master volume; Eq Filter
  built 2026-11-08 (`docs/FX.md`) — one-pole low-pass per column,
  log 200 Hz..18 kHz, left = bypass. Not a 3-band EQ.
- **FX** — routing, Master + channels 1–8, beat-repeat divisions
  (1/16, 1/8, 1/4, 1/2, 1, 2), XY pad. PlayRX built 2026-11-08
  (`docs/FX.md`): tempo-synced delay (divisions, mix, feedback),
  beat repeat (1/16–2), XY pad (X = master filter, Y = delay
  mix), per-column delay sends. No reverb, no per-pad inserts.
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
  melodies live over the loops. PlayRX (2026-11-08,
  `docs/DRUM-SCALE.md`): **built** — one-shot grid (loops keep
  playing underneath), Scale Keyboard with 6 scales, root follows
  the project key, octave 2..6, two octaves of keys; scale taps
  record into the sequence with their pitch when ● Rec is on.
  No glissando / hold-to-sustain yet.
- **Seq** — list of sequences, tap one + Edit for the step
  editor: rows are sounds, columns are steps/bars, tap to add,
  tap again to remove. A second tab gives per-step Velocity,
  Offset (timing/swing), Duration, Roll, Pitch. General controls:
  Loop / One shot, Gain, Quantize, Replay, length in beats.
  Recording a sequence goes through Drum View, then back to Seq.
  PlayRX (2026-11-08, `docs/SEQUENCER.md`): **built** — sequence
  list (New / Delete / select), Loop / One shot, Gain, ● Rec
  (pad taps quantize into the selected sequence), per-step
  Velocity / Offset / Duration / Roll / Pitch. One bar per
  sequence for now; rename UI and multi-bar lengths come later.
- **Song** — a timeline, NOT a grid: numbered bars across the
  top, tracks down the left, clips/loops placed on it, same
  Mixer panel below. Pinch to zoom, cut/copy/duplicate/shrink/
  remove clips, share as stems or full track. PlayRX (2026-11-08,
  `docs/SONG.md`): **built** — 16-bar timeline, 8 tracks, clips
  play loop pads for their bars (one-shots fire at the start bar),
  move / grow / shrink / duplicate / split / delete, zoom as cell
  width. No pinch zoom yet; share-as-stems lands with recording.
- **Live Set** — home-screen set of projects in chronological
  order; perform across Loop/Drum/Mixer/FX, move to the next
  project. PlayRX (2026-11-08, `docs/PROJECTS.md`): **built** —
  one JSON file per project (tempo, key, sequences, song,
  assignments), list newest first in the Live Set overlay, Load /
  Delete / Save. No auto-save yet.
- **MIDI** — settings gear > Preferences > MIDI devices, then
  Mapping / Quick Map (tap left to right). PlayRX's edge here
  is USB MIDI chord-following with Yamaha keyboards
  (PSR-SX600 floor, SX700 test device) — see docs/PSR-SX.md
  and docs/MIDI-MAP.md. Tempo sync is manual in v1; MIDI Clock
  and Bluetooth MIDI come later.
- **Recording** — top-right Rec dropdown: Song (loops/sequences
  to the timeline), Drums & Sounds (to a sequence), Master
  Audio. PlayRX (2026-11-08, `docs/PROJECTS.md`): **built** —
  all three modes; Master Audio saves a WAV (5-minute cap) with
  Share via FileProvider. Stem export is still to come.

## PlayRX implementation mapping

- `PlayRxState.selectedTab`: "LOOP" | "SEQ" | "DRUM" | "SONG".
- `PlayRxState.selectedPanel`: "EDIT" | "MIXER" | "FX" | "SAMPLES".
- `PlayRxScreen`: header (title, MIDI status, chord, transport),
  top tabs, main view, panel switch buttons, then the selected
  panel — ONE panel at a time, never Edit+Mixer stacked.
  Landscape: grid left, switchable panel right (260dp), grid
  height fitted so all 6 rows show. A bottom panel was tried
  first in landscape and rejected: on phone landscape it
  collapsed the grid to ~0 height (fixed 170dp panel + header
  + tabs in ~412dp of height). Portrait: stacked scroll, grid
  wrapped to 4 columns, panel below the grid.
- Screenshot states in `PlayRxScreenshotTest`: loopDefault and
  loopPlaying use the default MIXER panel; loopEditMixer uses
  the EDIT panel — so both panels are visually proven every run.

## Dark theme (2026-11-01, full UI round)

Dark overall, from the Tutorial Tour look and the user's spec.
Do not revert to a light screen — the light screenshots were
the "not satisfied" version.

- Background: near-black `#121218`. Panels: `#1C1C26` dark cards
  with 16dp rounded corners. Primary text `#F2F2F7`, dim/secondary
  text `#9A9AAA`. Unselected tabs/pills: `#262633`.
- Pads — the exact read, do not simplify it back to "selected =
  amber, active = teal":
  - Unselected, not playing: PALE column colour (solid colour at
    ~22% alpha over the dark background) + DIM outline (same
    colour at ~45% alpha, 1dp). Text in the column colour.
  - Playing: FILLED solid column colour. Text white, except
    Tops yellow where text is black.
  - Playing + selected: FILLED solid colour + DARK outline
    (black, 3dp). This is the mark for a selected playing pad
    when a side panel (EDIT / MIXER / FX / SAMPLES) is open.
  - Selected, not playing: pale fill + STRONG column-colour
    outline (2dp) — visible as selected, not faked as playing.
- Top tabs: pill buttons, selected = white fill + black text,
  unselected = dark pill + dim text.
- Panel switch: selected = amber fill + black text, unselected
  = dark pill + dim text, in a 2x2 grid (see panel-position note
  below).
- Transport: dark pills; Play while playing = teal fill + black
  text. Mixer sliders use their column colour for thumb/track,
  with a pale inactive track. M = white when muted, S = amber
  when soloed, otherwise dark pill + dim text.
- Sequencer steps follow the same pad read: off = dark pill +
  dim outline, on = filled teal + black text, current + on =
  filled teal + dark (black) outline, current + off = dark pill
  + white outline.
- Screenshot tests still use the Light Paparazzi theme name, but
  the screen itself forces the dark palette (explicit Surface,
  text, and button colours), so recorded PNGs are dark.

