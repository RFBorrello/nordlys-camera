# Nordlys

A cleanroom camera application for Android. Nordlys is an original
implementation of a Metro-styled, manually-controllable camera experience:
feature behavior was studied from an observation-only specification, and every
line of code, UI string, icon, and theme token here is authored fresh.

- **Design language:** Metro — flat surfaces, light type, aurora accent
- **Typeface:** [Selawik](https://github.com/microsoft/Selawik) (SIL OFL,
  bundled with license: `LICENSE-Selawik.txt`)
- **Platform APIs:** Android Camera2, Media/MediaCodec/MediaRecorder, MediaStore,
  Jetpack Compose. No proprietary code or assets.

## Feature surface (phase status)

| Mode / feature | Status |
|---|---|
| Pro camera — manual ISO, shutter, WB, focus, zoom, EV, flash off/strobe/torch | shipped |
| Viewfinder grids (thirds / square / golden), lens switch | shipped |
| JPEG still capture into the system library | shipped |
| Smart mode — YUV burst → block alignment → temporal median merge | shipped (baseline) |
| Video clips — record/stop, time code, video light | shipped (first pass) |
| Living Images — motion sidecar +library playback | shipped (first pass) |
| Library — photo/video pivots, viewer, details, delete, share | shipped |
| DNG (RAW) + dual-capture format selection | shipped (first pass) |
| Exposure bracketing — count + EV ladder | shipped (first pass) |
| Self-timer with countdown | shipped |
| Settings sheet (about, reset) | shipped |

Known gaps and next steps are tracked in [docs/FEATURE_SPEC.md](docs/FEATURE_SPEC.md).
The viewfinder's interaction design — concentric pro dials around the shutter,
the mode-wheel shutter, the indicator strip, oversampled stills and the smart
sequence editor — is specified in [docs/LUMIA_PARITY.md](docs/LUMIA_PARITY.md).

## Building

Open the folder in Android Studio (or `./gradlew :app:assembleDebug`).
minSdk 26, targetSdk 35; RAW/DNG features depend on the device sensor's
`RAW` capability. Microphone permission gates the recorded audio track.

`./gradlew :app:assembleRelease` builds a minified (R8) release APK. It is
signed only when a keystore is configured through these Gradle properties
(in `~/.gradle/gradle.properties`, never in this repo) or environment
variables of the same name:

| Name | Value |
| --- | --- |
| `NORDLYS_KEYSTORE_PATH` | absolute path to the `.jks` keystore |
| `NORDLYS_KEYSTORE_PASSWORD` | keystore password |
| `NORDLYS_KEY_ALIAS` | key alias inside the keystore |
| `NORDLYS_KEY_PASSWORD` | key password |

Without them the release APK is built unsigned.

## Releases

Pushing a `v*` tag builds a signed release APK in GitHub Actions and attaches
it, with its R8 `mapping.txt`, to the GitHub release. Tags with a suffix such
as `v0.3.0-beta1` are published as pre-releases; plain `v0.3.0` tags as full
releases. Branch pushes still produce a debug APK as a workflow artifact.

The workflow needs these repository secrets
(Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `NORDLYS_KEYSTORE_BASE64` | the keystore, base64-encoded: `base64 -i nordlys-release.jks \| pbcopy` |
| `NORDLYS_KEYSTORE_PASSWORD` | keystore password |
| `NORDLYS_KEY_ALIAS` | key alias |
| `NORDLYS_KEY_PASSWORD` | key password |

Every release must be signed with the same key, or Android refuses to install
it as an update. Keep an offline backup of the keystore and its passwords.

## License

Nordlys is licensed under the [Apache License 2.0](LICENSE). The bundled Selawik
fonts are under the SIL Open Font License (`LICENSE-Selawik.txt`).

## Cleanroom statement

This repository contains no decompiled code, borrowed assets, or copied
strings from the observed reference package. The design specification was
recorded from observable behavior only; everything shipped here is original
work, except the OFL-licensed Selawik fonts.
