package com.xera.ptzcam

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var libVlc: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var swipePad: SwipePadView
    private lateinit var flashView: View
    private lateinit var statusText: TextView
    private lateinit var recText: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnNight: Button
    private val presetButtons = LinkedHashMap<String, Button>()

    private lateinit var prefs: SharedPreferences
    private lateinit var settings: CamSettings
    private lateinit var ptz: PtzController

    private val ui = Handler(Looper.getMainLooper())
    private var onvifJob: Job? = null
    private var onvifRtsp: String? = null

    private var playerStarted = false
    private var wantPlaying = false
    private var isLive = false
    private var streamStatus = "Video: waiting"
    private var ptzStatus = "PTZ: connecting…"
    private var lastKey = ""

    private var recording = false
    private var recDir: File? = null
    private var recStartMs = 0L
    private var photoBusy = false

    /** Camera IR-cut state: "ON" = day, "OFF" = night, "AUTO", null = unknown. */
    private var irCut: String? = null

    private var lastBytes = -1
    private var stallCount = 0

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        prefs = getSharedPreferences("cam", MODE_PRIVATE)
        settings = CamSettings.load(prefs)

        videoLayout = findViewById(R.id.videoLayout)
        swipePad = findViewById(R.id.swipePad)
        flashView = findViewById(R.id.flashView)
        statusText = findViewById(R.id.statusText)
        recText = findViewById(R.id.recText)
        btnRecord = findViewById(R.id.btnRecord)
        btnNight = findViewById(R.id.btnNight)

        libVlc = LibVLC(
            this,
            arrayListOf("--clock-jitter=0", "--clock-synchro=0", "--drop-late-frames", "--skip-frames")
        )
        player = MediaPlayer(libVlc)
        player.setEventListener(MediaPlayer.EventListener { ev ->
            val type = ev.type
            runOnUiThread { onPlayerEvent(type) }
        })

        ptz = PtzController(lifecycleScope) { msg ->
            runOnUiThread {
                ptzStatus = if (msg == "ready") "PTZ ready" else "PTZ: $msg"
                updateStatus()
            }
        }
        ptz.maxSpeed = settings.maxSpeed

        // swipe / pinch on the video
        swipePad.invert = settings.swipeInvert
        swipePad.onPanTilt = { x, y -> ptz.swipePan = x; ptz.swipeTilt = y }
        swipePad.onZoom = { z -> ptz.swipeZoom = z }

        // joystick
        findViewById<JoystickView>(R.id.joystick).onMove = { x, y ->
            ptz.stickPan = shapeAxis(x)
            ptz.stickTilt = shapeAxis(y)
        }

        // right-hand buttons
        holdZoom(findViewById(R.id.btnZoomIn), ZOOM_SPEED)
        holdZoom(findViewById(R.id.btnZoomOut), -ZOOM_SPEED)
        btnNight.setOnClickListener { setNight(if (irCut == "OFF") false else true) }
        btnNight.setOnLongClickListener { setNight(null); true }
        findViewById<Button>(R.id.btnPhoto).setOnClickListener { takePhoto() }
        btnRecord.setOnClickListener { toggleRecord() }
        findViewById<Button>(R.id.btnHome).apply {
            setOnClickListener { goHome() }
            setOnLongClickListener { saveHome(); true }
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }

        // presets
        presetButtons["FRONT"] = findViewById(R.id.presetFront)
        presetButtons["REAR"] = findViewById(R.id.presetRear)
        presetButtons["LEFT"] = findViewById(R.id.presetLeft)
        presetButtons["RIGHT"] = findViewById(R.id.presetRight)
        presetButtons["UP"] = findViewById(R.id.presetUp)
        presetButtons["DOWN"] = findViewById(R.id.presetDown)
        presetButtons.forEach { (name, b) ->
            b.setOnClickListener { gotoNamedPreset(name) }
            b.setOnLongClickListener { saveNamedPreset(name); true }
        }
        refreshPresetButtons()
        updateNightButton()
        updateStatus()

        if (settings.pass.isEmpty()) ui.post { showSettings() }
    }

    override fun onStart() {
        super.onStart()
        player.attachViews(videoLayout, null, false, false)
        ptz.start()
        connectOnvif()
        ui.postDelayed(watchdog, 3000)
    }

    override fun onStop() {
        super.onStop()
        if (recording) stopRecording()
        ui.removeCallbacks(watchdog)
        onvifJob?.cancel()
        ptz.halt()
        stopStream()
        player.detachViews()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        player.setEventListener(null)
        player.release()
        libVlc.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ------------------------------------------------------------------ ONVIF connection

    private fun connectOnvif() {
        onvifJob?.cancel()
        val s = settings
        ptzStatus = "PTZ: connecting…"
        updateStatus()

        onvifJob = lifecycleScope.launch {
            while (isActive) {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val c = OnvifClient(s.host, s.port, s.user, s.pass)
                        val uri = c.connect()
                        Pair(c, uri)
                    }
                }
                val ok = result.getOrNull()
                if (ok != null) {
                    ptz.client = ok.first
                    onvifRtsp = ok.second
                    ptzStatus = "PTZ ready"
                    updateStatus()
                    if (!playerStarted) playStream()
                    readNightState(ok.first)
                    return@launch
                }
                ptzStatus = "PTZ: " + (result.exceptionOrNull()?.message ?: "failed") + " (retrying)"
                if (!playerStarted && s.rtspOverride.isNotBlank()) playStream()
                updateStatus()
                delay(5000)
            }
        }
    }

    private fun client(): OnvifClient? {
        val c = ptz.client
        if (c == null) toast("Camera not connected yet")
        return c
    }

    // ------------------------------------------------------------------ home position

    private var homeMode: String
        get() = prefs.getString("homeMode", "home") ?: "home"
        set(v) { prefs.edit().putString("homeMode", v).apply() }

    private var homePresetToken: String
        get() = prefs.getString("homeToken", "1") ?: "1"
        set(v) { prefs.edit().putString("homeToken", v).apply() }

    /** HOME: camera's home position (or the saved HOME preset on cameras with a fixed home). */
    private fun goHome() {
        val c = client() ?: return
        val usePreset = homeMode == "preset"
        val token = homePresetToken
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (usePreset) c.gotoPreset(token) else c.gotoHome()
                }.recoverCatching {
                    if (usePreset) c.gotoHome() else c.gotoPreset(token)
                }
            }
            toast(if (r.isSuccess) "Going to HOME" else "HOME failed: ${r.exceptionOrNull()?.message}")
        }
    }

    /** Long-press HOME: store the current view as home. */
    private fun saveHome() {
        val c = client() ?: return
        val token = homePresetToken
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { c.setHome(); "home" to token }
                    .recoverCatching { "preset" to c.setPreset(token, "HOME") }
                    .recoverCatching { "preset" to c.setPreset(null, "HOME") }
            }
            r.onSuccess { (mode, tok) ->
                homeMode = mode
                homePresetToken = tok
                toast("HOME position saved")
            }.onFailure { toast("Could not save HOME: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------ presets

    private fun presetToken(name: String): String? = prefs.getString("preset_$name", null)

    private fun gotoNamedPreset(name: String) {
        val token = presetToken(name)
            ?: return toast("$name not set yet – aim the camera, then press and hold $name")
        val c = client() ?: return
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { c.gotoPreset(token) } }
            toast(if (r.isSuccess) "→ $name" else "$name failed: ${r.exceptionOrNull()?.message}")
        }
    }

    private fun saveNamedPreset(name: String) {
        val c = client() ?: return
        val token = presetToken(name) ?: DEFAULT_PRESET_TOKEN[name] ?: "20"
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { c.setPreset(token, name) }
                    .recoverCatching { c.setPreset(null, name) }
            }
            r.onSuccess {
                prefs.edit().putString("preset_$name", it).apply()
                refreshPresetButtons()
                toast("$name saved")
            }.onFailure { toast("Could not save $name: ${it.message}") }
        }
    }

    private fun refreshPresetButtons() {
        presetButtons.forEach { (name, b) -> b.isActivated = presetToken(name) != null }
    }

    // ------------------------------------------------------------------ night mode

    private fun readNightState(c: OnvifClient) {
        lifecycleScope.launch {
            val mode = withContext(Dispatchers.IO) { runCatching { c.getIrCutFilter() }.getOrNull() }
            if (mode != null) {
                irCut = mode
                updateNightButton()
            }
        }
    }

    /** on = true → night (IR), false → day (colour), null → auto. */
    private fun setNight(on: Boolean?) {
        val c = client() ?: return
        val mode = when (on) { true -> "OFF"; false -> "ON"; null -> "AUTO" }
        val lamp = when (on) { true -> "tt:IRLamp|On"; false -> "tt:IRLamp|Off"; null -> "tt:IRLamp|Auto" }
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                val filter = runCatching { c.setIrCutFilter(mode) }
                val aux = runCatching { c.auxCommand(lamp) }
                if (filter.isFailure && aux.isFailure) filter else Result.success(Unit)
            }
            r.onSuccess {
                irCut = mode
                updateNightButton()
                toast(
                    when (on) {
                        true -> "Night mode ON"
                        false -> "Night mode OFF"
                        null -> "Night mode AUTO"
                    }
                )
            }.onFailure { toast("Night mode not supported: ${it.message}") }
        }
    }

    private fun updateNightButton() {
        btnNight.text = when (irCut) {
            "OFF" -> "NIGHT\nON"
            "AUTO" -> "NIGHT\nAUTO"
            else -> "NIGHT\nOFF"
        }
        btnNight.isSelected = irCut == "OFF"
    }

    // ------------------------------------------------------------------ video

    private fun playStream() {
        val url = settings.rtspOverride.ifBlank { onvifRtsp ?: "" }
        if (url.isBlank()) {
            streamStatus = "Video: no RTSP URL (set it in ⚙)"
            updateStatus()
            return
        }
        wantPlaying = true
        playerStarted = true
        isLive = false
        lastBytes = -1
        stallCount = 0
        streamStatus = "Video: opening…"
        updateStatus()

        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=${settings.cachingMs}")
            if (settings.rtspTcp) addOption(":rtsp-tcp")
            if (!url.contains("@")) {
                addOption(":rtsp-user=${settings.user}")
                addOption(":rtsp-pwd=${settings.pass}")
            }
        }
        player.stop()
        player.setMedia(media)
        media.release()
        player.play()
    }

    private fun stopStream() {
        wantPlaying = false
        playerStarted = false
        isLive = false
        ui.removeCallbacks(retry)
        player.stop()
    }

    private val retry = Runnable { if (wantPlaying) playStream() }

    private fun onPlayerEvent(type: Int) {
        when (type) {
            MediaPlayer.Event.Playing -> {
                isLive = true
                stallCount = 0
                streamStatus = "● LIVE"
                // recording stops when the stream drops – resume it automatically
                if (recording) recDir?.let { player.record(it.absolutePath) }
            }
            MediaPlayer.Event.Buffering -> if (!isLive) streamStatus = "Video: buffering…"
            MediaPlayer.Event.EncounteredError, MediaPlayer.Event.EndReached -> {
                isLive = false
                streamStatus = "Video lost – reconnecting…"
                if (wantPlaying) {
                    ui.removeCallbacks(retry)
                    ui.postDelayed(retry, 2000)
                }
            }
        }
        updateStatus()
    }

    /** RTSP over a radio link can freeze without an error: restart if no bytes arrive for ~6 s. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (wantPlaying && isLive) {
                val m = player.media
                val bytes = m?.stats?.readBytes ?: -1
                m?.release()
                if (bytes >= 0 && bytes == lastBytes) {
                    if (++stallCount >= 2) {
                        stallCount = 0
                        isLive = false
                        streamStatus = "Video stalled – reconnecting…"
                        updateStatus()
                        player.stop()
                        ui.removeCallbacks(retry)
                        ui.postDelayed(retry, 500)
                    }
                } else {
                    stallCount = 0
                }
                lastBytes = bytes
            }
            ui.postDelayed(this, 3000)
        }
    }

    // ------------------------------------------------------------------ photo

    private fun takePhoto() {
        if (photoBusy) return
        if (!isLive && ptz.client == null) return toast("No camera connected")
        photoBusy = true
        flash()
        val c = ptz.client
        lifecycleScope.launch {
            // 1) full-resolution JPEG straight from the camera
            val jpeg = if (c != null) withContext(Dispatchers.IO) { runCatching { c.snapshot() }.getOrNull() } else null
            if (jpeg != null) {
                finishPhoto(jpeg)
                return@launch
            }
            // 2) fallback: grab the frame shown on screen
            grabFrame { bmp ->
                if (bmp == null) {
                    photoBusy = false
                    toast("Photo failed – no video frame")
                } else {
                    lifecycleScope.launch {
                        val bytes = withContext(Dispatchers.Default) {
                            ByteArrayOutputStream().use { out ->
                                bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                                out.toByteArray()
                            }
                        }
                        bmp.recycle()
                        finishPhoto(bytes)
                    }
                }
            }
        }
    }

    private suspend fun finishPhoto(jpeg: ByteArray) {
        val name = withContext(Dispatchers.IO) { runCatching { saveJpeg(jpeg) }.getOrNull() }
        photoBusy = false
        toast(if (name != null) "Photo saved: Pictures/XeraCAM/$name" else "Could not save photo")
    }

    private fun saveJpeg(jpeg: ByteArray): String {
        val name = "XeraCAM_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/XeraCAM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            contentResolver.openOutputStream(uri)?.use { it.write(jpeg) }
                ?: throw IllegalStateException("cannot open output")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
            File(dir, name).writeBytes(jpeg)
        }
        return name
    }

    private fun grabFrame(done: (Bitmap?) -> Unit) {
        val texture = findViews(videoLayout, TextureView::class.java).firstOrNull { it.isAvailable }
        if (texture != null) {
            done(texture.bitmap)
            return
        }
        val surface = findViews(videoLayout, SurfaceView::class.java)
            .filter { it.width > 0 && it.height > 0 }
            .maxByOrNull { it.width * it.height }
        if (surface == null || !surface.holder.surface.isValid) {
            done(null)
            return
        }
        val bmp = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surface, bmp, { result ->
                if (result == PixelCopy.SUCCESS) done(bmp) else { bmp.recycle(); done(null) }
            }, ui)
        } catch (e: Exception) {
            bmp.recycle()
            done(null)
        }
    }

    private fun <T : View> findViews(root: View, cls: Class<T>): List<T> {
        val out = ArrayList<T>()
        fun walk(v: View) {
            if (cls.isInstance(v)) out.add(cls.cast(v)!!)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun flash() {
        flashView.animate().cancel()
        flashView.alpha = 0.85f
        flashView.visibility = View.VISIBLE
        flashView.animate().alpha(0f).setDuration(250).withEndAction {
            flashView.visibility = View.GONE
        }.start()
    }

    // ------------------------------------------------------------------ recording

    private fun toggleRecord() = if (recording) stopRecording() else startRecording()

    private fun startRecording() {
        if (!isLive) return toast("Wait for live video before recording")
        val base = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(base, "rec_$stamp").apply { mkdirs() }

        if (player.record(dir.absolutePath)) {
            recording = true
            recDir = dir
            recStartMs = SystemClock.elapsedRealtime()
            btnRecord.isSelected = true
            btnRecord.text = "STOP"
            recText.visibility = View.VISIBLE
            ui.removeCallbacks(recTicker)
            ui.post(recTicker)
            toast("Recording started")
        } else {
            dir.delete()
            toast("Recording not available in this libVLC build")
        }
    }

    private fun stopRecording() {
        player.record(null)
        recording = false
        btnRecord.isSelected = false
        btnRecord.text = "REC"
        recText.visibility = View.GONE
        ui.removeCallbacks(recTicker)

        val dir = recDir ?: return
        recDir = null
        lifecycleScope.launch {
            delay(1500) // let libVLC finish writing the file
            val n = withContext(Dispatchers.IO) { exportToGallery(dir) }
            toast(if (n > 0) "Video saved to Movies/XeraCAM" else "Recording kept in ${dir.absolutePath}")
        }
    }

    private val recTicker = object : Runnable {
        override fun run() {
            val s = (SystemClock.elapsedRealtime() - recStartMs) / 1000
            recText.text = String.format(Locale.US, "● REC %02d:%02d", s / 60, s % 60)
            ui.postDelayed(this, 500)
        }
    }

    /** Moves finished recordings into the public Movies/XeraCAM folder (visible in Gallery / over USB). */
    private fun exportToGallery(dir: File): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        var count = 0
        dir.listFiles()?.filter { it.isFile && it.length() > 0 }?.forEach { f ->
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "${dir.name}_${f.name}")
                    put(MediaStore.Video.Media.MIME_TYPE, mimeFor(f))
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/XeraCAM")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@forEach
                contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                f.delete()
                count++
            } catch (_: Exception) {
                // leave the file in the app folder
            }
        }
        if (dir.listFiles().isNullOrEmpty()) dir.delete()
        return count
    }

    private fun mimeFor(f: File) = when (f.extension.lowercase(Locale.US)) {
        "mp4", "m4v" -> "video/mp4"
        "ts", "m2ts" -> "video/mp2t"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        else -> "video/mp4"
    }

    // ------------------------------------------------------------------ gamepad

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE) {
            var x = axis(event, MotionEvent.AXIS_X)
            var y = axis(event, MotionEvent.AXIS_Y)
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            if (hx != 0f || hy != 0f) { x = hx * 0.6f; y = hy * 0.6f }
            ptz.padPan = shapeAxis(x)
            ptz.padTilt = -shapeAxis(y)
            ptz.padZoom = -shapeAxis(axis(event, MotionEvent.AXIS_RZ)) // right stick up = zoom in
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun axis(e: MotionEvent, a: Int): Float {
        val flat = e.device?.getMotionRange(a, e.source)?.flat ?: 0f
        val v = e.getAxisValue(a)
        return if (abs(v) > flat) v else 0f
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isGamepad(event)) {
            lastKey = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
            updateStatus()
            if (event.repeatCount > 0) return true
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_R1 -> { ptz.buttonZoom = ZOOM_SPEED; return true }
                KeyEvent.KEYCODE_BUTTON_L1 -> { ptz.buttonZoom = -ZOOM_SPEED; return true }
                KeyEvent.KEYCODE_BUTTON_A -> { toggleRecord(); return true }
                KeyEvent.KEYCODE_BUTTON_X -> { takePhoto(); return true }
                KeyEvent.KEYCODE_BUTTON_Y -> { goHome(); return true }
                KeyEvent.KEYCODE_BUTTON_B -> { setNight(irCut != "OFF"); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isGamepad(event)) {
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L1 -> {
                    ptz.buttonZoom = 0f
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_B -> return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isGamepad(e: KeyEvent) =
        e.isFromSource(InputDevice.SOURCE_GAMEPAD) || e.isFromSource(InputDevice.SOURCE_JOYSTICK)

    // ------------------------------------------------------------------ UI helpers

    @SuppressLint("ClickableViewAccessibility")
    private fun holdZoom(b: Button, speed: Float) {
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { ptz.buttonZoom = speed; v.isPressed = true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { ptz.buttonZoom = 0f; v.isPressed = false }
            }
            true
        }
    }

    private fun updateStatus() {
        statusText.text = listOf(streamStatus, ptzStatus, settings.host, lastKey)
            .filter { it.isNotBlank() }
            .joinToString("   |   ")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showSettings() {
        val s = settings
        val dp = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        fun <T : View> add(label: String, v: T): T {
            box.addView(TextView(this).apply { text = label; setPadding(0, (10 * dp).toInt(), 0, 0) })
            box.addView(v)
            return v
        }
        fun edit(value: String, type: Int) = EditText(this).apply {
            setText(value); inputType = type; isSingleLine = true
        }

        val ip = add("Camera IP address", edit(s.host, InputType.TYPE_CLASS_TEXT))
        val port = add("ONVIF port (camera HTTP port)", edit(s.port.toString(), InputType.TYPE_CLASS_NUMBER))
        val user = add("Username", edit(s.user, InputType.TYPE_CLASS_TEXT))
        val pass = add(
            "Password",
            edit(s.pass, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        )
        val rtsp = add(
            "RTSP URL (leave empty = take it from ONVIF)",
            edit(s.rtspOverride, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        )
        val cache = add(
            "Video buffer, ms (lower = less delay, higher = smoother on weak link)",
            edit(s.cachingMs.toString(), InputType.TYPE_CLASS_NUMBER)
        )
        val tcp = CheckBox(this).apply {
            text = "RTSP over TCP (recommended on the radio link)"
            isChecked = s.rtspTcp
        }
        box.addView(tcp)
        val swipeInv = CheckBox(this).apply {
            text = "Invert swipe (drag the picture instead of pointing the camera)"
            isChecked = s.swipeInvert
        }
        box.addView(swipeInv)
        val speedLabel = TextView(this)
        val speed = SeekBar(this).apply {
            max = 100
            progress = (s.maxSpeed * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    speedLabel.text = "Max pan/tilt speed: ${p.coerceAtLeast(10)} %"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        speedLabel.text = "Max pan/tilt speed: ${speed.progress.coerceAtLeast(10)} %"
        speedLabel.setPadding(0, (10 * dp).toInt(), 0, 0)
        box.addView(speedLabel)
        box.addView(speed)

        AlertDialog.Builder(this)
            .setTitle("XeraCAM settings")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save & reconnect") { _, _ ->
                settings = CamSettings(
                    host = ip.text.toString().trim(),
                    port = port.text.toString().toIntOrNull() ?: 80,
                    user = user.text.toString().trim(),
                    pass = pass.text.toString(),
                    rtspOverride = rtsp.text.toString().trim(),
                    rtspTcp = tcp.isChecked,
                    maxSpeed = speed.progress.coerceAtLeast(10) / 100f,
                    cachingMs = (cache.text.toString().toIntOrNull() ?: 200).coerceIn(50, 3000),
                    swipeInvert = swipeInv.isChecked,
                )
                settings.save(prefs)
                ptz.maxSpeed = settings.maxSpeed
                swipePad.invert = settings.swipeInvert
                reconnect()
            }
            .setNeutralButton("Clear presets") { _, _ ->
                prefs.edit().apply {
                    presetButtons.keys.forEach { remove("preset_$it") }
                }.apply()
                refreshPresetButtons()
                toast("Presets cleared – hold a preset button to save it again")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun reconnect() {
        if (recording) stopRecording()
        stopStream()
        ptz.client = null
        onvifRtsp = null
        irCut = null
        updateNightButton()
        updateStatus()
        connectOnvif()
    }

    companion object {
        private const val ZOOM_SPEED = 0.6f

        /** Camera preset numbers used for the named presets (avoid 32–99: special functions on many PTZs). */
        private val DEFAULT_PRESET_TOKEN = mapOf(
            "FRONT" to "11", "REAR" to "12", "LEFT" to "13",
            "RIGHT" to "14", "UP" to "15", "DOWN" to "16",
        )
    }
}
