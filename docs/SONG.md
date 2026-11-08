# SONG timeline — the arranger (2026-11-08)

Remixlive's Song view, as PlayRX's own code. Source: the Tutorial
Tour structure in `docs/UI-STRUCTURE.md` (a timeline, NOT a grid:
numbered bars across the top, tracks down the left, clips placed
on it, same Mixer panel below; pinch to zoom, cut/copy/duplicate/
shrink/remove clips, share as stems or full track).

## Model (`audio/SongEngine.kt`, pure Kotlin, unit-tested)

- `SongClip(id, track, startBar, lengthBars, pad, name)`: a clip
  references a pad. Track = instrument column 0..7.
- `SongTimeline(lengthBars = 16)`: add / remove / move / resize /
  duplicate / split. Clips on one track may not overlap — placing
  on an occupied cell is refused and the UI selects the existing
  clip instead (non-destructive; Remixlive cuts the old clip,
  PlayRX refuses the overlap). Copy = duplicate + move.
  `splitClip` cuts at a bar strictly inside the clip; the left
  part keeps the id. JSON is hand-rolled; the Projects slice
  persists the song with the project.

## Playback (`audio/AudioEngine.kt`)

- The timeline drives pads only while the SONG tab is selected
  (`songEnabled`, set from the tab). On every bar line the engine
  starts the clips covering the new bar and stops the ones that
  ended. Loop pads (Pad/FX column, or an imported LOOP sample)
  play for their clip's bars; one-shot pads fire once at the
  clip's start bar. The timeline loops with the transport; Stop
  resets to bar 1.
- `songActivePads` tracks exactly the pads the song started, so
  leaving SONG stops those and only those.

## Honest limits

- A pad the song started is stopped by the song at the clip end
  even if the player tapped it by hand in the meantime (the song
  owns its starts; the player can re-tap it after).
- Zoom is cell width only (4 / 8 / 16 bars visible) — no pinch
  gesture yet; the timeline scrolls horizontally.
- No clip copy/paste across tracks (duplicate stays on the same
  track; move it with the track's own controls — track change is
  not in the UI yet, it is in the engine's `moveClip`).
- Share as stems / full track is NOT built here — it lands with
  the Projects + recording slice (WAV export).

## UI

- SONG tab: bar-number header (amber = playhead bar while
  playing), 8 track rows, clips as wide coloured buttons with the
  pad name, empty cells place the selected pad as a 1-bar clip.
  Selected-clip controls: ← Move / Move → / − Bar / + Bar /
  Duplicate / Split / Delete. Split cuts at the clip's middle bar.
- Screenshot state `songTimeline` in all four viewport classes.
