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
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
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
import kotlinx.coroutines.suspendCancellableCoroutine
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
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.tan

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
    private lateinit var railPhoto: RailButton
    private lateinit var railRec: RailButton
    // model-specific rail buttons (null when the current camera doesn't have them)
    private var railNight: RailButton? = null
    private var railWipe: RailButton? = null
    private var railView: RailButton? = null
    private var railPalette: RailButton? = null
    private var railMode: RailButton? = null

    private lateinit var modelText: TextView
    private lateinit var tempText: TextView
    private lateinit var debugText: TextView

    private lateinit var presetOverlay: View
    private lateinit var presetDial: PresetDialView
    private lateinit var settingsOverlay: View
    private lateinit var settingsPanel: View
    private lateinit var settingsContent: LinearLayout
    private lateinit var connectOverlay: View

    private enum class Step { LINK, ONVIF, VIDEO }
    private enum class StepState { PENDING, ACTIVE, OK, FAIL }
    private class StepRow(val title: TextView, val icon: TextView, val progress: ProgressBar, val detail: TextView)
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
    private var lastBytes = -1L
    private var lastBytesTime = 0L
    private var lastFrames = -1L
    private var zeroTicks = 0          // seconds without any sign of new video (for the WEAK badge)
    private var kbps = 0
    /** Last time (elapsedRealtime) LibVLC showed the stream is moving. Written from the VLC thread. */
    @Volatile private var lastProgressMs = 0L
    /** Latency watchdog: smallest (wall clock − stream time) seen since the stream started. */
    private var minClockOffset = Long.MAX_VALUE
    private var playingSinceMs = 0L
    private var lagTicks = 0
    private var lastStreamTime = -1L

    // joystick channels → pan / tilt / zoom
    private var axisMap: MutableMap<AxisRole, AxisBinding> = mutableMapOf()
    private var axisDead = AxisMapping.DEFAULT_DEADZONE
    private val lastAxisValues = HashMap<Int, Float>()
    private var lastJoyDevice = ""
    private var axisLearner: AxisLearner? = null
    private var axisMonitor: TextView? = null
    private val axisLabels = LinkedHashMap<AxisRole, TextView>()
    private var axisDeadLabel: TextView? = null
    private var monitorUpdatedMs = 0L

    private var recording = false
    private var recDir: File? = null
    private var recStamp = ""
    private var recSegment = 0
    private var recStartMs = 0L
    private var recPulse: ObjectAnimator? = null
    private var photoBusy = false
    private var wiperOn = false

    // camera drivers (only one is active, depending on the selected model)
    private var onvif: OnvifClient? = null
    private var siyi: SiyiClient? = null
    private var siyiJob: Job? = null
    private var aimJob: Job? = null
    private var viewIndex = 0          // ZT6: 0 RGB, 1 thermal, 2 picture-in-picture
    private var paletteIndex = 0
    private var streamIndex = 0        // ZT6: 0 = first RTSP stream, 1 = second
    private val debugLines = ArrayDeque<String>()

    // tracking · patrol · tap-to-aim · button mapping
    private lateinit var trackOverlay: TrackOverlayView
    private lateinit var modeChip: TextView
    private lateinit var btnPatrol: Button
    private lateinit var patrolSummary: TextView
    private lateinit var toolGallery: View
    private lateinit var toolSettings: View
    private lateinit var tracking: TrackingController
    private lateinit var patrol: PatrolRunner
    private var railTrack: RailButton? = null
    private var patrolStep = ""
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var batteryTick = 0
    private val copyThread = HandlerThread("frame-copy").apply { start() }
    private val copyHandler by lazy { Handler(copyThread.looper) }

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
        axisMap = AxisMapping.load(prefs)
        axisDead = AxisMapping.deadzone(prefs)
        bindViews()

        libVlc = LibVLC(
            this,
            arrayListOf(
                // Low-latency playback: show frames as soon as they are decoded instead of
                // scheduling them against a buffered clock. Late frames are dropped again (VLC's
                // default) – keeping them made the delay grow while panning.
                "--clock-jitter=0", "--clock-synchro=0",
                "--drop-late-frames", "--skip-frames",
                "--no-audio",               // no audio clock to wait for (the cameras send video only)
            )
        )
        player = MediaPlayer(libVlc)
        player.setEventListener(MediaPlayer.EventListener { ev ->
            when (val type = ev.type) {
                // fired continuously while frames are played – just a heartbeat, no UI work
                MediaPlayer.Event.TimeChanged, MediaPlayer.Event.PositionChanged ->
                    lastProgressMs = SystemClock.elapsedRealtime()
                else -> runOnUiThread { onPlayerEvent(type) }
            }
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

        // tap-to-aim (ZT6) and target selection for tracking
        swipePad.onDoubleTap = { x, y -> aimAtPoint(x, y) }
        swipePad.onSelecting = { r -> trackOverlay.showSelection(r) }
        swipePad.onSelected = { r -> startTrackingAt(r) }

        toolGallery.setOnClickListener {
            it.tick()
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        toolSettings.setOnClickListener { it.tick(); openSettings() }
        modeChip.setOnClickListener { it.tick(); stopAutoModes(announce = true) }
        btnPatrol.setOnClickListener { it.tick(); togglePatrol() }

        tracking = TrackingController(
            lifecycleScope, ptz,
            grabFrame = { w -> grabVideoFrame(w) },
            angleScale = { trackingAngleScale() },
            listener = trackListener,
            fovDeg = { thermal -> trackingFov(thermal) },
            attitude = { trackingAttitude() },
            videoDelayMs = { trackingVideoDelay() },
        )
        patrol = PatrolRunner(
            lifecycleScope,
            goTo = { name -> patrolGoTo(name) },
            shouldAbort = { ptz.manualActive },
            onStep = { name -> patrolStep = name; updateModeUi() },
            onFinished = { reason -> onPatrolFinished(reason) },
        )

        buildRail()
        setupPresetDial()
        setupSettingsSheet()
        setupConnectScreen()

        hud.insetLeft = 80 * dp
        hud.insetRight = 86 * dp
        hud.insetTop = 54 * dp
        applyModelUi()
        updateNightUi()
        updateLinkUi()
        if (!settings.isSiyi && settings.pass.isEmpty()) ui.postDelayed({ openSettings() }, 400)
    }

    override fun onStart() {
        super.onStart()
        player.attachViews(videoLayout, null, false, false)
        ptz.start()
        connectCamera()
        ui.post(statsTicker)
        scheduleHide()
    }

    override fun onStop() {
        super.onStop()
        stopAutoModes(announce = false)
        if (recording) stopRecording(restartStream = false)
        if (wiperOn) setWiper(false)
        ui.removeCallbacks(statsTicker)
        ui.removeCallbacks(hideControls)
        onvifJob?.cancel()
        statusJob?.cancel()
        siyiJob?.cancel()
        aimJob?.cancel()
        ptz.halt()
        closeSiyi()
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
        copyThread.quitSafely()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        when {
            settingsOverlay.visibility == View.VISIBLE -> closeSettings()
            swipePad.selectMode -> cancelSelect()
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
        modelText = findViewById(R.id.modelText)
        tempText = findViewById(R.id.tempText)
        debugText = findViewById(R.id.debugText)
        trackOverlay = findViewById(R.id.trackOverlay)
        modeChip = findViewById(R.id.modeChip)
        btnPatrol = findViewById(R.id.btnPatrol)
        patrolSummary = findViewById(R.id.patrolSummary)
        toolGallery = findViewById(R.id.btnGallery)
        toolSettings = findViewById(R.id.btnSettings)
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
        rail.removeAllViews()
        railNight = null; railWipe = null; railView = null; railPalette = null; railMode = null; railTrack = null
        railPresets = railButton(R.drawable.ic_compass, "PRESETS", { openPresets() })
        railTrack = railButton(R.drawable.ic_track, "TRACK", { toggleTracking() })
        if (settings.isSiyi) {
            railView = railButton(R.drawable.ic_view, VIEW_LABELS[viewIndex], { cycleView() }, { switchStream() })
            railPalette = railButton(R.drawable.ic_thermo, "PALETTE", { cyclePalette() })
        } else {
            railNight = railButton(R.drawable.ic_moon, "NIGHT", { setNight(irCut != "OFF") }, { setNight(null) })
            railWipe = railButton(R.drawable.ic_wiper, "WIPE", { setWiper(!wiperOn) })
        }
        railPhoto = railButton(R.drawable.ic_camera, "PHOTO", { takePhoto() })
        railRec = railButton(R.drawable.ic_record, "REC", { toggleRecord() })
        railRec.accent = ContextCompat.getColor(this, R.color.rec)
        if (recording) {
            railRec.isSelected = true
            railRec.setIcon(R.drawable.ic_stop)
            railRec.setLabel("STOP")
        }
        if (settings.isSiyi) {
            railButton(R.drawable.ic_center, "CENTER", { centerGimbal() })
            railMode = railButton(R.drawable.ic_lock, MODE_LABELS[0], { cycleMotionMode() })
        } else {
            railButton(R.drawable.ic_home, "HOME", { goHome() }, { saveHome() })
        }
        updateNightUi()
        updateModeUi()
    }

    /** Rail, HUD instruments, badges and connect-screen titles for the selected camera. */
    private fun applyModelUi() {
        buildRail()
        hud.instruments = settings.isSiyi
        hud.heading = null
        hud.tilt = null
        modelText.text = if (settings.isSiyi) "ZT6" else "SHJ-TW10"
        tempText.visibility = View.GONE
        debugText.visibility = if (settings.debug) View.VISIBLE else View.GONE
        steps[Step.ONVIF]?.title?.text = if (settings.isSiyi) "GIMBAL" else "ONVIF"
        zoomText.text = "1.0×"
        swipePad.doubleTapEnabled = settings.isSiyi          // tap-to-aim is ZT6 only
        tracking.gain = settings.trackGain
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
        settingsOverlay.visibility == View.VISIBLE || connectOverlay.visibility == View.VISIBLE ||
        swipePad.selectMode

    private fun scheduleHide() {
        ui.removeCallbacks(hideControls)
        if (!anyOverlayOpen()) ui.postDelayed(hideControls, AUTO_HIDE_MS)
    }

    private fun setControlsVisible(show: Boolean) {
        controlsShown = show
        for (v in listOf(rail, zoomGroup, toolGallery, toolSettings)) {
            v.animate().cancel()
            val slide = when (v) {
                rail -> 30 * dp
                zoomGroup -> -30 * dp
                else -> 0f
            }
            if (show) {
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).translationX(0f).setDuration(220).start()
            } else {
                v.animate().alpha(0f).translationX(slide).setDuration(260)
                    .withEndAction { if (!controlsShown) v.visibility = View.INVISIBLE }.start()
            }
        }
        if (show) scheduleHide() else ui.removeCallbacks(hideControls)
    }

    private fun View.tick() = performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

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
            val t = v.findViewById<TextView>(R.id.stepTitle)
            t.text = title
            return StepRow(t, v.findViewById(R.id.stepIcon), v.findViewById(R.id.stepProgress), v.findViewById(R.id.stepDetail))
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

    private fun connectCamera() {
        if (settings.isSiyi) connectSiyi() else connectOnvif()
    }

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
                    onvif = ok.first
                    ptz.client = ok.first
                    setControlBadge(true)
                    onvifRtsp = ok.second
                    setStep(Step.ONVIF, StepState.OK, "PTZ ready")
                    if (!isLive) setStep(Step.VIDEO, StepState.ACTIVE, "Opening stream…")
                    if (!playerStarted) playStream()
                    readNightState(ok.first)
                    startStatusPolling(ok.first)
                    return@launch
                }
                setStep(Step.ONVIF, StepState.FAIL, result.exceptionOrNull()?.message ?: "Login failed")
                setControlBadge(false)
                if (!playerStarted && s.rtspOverride.isNotBlank()) playStream()
                delay(4000)
            }
        }
    }

    private fun client(): OnvifClient? {
        val c = onvif
        if (c == null) hudMessage("Camera not connected yet", error = true)
        return c
    }

    private fun siyiClient(): SiyiClient? {
        val c = siyi
        if (c == null) hudMessage(siyiError ?: "Gimbal control is still connecting…", error = true)
        return c
    }

    /** Runs a gimbal command off the UI thread; shows an error if it fails. */
    private fun siyiCmd(ok: String? = null, block: (SiyiClient) -> Unit) {
        val c = siyiClient() ?: return
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block(c) } }
            if (r.isSuccess) {
                if (ok != null) hudMessage(ok)
            } else {
                hudMessage("Gimbal: ${r.exceptionOrNull()?.message}", error = true)
            }
        }
    }

    // ------------------------------------------------------------------ SIYI ZT6

    /** Why the gimbal control link is down (shown when a control is used), null when connected. */
    private var siyiError: String? = null

    private fun rtspHost(url: String): String? =
        Regex("^\\w+://(?:[^@/]*@)?([^:/]+)").find(url.trim())?.groupValues?.get(1)

    /**
     * Connects the SIYI control link (UDP, separate from the RTSP video). Tries the configured
     * gimbal IP first, then the IP of the video URL(s) – if the video plays, the gimbal is there.
     */
    private fun connectSiyi() {
        onvifJob?.cancel()
        val s = settings
        onvifJob = lifecycleScope.launch {
            var attempt = 0
            while (isActive) {
                attempt++
                setControlBadge(null)
                setStep(Step.LINK, StepState.ACTIVE, "UDP ${s.host}:${s.port}")
                val hosts = (listOf(s.host) + listOfNotNull(rtspHost(s.rtspOverride), rtspHost(s.rtsp2))).distinct()
                var connected: SiyiClient? = null
                var result = SiyiClient.Handshake.NO_REPLY
                var invalidHost = false
                // Like UniGCS / SIYI FPV: SIYI SDK over UDP first, then over TCP on the same port
                // (TCP gets through networks / radio links that do not pass the gimbal's UDP).
                // the control port is always 37260 on SIYI gimbals; try it if a different port was entered
                val ports = listOf(s.port, SiyiClient.DEFAULT_PORT).distinct()
                search@ for (h in hosts) for (port in ports) {
                    for (mode in SiyiClient.Transport.values()) {
                        setStep(Step.LINK, StepState.ACTIVE, "${mode.name} $h:$port")
                        val c = SiyiClient(h, port)
                        c.onFeedback = { msg -> runOnUiThread { hudMessage(msg) } }
                        c.onDebug = { line -> runOnUiThread { addDebug(line) } }
                        val r = withContext(Dispatchers.IO) {
                            runCatching { c.open(mode); c.handshake() }.getOrElse { e ->
                                if (e is java.net.UnknownHostException && h == s.host) invalidHost = true
                                addDebugAsync("${mode.name} $h: ${e.message}")
                                SiyiClient.Handshake.NO_REPLY
                            }
                        }
                        if (r == SiyiClient.Handshake.OK) { connected = c; break@search }
                        withContext(Dispatchers.IO) { c.close() }
                        if (r == SiyiClient.Handshake.BAD_REPLY) result = r
                        if (invalidHost) break@search
                    }
                }

                if (connected == null) {
                    val msg = when {
                        invalidHost -> "Gimbal IP “${s.host}” is not a valid address – fix it in Settings"
                        result == SiyiClient.Handshake.BAD_REPLY ->
                            "Gimbal answers but the replies are unreadable – turn on Settings → Gimbal debug and send a screenshot"
                        else -> "Gimbal control not answering on ${s.host}:${s.port} (tried UDP and TCP). " +
                            "Video works separately – check the gimbal IP / port in Settings"
                    }
                    siyiError = msg
                    setControlBadge(false)
                    setStep(Step.LINK, StepState.FAIL, msg)
                    if (!playerStarted) playStream()                  // the video may still work
                    if (attempt == 1 || attempt % 10 == 0) hudMessage(msg, error = true)
                    delay(3000)
                    continue
                }

                if (connected.host != s.host || connected.port != s.port) {
                    // found via the video URL / standard port: remember the working address
                    settings = s.copy(host = connected.host, port = connected.port)
                    settings.save(prefs)
                    hudMessage("Gimbal control found at ${connected.host}:${connected.port} – saved")
                }
                siyi = connected
                siyiError = null
                ptz.client = connected
                setControlBadge(true)
                setStep(Step.LINK, StepState.OK, connected.host)
                setStep(Step.ONVIF, StepState.OK, "${connected.modelName} ready (${connected.transport.name})")
                addDebug("Control link: ${connected.transport.name} ${connected.host}:${connected.port}")
                if (attempt > 1 || connected.transport == SiyiClient.Transport.TCP) {
                    hudMessage("Gimbal control connected (${connected.transport.name})")
                }
                if (!isLive) setStep(Step.VIDEO, StepState.ACTIVE, "Opening stream…")
                if (!playerStarted) playStream()
                startSiyiPolling(connected)
                return@launch
            }
        }
    }

    /** Model badge colour = control link state: green connected, red failed, grey connecting. */
    private fun setControlBadge(ok: Boolean?) {
        val col = when (ok) {
            true -> R.color.ok
            false -> R.color.err
            null -> R.color.text_dim
        }
        modelText.setTextColor(ContextCompat.getColor(this, col))
    }

    /** Settings → Gimbal: one-off link test with the values currently typed in the form. */
    private fun testGimbalLink() {
        val host = formHost?.text?.toString()?.trim().orEmpty()
        val port = formPort?.text?.toString()?.toIntOrNull() ?: SiyiClient.DEFAULT_PORT
        if (!HOST_REGEX.matches(host)) return hudMessage("Enter a valid gimbal IP first", error = true)
        hudMessage("Testing gimbal control at $host:$port …")
        lifecycleScope.launch {
            val c = SiyiClient(host, port)
            val udp = withContext(Dispatchers.IO) {
                val res = runCatching { c.open(SiyiClient.Transport.UDP); c.handshake(3000) }
                    .getOrDefault(SiyiClient.Handshake.NO_REPLY)
                c.close()
                res
            }
            if (udp == SiyiClient.Handshake.OK) {
                return@launch hudMessage("✓ ${c.modelName} answered over UDP from ${c.lastSource ?: host} – control OK")
            }
            val t = SiyiClient(host, port)
            val tcp = withContext(Dispatchers.IO) {
                val res = runCatching { t.open(SiyiClient.Transport.TCP); t.handshake(3000) }
                    .getOrElse { e -> addDebugAsync("TCP test: ${e.message}"); SiyiClient.Handshake.NO_REPLY }
                t.close()
                res
            }
            if (tcp == SiyiClient.Handshake.OK) {
                return@launch hudMessage("✓ ${t.modelName} answered over TCP (UDP is blocked on this network) – control OK")
            }
            when (udp) {
                SiyiClient.Handshake.OK -> Unit
                SiyiClient.Handshake.BAD_REPLY ->
                    hudMessage("Answered from ${c.lastSource} but ${c.badPackets} replies were unreadable", error = true)
                SiyiClient.Handshake.NO_REPLY ->
                    hudMessage("No reply from $host:$port over UDP or TCP – check the IP, cable and port", error = true)
            }
        }
    }

    private fun closeSiyi() {
        val c = siyi ?: return
        siyi = null
        if (ptz.client === c) ptz.client = null
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { c.stop() }
            c.close()
        }
    }

    /** Attitude at 10 Hz, thermal min/max stream, config; updates HUD + badges. */
    private fun startSiyiPolling(c: SiyiClient) {
        siyiJob?.cancel()
        siyiJob = lifecycleScope.launch {
            var tick = 0
            var badge: Boolean? = true
            while (isActive) {
                if (!c.isOpen) {                                   // TCP session dropped
                    siyiError = "Gimbal control link lost – reconnecting…"
                    hudMessage(siyiError!!, error = true)
                    setControlBadge(false)
                    closeSiyi()
                    connectSiyi()
                    return@launch
                }
                val healthy = SystemClock.elapsedRealtime() - c.lastRxTime < 5000
                if (healthy != badge) {
                    badge = healthy
                    setControlBadge(healthy)
                    siyiError = if (healthy) null else "Gimbal is not answering – check the link"
                }
                withContext(Dispatchers.IO) {
                    runCatching {
                        c.requestAttitude()
                        if (tick % 30 == 0 && SystemClock.elapsedRealtime() - c.tempTime > 3000) c.requestThermalStream()
                        if (tick % 20 == 5) c.requestConfig()
                    }
                }
                val now = SystemClock.elapsedRealtime()
                val att = c.attitude
                if (att != null && now - c.attitudeTime < 1500) {
                    hud.heading = wrap360(-att.yaw)          // SIYI yaw + = left → clockwise heading
                    hud.tilt = att.pitch
                } else {
                    hud.heading = null
                    hud.tilt = null
                }
                c.zoomX?.let { zoomText.text = String.format(Locale.US, "%.1f×", it) }
                val tMax = c.tempMax
                val tMin = c.tempMin
                if (tMax != null && tMin != null && now - c.tempTime < 3000) {
                    tempText.text = String.format(Locale.US, "▲%.1f° ▼%.1f°C", tMax, tMin)
                    tempText.visibility = View.VISIBLE
                } else {
                    tempText.visibility = View.GONE
                }
                c.motionMode?.let { m -> railMode?.setLabel(MODE_LABELS.getOrElse(m) { "MODE" }) }
                tick++
                delay(if (tracking.isRunning) 50 else 100)       // 20 Hz angles while tracking
            }
        }
    }

    private fun wrap360(v: Float) = ((v % 360f) + 360f) % 360f

    private fun centerGimbal() {
        aimJob?.cancel()
        siyiCmd("Gimbal centred") { it.center() }
    }

    private fun cycleView() {
        stopTracking(announce = tracking.isRunning)     // picture changes: the tracked template no longer matches
        viewIndex = (viewIndex + 1) % VIEW_TYPES.size
        val type = VIEW_TYPES[viewIndex]
        railView?.setLabel(VIEW_LABELS[viewIndex])
        siyiCmd("View: ${VIEW_NAMES[viewIndex]}") { it.setImageType(type) }
    }

    /** Long-press VIEW: switch between the gimbal's two RTSP streams. */
    private fun switchStream() {
        if (settings.rtsp2.isBlank()) return hudMessage("Second stream URL not set (Settings)", error = true)
        stopTracking(announce = tracking.isRunning)
        streamIndex = 1 - streamIndex
        hudMessage("Stream ${streamIndex + 1}: ${currentStreamUrl()}")
        if (wantPlaying) playStream()
    }

    private fun cyclePalette() {
        paletteIndex = (paletteIndex + 1) % PALETTES.size
        val (value, name) = PALETTES[paletteIndex]
        siyiCmd("Palette: $name") { it.setPalette(value) }
    }

    private fun cycleMotionMode() {
        val next = ((siyi?.motionMode ?: 0) + 1) % 3
        railMode?.setLabel(MODE_LABELS[next])
        siyiCmd("Gimbal mode: ${MODE_NAMES[next]}") { it.setMotionMode(next) }
    }

    private fun addDebugAsync(line: String) = runOnUiThread { addDebug(line) }

    private fun addDebug(line: String) {
        if (!settings.debug) return
        debugLines.addLast(line)
        while (debugLines.size > 4) debugLines.removeFirst()
        debugText.text = debugLines.joinToString("\n")
    }

    // ---------------- ZT6 presets: angles stored in the app, reached by closed-loop aiming

    private fun siyiPresetKey(name: String) = "zt6_preset_$name"

    /** Stored (heading°, pitch°) or the built-in default for that direction. */
    private fun siyiPreset(name: String): Pair<Float, Float> {
        val raw = prefs.getString(siyiPresetKey(name), null)
        if (raw != null) {
            val parts = raw.split(",")
            val h = parts.getOrNull(0)?.toFloatOrNull()
            val p = parts.getOrNull(1)?.toFloatOrNull()
            if (h != null && p != null) return h to p
        }
        val curH = siyi?.attitude?.let { -it.yaw } ?: 0f
        return when (name) {
            "FRONT" -> 0f to 0f
            "REAR" -> 180f to 0f
            "LEFT" -> -90f to 0f
            "RIGHT" -> 90f to 0f
            "UP" -> curH to 25f
            else -> curH to -90f          // DOWN
        }
    }

    private enum class AimResult { REACHED, CANCELLED, TIMEOUT, WRONG_WAY, NO_DATA }

    /**
     * Closed-loop aim of the ZT6 at (heading°, pitch°) using its attitude feedback.
     * Suspends until the gimbal is within 1° or the attempt ends; always leaves the gimbal still.
     */
    private suspend fun aimTo(c: SiyiClient, targetH: Float, targetP: Float, timeoutMs: Long = 12000): AimResult {
        val tP = targetP.coerceIn(PITCH_MIN, PITCH_MAX)
        val start = SystemClock.elapsedRealtime()
        var worst = Float.MAX_VALUE
        var result: AimResult? = null
        try {
            while (result == null) {
                val att = c.attitude
                val now = SystemClock.elapsedRealtime()
                result = when {
                    ptz.manualActive -> AimResult.CANCELLED
                    att == null || now - c.attitudeTime > 1500 -> AimResult.NO_DATA
                    else -> {
                        val errH = targetH - (-att.yaw)            // degrees, + = turn right
                        val errP = tP - att.pitch                  // degrees, + = tilt up
                        val err = maxOf(abs(errH), abs(errP))
                        if (err < worst) worst = err
                        when {
                            err < 1.0f -> AimResult.REACHED
                            now - start > timeoutMs -> AimResult.TIMEOUT
                            err > worst + 25f -> AimResult.WRONG_WAY   // moving away → direction mismatch
                            else -> {
                                ptz.autoPan = aimSpeed(errH)
                                ptz.autoTilt = aimSpeed(errP)
                                null
                            }
                        }
                    }
                }
                if (result == null) delay(100)
            }
        } finally {
            ptz.autoPan = 0f
            ptz.autoTilt = 0f
        }
        return result ?: AimResult.CANCELLED
    }

    private fun reportAim(what: String, r: AimResult, quiet: Boolean = false) {
        when (r) {
            AimResult.REACHED -> if (!quiet) hudMessage("$what reached")
            AimResult.CANCELLED -> hudMessage("Aiming cancelled")
            AimResult.TIMEOUT -> hudMessage("$what: timed out", error = true)
            AimResult.WRONG_WAY -> hudMessage("$what: gimbal moved the wrong way – stopped", error = true)
            AimResult.NO_DATA -> hudMessage("No gimbal angle data – check the connection", error = true)
        }
    }

    private fun gotoSiyiPreset(name: String) {
        val c = siyiClient() ?: return
        if (c.attitude == null) return hudMessage("No gimbal angle data yet", error = true)
        closePresets()
        val (targetH, targetP) = siyiPreset(name)
        stopAutoModes(announce = false)
        aimJob = lifecycleScope.launch {
            hudMessage("→ $name")
            reportAim(name, aimTo(c, targetH, targetP))
        }
    }

    // ---------------- tap-to-aim (ZT6): double-tap a point → that point becomes the centre

    private fun aimAtPoint(x: Float, y: Float) {
        if (!settings.isSiyi) return
        val c = siyiClient() ?: return
        val att = c.attitude
        if (att == null || SystemClock.elapsedRealtime() - c.attitudeTime > 1500) {
            return hudMessage("No gimbal angle data yet", error = true)
        }
        val vr = videoRect(swipePad)
        if (vr.width() <= 0f || !vr.contains(x, y)) return
        stopAutoModes(announce = false)

        // pinhole camera model: angle = atan(2 · offset · tan(FOV/2) / zoom)
        val nx = (x - vr.centerX()) / vr.width()              // -0.5 … 0.5, + = right
        val ny = (y - vr.centerY()) / vr.height()             // -0.5 … 0.5, + = down
        val hfov = (if (viewIndex == 1) settings.fovThermal else settings.fovRgb).coerceIn(5f, 170f)
        val zoomX = (c.zoomX ?: 1f).coerceAtLeast(1f)
        val tanH = tan(Math.toRadians(hfov / 2.0)) / zoomX
        val tanV = tanH * vr.height() / vr.width()
        val dYaw = Math.toDegrees(atan(2.0 * nx * tanH)).toFloat()
        val dPitch = -Math.toDegrees(atan(2.0 * ny * tanV)).toFloat()

        trackOverlay.flashAim(x, y)
        aimJob = lifecycleScope.launch {
            reportAim("Target", aimTo(c, -att.yaw + dYaw, att.pitch + dPitch, 6000), quiet = true)
        }
    }

    // ---------------- video geometry + frame capture (tracking, photo fallback)

    private fun videoSurface(): SurfaceView? = findViews(videoLayout, SurfaceView::class.java)
        .filter { it.width > 0 && it.height > 0 && it.holder.surface.isValid }
        .maxByOrNull { it.width * it.height }

    /** Where the video picture is drawn, in [target]'s coordinates (whole view if unknown). */
    private fun videoRect(target: View): RectF {
        val sv = videoSurface() ?: return RectF(0f, 0f, target.width.toFloat(), target.height.toFloat())
        val a = IntArray(2)
        val b = IntArray(2)
        sv.getLocationOnScreen(a)
        target.getLocationOnScreen(b)
        val l = (a[0] - b[0]).toFloat()
        val t = (a[1] - b[1]).toFloat()
        return RectF(l, t, l + sv.width, t + sv.height)
    }

    /** The displayed video frame scaled to [width] px (aspect kept), or null. Main thread. */
    private suspend fun grabVideoFrame(width: Int): Bitmap? {
        val sv = videoSurface() ?: return null
        val h = (width.toFloat() * sv.height / sv.width).roundToInt().coerceAtLeast(16)
        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(sv, bmp, { result ->
                    if (result == PixelCopy.SUCCESS) cont.resume(bmp) else { bmp.recycle(); cont.resume(null) }
                }, copyHandler)
            } catch (e: Exception) {
                bmp.recycle()
                cont.resume(null)
            }
        }
    }

    // ---------------- active tracking (both cameras)

    private val trackListener = object : TrackingController.Listener {
        override fun onTrackBox(box: RectF?, searching: Boolean, score: Float, fps: Float) {
            if (box == null) {
                trackOverlay.showTarget(null)
                return
            }
            val vr = videoRect(trackOverlay)
            val r = RectF(
                vr.left + box.left * vr.width(), vr.top + box.top * vr.height(),
                vr.left + box.right * vr.width(), vr.top + box.bottom * vr.height(),
            )
            if (searching) trackOverlay.showTarget(r, TrackOverlayView.State.SEARCHING, "SEARCHING…")
            else trackOverlay.showTarget(
                r, TrackOverlayView.State.TRACKING,
                "TRACKING  ${(score * 100).roundToInt()}%  ·  ${fps.roundToInt()} FPS",
            )
        }

        override fun onTrackInfo(message: String) {
            runOnUiThread { hudMessage(message) }
        }

        override fun onTrackEnded(message: String) {
            val bad = message.contains("lost") || message.startsWith("No ") || message.startsWith("Nothing")
            hudMessage(message, error = bad)
            updateModeUi()
        }
    }

    /**
     * Real angle covered by the screen relative to the visible camera at 1× (see TrackingController).
     * ZT6: thermal lens and zoom are known. SHJ: no field-of-view data → 1 (use Tracking response).
     */
    /** Horizontal FOV (degrees, zoom applied) of the thermal or visible picture – ZT6 only. */
    private fun trackingFov(thermal: Boolean): Float {
        if (!settings.isSiyi) return 60f
        val zoomX = (siyi?.zoomX ?: 1f).coerceAtLeast(1f)
        return if (thermal) settings.fovThermal / (if (viewIndex == 1) zoomX else 1f)
        else settings.fovRgb / zoomX
    }

    /** Live gimbal angle for tracking (ZT6), or null → tracking falls back to speed control. */
    private fun trackingAttitude(): TrackingController.Att? {
        val c = siyi ?: return null
        val a = c.attitude ?: return null
        val t = c.attitudeTime
        if (SystemClock.elapsedRealtime() - t > 600) return null
        return TrackingController.Att(t, -a.yaw, a.pitch)       // SIYI yaw + = left → + = right
    }

    /** How old the picture on screen is: Settings value, or buffer + typical link/decoder delay. */
    private fun trackingVideoDelay(): Long =
        if (settings.trackDelayMs > 0) settings.trackDelayMs.toLong()
        else settings.cachingMs + 220L

    private fun trackingAngleScale(): Float {
        if (!settings.isSiyi) return 1f
        val fov = if (viewIndex == 1) settings.fovThermal else settings.fovRgb
        val zoomX = (siyi?.zoomX ?: 1f).coerceAtLeast(1f)
        return (fov / settings.fovRgb.coerceAtLeast(1f)) / zoomX
    }

    private fun toggleTracking() {
        when {
            tracking.isRunning -> stopTracking(announce = true)
            swipePad.selectMode -> cancelSelect()
            else -> beginSelect()
        }
    }

    private fun beginSelect() {
        if (!isLive) return hudMessage("Wait for live video before tracking", error = true)
        if (ptz.client == null) return hudMessage("Camera not connected yet", error = true)
        stopAutoModes(announce = false)
        swipePad.selectMode = true
        hudMessage("Draw a box around the target – or tap it")
        setControlsVisible(true)
        ui.removeCallbacks(hideControls)
        updateModeUi()
    }

    private fun cancelSelect() {
        swipePad.selectMode = false
        trackOverlay.showSelection(null)
        updateModeUi()
        scheduleHide()
    }

    private fun startTrackingAt(viewBox: RectF) {
        swipePad.selectMode = false
        val vr = videoRect(swipePad)
        val sel = RectF(viewBox)
        if (vr.width() <= 0f || !sel.intersect(vr)) {
            hudMessage("Select something inside the video picture", error = true)
            updateModeUi()
            return
        }
        val norm = RectF(
            (sel.left - vr.left) / vr.width(), (sel.top - vr.top) / vr.height(),
            (sel.right - vr.left) / vr.width(), (sel.bottom - vr.top) / vr.height(),
        )
        tracking.gain = settings.trackGain
        tracking.start(norm)
        hudMessage("Tracking · swipe or press TRACK to stop")
        updateModeUi()
        scheduleHide()
    }

    private fun stopTracking(announce: Boolean) {
        val was = tracking.isRunning || swipePad.selectMode
        tracking.stop()
        swipePad.selectMode = false
        trackOverlay.showTarget(null)
        trackOverlay.showSelection(null)
        if (announce && was) hudMessage("Tracking stopped")
        updateModeUi()
    }

    // ---------------- patrol / scan (both cameras)

    private fun togglePatrol() {
        if (patrol.isRunning) {
            patrol.stop()
            hudMessage("Patrol stopped")
            updateModeUi()
        } else {
            startPatrol()
        }
    }

    private fun startPatrol() {
        if (ptz.client == null) return hudMessage("Camera not connected yet", error = true)
        val wanted = settings.patrolList
        val list = if (settings.isSiyi) wanted else wanted.filter { presetToken(it) != null }
        if (list.isEmpty()) {
            return hudMessage(
                if (wanted.isEmpty()) "No patrol presets selected – choose them in Settings → Patrol"
                else "Save the patrol presets first: aim the camera and hold a preset on the dial",
                error = true,
            )
        }
        if (list.size < wanted.size) hudMessage("Patrol skips presets that are not saved yet")
        stopTracking(announce = false)
        aimJob?.cancel()
        closePresets()
        patrolStep = list.first()
        patrol.start(list, settings.patrolDwellSec * 1000L, settings.patrolLoop)
        updateModeUi()
    }

    /** Moves to one patrol preset; true once there, false if it failed or the user took over. */
    private suspend fun patrolGoTo(name: String): Boolean {
        return if (settings.isSiyi) {
            val c = siyi ?: return false
            val (h, p) = siyiPreset(name)
            when (aimTo(c, h, p)) {
                AimResult.REACHED, AimResult.TIMEOUT -> true   // close enough: carry on
                else -> false
            }
        } else {
            val c = onvif ?: return false
            val token = presetToken(name) ?: return true          // not saved: skip it
            val sent = withContext(Dispatchers.IO) { runCatching { c.gotoPreset(token) }.isSuccess }
            if (!sent) return false
            // the SHJ does not report its position: allow travel time before the dwell starts
            var waited = 0L
            while (waited < SHJ_TRAVEL_MS) {
                if (ptz.manualActive) return false
                delay(100)
                waited += 100
            }
            true
        }
    }

    private fun onPatrolFinished(reason: String) {
        hudMessage(reason, error = reason.contains("could not"))
        updateModeUi()
    }

    /** Stops patrol, tracking, target selection and any automatic aiming. */
    private fun stopAutoModes(announce: Boolean) {
        val hadPatrol = patrol.isRunning
        patrol.stop()
        aimJob?.cancel()
        aimJob = null
        stopTracking(announce)
        if (announce && hadPatrol) hudMessage("Patrol stopped")
        updateModeUi()
    }

    private fun patrolSummaryText(): String {
        val list = settings.patrolList
        if (list.isEmpty()) return "No presets selected (Settings → Patrol)"
        return list.joinToString(" → ") + " · ${settings.patrolDwellSec} s" + if (settings.patrolLoop) " · loop" else ""
    }

    /** Rail highlights, mode chip and patrol button reflect patrol / tracking / selection state. */
    private fun updateModeUi() {
        val isTracking = tracking.isRunning
        val isSelecting = swipePad.selectMode
        val isPatrolling = patrol.isRunning
        railTrack?.isSelected = isTracking || isSelecting
        railPresets.isSelected = isPatrolling
        when {
            isPatrolling -> { modeChip.text = "■  PATROL · $patrolStep"; modeChip.visibility = View.VISIBLE }
            isTracking -> { modeChip.text = "■  TRACKING"; modeChip.visibility = View.VISIBLE }
            isSelecting -> { modeChip.text = "✕  SELECT TARGET"; modeChip.visibility = View.VISIBLE }
            else -> modeChip.visibility = View.GONE
        }
        btnPatrol.text = if (isPatrolling) "STOP PATROL" else "START PATROL"
        btnPatrol.isSelected = isPatrolling
        patrolSummary.text = patrolSummaryText()
    }

    /** P-controller: 1.5 °/s per ° of error, 90 °/s = full speed, small minimum to beat the deadband. */
    private fun aimSpeed(errDeg: Float): Float {
        if (abs(errDeg) < 0.8f) return 0f
        val v = (errDeg * 1.5f / 90f).coerceIn(-1f, 1f)
        return if (abs(v) < 0.06f) 0.06f * kotlin.math.sign(v) else v
    }

    private fun saveSiyiPreset(name: String) {
        val att = siyi?.attitude ?: return hudMessage("No gimbal angle data yet", error = true)
        prefs.edit().putString(siyiPresetKey(name), "${-att.yaw},${att.pitch}").apply()
        presetDial.saved = savedPresets()
        hudMessage("$name saved")
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

    private fun savedPresets(): Set<String> =
        if (settings.isSiyi) PRESET_NAMES.toSet()      // ZT6 directions work out of the box
        else PRESET_NAMES.filter { presetToken(it) != null }.toSet()

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
        if (settings.isSiyi) return gotoSiyiPreset(name)
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
        if (settings.isSiyi) return saveSiyiPreset(name)
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
        railNight?.isSelected = irCut == "OFF"
        railNight?.setLabel(if (irCut == "AUTO") "AUTO" else "NIGHT")
        when (irCut) {
            "OFF" -> { nightBadge.text = "IR ON"; nightBadge.visibility = View.VISIBLE }
            "AUTO" -> { nightBadge.text = "IR AUTO"; nightBadge.visibility = View.VISIBLE }
            else -> nightBadge.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------ wiper

    private val wiperAutoOff = Runnable { if (wiperOn) setWiper(false) }

    private fun setWiper(on: Boolean) {
        val c = onvif ?: run {
            if (on) hudMessage("Camera not connected yet", error = true)
            wiperOn = false
            railWipe?.isSelected = false
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
                    railWipe?.isSelected = false
                    val list = c.auxCommands.ifEmpty { listOf("none reported") }.joinToString(", ")
                    hudMessage("Wiper refused · camera commands: $list", error = true)
                }
                used == "preset" -> {
                    wiperOn = false
                    railWipe?.isSelected = false
                    hudMessage("Wiper started")
                }
                on -> {
                    wiperOn = true
                    railWipe?.isSelected = true
                    ui.postDelayed(wiperAutoOff, WIPER_RUN_MS)
                    hudMessage("Wiper ON")
                }
                else -> {
                    wiperOn = false
                    railWipe?.isSelected = false
                }
            }
        }
    }

    // ------------------------------------------------------------------ video

    private fun playStream() {
        val url = currentStreamUrl()
        if (url.isBlank()) {
            setStep(Step.VIDEO, StepState.FAIL, "No RTSP URL – set one in Settings")
            return
        }
        wantPlaying = true
        playerStarted = true
        isLive = false
        lastBytes = -1L
        lastFrames = -1L
        zeroTicks = 0
        kbps = 0
        lastProgressMs = SystemClock.elapsedRealtime()
        minClockOffset = Long.MAX_VALUE
        lagTicks = 0
        lastStreamTime = -1L
        updateLinkUi()

        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=${settings.cachingMs}")
            addOption(":live-caching=${settings.cachingMs}")
            // RTSP has its own cache in VLC. Keep it aligned with the network/live cache;
            // otherwise RTSP can retain its much larger default even when these are low.
            addOption(":rtsp-caching=${settings.cachingMs}")
            addOption(":clock-jitter=0")
            addOption(":clock-synchro=0")
            addOption(":no-audio")
            if (settings.rtspTcp) addOption(":rtsp-tcp")
            // live555's default 250 kB frame buffer truncates large 4K key frames (ZT6), which
            // corrupts the picture until the next key frame – give it room for 2 MB frames
            addOption(":rtsp-frame-buffer-size=$RTSP_FRAME_BUFFER")
            if (!url.contains("@") && settings.user.isNotBlank()) {
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

    private fun currentStreamUrl(): String =
        if (settings.isSiyi && streamIndex == 1 && settings.rtsp2.isNotBlank()) settings.rtsp2
        else settings.rtspOverride.ifBlank { onvifRtsp ?: "" }

    private fun stopStream() {
        wantPlaying = false
        playerStarted = false
        isLive = false
        ui.removeCallbacks(retry)
        player.stop()
        updateLinkUi()
    }

    private val retry = Runnable { if (wantPlaying) playStream() }

    /** Times (ms) at which a live UDP video stream dropped, within the last minute. */
    private val udpDrops = ArrayDeque<Long>()

    /**
     * Called when a live stream drops. Over UDP, a lost packet is never re-sent, so a lossy link
     * (radio, weak Wi-Fi) keeps breaking the picture. After [UDP_DROP_LIMIT] drops within
     * [UDP_DROP_WINDOW_MS], switch the video to TCP (re-sent packets, session kept alive), save
     * that choice, and restart immediately. Returns true if it restarted the stream itself.
     */
    private fun noteVideoDrop(): Boolean {
        if (settings.rtspTcp) return false
        val now = SystemClock.elapsedRealtime()
        udpDrops.addLast(now)
        while (udpDrops.isNotEmpty() && now - udpDrops.first() > UDP_DROP_WINDOW_MS) udpDrops.removeFirst()
        if (udpDrops.size < UDP_DROP_LIMIT) return false
        udpDrops.clear()
        settings = settings.copy(rtspTcp = true)
        settings.save(prefs)
        hudMessage("Video kept dropping over UDP – switched to TCP automatically (Settings → Video)", error = true)
        ui.removeCallbacks(retry)
        if (wantPlaying) playStream()
        return true
    }

    private fun onPlayerEvent(type: Int) {
        when (type) {
            MediaPlayer.Event.Playing, MediaPlayer.Event.Vout -> {
                if (type == MediaPlayer.Event.Vout && isLive) return
                isLive = true
                zeroTicks = 0
                lastProgressMs = SystemClock.elapsedRealtime()
                playingSinceMs = lastProgressMs
                minClockOffset = Long.MAX_VALUE
                lagTicks = 0
                setStep(Step.VIDEO, StepState.OK, "Live")
                ui.postDelayed({ if (isLive) hideConnectScreen() }, 700)
            }
            MediaPlayer.Event.EncounteredError, MediaPlayer.Event.EndReached -> {
                val wasLive = isLive
                isLive = false
                setStep(Step.VIDEO, StepState.FAIL, "Stream error – retrying…")
                if (wantPlaying && !(wasLive && noteVideoDrop())) {
                    ui.removeCallbacks(retry)
                    ui.postDelayed(retry, 2000)
                }
            }
        }
        updateLinkUi()
    }

    /**
     * Every second: bitrate, link quality, stall watchdog, clock, battery.
     *
     * The old watchdog only looked at the input "read bytes" counter. For RTSP (live555) LibVLC
     * leaves that counter at 0, so the bitrate showed "0 kbps" and every 6 s the app decided the
     * video had stalled, stopped the player and reconnected – even though the picture was fine.
     * That caused the jerky / slow picture and cut recordings into pieces.
     * Now the stream counts as moving if ANY of these advance: playback time events, read or
     * demuxed bytes, decoded or displayed frames. It is only restarted after [STALL_MS] of nothing.
     */
    private val statsTicker = object : Runnable {
        override fun run() {
            if (wantPlaying && isLive) {
                val now = SystemClock.elapsedRealtime()
                val m = player.media
                val st = runCatching { m?.stats }.getOrNull()
                m?.release()
                if (st != null) {
                    // bytes: input counter if LibVLC fills it, otherwise the demuxer counter
                    val bytes = maxOf(st.readBytes.toLong(), st.demuxReadBytes.toLong())
                    val frames = maxOf(st.displayedPictures.toLong(), st.decodedVideo.toLong())
                    if (bytes > 0 && lastBytes >= 0 && bytes >= lastBytes && now > lastBytesTime) {
                        val delta = bytes - lastBytes
                        kbps = (delta * 8.0 / ((now - lastBytesTime) / 1000.0) / 1000.0).toInt()
                        if (delta > 0) lastProgressMs = now
                    }
                    if (frames > 0 && lastFrames >= 0 && frames > lastFrames) lastProgressMs = now
                    lastBytes = bytes
                    lastFrames = frames
                    lastBytesTime = now
                }
                val idle = now - lastProgressMs
                zeroTicks = if (idle >= WEAK_MS) (idle / 1000).toInt() else 0
                checkLatencyBuildUp(now)
                if (idle >= STALL_MS) {
                    zeroTicks = 0
                    isLive = false
                    if (!noteVideoDrop()) {
                        hudMessage("Video stalled – reconnecting…", error = true)
                        player.stop()
                        ui.removeCallbacks(retry)
                        ui.postDelayed(retry, 500)
                    }
                }
            } else {
                kbps = 0
            }
            updateLinkUi()
            clockText.text = clockFormat.format(Date())
            if (batteryTick++ % 10 == 0) updateBattery()
            ui.postDelayed(this, 1000)
        }
    }

    /**
     * Delay watchdog. If the radio link delivers the stream slower than real time for a while
     * (e.g. the camera bitrate is higher than the link can carry), the backlog queues up in the
     * TCP connection and the picture falls further and further behind – the gimbal then seems to
     * react seconds late. Stream time advancing slower than the wall clock shows that backlog;
     * once it exceeds [CATCHUP_LAG_MS] the stream is re-opened, which drops the backlog at once.
     * Not done while recording (it would split the file) or during tracking.
     */
    private fun checkLatencyBuildUp(now: Long) {
        if (!settings.autoCatchUp || recording || tracking.isRunning) { lagTicks = 0; return }
        if (now - playingSinceMs < 3000) return                    // let the stream settle first
        val t = player.time
        if (t <= 0) return
        if (t < lastStreamTime) minClockOffset = Long.MAX_VALUE   // timestamp jump: start over
        lastStreamTime = t
        val offset = now - t
        if (offset < minClockOffset) minClockOffset = offset
        val lag = offset - minClockOffset
        lagTicks = if (lag > CATCHUP_LAG_MS) lagTicks + 1 else 0
        if (lagTicks >= 2) {
            lagTicks = 0
            hudMessage(String.format(Locale.US, "Video delay built up (%.1f s) – resyncing", lag / 1000.0))
            ui.removeCallbacks(retry)
            playStream()
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
            !isLive || kbps <= 0 -> "— kbps"
            kbps >= 1000 -> String.format(Locale.US, "%.1f Mbps", kbps / 1000f)
            else -> "$kbps kbps"
        }
    }

    private fun updateBattery() {
        val i = ContextCompat.registerReceiver(
            this, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        ) ?: return
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
        siyi?.let { g -> lifecycleScope.launch(Dispatchers.IO) { runCatching { g.takePhoto() } } }
        val c = onvif
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
        val surface = videoSurface()
        if (surface == null) {
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
        setSdRecording(true)
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

        setSdRecording(false)
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

    /** ZT6: mirror REC on the gimbal's own SD card (toggle only when state differs). */
    private fun setSdRecording(on: Boolean) {
        val c = siyi ?: return
        if (!settings.sdRecord) return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                c.requestConfig()
                Thread.sleep(300)
                val current = c.recordStatus == 1
                if (current != on) c.toggleSdRecording()
            }
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
    private var formCatchUp: CheckBox? = null
    private var formCache: EditText? = null
    private var formZoom: EditText? = null
    private var formWiper: EditText? = null
    private var formTcp: CheckBox? = null
    private var formInvert: CheckBox? = null
    private var formSpeed: SeekBar? = null
    private var formRtsp2: EditText? = null
    private var formSd: CheckBox? = null
    private var formDebug: CheckBox? = null
    private var formModel: String = CamSettings.MODEL_SHJ
    private val formPatrolChecks = LinkedHashMap<String, CheckBox>()
    private var formDwell: EditText? = null
    private var formPatrolLoop: CheckBox? = null
    private var formTrackGain: SeekBar? = null
    private var formTrackDelay: EditText? = null
    private var formFovRgb: EditText? = null
    private var formFovThermal: EditText? = null

    private fun setupSettingsSheet() {
        settingsOverlay.setOnClickListener { closeSettings() }
        findViewById<ImageButton>(R.id.settingsClose).setOnClickListener { closeSettings() }
        findViewById<Button>(R.id.settingsSave).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.settingsClear).setOnClickListener {
            prefs.edit().apply {
                PRESET_NAMES.forEach { remove("preset_$it"); remove("zt6_preset_$it") }
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
        endAxisLearn()
        axisMonitor = null
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

    /** Builds the side-sheet form for [model] (defaults to the current camera). */
    private fun buildSettingsForm(model: String = settings.model) {
        formModel = model
        val s = if (model == settings.model) settings else CamSettings.loadFor(prefs, model)
        val siyiModel = model == CamSettings.MODEL_ZT6
        settingsContent.removeAllViews()

        section("CAMERA MODEL")
        val spinner = Spinner(this)
        spinner.adapter = ModelAdapter(CamSettings.MODELS)
        spinner.setSelection(CamSettings.MODELS.indexOf(model).coerceAtLeast(0), false)
        spinner.setBackgroundResource(R.drawable.field_bg)
        spinner.setPopupBackgroundDrawable(ColorDrawable(0xFF0B111C.toInt()))
        spinner.setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val m = CamSettings.MODELS[position]
                if (m != formModel) settingsContent.post { buildSettingsForm(m) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        settingsContent.addView(spinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (46 * dp).toInt()
        ).apply { topMargin = (8 * dp).toInt() })
        label(
            if (siyiModel) "SIYI ZT6 · 4K + 640×512 thermal gimbal · SIYI SDK over UDP"
            else "SHJ-TW10-IR · IR PTZ camera · ONVIF"
        )

        section("CONNECTION")
        formHost = field(if (siyiModel) "Gimbal IP address" else "Camera IP address", s.host, InputType.TYPE_CLASS_TEXT)
        formPort = field(
            if (siyiModel) "Gimbal control port (UDP, default 37260)" else "ONVIF port (camera HTTP port)",
            s.port.toString(), InputType.TYPE_CLASS_NUMBER
        )
        if (siyiModel) {
            formUser = null
            formPass = null
        } else {
            formUser = field("Username", s.user, InputType.TYPE_CLASS_TEXT)
            formPass = field("Password", s.pass, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        }

        section("VIDEO")
        formRtsp = field(
            if (siyiModel) "Main RTSP stream" else "RTSP URL (empty = automatic from ONVIF)", s.rtspOverride,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        formRtsp2 = if (siyiModel) field(
            "Second RTSP stream (hold VIEW to switch)", s.rtsp2,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        ) else null
        formCache = field("Video buffer, ms (50 recommended; lower delay can stutter on weak links)", s.cachingMs.toString(), InputType.TYPE_CLASS_NUMBER)
        formTcp = check("RTSP over TCP (recommended on radio links)", s.rtspTcp)
        formCatchUp = check("Auto resync when the video delay builds up", s.autoCatchUp)

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
        formZoom = if (siyiModel) null
        else field("Camera optical zoom (×) – for the zoom readout", s.maxZoom.toString(), InputType.TYPE_CLASS_NUMBER)


        section("PATROL")
        label("Presets to visit, in this order: FRONT → RIGHT → REAR → LEFT → UP → DOWN")
        val chosen = s.patrolList.toSet()
        formPatrolChecks.clear()
        for (rowNames in PATROL_ORDER.chunked(3)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (name in rowNames) {
                val cb = CheckBox(this).apply {
                    text = name
                    isChecked = name in chosen
                    typeface = fontSemi
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_main))
                    buttonTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.accent))
                }
                formPatrolChecks[name] = cb
                row.addView(cb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            settingsContent.addView(row)
        }
        formDwell = field("Pause at each preset (seconds, 1 – 300)", s.patrolDwellSec.toString(), InputType.TYPE_CLASS_NUMBER)
        formPatrolLoop = check("Repeat continuously", s.patrolLoop)
        if (!siyiModel) label("SHJ-TW10: only presets saved on the dial are visited.")

        section("TRACKING")
        val gainLabel = label("")
        formTrackGain = SeekBar(this).apply {
            max = 100
            progress = (s.trackGain * 100).toInt()
            progressTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.accent))
            thumbTintList = progressTintList
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    gainLabel.text = "Tracking response · ${p.coerceAtLeast(20)} % (lower = smoother, higher = faster)"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        gainLabel.text = "Tracking response · ${formTrackGain!!.progress.coerceAtLeast(20)} % (lower = smoother, higher = faster)"
        settingsContent.addView(formTrackGain)
        formTrackDelay = field(
            "Tracking: video delay, ms (0 = automatic). Raise it if the camera still overshoots a still target.",
            s.trackDelayMs.toString(), InputType.TYPE_CLASS_NUMBER,
        )

        if (siyiModel) {
            section("TAP-TO-AIM")
            label("Double-tap the video to turn the gimbal to that point.")
            formFovRgb = field(
                "Visible camera horizontal field of view (°)", s.fovRgb.toString(),
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            )
            formFovThermal = field(
                "Thermal camera horizontal field of view (°)", s.fovThermal.toString(),
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            )
        } else {
            formFovRgb = null
            formFovThermal = null
        }

        if (siyiModel) {
            section("GIMBAL")
            formSd = check("Also record on the gimbal's SD card when REC is pressed", s.sdRecord)
            formDebug = check("Show gimbal debug line (TX / RX packets)", s.debug)
            settingsContent.addView(smallButton("TEST GIMBAL CONTROL LINK") { testGimbalLink() },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (40 * dp).toInt()).apply {
                    topMargin = (8 * dp).toInt()
                })
            formWiper = null
        } else {
            section("WIPER")
            formWiper = field("Wiper preset number (only if WIPE doesn't work)", s.wiperPreset, InputType.TYPE_CLASS_NUMBER)
            formSd = null
            formDebug = null
        }

        buildAxisSection()

        section("ABOUT")
        label("XeraCAM ${versionName()} · SHJ-TW10-IR (ONVIF) · SIYI ZT6 (SIYI SDK) · LibVLC")
    }

    /** White-on-dark dropdown rows for the camera-model spinner. */
    private inner class ModelAdapter(private val items: List<String>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        private fun row(position: Int, dropDown: Boolean) = TextView(this@MainActivity).apply {
            text = items[position]
            typeface = fontBold
            textSize = 16f
            letterSpacing = 0.05f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_main))
            val v = if (dropDown) 14 else 10
            setPadding((12 * dp).toInt(), (v * dp).toInt(), (12 * dp).toInt(), (v * dp).toInt())
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = row(position, false)
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup?): View = row(position, true)
    }

    /** Section header with a hairline divider above it (except for the first section). */
    private fun section(title: String) {
        if (settingsContent.childCount > 0) {
            settingsContent.addView(View(this).apply {
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.glass_stroke))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, (1 * dp).toInt())).apply {
                topMargin = (18 * dp).toInt()
            })
        }
        settingsContent.addView(TextView(this).apply {
            text = title
            typeface = fontBold
            letterSpacing = 0.25f
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
            setPadding(0, (14 * dp).toInt(), 0, (2 * dp).toInt())
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

    /** Shows an inline error on [field] and returns false if [ok] is false. */
    private fun validate(field: EditText?, ok: Boolean, message: String): Boolean {
        if (!ok) {
            field?.error = message
            field?.requestFocus()
            hudMessage(message, error = true)
        }
        return ok
    }

    private fun saveSettings() {
        val model = formModel
        val siyiModel = model == CamSettings.MODEL_ZT6
        val base = if (model == settings.model) settings else CamSettings.loadFor(prefs, model)

        val host = formHost?.text?.toString()?.trim().orEmpty()
        val port = formPort?.text?.toString()?.toIntOrNull()
        val dwell = formDwell?.text?.toString()?.toIntOrNull()
        val fovRgb = formFovRgb?.text?.toString()?.toFloatOrNull()
        val fovThermal = formFovThermal?.text?.toString()?.toFloatOrNull()
        if (!validate(formHost, HOST_REGEX.matches(host), "Enter a valid IP address or host name")) return
        if (!validate(formPort, port != null && port in 1..65535, "Port must be 1 – 65535")) return
        if (!validate(formDwell, dwell != null && dwell in 1..300, "Pause must be 1 – 300 seconds")) return
        if (formFovRgb != null &&
            !validate(formFovRgb, fovRgb != null && fovRgb in 5f..170f, "Field of view must be 5 – 170°")
        ) return
        if (formFovThermal != null &&
            !validate(formFovThermal, fovThermal != null && fovThermal in 5f..170f, "Field of view must be 5 – 170°")
        ) return
        if (siyiModel) {
            val videoPort = Regex(":(\\d+)/").find(formRtsp?.text?.toString().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            if (!validate(formPort, port != videoPort,
                    "$port is the VIDEO port – the gimbal control port is ${SiyiClient.DEFAULT_PORT}")) return
        }
        val patrolNames = PATROL_ORDER.filter { formPatrolChecks[it]?.isChecked == true }

        settings = CamSettings(
            model = model,
            host = host,
            port = port ?: (if (siyiModel) SiyiClient.DEFAULT_PORT else 80),
            user = formUser?.text?.toString()?.trim() ?: base.user,
            pass = formPass?.text?.toString() ?: base.pass,
            rtspOverride = formRtsp?.text?.toString()?.trim().orEmpty(),
            rtsp2 = formRtsp2?.text?.toString()?.trim() ?: base.rtsp2,
            rtspTcp = formTcp?.isChecked ?: true,
            autoCatchUp = formCatchUp?.isChecked ?: base.autoCatchUp,
            maxSpeed = (formSpeed?.progress ?: 100).coerceAtLeast(10) / 100f,
            cachingMs = (formCache?.text?.toString()?.toIntOrNull() ?: CamSettings.DEFAULT_CACHE_MS).coerceIn(30, 500),
            swipeInvert = formInvert?.isChecked ?: false,
            wiperPreset = formWiper?.text?.toString()?.trim() ?: base.wiperPreset,
            maxZoom = (formZoom?.text?.toString()?.toIntOrNull() ?: base.maxZoom).coerceIn(1, 100),
            sdRecord = formSd?.isChecked ?: base.sdRecord,
            debug = formDebug?.isChecked ?: base.debug,
            patrolPresets = patrolNames.joinToString(","),
            patrolDwellSec = dwell ?: base.patrolDwellSec,
            patrolLoop = formPatrolLoop?.isChecked ?: base.patrolLoop,
            trackGain = ((formTrackGain?.progress ?: 60).coerceAtLeast(20)) / 100f,
            trackDelayMs = (formTrackDelay?.text?.toString()?.toIntOrNull() ?: base.trackDelayMs).coerceIn(0, 2000),
            fovRgb = fovRgb ?: base.fovRgb,
            fovThermal = fovThermal ?: base.fovThermal,
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
        stopAutoModes(announce = false)
        if (recording) stopRecording()
        stopStream()
        statusJob?.cancel()
        siyiJob?.cancel()
        aimJob?.cancel()
        closeSiyi()
        siyiError = null
        ptz.client = null
        onvif = null
        onvifRtsp = null
        streamIndex = 0
        irCut = null
        updateNightUi()
        applyModelUi()
        showConnectScreen()
        connectCamera()
    }

    // ------------------------------------------------------------------ gamepad

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE) {
            lastJoyDevice = event.device?.name ?: "Joystick"
            for (r in AxisMapping.joystickAxes(event.device)) lastAxisValues[r.axis] = event.getAxisValue(r.axis)
            updateAxisMonitor(event)

            // Settings → Joystick channels is waiting for a stick movement
            axisLearner?.let { learner ->
                learner.feed(event)?.let { b -> assignAxis(learner.role, b) }
                return true
            }

            var x = shapeAxis(AxisMapping.value(event, axisMap[AxisRole.RIGHT] ?: AxisBinding(-1, false)), axisDead) -
                shapeAxis(AxisMapping.value(event, axisMap[AxisRole.LEFT] ?: AxisBinding(-1, false)), axisDead)
            var y = shapeAxis(AxisMapping.value(event, axisMap[AxisRole.UP] ?: AxisBinding(-1, false)), axisDead) -
                shapeAxis(AxisMapping.value(event, axisMap[AxisRole.DOWN] ?: AxisBinding(-1, false)), axisDead)
            // D-pad (HAT) always works as a slow pan / tilt
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            if (hx != 0f || hy != 0f) { x = shapeAxis(hx * 0.6f); y = -shapeAxis(hy * 0.6f) }
            ptz.padPan = x
            ptz.padTilt = y
            ptz.padZoom = 0f
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // ---------------- Settings → four joystick movement directions

    private fun buildAxisSection() {
        section("JOYSTICK MOVEMENT")
        label("Map the four movement directions. Tap LEARN, then push the stick in the indicated " +
            "direction. The controller must expose its stick axes to Android. Mappings save instantly.")
        axisLabels.clear()
        for (role in AxisRole.values()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            }
            row.addView(TextView(this).apply {
                text = role.label
                typeface = fontSemi
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_main))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val tv = TextView(this).apply {
                typeface = ResourcesCompat.getFont(this@MainActivity, R.font.jetbrains_mono)
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                setBackgroundResource(R.drawable.glass_pill)
            }
            row.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * dp).toInt() })
            row.addView(smallButton("LEARN") { beginAxisLearn(role) })
            row.addView(smallButton("REV") {
                val b = axisMap[role] ?: return@smallButton
                if (!b.used) return@smallButton
                axisMap[role] = b.copy(invert = !b.invert)
                saveAxisMap()
                hudMessage("${role.label}: direction reversed")
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (34 * dp).toInt()).apply {
                marginStart = (6 * dp).toInt()
            })
            row.addView(smallButton("✕") {
                axisMap[role] = AxisBinding(-1, false)
                saveAxisMap()
            }, LinearLayout.LayoutParams((40 * dp).toInt(), (34 * dp).toInt()).apply {
                marginStart = (6 * dp).toInt()
            })
            axisLabels[role] = tv
            settingsContent.addView(row)
        }

        // dead zone
        val deadRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, (4 * dp).toInt())
        }
        deadRow.addView(TextView(this).apply {
            text = "Stick dead zone"
            typeface = fontSemi
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_main))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        deadRow.addView(smallButton("−") { changeDeadzone(-0.02f) },
            LinearLayout.LayoutParams((40 * dp).toInt(), (34 * dp).toInt()))
        axisDeadLabel = TextView(this).apply {
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.jetbrains_mono)
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
        }
        deadRow.addView(axisDeadLabel, LinearLayout.LayoutParams((56 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        deadRow.addView(smallButton("+") { changeDeadzone(0.02f) },
            LinearLayout.LayoutParams((40 * dp).toInt(), (34 * dp).toInt()))
        settingsContent.addView(deadRow)

        // live channel monitor
        label("Live channels – move a stick and watch which channel changes:")
        axisMonitor = TextView(this).apply {
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.jetbrains_mono)
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_main))
            setBackgroundResource(R.drawable.field_bg)
            setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
            text = joystickListText()
        }
        settingsContent.addView(axisMonitor)

        val reset = smallButton("RESET JOYSTICK CHANNELS") {
            AxisMapping.reset(prefs)
            axisMap = AxisMapping.load(prefs)
            axisDead = AxisMapping.deadzone(prefs)
            refreshAxisLabels()
            hudMessage("Joystick directions reset (left stick X / Y)")
        }
        settingsContent.addView(reset, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (40 * dp).toInt()
        ).apply { topMargin = (8 * dp).toInt() })
        refreshAxisLabels()
    }

    /** Joysticks currently connected (shown until the first stick movement arrives). */
    private fun joystickListText(): String {
        val names = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
            .filter { (it.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK }
            .map { d -> "${d.name}  (${AxisMapping.joystickAxes(d).size} channels)" }
        return if (names.isEmpty())
            "No joystick axes received by Android. The G20's built-in sticks may be routed\n" +
                "over the radio link (SBUS) instead of being exposed to apps."
        else "Connected:\n" + names.joinToString("\n") + "\n\nMove a stick…"
    }

    private fun updateAxisMonitor(e: MotionEvent) {
        val tv = axisMonitor ?: return
        if (settingsOverlay.visibility != View.VISIBLE) return
        val now = SystemClock.elapsedRealtime()
        if (now - monitorUpdatedMs < 80) return
        monitorUpdatedMs = now
        val used = axisMap.entries.filter { it.value.used }.groupBy({ it.value.axis }, { it.key })
        val sb = StringBuilder(lastJoyDevice).append('\n')
        for (r in AxisMapping.joystickAxes(e.device)) {
            val v = e.getAxisValue(r.axis)
            val frac = if (r.max > r.min) ((v - r.min) / (r.max - r.min)).coerceIn(0f, 1f) else 0.5f
            val bar = CharArray(12) { if (it < (frac * 12).roundToInt()) '█' else '·' }
            val role = used[r.axis]?.joinToString(prefix = "  ← ") { it.name } ?: ""
            sb.append(String.format(Locale.US, "%-9s %+6.2f %s%s\n", AxisMapping.axisName(r.axis), v, String(bar), role))
        }
        tv.text = sb.toString().trimEnd()
    }

    private fun refreshAxisLabels() {
        val accent = ContextCompat.getColor(this, R.color.accent)
        val dim = ContextCompat.getColor(this, R.color.text_dim)
        for ((role, tv) in axisLabels) {
            if (axisLearner?.role == role) {
                tv.text = "MOVE STICK…"
                tv.setTextColor(accent)
                continue
            }
            val b = axisMap[role]
            if (b == null || !b.used) {
                tv.text = "—"
                tv.setTextColor(dim)
            } else {
                tv.text = AxisMapping.axisName(b.axis) + if (b.invert) " ⇅" else ""
                tv.setTextColor(accent)
            }
        }
        axisDeadLabel?.text = "${(axisDead * 100).roundToInt()} %"
    }

    private fun beginAxisLearn(role: AxisRole) {
        currentFocus?.clearFocus()
        ptz.padPan = 0f; ptz.padTilt = 0f; ptz.padZoom = 0f
        axisLearner = AxisLearner(role).apply { seed(lastAxisValues) }
        refreshAxisLabels()
        hudMessage("${role.label}: release all sticks, then ${role.learnHint}")
        ui.removeCallbacks(axisLearnTimeout)
        ui.postDelayed(axisLearnTimeout, 10_000)
    }

    private val axisLearnTimeout = Runnable {
        if (axisLearner != null) {
            endAxisLearn()
            hudMessage("No stick movement detected – is the joystick listed under Live channels?", error = true)
        }
    }

    private fun endAxisLearn() {
        axisLearner = null
        ui.removeCallbacks(axisLearnTimeout)
        if (axisLabels.isNotEmpty()) refreshAxisLabels()
    }

    private fun assignAxis(role: AxisRole, b: AxisBinding) {
        // Opposite directions intentionally share an axis (for example, X drives left and right).
        axisMap[role] = b
        endAxisLearn()
        saveAxisMap()
        hudMessage("${AxisMapping.axisName(b.axis)} → ${role.label}")
    }

    private fun saveAxisMap() {
        AxisMapping.save(prefs, axisMap)
        refreshAxisLabels()
    }

    private fun changeDeadzone(d: Float) {
        axisDead = (axisDead + d).coerceIn(0f, 0.5f)
        AxisMapping.setDeadzone(prefs, axisDead)
        refreshAxisLabels()
    }

    private fun smallButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        typeface = fontBold
        textSize = 12f
        letterSpacing = 0.06f
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_main))
        setBackgroundResource(R.drawable.btn_secondary)
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (34 * dp).toInt())
        setOnClickListener { it.tick(); onClick() }
    }

    // ------------------------------------------------------------------ misc

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val HOME_PRESET = "10"
        private const val WIPER_RUN_MS = 6000L
        private const val MIN_VIDEO_BYTES = 50_000L
        private const val AUTO_HIDE_MS = 4000L
        private const val WEAK_MS = 2_500L          // no new video for this long → WEAK badge
        private const val STALL_MS = 12_000L        // … and for this long → reconnect
        private const val CATCHUP_LAG_MS = 600L    // sustained backlog beyond the small playback cache → resync
        private const val RTSP_FRAME_BUFFER = 2_000_000
        private const val UDP_DROP_LIMIT = 2
        private const val UDP_DROP_WINDOW_MS = 60_000L
        private const val SHJ_TRAVEL_MS = 3000L        // SHJ preset travel allowance during patrol
        private const val PITCH_MIN = -90f             // ZT6 controllable pitch range
        private const val PITCH_MAX = 25f
        private val PATROL_ORDER = listOf("FRONT", "RIGHT", "REAR", "LEFT", "UP", "DOWN")
        private val HOST_REGEX = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,252})$")

        private val PRESET_NAMES = listOf("FRONT", "REAR", "LEFT", "RIGHT", "UP", "DOWN")

        // SIYI ZT6
        private val VIEW_TYPES = intArrayOf(3, 7, 0)                 // RGB main, thermal main, PiP
        private val VIEW_LABELS = listOf("RGB", "THERMAL", "SPLIT")
        private val VIEW_NAMES = listOf("Visible (RGB)", "Thermal", "Split / picture-in-picture")
        private val MODE_LABELS = listOf("LOCK", "FOLLOW", "FPV")
        private val MODE_NAMES = listOf("Lock", "Follow", "FPV")
        private val PALETTES = listOf(
            0 to "White-hot", 10 to "Black-hot", 3 to "Ironbow", 4 to "Rainbow",
            7 to "Red-hot", 9 to "Medical", 5 to "Night", 6 to "Aurora",
            2 to "Sepia", 8 to "Jungle", 11 to "Glory-hot",
        )

        /** Camera preset numbers for the named presets (avoid 32–99: special functions on many PTZs). */
        private val DEFAULT_PRESET_TOKEN = mapOf(
            "FRONT" to "11", "REAR" to "12", "LEFT" to "13",
            "RIGHT" to "14", "UP" to "15", "DOWN" to "16",
        )
    }
}
