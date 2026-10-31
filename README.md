# PlayRX Live

An open-source, free Android beat-making and live-performance app — inspired by pad/loop apps like Remixlive, but with **no paywall, no locked packs, and no locked features**.

PlayRX Live is built to work **in parallel with a Yamaha PSR-SX arranger keyboard** (SX600 / SX700 / SX900): the keyboard keeps doing its own backing/styles and lead playing, while the app adds synced loops, pads, and chord-aware accompaniment on the phone/tablet.

## Status

Phase 0 scaffold. The pad grid, MIDI plumbing, and chord detector in this repo are the starting skeleton — the native low-latency audio engine (Oboe) is the first real build milestone, not yet wired in. See `docs/ROADMAP.md`.

## Decisions (locked 2026-10-31)

- **License:** MIT — chosen for maximum open-source contribution. Anyone can use, fork, and contribute, including commercially, as long as the license notice stays.
- **MIDI first:** USB MIDI now, via the keyboard's USB TO HOST port. Bluetooth MIDI later (v2). This is deliberate: the SX600 has no 5-pin MIDI DIN ports, so USB TO HOST is the one connection that works across the whole SX family.
- **Parallel backing:** The app does not replace the keyboard's styles. It listens to what you play, detects the chord, and follows it — so app loops and keyboard backing stay in the same key/chord.
- **All features free:** No premium tier. Sample packs in this repo must be CC0 or otherwise clearly licensed — never copied from a commercial app.

## Get the APK (no Android Studio needed)

Every push to `main` runs the **Android Build** GitHub Action, which builds a debug APK and uploads it as an artifact:

1. Open the repo on GitHub → **Actions** tab → latest successful **Android Build** run.
2. Scroll to **Artifacts** → download **PlayRX-Live-debug** (a zip containing `app-debug.apk`).
3. On your test Android phone, open the APK and allow "Install unknown apps" for your browser/files app when asked.
4. Connect a PSR-SX keyboard with a USB OTG cable to the phone/tablet's USB port, then pick the device in the app's MIDI screen. Android will ask for USB permission — allow it.

## Quick start (Android Studio)

1. Open this folder in Android Studio (Hedgehog or newer).
2. Connect an Android device (Android 8.0+ recommended for low-latency audio).
3. Run the `app` configuration.
4. Connect a PSR-SX keyboard as above.

## Project layout

```
app/src/main/java/com/playrx/live/
  MainActivity.kt        # Compose pad grid, transport, chord display
  midi/
    MidiEngine.kt        # USB MIDI device open / note on-off / CC
    ChordDetector.kt     # Pure-Kotlin chord detection (no Android deps)
  audio/
    AudioEngine.kt       # Audio interface; Oboe native engine is TODO
docs/
  PSR-SX.md              # How the SX family connects, and parallel-backing rules
  MIDI-MAP.md            # Transport, chord, and control mappings
  ROADMAP.md             # Phases 1-4
```

## Contributing

See `CONTRIBUTING.md`. Issues and PRs welcome — especially PSR-SX hardware test reports (model, cable, Android device, latency you felt).

## License

MIT. See `LICENSE`.
