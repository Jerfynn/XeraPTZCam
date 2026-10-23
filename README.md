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

## 3. Use (v3.0 – two camera models)

Choose the camera in Settings → CAMERA MODEL (dropdown). Each model keeps its own IP / ports / streams.

### SHJ-TW10-IR (ONVIF)
- Rail: PRESETS · NIGHT (hold = AUTO) · WIPE · PHOTO · REC · HOME (hold = save) · GALLERY · SETTINGS
- Presets are stored in the camera: aim, then hold a preset on the dial to save.

### SIYI ZT6 (SIYI SDK over UDP, default 192.168.144.25:37260)
- Video: rtsp://192.168.144.25:8554/video1 (main), /video2 (second) – hold VIEW to switch streams.
- Rail: PRESETS · VIEW (RGB / THERMAL / SPLIT) · PALETTE (11 thermal palettes) · PHOTO · REC ·
  CENTER · MODE (LOCK / FOLLOW / FPV) · GALLERY · SETTINGS
- HUD: live compass (F/R/B/L) and tilt scale from the gimbal's attitude; thermal ▲max ▼min °C in the status strip.
- Presets work immediately: FRONT 0°, RIGHT 90°, REAR 180°, LEFT −90°, UP +25°, DOWN −90°.
  Hold a preset to store the current angles instead. The app steers the gimbal there (closed loop).
- REC records MP4 in the app AND (optional) on the gimbal SD card. PHOTO saves on the gimbal SD + a frame in the app.
- Settings → "Show gimbal debug line" displays TX/RX packets for troubleshooting.

### Common
- Swipe = pan/tilt, pinch or ZOOM rocker = zoom, tap video = show/hide controls.
- Gamepad: left stick pan/tilt, right stick zoom, LB/RB zoom, A = REC, X = PHOTO,
  Y = HOME / CENTER, B = NIGHT / PALETTE.
- Requires Android 10 or newer.

Fonts: Rajdhani and JetBrains Mono (SIL Open Font License, see fonts-license/).

## Notes

- The G20's physical sticks/wheel are sent over Skydroid's RC link to the GR01's SBUS/PWM output,
  not to Android, so the app uses the on-screen stick or a gamepad. To use the G20's own gimbal
  wheel, either ask Skydroid for their SDK, or read the GR01 SBUS output with a Teensy/ESP32 and
  drive the camera's RS485 input with Pelco-D.
- If recording reports "not available", change libvlc-all in app/build.gradle.kts to 3.6.0 and rebuild,
  or record on the camera's SD card (Storage menu).
