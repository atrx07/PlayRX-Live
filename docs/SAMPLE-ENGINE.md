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

## Bar sync + tempo fixes (2026-11-02)

Three user-reported bugs from phone testing, and their real causes:

1. **Loops started mid-bar.** Imported loops were voiced the
   instant they were tapped, so a tap mid-bar started a loop
   mid-bar forever. Now loop pads tapped while the transport
   runs are ARMED (white ring on the pad) and start at their
   next quantize boundary — Bar by default, Beat or Off in the
   EDIT panel. Tapping an armed pad cancels the arm; tapping a
   playing loop stops it immediately. A tap while stopped starts
   immediately and starts the transport. Synth loop pads use the
   same arm path, so their patterns also start on a bar.
2. **Tempo snap did nothing.** Original BPM defaulted to the
   project BPM at import, so the snap rate was always 1.0x.
   Now the EDIT panel has Original BPM (60–180) and Original
   Key sliders for the assigned sample, persisted to the
   manifest. Set a loop's real BPM and Snap stretches it to the
   project BPM. A second tempo bug: a 48 kHz file was played at
   44.1 kHz without conversion (8% slow/flat). Render now
   converts every sample to the 44100 Hz mixer rate first.
3. **Trash sound quality.** OLA time-stretch summed Hann grains
   without normalizing the window overlap, so stretched audio
   throbbed and peaked. OLA now divides by the accumulated
   window sum. The mixer also soft-clips with tanh instead of a
   hard clamp, and LOOP renders get a ~6 ms fade at the wrap
   point so repeats do not click. LOOP length is also snapped
   to whole bars at the project BPM, so repeats cannot drift.

Pause also changed: the transport clock only advances while
playing, and transport-locked (imported loop) voices freeze
silently while paused, so they resume on the bar. One-shots
still play out while paused.

## Autodetect tempo + beat counter + project settings (2026-11-02)

- **Autodetect tempo:** on import, SampleEngine.detectBpm runs
  on the decoded frames (low-passed RMS envelope, autocorrelation
  60..200 BPM, earliest strong peak, whole-bar snap for loops).
  If it finds a clear tempo, that becomes the sample's original
  BPM; if not, it falls back to the project BPM, exactly as
  before. The EDIT panel still has the manual Original BPM
  slider, plus a **Detect tempo** button that re-runs detection
  on an already-assigned sample and persists the result. Manual
  edit is never removed — detection is a starting point, not a lock.
- **Circular beat counter:** top bar, next to the transport.
  A ring fills through the current bar, the centre shows the
  beat (1..4) and the bar number below it. The baseline starts
  at Bar 1 Beat 1 when Play is tapped or the first loop starts
  (Stop resets it to Bar 1; Pause resumes, it does not reset).
  Driven by the engine's transport position, not by the UI clock.
- **Project settings menu:** top-bar "Project" button opens a
  full-screen settings menu. BPM, project key, master volume,
  default loop start (Off/Beat/Bar) and default snap for newly
  assigned loops live there. MIXER is now only the per-column
  strips. Per-pad tempo/key/quantize for an already-assigned
  sample stay in EDIT.

## Tempo detection v2 + stretch v2 + delete (2026-11-02)

- **Detection v2:** three onset envelopes vote — low-pass RMS
  (kick/bass), full RMS, and spectral flux (FFT, Hann 1024) for
  melodic loops with little level movement. Candidates within
  4 BPM cluster; the low-pass candidate wins near-ties, because
  kick marks beats and hats mark subdivisions. Long files use
  their loudest 30-second segment, not a quiet intro. Bar snap
  tightened to 3%. Half-time/double-time ambiguity is real, so
  EDIT has one-tap **×2 / ÷2** buttons for Original BPM, and
  manual edit is still always available.
- **Stretch v2:** tempo snap and bar-length snap are now ONE
  stretch, not two in a row. Resampling is cubic (not linear)
  with an anti-alias low-pass on pitch-up. WSOLA search widened
  to ±512 samples, grain is 1024 for drums and 2048 for melodic
  samples, and stretches within 2% of 1.0x are skipped entirely
  (inaudible tempo error, audible artifacts). Large stretches
  (beyond ~±30%) will still degrade — that is a WSOLA limit, not
  a bug to hide; prefer loops close to the project tempo.
- **Delete samples:** SAMPLES rows now have Delete next to
  Assign. It removes the app-private file and its manifest
  entry, clears every pad using that sample back to the
  built-in synth, and stops any playing voice of it. Your
  original file on the device is untouched — PlayRX only copied it.

## Stretch audit + live tempo fix (2026-11-02)

User report: "detection works now but it doesn't stretch or
match to the project tempo — the only thing that changes is
how it fires when the project track starts." Audit found the
render itself was mostly sound (fresh assigns stretched), and
two real bugs that made it *look* unstretched in normal use:

1. **Playing loops never heard a re-render (main bug).** A
   loop voice holds a reference to the buffer it started with.
   BPM change, Detect tempo, Original BPM edit, transpose, and
   project-key change all re-rendered the stored buffer but
   left the sounding voice on the old buffer. Only stop +
   re-trigger picked the new tempo up. Synth loops never had
   this bug — they are pattern-driven from the transport clock.
   Fix: any re-render flags a voice swap; the mixer thread
   swaps active imported-loop voices to the new buffer at the
   transport-aligned position (totalBeats modulo the loop's
   bar cycle), so a BPM change lands in phase with the beat
   counter and synth loops, with no gap and no restart. Paused
   voices swap the same way and resume in phase. One-shots
   are never swapped — a fired one-shot is an event, not a loop.
2. **Pitch + tempo double-counted for one-shots.** The tempo
   stretch was applied to the already pitch-resampled buffer
   at tempoRate, so a transposed one-shot came out short/long
   by the pitch factor (octave up = half duration, even with
   Snap on at the same BPM). LOOP pads were accidentally saved
   by bar snap recomputing the total stretch; one-shots were
   not. Fix: tempo stretch on a pitched buffer is
   tempoRate / pitchRate, so afterTempo is exactly the
   tempo-correct length before bar snap.
3. **WSOLA jitter on drums.** When the overlap region is
   silence, every search candidate scores ~0 and the search
   picked an arbitrary offset up to 512 samples (11 ms) per
   grain — enough to read as "doesn't match the tempo" even
   though total length was exactly a bar. Fix: if the expected
   position scores within 10% of the searched best, the
   expected position wins; tonal material still gets the
   searched (phase-aligned) position, where the gain is large.

Tests added for this round prove beats, not just length: a
100 BPM click loop rendered at 120 BPM must have bursts on
every 0.5 s beat and quiet mid-beats, a transposed one-shot
must keep its tempo length, and a transposed loop must still
land on exactly 2 bars at 120 BPM.

4. **WSOLA search drift (found by that onset test).** The
   search was centred on the previous *searched* position, so
   a systematic phase bias accumulated — for a periodic signal
   the search kept advancing by a whole period instead of the
   tempo hop, the input was consumed too fast, and the tail of
   the stretched loop came out silent (beats 2+ of a click loop
   vanished in simulation, even though total length was exactly
   a bar). Fix: the search is now anchored to an ideal position
   that advances by exactly hopA per grain, independent of the
   search offset, so the effective stretch rate is exactly the
   requested rate. Verified in simulation before pushing: all 8
   beats present at 0.92 peak, mid-beats silent.

## Detection v3 — melodic peak-picking fix (2026-11-02)

Phone test (Datacode Fusion Downtempo Breaks, ground truth
80 BPM): the drum break detected at 80 and stretched fine,
but Melody Loop 01-Bb detected at 97 and never synced — with
the wrong original BPM, bar snap stretched it to 5 bars
instead of 4, so its beats landed at ~64 BPM against the
project's 80. The envelopes were not wrong: a local port of
the detector reproduced 97.3 exactly, and showed low/full
autocorrelation peaking at 80.7 with 0.62/0.64 strength.
The bug was peak-picking in autocorrPeak: the old code took
the earliest lag at >= 90% of max, which for a smooth melodic
envelope sits on the rising edge of a broad, flat-topped
peak, then applied parabolic refinement unclamped — with
a - 2b + c ≈ 0.006 that shift exploded to ~9 hops, turning
80.7 into 96.6/98.0, while flux (sharper peak) returned 80.4
and was outvoted by the two bad RMS candidates.
Fix: take the earliest *local* peak >= 90% of max (a beat lag
is a local peak, and this still prefers the beat over a
stronger 2-beat repetition), and only refine a concave peak
with the shift clamped to half a hop. Same melody loop now
returns 80.2 on all three envelopes, then bar-snaps to 80.
Regression test: a synthetic sustained-note loop at 80 BPM
(new sine note every beat, slow decay, short crossfade) —
old picker ~76 BPM, fixed picker 80 BPM. Workaround that
always existed, and still works for any mis-detection:
set Original BPM manually in EDIT (97 is not an octave
error, so ×2/÷2 does not fix it — the slider does).
