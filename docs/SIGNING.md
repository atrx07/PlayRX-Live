# Signing (debug builds only)

Debug APKs from GitHub Actions are signed with one shared debug keystore
so that a new APK installs over the previous one instead of Android
refusing it with "package conflicts with an existing package".

How it works:

- The keystore itself is NOT in this repo. It is stored as a GitHub
  Actions repository secret named `DEBUG_KEYSTORE_B64` (base64 of a
  standard Android debug keystore, alias `androiddebugkey`,
  store/key password `android`, valid until 2054).
- `.github/workflows/android.yml` restores that keystore to
  `~/.android/debug.keystore` before building, but only when the secret
  exists. If the secret is missing, the build still succeeds — it just
  falls back to the runner's throwaway debug key, and the next APK will
  again conflict with the previous install.
- No code change is needed for this: Android Gradle Plugin signs debug
  builds with `~/.android/debug.keystore` by default.

One-time catch: switching to the shared key still conflicts once with
an APK signed by an older throwaway key. Uninstall the old app once,
install the new APK, and every later build will update in place.

This is a DEBUG key only. It must never be used for a Play Store
release — a release needs its own private keystore, kept as a secret
and never committed to the repo.
