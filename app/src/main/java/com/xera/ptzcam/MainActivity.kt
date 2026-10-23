package com.xera.ptzcam

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
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
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    // ------------------------------------------------------------------ views
    private lateinit var libVlc: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var hud: HudView
    private lateinit var swipePad: SwipePadView
    private lateinit var flashView: View
    private lateinit var recFrame: View

    private lateinit var linkDot: View
    private lateinit var linkText: TextView
    private lateinit var bitrateText: TextView
    private lateinit var recPill: TextView
    private lateinit var nightBadge: TextView
    private lateinit var batteryText: TextView
    private lateinit var clockText: TextView
    private lateinit var hudMsg: TextView

    private lateinit var rail: LinearLayout
    private lateinit var zoomGroup: View
    private lateinit var zoomText: TextView

    private lateinit var railPresets: RailButton
    private lateinit var railNight: RailButton
    private lateinit var railWipe: RailButton
    private lateinit var railPhoto: RailButton
    private lateinit var railRec: RailButton
    private lateinit var railHome: RailButton

    private lateinit var presetOverlay: View
    private lateinit var presetDial: PresetDialView
    private lateinit var settingsOverlay: View
    private lateinit var settingsPanel: View
    private lateinit var settingsContent: LinearLayout
    private lateinit var connectOverlay: View

    private enum class Step { LINK, ONVIF, VIDEO }
    private enum class StepState { PENDING, ACTIVE, OK, FAIL }
    private class StepRow(val icon: TextView, val progress: ProgressBar, val detail: TextView)
    private val steps = HashMap<Step, StepRow>()

    // ------------------------------------------------------------------ state
    private lateinit var prefs: SharedPreferences
    private lateinit var settings: CamSettings
    private lateinit var ptz: PtzController

    private val ui = Handler(Looper.getMainLooper())
    private var onvifJob: Job? = null
    private var statusJob: Job? = null
    private var onvifRtsp: String? = null

    private var playerStarted = false
    private var wantPlaying = false
    private var isLive = false
    private var lastBytes = -1
    private var lastBytesTime = 0L
    private var zeroTicks = 0
    private var kbps = 0

    private var recording = false
    private var recDir: File? = null
    private var recStamp = ""
    private var recSegment = 0
    private var recStartMs = 0L
    private var recPulse: ObjectAnimator? = null
    private var photoBusy = false
    private var wiperOn = false

    /** Camera IR-cut state: "ON" = day, "OFF" = night, "AUTO", null = unknown. */
    private var irCut: String? = null
    private var controlsShown = true

    private val fontBold by lazy { ResourcesCompat.getFont(this, R.font.rajdhani_bold) }
    private val fontSemi by lazy { ResourcesCompat.getFont(this, R.font.rajdhani_semibold) }
    private val dp by lazy { resources.displayMetrics.density }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        prefs = getSharedPreferences("cam", MODE_PRIVATE)
        settings = CamSettings.load(prefs)
        bindViews()

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
            runOnUiThread { if (msg != "ready") hudMessage("PTZ: $msg", error = true) }
        }
        ptz.maxSpeed = settings.maxSpeed

        // gestures on the video
        swipePad.invert = settings.swipeInvert
        swipePad.onPanTilt = { x, y -> ptz.swipePan = x; ptz.swipeTilt = y }
        swipePad.onZoom = { z -> ptz.swipeZoom = z }
        swipePad.onTap = { setControlsVisible(!controlsShown) }

        findViewById<ZoomRockerView>(R.id.zoomRocker).onChange = { v -> ptz.rockerZoom = v }

        buildRail()
        setupPresetDial()
        setupSettingsSheet()
        setupConnectScreen()

        updateNightUi()
        updateLinkUi()
        if (settings.pass.isEmpty()) ui.postDelayed({ openSettings() }, 400)
    }

    override fun onStart() {
        super.onStart()
        player.attachViews(videoLayout, null, false, false)
        ptz.start()
        connectOnvif()
        ui.post(statsTicker)
        scheduleHide()
    }

    override fun onStop() {
        super.onStop()
        if (recording) stopRecording(restartStream = false)
        if (wiperOn) setWiper(false)
        ui.removeCallbacks(statsTicker)
        ui.removeCallbacks(hideControls)
        onvifJob?.cancel()
        statusJob?.cancel()
        ptz.halt()
        stopStream()
        player.detachViews()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        recPulse?.cancel()
        player.setEventListener(null)
        player.release()
        libVlc.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            settingsOverlay.visibility == View.VISIBLE -> closeSettings()
            presetOverlay.visibility == View.VISIBLE -> closePresets()
            else -> super.onBackPressed()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && controlsShown) scheduleHide()
        return super.dispatchTouchEvent(ev)
    }

    private fun bindViews() {
        videoLayout = findViewById(R.id.videoLayout)
        hud = findViewById(R.id.hud)
        swipePad = findViewById(R.id.swipePad)
        flashView = findViewById(R.id.flashView)
        recFrame = findViewById(R.id.recFrame)
        linkDot = findViewById(R.id.linkDot)
        linkText = findViewById(R.id.linkText)
        bitrateText = findViewById(R.id.bitrateText)
        recPill = findViewById(R.id.recPill)
        nightBadge = findViewById(R.id.nightBadge)
        batteryText = findViewById(R.id.batteryText)
        clockText = findViewById(R.id.clockText)
        hudMsg = findViewById(R.id.hudMsg)
        rail = findViewById(R.id.rail)
        zoomGroup = findViewById(R.id.zoomGroup)
        zoomText = findViewById(R.id.zoomText)
        presetOverlay = findViewById(R.id.presetOverlay)
        presetDial = findViewById(R.id.presetDial)
        settingsOverlay = findViewById(R.id.settingsOverlay)
        settingsPanel = findViewById(R.id.settingsPanel)
        settingsContent = findViewById(R.id.settingsContent)
        connectOverlay = findViewById(R.id.connectOverlay)
    }

    // ------------------------------------------------------------------ rail

    private fun buildRail() {
        railPresets = railButton(R.drawable.ic_compass, "PRESETS", { openPresets() })
        railNight = railButton(R.drawable.ic_moon, "NIGHT", { setNight(irCut != "OFF") }, { setNight(null) })
        railWipe = railButton(R.drawable.ic_wiper, "WIPE", { setWiper(!wiperOn) })
        railPhoto = railButton(R.drawable.ic_camera, "PHOTO", { takePhoto() })
        railRec = railButton(R.drawable.ic_record, "REC", { toggleRecord() })
        railRec.accent = ContextCompat.getColor(this, R.color.rec)
        railHome = railButton(R.drawable.ic_home, "HOME", { goHome() }, { saveHome() })
        railButton(R.drawable.ic_gallery, "GALLERY", {
            startActivity(Intent(this, GalleryActivity::class.java))
        })
        railButton(R.drawable.ic_tune, "SETTINGS", { openSettings() })
    }

    private fun railButton(icon: Int, label: String, onClick: () -> Unit, onLong: (() -> Unit)? = null): RailButton {
        val b = RailButton(this, icon, label)
        b.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
        if (onLong != null) {
            b.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLong()
                true
            }
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        lp.topMargin = (2 * dp).toInt()
        lp.bottomMargin = (2 * dp).toInt()
        rail.addView(b, lp)
        return b
    }

    // ------------------------------------------------------------------ auto-hide controls

    private val hideControls = Runnable { setControlsVisible(false) }

    private fun anyOverlayOpen() = presetOverlay.visibility == View.VISIBLE ||
        settingsOverlay.visibility == View.VISIBLE || connectOverlay.visibility == View.VISIBLE

    private fun scheduleHide() {
        ui.removeCallbacks(hideControls)
        if (!anyOverlayOpen()) ui.postDelayed(hideControls, AUTO_HIDE_MS)
    }

    private fun setControlsVisible(show: Boolean) {
        controlsShown = show
        for (v in listOf<View>(rail, zoomGroup)) {
            v.animate().cancel()
            if (show) {
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).translationX(0f).setDuration(220).start()
            } else {
                val dx = if (v === rail) 30 * dp else -30 * dp
                v.animate().alpha(0f).translationX(dx).setDuration(260)
                    .withEndAction { if (!controlsShown) v.visibility = View.INVISIBLE }.start()
            }
        }
        if (show) scheduleHide() else ui.removeCallbacks(hideControls)
    }

    // ------------------------------------------------------------------ HUD message strip

    private val hideHudMsg = Runnable {
        hudMsg.animate().alpha(0f).translationY(8 * dp).setDuration(250).start()
    }

    private fun hudMessage(text: String, error: Boolean = false) {
        hudMsg.text = text
        hudMsg.setTextColor(ContextCompat.getColor(this, if (error) R.color.err else R.color.text_main))
        hudMsg.animate().cancel()
        hudMsg.translationY = 8 * dp
        hudMsg.animate().alpha(1f).translationY(0f).setDuration(180).start()
        ui.removeCallbacks(hideHudMsg)
        ui.postDelayed(hideHudMsg, if (error) 4500L else 2600L)
    }

    // ------------------------------------------------------------------ connect screen

    private fun setupConnectScreen() {
        fun row(id: Int, title: String): StepRow {
            val v = findViewById<View>(id)
            v.findViewById<TextView>(R.id.stepTitle).text = title
            return StepRow(v.findViewById(R.id.stepIcon), v.findViewById(R.id.stepProgress), v.findViewById(R.id.stepDetail))
        }
        steps[Step.LINK] = row(R.id.stepLink, "LINK")
        steps[Step.ONVIF] = row(R.id.stepOnvif, "ONVIF")
        steps[Step.VIDEO] = row(R.id.stepVideo, "VIDEO")
        resetSteps()

        findViewById<Button>(R.id.connectSkip).setOnClickListener { hideConnectScreen() }
        findViewById<Button>(R.id.connectSettings).setOnClickListener { openSettings() }

        // gentle breathing logo
        ObjectAnimator.ofFloat(findViewById<View>(R.id.connectLogo), View.ALPHA, 1f, 0.55f).apply {
            duration = 1100
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun resetSteps() {
        setStep(Step.LINK, StepState.PENDING, "")
        setStep(Step.ONVIF, StepState.PENDING, "")
        setStep(Step.VIDEO, StepState.PENDING, "")
    }

    private fun setStep(step: Step, state: StepState, detail: String) {
        val r = steps[step] ?: return
        r.progress.visibility = if (state == StepState.ACTIVE) View.VISIBLE else View.GONE
        r.icon.visibility = if (state == StepState.ACTIVE) View.INVISIBLE else View.VISIBLE
        when (state) {
            StepState.PENDING -> { r.icon.text = "○"; r.icon.setTextColor(ContextCompat.getColor(this, R.color.text_dim)) }
            StepState.OK -> { r.icon.text = "✓"; r.icon.setTextColor(ContextCompat.getColor(this, R.color.ok)) }
            StepState.FAIL -> { r.icon.text = "✕"; r.icon.setTextColor(ContextCompat.getColor(this, R.color.err)) }
            StepState.ACTIVE -> Unit
        }
        r.detail.text = detail
        r.detail.setTextColor(
            ContextCompat.getColor(this, if (state == StepState.FAIL) R.color.err else R.color.text_dim)
        )
    }

    private fun showConnectScreen() {
        resetSteps()
        connectOverlay.animate().cancel()
        connectOverlay.alpha = 1f
        connectOverlay.visibility = View.VISIBLE
        ui.removeCallbacks(hideControls)
    }

    private fun hideConnectScreen() {
        if (connectOverlay.visibility != View.VISIBLE) return
        connectOverlay.animate().alpha(0f).setDuration(450).withEndAction {
            connectOverlay.visibility = View.GONE
            scheduleHide()
        }.start()
    }

    // ------------------------------------------------------------------ ONVIF connection

    private fun connectOnvif() {
        onvifJob?.cancel()
        val s = settings
        onvifJob = lifecycleScope.launch {
            while (isActive) {
                setStep(Step.LINK, StepState.ACTIVE, "${s.host}:${s.port}")
                val link = withContext(Dispatchers.IO) {
                    runCatching { Socket().use { it.connect(InetSocketAddress(s.host, s.port), 2500) } }
                }
                if (link.isFailure) {
                    setStep(Step.LINK, StepState.FAIL, "Can't reach ${s.host}:${s.port} – check network / IP")
                    if (!playerStarted && s.rtspOverride.isNotBlank()) playStream()
                    updateLinkUi()
                    delay(4000)
                    continue
                }
                setStep(Step.LINK, StepState.OK, s.host)
                setStep(Step.ONVIF, StepState.ACTIVE, "Signing in…")

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
                    setStep(Step.ONVIF, StepState.OK, "PTZ ready")
                    if (!isLive) setStep(Step.VIDEO, StepState.ACTIVE, "Opening stream…")
                    if (!playerStarted) playStream()
                    readNightState(ok.first)
                    startStatusPolling(ok.first)
                    return@launch
                }
                setStep(Step.ONVIF, StepState.FAIL, result.exceptionOrNull()?.message ?: "Login failed")
                if (!playerStarted && s.rtspOverride.isNotBlank()) playStream()
                delay(4000)
            }
        }
    }

    private fun client(): OnvifClient? {
        val c = ptz.client
        if (c == null) hudMessage("Camera not connected yet", error = true)
        return c
    }

    // ------------------------------------------------------------------ zoom readout

    private fun startStatusPolling(c: OnvifClient) {
        statusJob?.cancel()
        statusJob = lifecycleScope.launch {
            var fails = 0
            while (isActive) {
                val st = withContext(Dispatchers.IO) { runCatching { c.getStatus() }.getOrNull() }
                if (st != null) {
                    fails = 0
                    applyStatus(st)
                } else {
                    fails++
                }
                delay(if (fails > 3) 5000L else 300L)
            }
        }
    }

    private fun applyStatus(st: PtzStatus) {
        st.zoom?.let { z ->
            val mag = 1f + z.coerceIn(0f, 1f) * (settings.maxZoom - 1)
            zoomText.text = String.format(Locale.US, "%.1f×", mag)
        }
    }

    // ------------------------------------------------------------------ home position

    private var homeSaved: Boolean
        get() = prefs.getBoolean("homeSaved", false)
        set(v) { prefs.edit().putBoolean("homeSaved", v).apply() }

    private var homePresetToken: String
        get() = prefs.getString("homePreset", HOME_PRESET) ?: HOME_PRESET
        set(v) { prefs.edit().putString("homePreset", v).apply() }

    private fun goHome() {
        val c = client() ?: return
        val saved = homeSaved
        val token = homePresetToken
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                if (saved) runCatching { c.gotoPreset(token) }.recoverCatching { c.gotoHome() }
                else runCatching { c.gotoHome() }
            }
            when {
                r.isSuccess && saved -> hudMessage("Returning to HOME")
                r.isSuccess -> hudMessage("Camera home · hold HOME to set your own")
                else -> hudMessage("No HOME yet – aim the camera, then hold HOME", error = true)
            }
        }
    }

    private fun saveHome() {
        val c = client() ?: return
        val token = homePresetToken
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                val preset = runCatching { c.setPreset(token, "HOME") }
                    .recoverCatching { c.setPreset(null, "HOME") }
                runCatching { c.setHome() }
                preset
            }
            r.onSuccess {
                homePresetToken = it
                homeSaved = true
                hudMessage("HOME position saved")
            }.onFailure { hudMessage("Could not save HOME: ${it.message}", error = true) }
        }
    }

    // ------------------------------------------------------------------ presets (compass dial)

    private fun presetToken(name: String): String? = prefs.getString("preset_$name", null)

    private fun savedPresets(): Set<String> = PRESET_NAMES.filter { presetToken(it) != null }.toSet()

    private fun setupPresetDial() {
        presetOverlay.setOnClickListener { closePresets() }
        presetDial.onGo = { name -> gotoNamedPreset(name) }
        presetDial.onSave = { name -> saveNamedPreset(name) }
    }

    private fun openPresets() {
        presetDial.saved = savedPresets()
        presetOverlay.alpha = 0f
        presetDial.scaleX = 0.85f
        presetDial.scaleY = 0.85f
        presetOverlay.visibility = View.VISIBLE
        presetOverlay.animate().alpha(1f).setDuration(180).start()
        presetDial.animate().scaleX(1f).scaleY(1f).setDuration(260)
            .setInterpolator(DecelerateInterpolator()).start()
        ui.removeCallbacks(hideControls)
    }

    private fun closePresets() {
        presetOverlay.animate().alpha(0f).setDuration(160).withEndAction {
            presetOverlay.visibility = View.GONE
            scheduleHide()
        }.start()
    }

    private fun gotoNamedPreset(name: String) {
        val token = presetToken(name)
            ?: return hudMessage("$name not set – aim the camera, then hold $name", error = true)
        val c = client() ?: return
        closePresets()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { c.gotoPreset(token) } }
            if (r.isSuccess) hudMessage("→ $name") else hudMessage("$name failed: ${r.exceptionOrNull()?.message}", true)
        }
    }

    private fun saveNamedPreset(name: String) {
        val c = client() ?: return
        val token = presetToken(name) ?: DEFAULT_PRESET_TOKEN[name] ?: "20"
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { c.setPreset(token, name) }.recoverCatching { c.setPreset(null, name) }
            }
            r.onSuccess {
                prefs.edit().putString("preset_$name", it).apply()
                presetDial.saved = savedPresets()
                hudMessage("$name saved")
            }.onFailure { hudMessage("Could not save $name: ${it.message}", error = true) }
        }
    }

    // ------------------------------------------------------------------ night mode

    private fun readNightState(c: OnvifClient) {
        lifecycleScope.launch {
            val mode = withContext(Dispatchers.IO) { runCatching { c.getIrCutFilter() }.getOrNull() }
            if (mode != null) {
                irCut = mode
                updateNightUi()
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
                updateNightUi()
                hudMessage(when (on) { true -> "Night mode ON"; false -> "Night mode OFF"; null -> "Night mode AUTO" })
            }.onFailure { hudMessage("Night mode not supported: ${it.message}", error = true) }
        }
    }

    private fun updateNightUi() {
        railNight.isSelected = irCut == "OFF"
        railNight.setLabel(if (irCut == "AUTO") "AUTO" else "NIGHT")
        when (irCut) {
            "OFF" -> { nightBadge.text = "IR ON"; nightBadge.visibility = View.VISIBLE }
            "AUTO" -> { nightBadge.text = "IR AUTO"; nightBadge.visibility = View.VISIBLE }
            else -> nightBadge.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------ wiper

    private val wiperAutoOff = Runnable { if (wiperOn) setWiper(false) }

    private fun setWiper(on: Boolean) {
        val c = ptz.client ?: run {
            if (on) hudMessage("Camera not connected yet", error = true)
            wiperOn = false
            railWipe.isSelected = false
            return
        }
        val state = if (on) "on" else "off"
        val advertised = c.auxCommands.filter {
            it.contains("wiper", ignoreCase = true) && it.substringAfterLast('|').trim().equals(state, true)
        }
        val candidates = (advertised + listOf(
            if (on) "tt:Wiper|On" else "tt:Wiper|Off",
            if (on) "Wiper|On" else "Wiper|Off",
        )).distinct()
        val wiperPreset = settings.wiperPreset.trim()

        lifecycleScope.launch {
            val used = withContext(Dispatchers.IO) {
                var ok: String? = null
                for (cmd in candidates) {
                    if (runCatching { c.auxCommand(cmd) }.isSuccess) { ok = cmd; break }
                }
                if (ok == null && on && wiperPreset.isNotEmpty() &&
                    runCatching { c.gotoPreset(wiperPreset) }.isSuccess
                ) ok = "preset"
                ok
            }
            ui.removeCallbacks(wiperAutoOff)
            when {
                used == null && on -> {
                    wiperOn = false
                    railWipe.isSelected = false
                    val list = c.auxCommands.ifEmpty { listOf("none reported") }.joinToString(", ")
                    hudMessage("Wiper refused · camera commands: $list", error = true)
                }
                used == "preset" -> {
                    wiperOn = false
                    railWipe.isSelected = false
                    hudMessage("Wiper started")
                }
                on -> {
                    wiperOn = true
                    railWipe.isSelected = true
                    ui.postDelayed(wiperAutoOff, WIPER_RUN_MS)
                    hudMessage("Wiper ON")
                }
                else -> {
                    wiperOn = false
                    railWipe.isSelected = false
                }
            }
        }
    }

    // ------------------------------------------------------------------ video

    private fun playStream() {
        val url = settings.rtspOverride.ifBlank { onvifRtsp ?: "" }
        if (url.isBlank()) {
            setStep(Step.VIDEO, StepState.FAIL, "No RTSP URL – set one in Settings")
            return
        }
        wantPlaying = true
        playerStarted = true
        isLive = false
        lastBytes = -1
        zeroTicks = 0
        updateLinkUi()

        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=${settings.cachingMs}")
            if (settings.rtspTcp) addOption(":rtsp-tcp")
            if (!url.contains("@")) {
                addOption(":rtsp-user=${settings.user}")
                addOption(":rtsp-pwd=${settings.pass}")
            }
            // Recording: show the video AND write the same H.264 stream into an MP4 (no re-encode).
            if (recording) {
                recDir?.let { dir ->
                    recSegment++
                    val f = File(dir, String.format(Locale.US, "XeraCAM_%s_%02d.mp4", recStamp, recSegment))
                    addOption(":sout=#duplicate{dst=display,dst=std{access=file,mux=mp4,dst=\"${f.absolutePath}\"}}")
                    addOption(":no-sout-audio")
                }
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
        updateLinkUi()
    }

    private val retry = Runnable { if (wantPlaying) playStream() }

    private fun onPlayerEvent(type: Int) {
        when (type) {
            MediaPlayer.Event.Playing -> {
                isLive = true
                zeroTicks = 0
                setStep(Step.VIDEO, StepState.OK, "Live")
                ui.postDelayed({ if (isLive) hideConnectScreen() }, 700)
            }
            MediaPlayer.Event.EncounteredError, MediaPlayer.Event.EndReached -> {
                isLive = false
                setStep(Step.VIDEO, StepState.FAIL, "Stream error – retrying…")
                if (wantPlaying) {
                    ui.removeCallbacks(retry)
                    ui.postDelayed(retry, 2000)
                }
            }
        }
        updateLinkUi()
    }

    /** Every second: bitrate, link quality, stall watchdog, clock, battery. */
    private val statsTicker = object : Runnable {
        override fun run() {
            if (wantPlaying && isLive) {
                val m = player.media
                val bytes = m?.stats?.readBytes ?: -1
                m?.release()
                val now = SystemClock.elapsedRealtime()
                if (bytes >= 0 && lastBytes >= 0 && bytes >= lastBytes && now > lastBytesTime) {
                    val delta = bytes - lastBytes
                    kbps = (delta * 8.0 / ((now - lastBytesTime) / 1000.0) / 1000.0).toInt()
                    zeroTicks = if (delta == 0) zeroTicks + 1 else 0
                }
                lastBytes = bytes
                lastBytesTime = now
                if (zeroTicks >= STALL_TICKS) {
                    zeroTicks = 0
                    isLive = false
                    hudMessage("Video stalled – reconnecting…", error = true)
                    player.stop()
                    ui.removeCallbacks(retry)
                    ui.postDelayed(retry, 500)
                }
            } else {
                kbps = 0
            }
            updateLinkUi()
            clockText.text = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            updateBattery()
            ui.postDelayed(this, 1000)
        }
    }

    private fun updateLinkUi() {
        val (colorRes, stateText) = when {
            isLive && zeroTicks == 0 -> R.color.ok to "LIVE"
            isLive -> R.color.warn to "WEAK"
            wantPlaying -> R.color.warn to "LINKING"
            else -> R.color.err to "OFFLINE"
        }
        linkDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
        linkText.text = stateText
        bitrateText.text = when {
            !isLive -> "— kbps"
            kbps >= 1000 -> String.format(Locale.US, "%.1f Mbps", kbps / 1000f)
            else -> "$kbps kbps"
        }
    }

    private fun updateBattery() {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) {
            val pct = level * 100 / scale
            val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            batteryText.text = if (charging) "⚡$pct%" else "$pct%"
            batteryText.setTextColor(
                ContextCompat.getColor(this, if (pct <= 15 && !charging) R.color.err else R.color.text_dim)
            )
        }
    }

    // ------------------------------------------------------------------ photo

    private fun takePhoto() {
        if (photoBusy) return
        if (!isLive && ptz.client == null) return hudMessage("No camera connected", error = true)
        photoBusy = true
        shutter()
        val c = ptz.client
        lifecycleScope.launch {
            val jpeg = if (c != null) withContext(Dispatchers.IO) { runCatching { c.snapshot() }.getOrNull() } else null
            if (jpeg != null) {
                finishPhoto(jpeg)
                return@launch
            }
            grabFrame { bmp ->
                if (bmp == null) {
                    photoBusy = false
                    hudMessage("Photo failed – no video frame", error = true)
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
        if (name != null) hudMessage("Photo saved · $name") else hudMessage("Could not save photo", error = true)
    }

    private fun saveJpeg(jpeg: ByteArray): String {
        val name = "XeraCAM_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
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

    /** Shutter: white flash + quick "blink" of the video. */
    private fun shutter() {
        flashView.animate().cancel()
        flashView.alpha = 0.9f
        flashView.visibility = View.VISIBLE
        flashView.animate().alpha(0f).setDuration(320).withEndAction { flashView.visibility = View.GONE }.start()
        videoLayout.animate().cancel()
        videoLayout.scaleX = 0.97f
        videoLayout.scaleY = 0.97f
        videoLayout.animate().scaleX(1f).scaleY(1f).setDuration(260).start()
        railPhoto.isSelected = true
        ui.postDelayed({ railPhoto.isSelected = false }, 350)
    }

    // ------------------------------------------------------------------ recording

    private fun toggleRecord() = if (recording) stopRecording() else startRecording()

    private fun startRecording() {
        if (!isLive) return hudMessage("Wait for live video before recording", error = true)
        val base = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        recStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(base, "rec_$recStamp").apply { mkdirs() }

        recording = true
        recDir = dir
        recSegment = 0
        recStartMs = SystemClock.elapsedRealtime()
        railRec.isSelected = true
        railRec.setIcon(R.drawable.ic_stop)
        railRec.setLabel("STOP")
        recPill.visibility = View.VISIBLE
        recFrame.visibility = View.VISIBLE
        recPulse?.cancel()
        recPulse = ObjectAnimator.ofFloat(recFrame, View.ALPHA, 1f, 0.15f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        ui.removeCallbacks(recTicker)
        ui.post(recTicker)
        playStream()
        hudMessage("Recording started")
    }

    private fun stopRecording(restartStream: Boolean = true) {
        recording = false
        railRec.isSelected = false
        railRec.setIcon(R.drawable.ic_record)
        railRec.setLabel("REC")
        recPill.visibility = View.GONE
        recPulse?.cancel()
        recPulse = null
        recFrame.visibility = View.GONE
        ui.removeCallbacks(recTicker)

        val dir = recDir
        recDir = null
        // stopping the player closes the MP4 (writes index / duration)
        if (restartStream && wantPlaying) playStream() else player.stop()

        if (dir == null) return
        lifecycleScope.launch {
            delay(1000)
            val n = withContext(Dispatchers.IO) { exportToGallery(dir) }
            if (n > 0) hudMessage("Video saved to Movies/XeraCAM")
            else hudMessage("Recording was empty – video must be live while recording", error = true)
        }
    }

    private val recTicker = object : Runnable {
        var blink = false
        override fun run() {
            val s = (SystemClock.elapsedRealtime() - recStartMs) / 1000
            blink = !blink
            recPill.text = String.format(Locale.US, "%s REC %02d:%02d", if (blink) "●" else "○", s / 60, s % 60)
            ui.postDelayed(this, 500)
        }
    }

    /** Moves finished MP4 segments into Movies/XeraCAM (Gallery, USB, in-app gallery). */
    private fun exportToGallery(dir: File): Int {
        var count = 0
        dir.listFiles()?.filter { it.isFile && it.length() < MIN_VIDEO_BYTES }?.forEach { it.delete() }
        dir.listFiles()?.filter { it.isFile && it.length() >= MIN_VIDEO_BYTES }?.forEach { f ->
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
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
                // keep the file in the app folder
            }
        }
        if (dir.listFiles().isNullOrEmpty()) dir.delete()
        return count
    }

    // ------------------------------------------------------------------ settings side sheet

    private var formHost: EditText? = null
    private var formPort: EditText? = null
    private var formUser: EditText? = null
    private var formPass: EditText? = null
    private var formRtsp: EditText? = null
    private var formCache: EditText? = null
    private var formZoom: EditText? = null
    private var formWiper: EditText? = null
    private var formTcp: CheckBox? = null
    private var formInvert: CheckBox? = null
    private var formSpeed: SeekBar? = null

    private fun setupSettingsSheet() {
        settingsOverlay.setOnClickListener { closeSettings() }
        findViewById<ImageButton>(R.id.settingsClose).setOnClickListener { closeSettings() }
        findViewById<Button>(R.id.settingsSave).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.settingsClear).setOnClickListener {
            prefs.edit().apply {
                PRESET_NAMES.forEach { remove("preset_$it") }
                remove("homeSaved")
            }.apply()
            presetDial.saved = emptySet()
            hudMessage("Presets and HOME cleared")
        }
    }

    private fun openSettings() {
        buildSettingsForm()
        settingsOverlay.alpha = 0f
        settingsOverlay.visibility = View.VISIBLE
        settingsOverlay.animate().alpha(1f).setDuration(180).start()
        settingsPanel.post {
            settingsPanel.translationX = settingsPanel.width.toFloat()
            settingsPanel.animate().translationX(0f).setDuration(280)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
        ui.removeCallbacks(hideControls)
    }

    private fun closeSettings() {
        currentFocus?.let {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0)
        }
        settingsPanel.animate().translationX(settingsPanel.width.toFloat()).setDuration(220).start()
        settingsOverlay.animate().alpha(0f).setDuration(240).withEndAction {
            settingsOverlay.visibility = View.GONE
            hideSystemBars()
            scheduleHide()
        }.start()
    }

    private fun buildSettingsForm() {
        val s = settings
        settingsContent.removeAllViews()

        section("CONNECTION")
        formHost = field("Camera IP address", s.host, InputType.TYPE_CLASS_TEXT)
        formPort = field("ONVIF port (camera HTTP port)", s.port.toString(), InputType.TYPE_CLASS_NUMBER)
        formUser = field("Username", s.user, InputType.TYPE_CLASS_TEXT)
        formPass = field("Password", s.pass, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

        section("VIDEO")
        formRtsp = field(
            "RTSP URL (empty = automatic from ONVIF)", s.rtspOverride,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        formCache = field("Video buffer, ms (lower = less delay)", s.cachingMs.toString(), InputType.TYPE_CLASS_NUMBER)
        formTcp = check("RTSP over TCP (recommended on radio links)", s.rtspTcp)

        section("CONTROL")
        formInvert = check("Invert swipe (drag the picture)", s.swipeInvert)
        val speedLabel = label("")
        formSpeed = SeekBar(this).apply {
            max = 100
            progress = (s.maxSpeed * 100).toInt()
            progressTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.accent))
            thumbTintList = progressTintList
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    speedLabel.text = "Max pan / tilt speed · ${p.coerceAtLeast(10)} %"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        speedLabel.text = "Max pan / tilt speed · ${formSpeed!!.progress.coerceAtLeast(10)} %"
        settingsContent.addView(formSpeed)
        formZoom = field("Camera optical zoom (×) – for the zoom readout", s.maxZoom.toString(), InputType.TYPE_CLASS_NUMBER)

        section("WIPER")
        formWiper = field("Wiper preset number (only if WIPE doesn't work)", s.wiperPreset, InputType.TYPE_CLASS_NUMBER)

        section("ABOUT")
        label("XeraCAM ${versionName()} · ONVIF PTZ + RTSP · LibVLC")
    }

    private fun section(title: String) {
        settingsContent.addView(TextView(this).apply {
            text = title
            typeface = fontBold
            letterSpacing = 0.25f
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
            setPadding(0, (20 * dp).toInt(), 0, (2 * dp).toInt())
        })
    }

    private fun label(text: String): TextView {
        val t = TextView(this).apply {
            this.text = text
            typeface = fontSemi
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
            setPadding(0, (10 * dp).toInt(), 0, (4 * dp).toInt())
        }
        settingsContent.addView(t)
        return t
    }

    private fun field(title: String, value: String, type: Int): EditText {
        label(title)
        val e = EditText(this).apply {
            setText(value)
            inputType = type
            isSingleLine = true
            typeface = fontSemi
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_main))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
            setBackgroundResource(R.drawable.field_bg)
            setPadding((12 * dp).toInt(), (9 * dp).toInt(), (12 * dp).toInt(), (9 * dp).toInt())
        }
        settingsContent.addView(e)
        return e
    }

    private fun check(title: String, value: Boolean): CheckBox {
        val c = CheckBox(this).apply {
            text = title
            isChecked = value
            typeface = fontSemi
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_main))
            buttonTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.accent))
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        settingsContent.addView(c)
        return c
    }

    private fun saveSettings() {
        settings = CamSettings(
            host = formHost?.text?.toString()?.trim().orEmpty(),
            port = formPort?.text?.toString()?.toIntOrNull() ?: 80,
            user = formUser?.text?.toString()?.trim().orEmpty(),
            pass = formPass?.text?.toString().orEmpty(),
            rtspOverride = formRtsp?.text?.toString()?.trim().orEmpty(),
            rtspTcp = formTcp?.isChecked ?: true,
            maxSpeed = (formSpeed?.progress ?: 100).coerceAtLeast(10) / 100f,
            cachingMs = (formCache?.text?.toString()?.toIntOrNull() ?: 200).coerceIn(50, 3000),
            swipeInvert = formInvert?.isChecked ?: false,
            wiperPreset = formWiper?.text?.toString()?.trim().orEmpty(),
            maxZoom = (formZoom?.text?.toString()?.toIntOrNull() ?: 30).coerceIn(1, 100),
        )
        settings.save(prefs)
        ptz.maxSpeed = settings.maxSpeed
        swipePad.invert = settings.swipeInvert
        closeSettings()
        reconnect()
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }.getOrDefault("")

    private fun reconnect() {
        if (recording) stopRecording()
        stopStream()
        statusJob?.cancel()
        ptz.client = null
        onvifRtsp = null
        irCut = null
        updateNightUi()
        showConnectScreen()
        connectOnvif()
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
            ptz.padZoom = -shapeAxis(axis(event, MotionEvent.AXIS_RZ))
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
            if (event.repeatCount > 0) return true
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_R1 -> { ptz.buttonZoom = ZOOM_SPEED; return true }
                KeyEvent.KEYCODE_BUTTON_L1 -> { ptz.buttonZoom = -ZOOM_SPEED; return true }
                KeyEvent.KEYCODE_BUTTON_A -> { toggleRecord(); return true }
                KeyEvent.KEYCODE_BUTTON_X -> { takePhoto(); return true }
                KeyEvent.KEYCODE_BUTTON_Y -> { goHome(); return true }
                KeyEvent.KEYCODE_BUTTON_B -> { setNight(irCut != "OFF"); return true }
            }
            hudMessage("Gamepad key: ${KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")}")
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

    // ------------------------------------------------------------------ misc

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val ZOOM_SPEED = 0.6f
        private const val HOME_PRESET = "10"
        private const val WIPER_RUN_MS = 6000L
        private const val MIN_VIDEO_BYTES = 50_000L
        private const val AUTO_HIDE_MS = 4000L
        private const val STALL_TICKS = 6

        private val PRESET_NAMES = listOf("FRONT", "REAR", "LEFT", "RIGHT", "UP", "DOWN")

        /** Camera preset numbers for the named presets (avoid 32–99: special functions on many PTZs). */
        private val DEFAULT_PRESET_TOKEN = mapOf(
            "FRONT" to "11", "REAR" to "12", "LEFT" to "13",
            "RIGHT" to "14", "UP" to "15", "DOWN" to "16",
        )
    }
}
