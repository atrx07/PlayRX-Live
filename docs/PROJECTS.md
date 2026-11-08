# Projects + Live Set + recording (2026-11-08)

Remixlive's project home (Live Set) and its Rec dropdown, as
PlayRX's own code. Source: the Tutorial Tour structure in
`docs/UI-STRUCTURE.md` (Live Set = home-screen set of projects in
chronological order; Rec dropdown: Song / Drums & Sounds / Master
Audio).

## Projects (`audio/ProjectStore.kt`)

- One JSON file per project under `filesDir/projects/`. The Live
  Set is that list, newest first — no separate database.
- A project holds: name, BPM, project key, master volume, the
  loop-start / snap defaults, the sequences
  (`SequenceStore.toJson`), the song (`SongTimeline.toJson`) and
  the per-pad sample assignments (pad -> sample id +
  `PadSample`). Sample *files* are not duplicated — they stay in
  SampleStore's manifest and are looked up by id on load. A pad
  whose sample was deleted since the save loads back to the
  built-in synth, never as an error.
- Load stops the transport first: a loaded project starts from
  bar 1. Save / load / delete are in the Live Set overlay
  (top-bar "Live Set" button); the project name is typed in the
  overlay, blank saves as "Project N".

## Recording — the Rec dropdown

- **Song:** loop taps become timeline clips. A loop the player
  starts opens a clip at the current bar; stopping it closes the
  clip (at least 1 bar, clamped to the song end). One-shot taps
  become 1-bar clips that fire once at their start bar on
  playback. Song-driven starts (the timeline playing back) are
  never recorded — only the player's taps. Stopping the transport
  closes any open clips.
- **Drums & Sounds:** the path built in the SEQ slice — pad taps
  (grid, Drum one-shots, scale keys) quantize into the selected
  sequence. The ● Rec button in SEQ mirrors this mode.
- **Master Audio:** the live mix is captured exactly as the
  speakers get it (mono, post soft-clip) and, on leaving the
  mode, written as a 16-bit PCM WAV to
  `filesDir/recordings/master-YYYYMMDD-HHMMSS.wav`. Captures under
  0.1 s are discarded as mis-taps. The capture caps at 5 minutes
  (~53 MB) and stops itself there; the overlay says so.
- **Share:** a recording's Share button sends the WAV through
  Android's share sheet via a FileProvider that exposes only the
  recordings directory, one file at a time.

## Honest limits

- No stem export yet: "share as stems" needs an offline render
  per column, which is a later slice. What exists is the full
  mix: record Master Audio while the song plays and share that
  WAV — it is the whole track, not stems.
- No auto-save and no crash recovery: a project exists only
  after Save is tapped.
- Loading a project replaces the current sequences and song
  outright — there is no merge and no undo. Save first if the
  current work matters.
