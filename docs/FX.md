# FX + Mixer EQ (2026-11-08)

Remixlive's FX panel (Master + channel routing, beat-repeat
divisions, XY pad) and the Mixer's Eq Filter tab, as PlayRX's own
code. The mixer is still a mono sum duplicated to stereo; the FX
work on that sum per column (see below).

## What is built

- **Delay** (`FxState.delay*`): on/off, tempo-synced division
  (1/16, 1/8, dotted 1/8, 1/4, 1/2 — frames = beats at the
  current BPM), mix, feedback (standard feedback delay: the
  buffer is fed the per-column send sum plus the delayed signal
  times feedback). The old v1 delay was a fixed dotted eighth at
  0.25 mix / 0.35 feedback; those are now the defaults.
- **Beat repeat** (`BeatRepeat`): Off / 1/16 / 1/8 / 1/4 / 1/2 /
  1 / 2. Enabling freezes the slice that *just played* (length
  = the division at the current BPM) and stutters it, mixed with
  the live signal at the repeat-mix level, until switched Off.
- **XY pad**: X = master low-pass cutoff (log 80 Hz..18 kHz),
  Y = delay mix (top = 1). Dragging sets both live.
- **Routing**: a per-column delay send (0..1, default 0.5). The
  delay eats the filtered column sums weighted by their sends,
  not the whole mix.
- **Mixer "Eq Filter" tab**: a one-pole low-pass per instrument
  column (slider log 200 Hz..18 kHz, left = bypass). The mixer
  sums voices per column first, filters each column sum, then
  sums the columns — so the filter is per column, not per voice.

## Where it lives

- `audio/FxEngine.kt`: `BeatRepeat`, `DelayDivision`,
  `FxState`, `FxMath` (division frames, one-pole alpha/step, XY
  <-> cutoff, repeat ring read position). Pure Kotlin, tested in
  `FxTest.kt`.
- `PlaybackEngine.fxState` + setters (`setDelayEnabled`,
  `setBeatRepeat`, `setXy`, …). The mixer owns the live buffers:
  a 4 s delay line (covers 2 beats at 60 BPM), a 10 s
  beat-repeat ring, per-column filter states.
- UI: `FxPanel` + `XyPad` + `ColumnFilterPanel` in
  `PlayRxScreen.kt`; Mixer inner tabs Volumes / Eq Filter.

## Honest limits

- The per-column "EQ" is a single one-pole low-pass per column,
  not a 3-band EQ and not a resonant filter — no Q, no high-pass,
  no shelf. It cuts highs; it cannot boost or notch.
- The master filter is the same one-pole design. There is no
  per-pad filter yet (EDIT's filter slider is still stored, not
  wired — slice 6).
- Beat repeat freezes audio, not MIDI: it stutters the mix that
  just played, so it cannot retrigger a pad's own pattern.
- Delay and beat repeat are master-bus effects; there is no
  per-pad FX insert, and no reverb (a feedback delay is not a
  reverb).
