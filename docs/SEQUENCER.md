# SEQ for real — sequences + step editor (2026-11-08)

Remixlive's Seq view, as PlayRX's own code. Source: the Tutorial Tour
structure in `docs/UI-STRUCTURE.md` (list of sequences, tap one +
Edit for the step editor, per-step Velocity / Offset / Duration /
Roll / Pitch, recording through Drum View).

## Model (`audio/Sequencer.kt`, pure Kotlin, unit-tested)

- `StepCell(on, velocity, offsetBeats, durationBeats, roll, pitchSemitones)`:
  velocity 0.05..1, offset -0.25..+0.25 beats (a 16th early/late),
  duration 0.0625..1 beats, roll 1/2/4 retriggers inside the step,
  pitch -12..+12 semitones. `sanitized()` clamps every field.
- `Sequence(id, name, loop, gain, cells[48][16])`: one bar at 16th
  notes. `loop = false` plays the bar once, then stops the
  transport at the bar line. JSON is hand-rolled, only on-cells are
  serialized; the Projects slice reuses this format.
- `SequenceStore`: the list. Create / select / delete / rename;
  delete refuses when it is the last sequence. `recordTap(pad,
  totalBeats)` quantizes a live tap to the nearest 16th and turns
  that cell on, keeping its existing parameters if it is already on.

## Engine (`audio/AudioEngine.kt`)

- The old `BooleanArray` pattern is gone. `getStep` / `setStep`
  stay as compatible wrappers over the selected sequence's cells.
- Step firing honours the cell: velocity scales the hit, offset
  and roll subdivisions wait on a frame-counted `DelayedVoice`
  queue (frozen while paused, cleared on pause/stop), duration
  caps the voice via `Voice.maxFrames`, pitch resamples synth
  one-shots (`pitchedCache`, linear interpolation).
- Sequencer steps now play a pad's **imported** sample when one is
  assigned — the old code always played the synth from steps.
- `isRecording` + `recordPadTap`: every `triggerPad` while
  recording quantizes into the selected sequence. That is the
  Drums & Sounds recording path; the Rec dropdown (Song / Drums &
  Sounds / Master Audio) lands with the Projects slice.
- Sequence gain scales step hits; a one-shot sequence stops the
  transport when its bar wraps.

## Honest limits

- Per-step pitch applies to **synth** pads only. An imported sample
  keeps its own transpose (EDIT) and project-key dynamics; pitching
  a stretched imported buffer per step is a later phase.
- One bar (16 steps) per sequence for now. Multi-bar sequences
  come with the Song slice, which needs the same length plumbing.
- No rename UI yet: `SequenceStore.rename` exists and is tested,
  the SEQ view shows New / Delete / select only.
- Roll hits share the step's duration divided by the roll count,
  so a x4 roll on a 0.25-beat duration is four short hits, not
  four full-length ones.

## UI

- SEQ tab: sequence list (name + step count, Delete), Loop / One
  shot toggle, Gain slider, ● Rec toggle, pad chips (selected pad
  + pads with steps), the 16-step row for the selected pad, and
  the per-step detail strip for the tapped step (amber outline).
  Editing a parameter turns that step on, so the edit is audible.
- Screenshot state `seqEditor` in all four viewport classes.
