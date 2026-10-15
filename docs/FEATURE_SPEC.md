# Nordlys — Cleanroom Feature Specification v0.6

Derived from observed feature surface of a Windows-Phone-era camera package
(XAP `4.10.0.6`, REA inventory Evidence `ev_c3a401c7…`). This document records
**observable behavior and capability names only** — no original code, assets,
or UI strings are reproduced. Every Nordlys implementation artifact is authored
from scratch against public platform APIs (Android Camera2 / Media / Jetpack
Compose) and the open-licensed Selawik typeface (SIL OFL, bundled with license).

## Identity
- App name: **Nordlys** (package `com.nordlys.camera`)
- Design language: Metro — flat surfaces, light type weights, accent-tinted
  state, generous spacing. Persona: Nordic aurora/glacier colorway.

## Build order (agreed)
1. **Phase 1 — Pro Camera core + Metro viewfinder** *(this milestone: shipped skeleton)*
2. Phase 2 — Gallery (photo/video pivots, info, delete/share)
3. Phase 3 — Smart mode computational pipeline (HDR/multi-frame merging)
4. Phase 4 — Living Images (burst + motion sidecar player)
5. Phase 5 — Video mode + trimmer
6. Phase 6 — RAW/DNG + dual capture, bracketing, self-timer, first-run flow

## Phase 1 status (implemented)
| Feature | Source behavior | Nordlys implementation |
|---|---|---|
| Manual ISO | ISO ladder via wheel | `CaptureRequest.SENSOR_SENSITIVITY` + staged stops from `SENSOR_INFO_SENSITIVITY_RANGE` (`model/ManualState.kt`, `camera/ManualMapping.kt`) |
| Manual shutter | 1/2000…4 s ladder | `SENSOR_EXPOSURE_TIME` with photographic ladder clamped to device range |
| AE off when manual | Preview mirrors capture | Full-manual path sets `CONTROL_AE_MODE_OFF` |
| EV bias | ±EV steps | `CONTROL_AE_EXPOSURE_COMPENSATION` (plumbed; UI slot reserved) |
| White balance | Auto/Daylight/Cloudy/Shade/Fluorescent/Incandescent presets | `CONTROL_AWB_MODE` mapping (`ManualMapping.kt`) |
| Manual focus | Diopter wheel | `LENS_FOCUS_DISTANCE` + `CONTROL_AF_MODE_OFF` (state plumbed; wheel slot reserved) |
| Zoom | Wheel/slide | `CONTROL_ZOOM_RATIO` (plumbed) |
| Flash modes | Off / Strobe / Video-light (torch) | `FLASH_MODE` + `AE_MODE_ON_ALWAYS_FLASH` cycling HUD chip |
| Grid lines | Off / Thirds / Square / Golden | `GridOverlay.kt` hairline overlays |
| Front/back lens | Lens menu | `backLensIds()` / `frontLensId` + HUD switch; multi-camera concurrent sessions land in Phase 6 |
| JPEG still capture | One-press capture to roll | `captureStill()` → MediaStore, pending-flagged |
| Grid/mode pivot | Camera·Smart·Video header pivot | `MetroPivot` placeholder tabs top-left |
| Metro design | FLAT UI, light headers | `ui/theme/*` tokens + Selawik family (Light/ExtraLight/Regular/SemiBold/Bold) |
| Capture HUD | Shutter, lens chips, settings | `ShutterButton`, `HudChip` row |

## Intentionally not reproduced (cleanroom boundaries)
- No telemetry/analytics, no device-identity or cellular interop capability.
- No proprietary battery/thermal dialog workflows; replaced with modern Android
  Thermal Service handling when that phase lands.
- No reliance on manufacturer OEM pass-through services; lens-pair choice uses
  public `CameraManager` APIs (including concurrent-cameras on API 30+).

## Phase 2 status (shared-library surface)

| Feature | Source behavior | Nordlys implementation |
|---|---|---|
| Gallery pivots | Photos + videos pivots over camera roll | `ui/gallery/GalleryScreen.kt`: MetroPivot "Gallery / Videos" over MediaStore queries (`data/MediaStoreRepository.kt`) |
| Thumbnail wall | fastest scan overview | 3-up flat wall with 1 px separation, coil thumbnails, duration chip on clips |
| Detail viewer | single image browsing | `HorizontalPager` full-bleed viewer with position counter |
| Video playback | trimmer + playback shell | `VideoPlayback.kt`: MediaPlayer → SurfaceView, tap pause/play (trimmer lands in Phase 5) |
| Details sheet | EXIF/facts surface | InfoSheet: name, dimensions, file size, capture time, kind |
| Delete flows | delete with confirmation | flat flat confirm → direct delete for app-owned rows, API-30+ `createDeleteRequest` otherwise |
| Share | system share sheet | `ACTION_SEND` via `createChooser` |
| Open goal | send to editors/heavy editors later | "Edit later" chip reserved (external-editor chooser in Phase 3+) |
| Viewfinder entry | thumb → library | "Library" HUD chip + grid/lens chips |
| Wheel additions | EV + manual focus + zoom | EV stop labels (± in float e.g. EV+0.3), diopter↔meters focus stops, 0.1× zoom ladder; all plumbed through `ManualMapping.kt` |
| Session API | modern application | migrated preview session to `SessionConfiguration` + dedicated executor |

## Still-open Phase 2 items
- Grid type wheel target (currently HUD cycles grid — Add wheel `Param.GRID` value list) – minor
- Coordinate system result code for the delete PendingIntent result is hardcoded (7100) — add an `ActivityResultLauncher` decoupler before release.

## Phase 3 status (Smart-mode computational core)

Algorithms below are written from first principles (block matching + trimmed
temporal statistics); no computational-photography technique is copied from any
proprietary implementation.

| Feature | Source behavior | Nordlys implementation |
|---|---|---|
| Burst acquisition | quick frame grab while the scene streams | `CameraEngine.captureYuvBurst(7)`: dedicated `YUV_420_888` analysis stream (≤1.3 MP) in the three-output session, sequential capture-awaits-image |
| Frame compaction | normalized luma/chroma planes | `smart/YuvFrame.kt` stride/pixel-stride normalization + NV21 assembly for JPEG emission |
| Frame alignment | crop-to-fit geometric correction | `smart/BlockAligner.kt`: 2-stage 16×16 block SAD (coarse half-res ±6 → fine full-res ±2) |
| Frame fusion | rule-of-stability (hold-steady effect) | `smart/TemporalMerge.kt`: per-pixel median of the aligned contributor stack rejects grain and ghost pixels; chroma from reference |
| Orchestration | background running under UI | `smart/SmartProcessor.kt` on `Dispatchers.Default` with align/fuse phase callbacks |
| UX feedback | keep-steady caption during smart capture | viewfinder overlay: "Keep still" (capture/align) → "Fusing frames" (merge) → "Saved" chip |
| Smart pivot activation | smart capture button fires pipeline | `ShutterButton` mode==1 path wired end-to-end |

**Fixed in this phase:** the Phase-1 still path had its JPEG reader surface
outside the session — now the session is honestly three-stream
(preview + JPEG-still + YUV-analysis) and `captureStill` reads the configured
reader.

## Phase 4 status (Living Images, skeleton)

| Feature | Source behavior | Nordlys implementation |
|---|---|---|
| Live still at a time | motion-capped photo capture | `CameraEngine.captureLivingStill`: pre-press ring snapshot (6 frames) + JPEG still + post-press collection (6 frames) |
| Motion ring | continuously warmed analysis frames | analysis stream targets armed only when motion toggle is ON (`setLivingEnabled`), frames indexed with capture timestamps |
| Sidecar storage | motion pairs attach to stills | `living/LivingWriter`: app-private `living/<id>/` JPEG frame set + cadence manifest, `living/index.json` maps gallery URI → sidecar |
| Motion playback | viewer array clip playback | `ui/gallery/LivingPlayback`: chronological cycling at measured cadence, tap pauses; static fallback when no sidecar |
| Delete pairing | sidecar removal with photo removal | viewer delete removes the sidecar directory and index entry with the media row |

## Phase 5 status (Video mode, first pass)

| Feature | Source behavior | Nordlys implementation |
|---|---|---|
| Clip recording | video pivot | `video/VideoRecorder`: platform MediaRecorder surface-input mode; encoder surface joins a dedicated preview-only session on the RECORD template |
| Start/stop | record/stop control | Video pivot hides photo controls; the ring switches to pressed state while recording, "Record/Stop" chip mirrors it |
| Time code | duration display | elapsed counter chip ticking at 500 ms while recording |
| Torch as video light | light continues through video | `FlashMode.TORCH` is carried by the RECORD request (`applyManualState`) |
| Audio track | microphone when allowed | AAC encoder included only when mic permission granted (`MainActivity` now bundles CAMERA + RECORD_AUDIO) |
| Output | clip in camera roll | streamed into a MediaStore-pending mp4 row; landed on stop (broken/failed starts are deleted) |
| Resolution | 1080p default | platform quality read via `CamcorderProfile.get(camera, 10)` with an equivalent manual fallback (deprecated stub ctor avoided) |
| Post-stop restore | preview returns | 3-stream capture session rebuilt after recording; readers recycled safely |

## Phase 6 status (RAW/DNG, bracketing, timer, settings)

| Feature | Source behavior | Nordlys implementation |
|---|---|---|
| DNG stills | RAW capture for advanced uses | `CameraEngine.captureStill(state, withRaw)`: RAW sensor stream in a dedicated 3-stream capture session, `DngCreator` over the capture result, `.dng` media row beside the JPEG |
| Dual capture | one press, JPEG + DNG | `captureFormat` chip ("JPEG only / JPEG + DNG", featured when `REQUEST_AVAILABLE_CAPABILITIES_RAW`) |
| Bracketing | count + exposure range league | `captureBrackets()`: ±EV ladder around the wheel center; AE-bias path and manual shutter-scaling path (2^k-stops); each frame saved individually |
| Self-timer | seconds, per-capture | timer chip (No delay / 3 s / 10 s) + countdown overlay |
| Settings sheet | about, copy-state reset | `ui/settings/SettingsSheet.kt`: About facts, "Reset now" covering wheel/format/timer/lens states |

## Phase 6 tracked gaps
- DNG orientation currently 0 for snapshot simplicity; lens-orientation decorrelation lands with the gallery edit pass.
- Bracket uses sequential capture (AE bias restart cost noted) — could pair with a burst session later.
- Settings sheet shows About facts; preview quality and defaults-apply toggle coming with the gallery edit pass.

## Phase 5 tracked gaps
- No frame-rate/resolution user setting yet (locked to the resolved profile).
- Torch-only video light: flash strobe state intentionally not applied during clip recording.
- Trim entry (viewer chip) is still a placeholder until the muxer path lands.

## Phase 4 tracked gaps
- Motion window is pre-roll + post-roll bounded by analysis fps (surround interval roughly 0.4 to 0.8 s) — extend the ring depth when GPU heat budget allows.
- `clipFor` is called during composition; move load off the main path when the on-device trace reaches the gallery.
- Sidecar frames are JPEG-encoded; frame-level raw storage doubles size — decision deferred until Phase 6 (RAW).
- Alignment is block-translational only (no rotation/scale); plan pyramid-based
  motion model for Phase 3.1 polish.
- Single-threaded merge; parallelize per-row ranges when burst sizes grow.
- Burst uses auto exposure (AE-ON) as designed for merge input — manual wheel
  is intentionally ignored in Smart mode (mirrors source behavior).

## Beyond v0.6 (candidate order, subject to feedback)
1. Phase 7 — Smart pipeline quality pass: pyramid motion model, parallel merge, timed device runs.
2. Phase 8 — Face detection hint + tracker overlay, exposure/status bar, thermal guardrails.
3. Phase 9 — Video trim + first-run flow, localization pass (strings grid), release build config.
