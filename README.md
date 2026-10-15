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

## Building

Open the folder in Android Studio (or `./gradlew :app:assembleDebug`).
minSdk 26, targetSdk 35; RAW/DNG features depend on the device sensor's
`RAW` capability. Microphone permission gates the recorded audio track.

## Cleanroom statement

This repository contains no decompiled code, borrowed assets, or copied
strings from the observed reference package. The design specification was
recorded from observable behavior only; everything shipped here is original
work, except the OFL-licensed Selawik fonts.
