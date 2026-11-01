# Sample import + advanced engine (2026-11-01)

## What it is

Sample import, with the advanced engine built over it — not a
bare file picker. Modelled on the Remixlive Tutorial Tour's
import/edit behaviour, implemented in PlayRX's own code, with
PlayRX's own manifest format. No Remixlive pack, sample, or
artwork is bundled, downloaded, or copied, even for testing.

## Import flow

1. SAMPLES panel > **Import sample…** opens the Android file
   picker (`audio/*`).
2. The file is copied into app-private `filesDir/samples/` and
   recorded in `filesDir/samples/manifest.json` (SampleEngine's
   manual JSON — no Gson dependency).
3. WAV (PCM 16/24/32, float32, mono-mixed) is parsed in pure
   Kotlin (SampleEngine.parseWav). OGG/MP3/AAC fall back to
   Android MediaExtractor + MediaCodec (SampleDecoder.kt).
   A file that decodes neither way is rejected with an error
   in the SAMPLES panel, not a crash, and its copy is deleted.
4. SAMPLES panel lists the collection. **Assign** puts the
   sample on the currently selected pad.
5. Original BPM and original key default to the project BPM/key
   at import time, so tempo snap starts at 1.0x and key dynamics
   start at 0 semitones. Edit those defaults in a future pass;
   per-pad transpose is live now.

## Advanced engine (SampleEngine.kt, pure Kotlin, unit-tested)

- **Snap to project tempo:** playback rate = project BPM /
  original BPM. When snap is on, the sample is time-stretched
  with windowed overlap-add (grain 1024, hop 512), so pitch is
  preserved while duration follows the project tempo. Snap off
  = natural tempo. Re-rendering happens on every BPM change
  (PlaybackEngine.refreshImportedSamples).
- **Cut to grid:** when Cut is on, start snaps down and end
  snaps up to the project-tempo grid (default 16th = 0.25 beat;
  grid frames = sampleRate * 60 / BPM * division). A sliced loop
  therefore lands on a bar/beat boundary.
- **Transpose:** per-pad semitones (-12..+12 slider) plus
  octave shift, as a resample rate 2^(semitones/12).
- **Project-key dynamics:** when the project key changes
  (Mixer panel slider, 0..11, C=0), melodic samples (Bass,
  Melody, Pad, Vox, FX, Other) transpose by the in-key delta
  (0..11 upward, original key -> project key) on top of the
  user's transpose. Drum samples are exempt — a kick does not
  transpose with the key. The category follows the *target*
  pad's column at assign time, not the pad selected at import.
  Re-rendering happens on every key change.
- **Play modes:** LOOP (toggle, whole buffer loops as a voice
  until toggled off), ONE_SHOT (fire once), GATE (stored;
  PlaybackEngine.releasePad stops its voices — the UI tap
  button treats GATE as ONE_SHOT for now, because a tap has no
  release event; true hold-to-play needs pointer-release UI,
  which is the next UI pass for GATE, not a faked behaviour).
- **Per-pad:** gain, pan (stored; the v1 mixer is mono, so pan
  is not yet audible — it is not claimed as live), one-pole
  low-pass filter, reverse, fade in/out, quantize launch
  (beats until next boundary, used by the transport in a
  future pass).
- **Pitch + tempo, honestly:** pitch-shift by resample, then
  tempo-stretch by OLA. That is not a phase vocoder and is not
  claimed as one. It is audible, testable, and keeps loop pads
  in sync with the transport clock.

## What is NOT built yet (do not claim it)

- Original BPM/key editing per sample after import (defaults
  only, see above).
- GATE hold-to-play UI (pointer release).
- Stereo pan in the mixer (stored only).
- Per-step sample controls, sample drag-and-drop between pads
  (Assign button is the v1 route), Scale Keyboard melody
  loading, AI Remix / stem separation.
- Sample slicing into multiple pads from one file (cut-to-grid
  slices one pad's start/end; multi-pad slicing is next).

## Files

- `audio/SampleEngine.kt` — pure core + WAV + manifest JSON.
- `audio/SampleDecoder.kt` — Android MediaCodec fallback.
- `audio/SampleStore.kt` — app-private files + manifest.
- `audio/AudioEngine.kt` — PlaybackEngine imported-sample
  support, re-render on BPM/key change, loop-voice looping.
- `ui/PlayRxState.kt`, `ui/PlayRxScreen.kt` — SAMPLES list +
  Import, EDIT advanced controls, Mixer project-key slider.
- `MainActivity.kt` — SAF picker, assign, BPM/key refresh.
- `test/.../audio/SampleEngineTest.kt` — tempo, pitch, key
  dynamics, cut-to-grid, quantize, reverse, manifest, WAV.
