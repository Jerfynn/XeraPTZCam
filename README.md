# XeraCAM – Android app for SHJ-TW10 on Skydroid G20

Live RTSP video, PTZ control (on-screen joystick + USB/Bluetooth gamepad) and recording,
running on the Skydroid G20 ground controller (Android 13) through the GR01 link.

## 1. Network setup (do this first)

The Skydroid link bridges Ethernet on the 192.168.144.x subnet. The camera must be on that subnet.

1. With the camera still connected to your PC, open http://192.168.1.68 →
   Configuration → Device → Network → TCP/IP and set:
   - IPv4 Address: 192.168.144.68
   - Subnet Mask: 255.255.255.0
   - Gateway: 192.168.144.1
   Save. (Avoid 192.168.144.10 / .11 / .12 – Skydroid uses those for the link units.)
2. Connect the camera's Ethernet to the GR01 Ethernet port and power both.
3. To reach the camera from the PC again, set the PC's Ethernet to 192.168.144.50 / 255.255.255.0.

Recommended for a 30 km radio link (Video/Audio page):
- Max. Bitrate: 2048 Kbps (or use the Sub Stream), Bitrate Type: Constant
- I Frame Interval: 50 (currently 263 – long recovery after a dropout)

## 2. Build and install

1. Open this folder in Android Studio (Ladybug or newer) and let Gradle sync.
2. On the G20: Settings → About → tap "Build number" 7 times → Developer options → USB debugging ON.
3. Connect the G20 to the PC with USB-C, select it in Android Studio, press Run.
   Or: Build → Build APK(s), copy app-debug.apk to the G20 and install it.

## 3. Use (v3.1)

Choose the camera in Settings → CAMERA MODEL. Each model keeps its own IP / ports / streams / button mapping.

### Screen layout
- Top-left: status strip (link, bitrate, REC timer, temperature, IR, battery, clock). Top-right: mode chip
  (PATROL / TRACKING – tap to stop), GALLERY and SETTINGS.
- Right rail – SHJ-TW10: PRESETS · TRACK · NIGHT · WIPE · PHOTO · REC · HOME
- Right rail – ZT6:     PRESETS · TRACK · VIEW · PALETTE · PHOTO · REC · CENTER · MODE
- Left: ZOOM rocker. Tap the video to show / hide the controls (they auto-hide after 4 s).

### Patrol / scan (both cameras)
- PRESETS → START PATROL. Visits the presets chosen in Settings → Patrol (order FRONT → RIGHT → REAR →
  LEFT → UP → DOWN), pausing at each (default 5 s), looping if "Repeat" is on.
- Any swipe, joystick input, or the PATROL chip stops it.
- SHJ-TW10 visits only presets saved on the dial. ZT6 presets work immediately (angles).

### Active tracking (both cameras)
- Press TRACK, then draw a box around the target (or tap it). The camera keeps it centred.
- Cyan box = tracking, amber = searching. Lost for 3 s → stops and tells you. Swipe or TRACK to stop.
- Settings → Tracking response: lower = smoother, higher = faster.
- Works best on objects with visible detail; thermal view is excellent for people / animals.

### Tap-to-aim (ZT6 only)
- Double-tap any point of the video: the gimbal turns so that point becomes the centre.
- Uses the field of view (Settings → Tap-to-aim: visible 84.5°, thermal 32.8°) and the current zoom.

### Button mapping (both cameras)
- Settings → BUTTON MAPPING: tap SET, press a G20 / gamepad button. ✕ clears, RESET restores defaults.
- Actions: zoom in/out (hold), photo, record, presets, patrol, tracking, plus HOME / NIGHT / WIPE (SHJ)
  or CENTER / VIEW / PALETTE / MODE (ZT6).
- Defaults: R1/L1 zoom, A record, X photo, Y home/centre, B night/palette, START patrol, SELECT tracking.

### Common
- Swipe = pan/tilt, pinch or ZOOM rocker = zoom. Recording → Movies/XeraCAM, photos → Pictures/XeraCAM.
- Requires Android 10 or newer.

Fonts: Rajdhani and JetBrains Mono (SIL Open Font License, see fonts-license/).

## Notes

- The G20's physical sticks/wheel are sent over Skydroid's RC link to the GR01's SBUS/PWM output,
  not to Android, so the app uses the on-screen stick or a gamepad. To use the G20's own gimbal
  wheel, either ask Skydroid for their SDK, or read the GR01 SBUS output with a Teensy/ESP32 and
  drive the camera's RS485 input with Pelco-D.
- If recording reports "not available", change libvlc-all in app/build.gradle.kts to 3.6.0 and rebuild,
  or record on the camera's SD card (Storage menu).
