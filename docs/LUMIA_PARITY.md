# Nordlys ↔ reference camera: parity spec and gap analysis

This document is the cleanroom hand-off between analysis and implementation.
It records **observable behavior, geometry, timing and value ladders** of the
reference package (XAP `4.10.0.6`) as measured from its layout descriptions
and control logic. It contains no reference code, assets, icons or UI strings;
Nordlys implements every item below from scratch (Compose + Camera2), draws its
own glyphs, and writes its own copy.

Coordinates are given in the reference's design frame: a **landscape 800 × 480**
canvas with the shutter on the right edge, vertically centred at y = 240. The
reference fixes this frame to the device and, when the phone is held upright,
rotates the whole frame 90° (shutter at the bottom-centre) while individual
glyphs counter-rotate to stay upright (0.5 s, exponential ease-in-out).

---

## 1. Viewfinder layout

| Element | Reference behavior |
|---|---|
| Frame | Black page, preview letter-boxed to the capture aspect (4:3 default, 16:9 option); system chrome hidden |
| Shutter | Right edge centre (bottom-centre when upright); see §2 |
| Indicator strip | Pill centred on the long edge (top in landscape, right edge when upright); 50 px tall, corner radius 25, black @ 40 %; slides in/out 0.3 s exp-out (offset −72 → 0) |
| Gallery button | Corner at the far end of the strip edge (top-left in landscape → top-right upright); 48 px glyph in a circle; after capture it shows the newest photo |
| Menu button | "…" glyph at the other corner of that edge (top-right in landscape → bottom-right upright); opens a slide-up menu (0.2 s exp-out, panel 450 high) |
| Info text | Large light text (≈60 pt) bottom-left; fades in 0.25 s, holds 0.85 s, fades out 0.25 s; used for mode names and dial hints |
| Grid | Off / 3×3 / golden ratio / crosshair / square; white hairlines @ 70 % |
| Focus reticle | 126 px white ring (2 px) + 122 px translucent track + 14 px rounded square centre; 11 px accent progress arc; blinks (0.1 s, 1 → 0.1) while focusing; fades in 0.5 s / out 0.5 s after 0.2 s |
| Shutter iris | On capture a black ring closes from the screen edge inward over 0.25 s (cubic-out) and reopens over 0.25 s |
| Capture confirmation | Captured frame slides up from the bottom (0.4 s quintic-out) over a fading black scrim, then flies to the gallery button |

## 2. Shutter + mode wheel

- Each mode is a 76 px disc: black @ 30 % fill, 3.25 px white ring, centred glyph.
- Modes sit on a large wheel whose centre is **70.6 px outside** the shutter edge
  (radius 150.6). The selected mode sits on the edge at the centre; neighbours
  sit ±0.56 rad around the wheel at 63 % scale; anything beyond ±2 is hidden.
- Order along the wheel: **video · photo · smart** (photo is default).
- **Drag along the edge** (vertical in landscape) rotates the wheel 1 slot per
  70 px; release snaps to nearest (0.15 s exp-out). Tapping a neighbour selects it.
  Mode switching is disabled while the pro dials are open.
- **Flick inward** (toward the preview, 25 px travel, >50 % committed) toggles the
  pro dials ("all rings") open/closed. When already open, dragging past −50 %
  further requests **reset all manual values to auto**.
- Pressed state fills the disc with the accent colour. Holding ≥150 ms counts as
  a half-press (focus first, then capture on release).
- A 70 px accent progress arc (3 px) wraps the shutter for timed operations:
  self-timer countdown (start angle 0→360 over the delay), smart burst (2.55 s).

## 3. Pro dials ("rings")

Five concentric arc sliders all centred on the shutter-edge midpoint.

| Ring (inner → outer) | Outer radius | Values |
|---|---|---|
| Exposure compensation | 294 | −max…+max in 1/3 EV steps; 0.0 is shown dimmed; quarter ticks |
| Shutter speed | 394 | `auto` + photographic ladder 1/16000 … 30 s filtered to device range; quarter ticks |
| ISO | 494 | `auto` + 50,100,200,400,800,1600,3200,4000,6400,10000,16000 filtered to device; quarter ticks |
| Focus | 594 | `auto` + 5 continuous manual stops (near → far) + ∞ preset; first/last ticks only |
| White balance | 694 | `auto` + daylight, cloudy, fluorescent, tungsten (glyphs); every value ticked |

Ring anatomy: band thickness 100; band fill black @ 40 %; a 1 px white @ 70 %
line on the band's inner edge; the **thumb** (42 px disc, black, glyph of the
parameter; turns accent while touched) rides the inner edge; small 10 pt tick
labels sit just inside the inner edge; the **current value** (24 pt bold, or a
56 px glyph) is drawn inside the band on the centre axis and cross-slides 30 px
with an ease-in-out as the value changes. The usable sweep is limited so the
thumb stays 5 px inside the visible short edge; the band itself spans the full
short edge.

Interaction: drag anywhere on a band or its thumb; angular delta maps linearly to
index; values snap except inside a continuous range (focus manual stops). Tapping
a band jumps the thumb there. During a drag the other rings fade to 0 (0.3 s) and
return on release.

Open/close: "bring in" sweeps the ring from twice its half-angle to 0 while fading
in (0.5 s exp-out); "bring out" sweeps the other way. Back closes the rings.

Single-ring mode: tapping an indicator in the strip opens just that ring at the
innermost radius (294); tapping again (or the preview) closes it; the info text
names the parameter.

## 4. Indicator strip

- **Basic** (default): flash (auto → off → on) and scene (auto → night → sport)
  as large glyphs, plus the expand arrow. Strip width 175.
- **Advanced**: flash, white balance, focus, ISO, shutter, EV — each a 50 px cell
  with glyph above and a 17 pt semibold value label (`auto`, `400`, `1/60`,
  `+0.3`…), plus the collapse arrow. Strip width 375 (−50 without focus).
- While in auto, ISO and shutter cells show the **live metered value** dimmed.
- A **red 3 px underline** marks risky values: ISO ≥ 800, shutter ≥ 0.5 s.
- Switching Advanced → Basic resets all manual values; the change cross-fades the
  cells (0.1 s out / 0.3 s in) and animates the pill width (0.2 s).
- Video mode: torch toggle, white balance, focus only.
- With one ring open, all cells except the open one are dimmed.

## 5. Capture modes

| Mode | Behavior |
|---|---|
| Photo | Tap = capture (AF if not converged). Optional Living Image (§8): a short H.264 clip from a rolling buffer that ends just after the press, stored beside the still. Dual capture: oversampled ~5 MP JPEG **plus** full-resolution JPEG, or plus DNG |
| Video | Shutter starts/stops; large time readout; torch light; 1080p/720p at 30/25/24 fps (§9) |
| Smart | One press captures a **sequence of 7–10 frames over ~2.5 s** (≈250–350 ms apart) with AE/AF locked, then opens an editor: **best shot**, **action shot** (stroboscopic overlay of a moving subject), **motion focus** (subject sharp, background streaked), **remove moving objects**, **change faces** |

Self-timer: 2 s / 5 s / 10 s through the menu, shown as the shutter progress arc.
Bracketing: 3 or 5 frames at ±0.3/0.7/1.0/2.0/3.0 EV via the menu.

## 6. Imaging character

- **Oversampling ("PureView")**: high-res sensor readout is averaged down to a
  smaller file, trading resolution for lower noise and cleaner edges; the large
  file is optional.
- Driver-level multi-frame denoise, chroma-noise filter, restrained sharpening,
  vibrance and red-eye removal are toggled per mode; the smart sequence disables
  them per frame (they are applied to the final composite instead).
- Scene modes: night (longer exposure ceiling), sport (short exposure bias).
- Manual ISO with auto shutter and vice-versa both work (semi-auto priority).

## 7. Library viewer

- A single tap (once it can no longer be a double-tap) shows or hides all
  chrome — header, actions, video transport — with a 0.3 s fade; pinching or
  panning a photo hides it.
- Clips do not autoplay: a 100 px ringed play button sits centred on the frame
  until playback starts, and the volume eases up from 20 % over 1.5 s.
- Transport (part of the chrome): 74 px ringed play/pause disc, then a 4 px
  timeline (white @ 40 % track, accent fill, 18 px white knob), then
  "elapsed / total". Scrubbing pauses playback and resumes it on release.
  *Nordlys choice:* play/pause lives only in the centred disc (shown while
  paused, and with the chrome while playing); the bottom keeps the timeline.
- While a clip plays, the chrome hides itself after 5 s without a touch.

## 8. Living Images

**Capture.** A back-camera-only photo option, off by default (a first-run page
offers to turn it on; accepting turns on both capture and playback).

- While the photo viewfinder is open and the option is on, the preview stream is
  encoded **continuously into a rolling buffer** (a native recorder fed by the
  viewfinder source). Nothing is written until a photo is taken.
- When the still capture *starts* (after any focus settle), recording is told to
  stop **150 ms later**; the buffered stretch therefore ends 150 ms after the
  exposure begins and holds the moment *before* the press. The orientation for
  the clip is sampled from the accelerometer at that same moment.
- When the frame is buffered the recording is *validated* (too little footage →
  no clip, the still is saved alone). The saving caption switches to a "saving
  living image" variant while it writes.
- The clip is saved as an MP4 beside the JPEG with the same base name and a
  hidden-to-the-gallery extension, plus a small thumbnail sidecar. Recording
  restarts right after the save.
- Recording is paused (and its buffer cleared) during bracketing and during a
  held-shutter sequence, and resumes when they end.
- The buffer length and bit-rate live in native code and are not visible to
  static analysis. *Nordlys choice:* **≈1.5 s before the capture start + 0.15 s
  after**, H.264 at the analysis-stream size (≤ 1440 × 1080, same aspect as the
  photo), 30 fps, key frame every 0.5 s.

**Playback.** A separate setting ("play living images", off by default) gates it.

- A photo counts as living only if its clip exists, the photo was not reframed or
  edited by another app, and (when edit status is unknown) the clip's file time
  is within **60 s** of the photo's.
- In the library viewer the photo is shown first; when it becomes the current
  item the related clip plays **once, muted, in place over the still** and the
  view settles back on the still (the clip is handed to the native viewer as a
  non-looping related video; the exact transition lives in native code). The viewer is told whether flash fired for
  the still (the clip, shot from the viewfinder, will not contain the flash).
  No play button is shown for living images.
- *Nordlys choice:* the clip starts when the pager settles on the photo, fades
  out over 0.3 s into the still at its end (a quicker 0.15 s cut when flash
  fired), and stops at once if the user swipes away or starts zooming. Swiping
  back plays it again.

## 9. Video

**Resolution / frame rate** (settings → videos, back camera only; the front
camera always records at its best size):

| Device class | Choices, in order | Default |
|---|---|---|
| 1080p capable | 1080p 30 · 1080p 25 · 1080p 24 · 720p 30 · 720p 25 · 720p 24 | 1080p 30 |
| 720p only | 720p 30 · 720p 25 · 720p 24 | 720p 30 |

The chosen rate is applied as a fixed sensor frame rate; continuous AF stays on.
*Nordlys:* a choice is offered only when the encoder and the camera can produce
that size and a fixed frame-rate range for it.

**Timer readout.**

- Large light white digits (60 pt in the 480 × 800 frame), `mm:ss`, switching to
  `h:mm:ss` after an hour; never hit-testable.
- Shown as `00:00` whenever the video viewfinder controls are visible, not only
  while recording. Entering video mode fades it in (0.25 s) after a ≈1 s delay;
  starting a recording shows it at once.
- Counts one second per tick while recording; stopping resets it to `00:00`.
- Hidden (0.25 s fade) while the pro dials are open or an info caption is
  showing; returns when they close.
- Placement: upright portrait → horizontally centred, 163 px above the bottom
  edge (just above the shutter); landscape → left-aligned, 18 px from the left
  and 38 px above the bottom.

**Trimming.** The library's edit action on a clip hands it to the platform's
video editor (or, on older systems, a separate trimmer app; without it a stub
page shows the clip's thumbnail dimmed to 70 % behind a large light title, a
short explanation and an install button). *Nordlys choice:* a built-in basic
trimmer opened from the viewer's "Edit later" on a clip — film strip with two
handles, preview of the selection, and "save copy", which writes a new clip
without re-encoding (the start snaps to the nearest earlier key frame); the
selection is at least 1 s and the strip is kept clear of the system back
gesture so the handles can be grabbed at the screen edges. The original is
never changed.

---

## Gap analysis (Nordlys v0.1.0 → this pass)

| Area | v0.1.0 | Gap | This pass |
|---|---|---|---|
| Overall look | Text chips ("No flash", "Back lens"), Material defaults | No resemblance | Rebuilt viewfinder per §1–4 with original glyphs |
| Orientation | Activity follows sensor; layout reflows | Reference keeps chrome fixed and rotates glyphs | Fixed frame + counter-rotating glyphs |
| Preview | Full-screen SurfaceView, buffer never sized | Stretched preview | Sized buffer, aspect-fit 4:3 / 16:9 |
| Shutter | Plain ring button + pivot tabs | No mode wheel, no flick-to-pro | §2 wheel, flick, reset, progress arc |
| Manual controls | Full-screen chip grids | No concentric dials | §3 rings, single-ring + all-ring modes |
| Indicators | — | No strip, no live readouts, no danger marks | §4 strip, basic/advanced |
| Focus | No tap-to-focus | | Tap AF/AE regions + reticle |
| Semi-auto | Manual ISO ignored under AE; shutter likewise | Broken | Priority exposure from live metering |
| Flash | off/on/torch | No auto | auto/off/on, torch in video |
| Scene modes | — | | auto / night / sport |
| JPEG orientation | Not set (sideways files) | Bug | Device-orientation aware |
| Oversampling | — | Core of the image character | Oversampled default + optional full-res |
| Smart mode | 7-frame temporal median → one JPEG | Different feature | Timed sequence + editor (best shot, remove objects, action shot, motion focus) |
| Change faces | — | Needs face pipeline | **Deferred** |
| Living Images | First pass (≈12 JPEG frames cycled) | Not a clip; no playback gate | §8: rolling H.264 buffer → MP4 sidecar; plays once when swiped to; playback setting |
| Video | Works | Indicator/torch UI; no quality setting, small timer, no trim | Wheel + torch cell; §9 resolution/fps, timer readout, built-in trimmer |
