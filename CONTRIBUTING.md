# Contributing to PlayRX Live

All contributions are welcome, under the MIT license. By opening a PR you agree your contribution is licensed MIT as well.

## What helps most right now

1. **Oboe low-latency audio engine** — the biggest Phase 1 item.
2. **PSR-SX hardware reports** — model (SX600/700/900), Android device, cable type, what worked, what lagged.
3. **CC0 sample packs** — only with a clear license/source link. No samples copied from commercial apps or packs.
4. **Chord-detector tests** — new chord shapes in `ChordDetector.kt` with unit tests.

## Rules

- No paywall, no locked features, no telemetry that phones home.
- No copyrighted samples, styles, or voices from Yamaha or any commercial pack.
- Keep the audio callback lock-free: no file I/O, allocation, or blocking calls on the audio thread.
- Test on a real device where possible; emulators hide latency problems.
