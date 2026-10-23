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

## 3. Use

- First launch opens Settings: camera IP, port 80, admin + your password.
- Swipe on the video: camera follows the swipe; further = faster, flick = short burst. Pinch = zoom.
- Joystick (bottom-left): pan/tilt.
- ZOOM + / ZOOM − (hold): zoom in / out.
- NIGHT: tap toggles night (IR, black-and-white) on/off; hold = AUTO (camera decides).
- PHOTO: full-resolution JPEG from the camera (falls back to a screen grab) → Pictures/XeraCAM.
- REC: start/stop video recording → Movies/XeraCAM.
- HOME: tap = go to home position; hold = save the current view as home.
- Presets FRONT / REAR / LEFT / RIGHT / UP / DOWN: aim the camera, hold the button to save
  (it turns blue), then tap to return. "Clear presets" in Settings resets them.
- Gamepad: left stick pan/tilt, right stick zoom, LB/RB zoom, A = REC, X = PHOTO, Y = HOME, B = NIGHT.
- Video auto-reconnects after link dropouts; recording resumes automatically.

## Notes

- The G20's physical sticks/wheel are sent over Skydroid's RC link to the GR01's SBUS/PWM output,
  not to Android, so the app uses the on-screen stick or a gamepad. To use the G20's own gimbal
  wheel, either ask Skydroid for their SDK, or read the GR01 SBUS output with a Teensy/ESP32 and
  drive the camera's RS485 input with Pelco-D.
- If recording reports "not available", change libvlc-all in app/build.gradle.kts to 3.6.0 and rebuild,
  or record on the camera's SD card (Storage menu).
# XeraPTZCam
