# DRUM for real — one-shot grid + Scale Keyboard (2026-11-08)

Remixlive's Drum View, as PlayRX's own code. Source: the Tutorial
Tour structure in `docs/UI-STRUCTURE.md` (same grid, one-shot pads
for finger-drumming; a Scale Keyboard expands at the bottom).

## What it is

- **One-shot grid:** the same 8x6 pad grid, but every tap fires
  once — even Pad/FX loop columns. Loops already playing keep
  playing underneath (they are not stopped by switching to Drum),
  so you can finger-drum over them. `PlaybackEngine.triggerOneShot`
  fires the pad's imported sample when one is assigned, else the
  synth one-shot. Taps record into the sequence exactly like LOOP
  taps when ● Rec is on.
- **Scale Keyboard** (`audio/ScaleKeyboard.kt`, pure Kotlin):
  scales Major / Minor / Pentatonic / Blues / Dorian / Chromatic.
  The root **follows the project key**, so the keys are always in
  key. Octave 2..6 (C4 = middle C, MIDI 60), two octaves of the
  scale shown as labelled keys. Notes are synth (sine + harmonics,
  0.8 s decay), played live through `PlaybackEngine.playNote`.
- **Recording a melody:** a scale tap while recording writes a step
  into the selected sequence on the selected pad, with the step's
  pitch set to the note's offset from middle C (clamped -12..+12).
  The melody then replays through the sequencer's per-step pitch
  (synth pads; see `docs/SEQUENCER.md`).

## Honest limits

- Keys are tap-to-play buttons, not a piano roll and not a
  drag-to-glide strip: no glissando, no velocity from touch
  position, no hold-to-sustain (notes ring their 0.8 s decay).
- The keyboard shows scale notes only — no black/white piano
  layout, no chord buttons. Chromatic covers every note if the
  player wants them.
- Scale taps do not transpose the selected pad's own sound; they
  play the note synth. Recording stores the pitch on the selected
  pad's row, so replays use that pad's sound pitched by the step.
