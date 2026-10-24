# XeraCAM – Android app for SHJ-TW10 on Skydroid G20

Live RTSP video, PTZ control (on-screen joystick + USB/Bluetooth gamepad) and recording,
running on the Skydroid G20 ground controller (Android 13) through the GR01 link.

## 1. Network setup (do this first)

The Skydroid link bridges Ethernet on the 192.168.144.x subnet. The camera must be on that subnet.

1. With the camera still connected to your PC, open http://192.168.1.68 →
   Configuration → Device → Network → TCP/IP and set:
   - IPv4 Address: 192.168.144.68
   - Subnet Mask: 255.255.255.0
   - Gateway: 192.168.144.10 (required for G20 video sharing)
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
- The target label shows the match score and the tracking frame rate (FPS).
- ZT6: corrections are scaled automatically to the active lens (visible / thermal) and the zoom level,
  so tracking behaves the same in thermal view and when zoomed in.

- v3.1.6 tracking rework:
  - ZT6: steers by gimbal ANGLE. Each frame is turned into the target's absolute angle using the gimbal
    angle at the moment that frame was filmed (video delay compensated), then the gimbal is driven there
    with its live angle reading. A still target → the gimbal settles and stops (no overshoot / run-away).
  - Other cameras: gentle proportional control, no integral term, stop zone with hysteresis.
  - The camera only moves on confident matches. Weak, look-alike or jumping matches hold the camera still
    and keep the last good position, so it no longer chases wrong detections.
  - Split view (visible | thermal side by side) is detected automatically: the target is tracked and
    centred within its own half.
  - Settings → "Tracking: video delay" (0 = automatic). If the gimbal still overshoots a still target,
    raise it in steps of 100 ms; if it reacts sluggishly, lower it.

### Tap-to-aim (ZT6 only)
- Double-tap any point of the video: the gimbal turns so that point becomes the centre.
- Uses the field of view (Settings → Tap-to-aim: visible 84.5°, thermal 32.8°) and the current zoom.

### Video transport
- "RTSP over TCP" (default, recommended for Skydroid / any radio link): lost packets are re-sent and the
  RTSP session stays alive. UDP has slightly less delay but drops on lossy links.
- If a live UDP stream drops twice within a minute, the app switches to TCP automatically and saves it.
- Large 4K key frames (ZT6) get a 2 MB receive buffer so they are no longer truncated / corrupted.
- v3.1.4: the stall watchdog no longer relies on LibVLC's input byte counter (always 0 for RTSP, which
  caused "0 kbps" and a false "Video stalled – reconnecting…" every 6 s). It now watches playback time,
  demuxed bytes and decoded/displayed frames, and only reconnects after 12 s with no video at all.
  Late frames are dropped to keep the live picture from accumulating delay, and recordings are no
  longer cut into pieces by false reconnects.

### Low latency (v3.1.5)
- Video buffer default is 50 ms and applies to VLC's network, live, and RTSP caches (Settings → Video buffer; raise it if the link stutters).
- Frames are shown the moment they are decoded; late frames are dropped so the delay cannot grow.
- "Auto resync when the video delay builds up" (on by default): if the picture falls more than ~0.6 s
  behind its recent baseline for two checks (backlog on the radio link), the stream is re-opened to catch up. Skipped while recording
  or tracking.
- Biggest remaining factor is the camera bitrate vs. the radio link: use 1080p / the second stream,
  2–4 Mbps, and a short I-frame interval for the lowest delay.

### Joystick movement
- Settings → JOYSTICK MOVEMENT has four learnable directions: left, right, up, and down. Tap LEARN,
  release the stick, then push it in the shown direction. Reverse and clear controls are available for each.
- On a Skydroid G20, the app reads the built-in sticks through Skydroid RCSDK. Open settings and use
  LEARN once for each direction; the live channel monitor shows the RCSDK channel values.
- On other Android devices, standard Android joystick axes remain supported.

### View the camera stream on a laptop
- Settings → VIDEO SHARING shows the current camera RTSP URL and opens the G20 hotspot settings.
- Connect the laptop to the G20 WLAN hotspot (the same external Wi-Fi alone does not share the camera's
  wired stream), then open the copied URL in VLC (Media → Open Network Stream).
- The camera must use a 192.168.144.x address with gateway 192.168.144.10. G20 system software
  20241130 or newer is required for image sharing.

### Skydroid controls
- Settings → CONTROL → Joystick: learn left/right/up/down from the four gimbal movement channels.
- Learn the Zoom slider by tapping LEARN on Zoom, then move the G20 slider toward zoom in. Use REV if
  the direction is backwards.
- Under REMOTE BUTTONS, learn Recording, Capture photo, Wipe, IR Auto, and Home by pressing each G20
  button or switch. The app learns either RC channel switches or Android key buttons.
- When an SHJ camera connects over ONVIF, XeraCAM synchronizes its embedded video date/time watermark
  to the G20 clock and timezone. The ONVIF status shows whether the camera accepted the sync.

### Common
- Swipe = pan/tilt, pinch or ZOOM rocker = zoom. Recording → Movies/XeraCAM, photos → Pictures/XeraCAM.
- Requires Android 10 or newer.

Fonts: Rajdhani and JetBrains Mono (SIL Open Font License, see fonts-license/).

## Notes

- The G20 built-in sticks are read using Skydroid RCSDK 1.9.2. Close other Skydroid apps that use
  the controller connection if the live RCSDK channel monitor stays empty.
- If recording reports "not available", change libvlc-all in app/build.gradle.kts to 3.6.0 and rebuild,
  or record on the camera's SD card (Storage menu).
