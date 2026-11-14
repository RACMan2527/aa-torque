package com.aatorque.stats

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Matrix
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.MediaMetadata
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ImageButton
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.InputDeviceCompat
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.aatorque.datastore.UserPreference
import com.aatorque.datastore.Display
import com.aatorque.prefs.SettingsViewModel
import com.aatorque.prefs.dataStore
import com.aatorque.stats.databinding.FragmentDashboardBinding
import com.aatorque.utils.CountDownLatch
import com.google.android.apps.auto.sdk.StatusBarController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.abs


open class DashboardFragment : AlbumArt() {
    lateinit var rootView: View
    lateinit var mLayoutDashboard: ConstraintLayout

    val torqueRefresher = TorqueRefresher()
    private val torqueService = TorqueService()
    private val fuelTracker by lazy { FuelTracker(requireContext(), torqueService) }
    private var tankSummaryOverlay: View? = null
    private val tankSummaryHandler = Handler(Looper.getMainLooper())
    private val tankSummaryCheck = object : Runnable {
        override fun run() {
            if (isResumed && tankSummaryOverlay == null && quickOverlay == null) {
                fuelTracker.pendingSummary()?.let { showTankSummary(it) }
            }
            tankSummaryHandler.postDelayed(this, 2_000L)
        }
    }

    private fun showTankSummary(summary: FuelTracker.Summary) {
        if (!isAdded || !isResumed || tankSummaryOverlay != null) return
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val overlay = FrameLayout(ctx).apply {
            setBackgroundColor(Color.argb(225, 0, 0, 0))
            elevation = dp(120).toFloat()
            isClickable = true
            isFocusable = true
        }
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(32, 37, 44))
                cornerRadius = dp(12).toFloat()
            }
        }
        fun row(label: String, value: String, large: Boolean = false) {
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            line.addView(TextView(ctx).apply {
                text = label
                textSize = if (large) 22f else 16f
                setTextColor(if (large) Color.rgb(232, 61, 61) else Color.WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(TextView(ctx).apply {
                text = value
                textSize = if (large) 27f else 17f
                setTextColor(Color.WHITE)
            })
            panel.addView(line)
        }
        val hours = summary.seconds / 3600
        val minutes = (summary.seconds % 3600) / 60
        row("LAST TANK", "")
        row("Tank MPG", summary.mpg?.let { String.format(java.util.Locale.US, "%.1f MPG", it) } ?: "Unavailable", true)
        row("Distance", String.format(java.util.Locale.US, "%.1f mi", summary.miles))
        row("Fuel Consumed", summary.gallons?.let { String.format(java.util.Locale.US, "%.1f gal", it) } ?: "Unavailable")
        row("Driving Time", "${hours}h ${minutes}m")
        val ok = TextView(ctx).apply {
            text = "OK"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(15), dp(12), dp(15), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(57, 85, 115))
                cornerRadius = dp(6).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                fuelTracker.acknowledge(summary.id)
                (overlay.parent as? ViewGroup)?.removeView(overlay)
                tankSummaryOverlay = null
            }
        }
        panel.addView(ok)
        overlay.addView(panel, FrameLayout.LayoutParams(
            minOf(dp(490), resources.displayMetrics.widthPixels - dp(32)),
            -2, Gravity.CENTER))
        mLayoutDashboard.addView(overlay, ConstraintLayout.LayoutParams(-1, -1).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        })
        overlay.bringToFront()
        tankSummaryOverlay = overlay
        ok.requestFocus()
    }


    private lateinit var mBtnNext: ImageButton
    private lateinit var mBtnPrev: ImageButton
    private lateinit var mTitleElement: TextView
    private lateinit var mWrapper: RelativeLayout
    lateinit var mConStatus: TextView

    var guages = arrayOfNulls<TorqueGauge>(3)
    var displays = arrayOfNulls<TorqueDisplay>(4)
    var gaugeViews = arrayOfNulls<FragmentContainerView>(3)

    private var screensAnimating = false
    private var quickOverlay: View? = null
    private var quickPage = "main"
    private var quickIndex = 0
    private val quickHandler = Handler(Looper.getMainLooper())
    private val quickTimeout = Runnable {
        if (quickPage != "diagnostics") closeQuickControls()
    }
    private val quickMain = listOf("Daily MIN/MAX Reset", "Gauge Sizes", "PID Polling",
        "Screen Refresh", "Live Diagnostics", "Close Menu")
    private val quickReset = listOf("Reset Daily MAX", "Reset Daily MIN", "Reset Both", "Back")
    private val quickSizes = listOf("All Round Gauges", "Center Gauge", "Back")
    private var quickResetSelection = 0
    private val diagnosticsHandler = Handler(Looper.getMainLooper())
    private var diagnosticsValueViews: List<TextView> = emptyList()
    private var diagnosticsLastCount = 0L
    private var diagnosticsLastSampleMs = 0L
    private val diagnosticsRefresh = object : Runnable {
        override fun run() {
            if (quickPage != "diagnostics" || quickOverlay == null || !isAdded) return
            val snapshot = torqueRefresher.diagnosticsSnapshot()
            val now = android.os.SystemClock.elapsedRealtime()
            val elapsed = now - diagnosticsLastSampleMs
            val rate = if (elapsed > 0) (snapshot.successfulPolls - diagnosticsLastCount) * 1000.0 / elapsed else 0.0
            diagnosticsLastCount = snapshot.successfulPolls
            diagnosticsLastSampleMs = now
            val age = if (snapshot.lastSuccessAtMs > 0L) "${now - snapshot.lastSuccessAtMs} ms" else "—"
            val values = listOf(
                "${TorqueRefresher.SAMPLE_INTERVAL} ms",
                String.format(java.util.Locale.US, "%.1f /sec", rate),
                "${TorqueRefresher.REFRESH_INTERVAL} ms",
                if (snapshot.lastResponseMs >= 0) "${snapshot.lastResponseMs} ms" else "—",
                snapshot.failedPolls.toString(),
                age
            )
            diagnosticsValueViews.forEachIndexed { index, view -> view.text = values[index] }
            diagnosticsHandler.postDelayed(this, 1_000L)
        }
    }
    private var quickSliderMode = ""
    private var quickSliderControl: SeekBar? = null
    private var quickSliderSave: View? = null
    private val gaugePressHandler = Handler(Looper.getMainLooper())
    private var allRoundGaugesLarge = false
    private var centerGaugeLarge = false
    private val logoLongPressHandler = Handler(Looper.getMainLooper())
    private var refreshIntervalOverlay: View? = null
    private var pollingIntervalOverlay: View? = null
    private var pollingIntervalInitialValue = 200
    private var refreshIntervalInitialValue = 300
    private var dashboardTitle: String = ""
    private var currentConnectionStatus = ConnectStatus.CONNECTING_TORQUE
    private var loggedDashboardIndex: Int? = null
    private var communicationFaultActive = false
    private var torqueStartupTimedOut = false
    private val connectionLogStartMs = android.os.SystemClock.elapsedRealtime()
    private val torqueStartupHandler = Handler(Looper.getMainLooper())
    private val torqueStartupTimeout = Runnable {
        if (currentConnectionStatus == ConnectStatus.CONNECTING_TORQUE) {
            torqueStartupTimedOut = true
            DiagnosticLog.event("TORQUE_CONNECT_TIMEOUT", "elapsedMs=${android.os.SystemClock.elapsedRealtime() - connectionLogStartMs} timeoutMs=5000")
            updateConnectionIndicators()
        }
    }

    private fun updateConnectionIndicators() {
        binding.connectingEcu = currentConnectionStatus == ConnectStatus.CONNECTING_ECU
        binding.brokenLink = (currentConnectionStatus == ConnectStatus.CONNECTING_TORQUE &&
            torqueStartupTimedOut) ||
            (currentConnectionStatus == ConnectStatus.CONNECTED && communicationFaultActive)
    }
    private var mStarted = false
    lateinit var binding: FragmentDashboardBinding
    lateinit var torqueChart: TorqueChart
    private lateinit var loadBar: TorqueLoadBar

    lateinit var settingsViewModel: SettingsViewModel

    val viewReady = CountDownLatch(1)
    private var lastBackground: Int = 0
    val albumArtReady = CountDownLatch(2)
    var shouldDisplayArtwork = false
    var displayingArtwork = false
    var albumBlurEffect: RenderEffect? = null
        set(value) {
            if (Build.VERSION.SDK_INT >= 31) {
                if (displayingArtwork) {
                    binding.blurEffect = value
                }
                field = value
            }
        }
        get() {
            return if (Build.VERSION.SDK_INT >= 31) field else null
        }
    var albumColorFilter: PorterDuffColorFilter? = null
        set(value) {
            field = value
            if (displayingArtwork) {
                binding.colorFilter = value
            }
        }

    companion object {
        const val DISPLAY_OFFSET = 3
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]
        val context = requireContext()
        torqueRefresher.initialize(context)
        torqueService.startTorque(context)
        fuelTracker.start()
        val registerWithView = { call: suspend (Flow<UserPreference>) -> Unit ->
            lifecycleScope.launch {
                viewReady.await()
                call(context.dataStore.data)
            }
        }
        registerWithView {
            data -> data.map {
                it.selectedBackground
            }.distinctUntilChanged().collect {
                setupBackground(it)
                albumArtReady.countDown()
            }
        }
        registerWithView {
            data -> data.collect {
                TorqueRefresher.REFRESH_INTERVAL = (if (it.refreshIntervalMs == 0) 300 else it.refreshIntervalMs).toLong()
                val screenIndex = abs(it.currentScreen) % it.screensCount
                if (loggedDashboardIndex != screenIndex) {
                    DiagnosticLog.flushRecords()
                    DiagnosticLog.event("DASHBOARD_CHANGE", "old=$loggedDashboardIndex new=$screenIndex")
                    loggedDashboardIndex = screenIndex
                }
                val screens = it.screensList[screenIndex]
                val showChartChanged = binding.showChart != it.showChart
                dashboardTitle = screens.title
                binding.title = screens.title
            binding.showBtns = false
            settingsViewModel.chartVisible.value = it.showChart
                settingsViewModel.minMaxBelow.value = it.minMaxBelow
                shouldDisplayArtwork = it.albumArt

                albumArtReady.countDown()

                if (it.showChart) {
                    torqueChart.setupItems(
                        screens.gaugesList.mapIndexed { index, display ->
                            torqueRefresher.updateIfNeeded(index, screenIndex, display)
                        }.toTypedArray()
                    )
                } else {
                    screens.gaugesList.forEachIndexed { index, display ->
                        if (showChartChanged || torqueRefresher.hasChanged(index, display)) {
                            val clock = torqueRefresher.populateQuery(index, screenIndex, display)
                            guages[index]?.setupClock(clock)
                        }
                    }
                }
                setupConfiguredBar(screenIndex, it)
                screens.displaysList.forEachIndexed { index, display ->
                    if (torqueRefresher.hasChanged(index + DISPLAY_OFFSET, display)) {
                        val td = torqueRefresher.populateQuery(
                            index + DISPLAY_OFFSET,
                            screenIndex,
                            display
                        )
                        displays[index]?.setupElement(td)
                    }
                }
                torqueRefresher.makeExecutors(torqueService)
            }
        }
        registerWithView {
            data -> data.map {
                it.opacity
            }.distinctUntilChanged().collect {
                binding.gaugeAlpha = if (it == 0) 1f else 0.01f * it
            }
        }
        registerWithView {
            data -> data.map {
                it.darkenArt
            }.distinctUntilChanged().collect {
                albumColorFilter = if (it != 0) {
                    PorterDuffColorFilter(
                        Color.valueOf(0f, 0f, 0f, it * 0.01f).toArgb(),
                        PorterDuff.Mode.DARKEN,
                    )
                } else null
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            registerWithView {
                data -> data.map {
                    it.blurArt
                }.distinctUntilChanged().collect {
                    albumBlurEffect = if (it != 0) {
                        val blurFloat = it.toFloat()
                        RenderEffect.createBlurEffect(
                            blurFloat, blurFloat,
                            Shader.TileMode.MIRROR
                        )
                    } else null
                }
            }
        }
        registerWithView {
            data -> data.map {
                it.selectedTheme
            }.distinctUntilChanged().collect { selectedTheme ->
                binding.cadillacVSeriesBadge.visibility =
                    if (selectedTheme == "Cadillac V-Sport") View.VISIBLE else View.GONE
            }
        }
        registerWithView {
            data -> data.map {
                it.selectedFont
            }.distinctUntilChanged().collect(
                this@DashboardFragment::setupTypeface
            )
        }
        registerWithView {
            data -> data.map {
                it.centerGaugeLarge
            }.distinctUntilChanged().collect(
                this@DashboardFragment::updateScale
            )
        }
        registerWithView{
            torqueRefresher.connectStatus.collect {
                if (currentConnectionStatus != it) {
                    val elapsed = android.os.SystemClock.elapsedRealtime() - connectionLogStartMs
                    DiagnosticLog.event("CONNECTION_STATE", "old=$currentConnectionStatus new=$it elapsedMs=$elapsed")
                    if (it == ConnectStatus.CONNECTING_ECU) DiagnosticLog.event("ECU_CONNECT_WAIT", "elapsedMs=$elapsed")
                    if (it == ConnectStatus.CONNECTED) DiagnosticLog.event("GAUGE_DATA_CONNECTED", "elapsedMs=$elapsed")
                    if (currentConnectionStatus == ConnectStatus.CONNECTED && it != ConnectStatus.CONNECTED) DiagnosticLog.event("GAUGE_DATA_DISCONNECTED", "elapsedMs=$elapsed new=$it")
                }
                currentConnectionStatus = it
                if (it != ConnectStatus.CONNECTING_TORQUE) {
                    torqueStartupHandler.removeCallbacks(torqueStartupTimeout)
                    torqueStartupTimedOut = false
                }
                binding.status = when (it) {
                    ConnectStatus.CONNECTING_TORQUE -> R.string.status_connecting_torque
                    ConnectStatus.CONNECTING_ECU -> R.string.status_connecting_to_ecu
                    ConnectStatus.SETUP_GAUGE -> R.string.status_setup_gauges
                    ConnectStatus.CONNECTED -> 0
                }
                updateConnectionIndicators()
            }
        }
        registerWithView {
            torqueRefresher.communicationFault.collect {
                communicationFaultActive = it
                updateConnectionIndicators()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Timber.i("onCreateView")
        binding = FragmentDashboardBinding.inflate(inflater, container, false)
        binding.connectingEcu = false
        binding.brokenLink = false
        torqueStartupTimedOut = false
        torqueStartupHandler.removeCallbacks(torqueStartupTimeout)
        torqueStartupHandler.postDelayed(torqueStartupTimeout, 5_000L)

        settingsViewModel.typefaceLiveData.observe(viewLifecycleOwner) {
            binding.font = it
        }
        settingsViewModel.chartVisible.observe(viewLifecycleOwner) {
            binding.showChart = it
        }
        settingsViewModel.minMaxBelow.observe(viewLifecycleOwner) {
            binding.minMaxBelow = it
        }

        rootView = binding.root

        mLayoutDashboard = rootView.findViewById(R.id.layoutDashboard)
        mBtnNext = binding.nextBtn
        mBtnPrev = binding.prevButton
        mBtnNext.setOnClickListener { setScreen(1) }
        mBtnPrev.setOnClickListener  { setScreen(-1) }
        binding.chartBtn.setOnClickListener { toggleShowChart(binding.showChart != true)  }
        configureQuickClockHold()
        mTitleElement = binding.textTitle
        mWrapper = binding.includeWrap
        mConStatus = binding.conStatus
        gaugeViews[0] = binding.gaugeLeft
        gaugeViews[1] = binding.gaugeCenter
        gaugeViews[2] = binding.gaugeRight
        allRoundGaugesLarge = requireContext().getSharedPreferences("round_gauge_display", Context.MODE_PRIVATE)
            .getBoolean("all_round_gauges_large", false)
        applyRoundGaugeScale()
        // Gauge sizes are adjusted through Quick Controls.

        guages[0] = childFragmentManager.findFragmentById(R.id.gaugeLeft)!! as TorqueGauge
        guages[1] = childFragmentManager.findFragmentById(R.id.gaugeCenter)!! as TorqueGauge
        guages[2] = childFragmentManager.findFragmentById(R.id.gaugeRight)!! as TorqueGauge
        displays[0] = childFragmentManager.findFragmentById(R.id.display1)!! as TorqueDisplay
        displays[1] = childFragmentManager.findFragmentById(R.id.display2)!! as TorqueDisplay
        displays[2] = childFragmentManager.findFragmentById(R.id.display3)!! as TorqueDisplay
        displays[3] = childFragmentManager.findFragmentById(R.id.display4)!! as TorqueDisplay
        displays[2]!!.isBottomDisplay = true
        displays[3]!!.isBottomDisplay = true
        torqueChart = childFragmentManager.findFragmentById(R.id.chartFrag)!! as TorqueChart
        loadBar = childFragmentManager.findFragmentById(R.id.loadBar)!! as TorqueLoadBar
        // Reposition the small displays whenever the measured dial geometry changes.
        val geometryListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scheduleDisplayAlignment()
        }
        listOf(binding.dashContent, binding.gaugeLeft, binding.gaugeCenter, binding.gaugeRight).forEach {
            it.addOnLayoutChangeListener(geometryListener)
        }
        listOf(binding.gaugeLeft, binding.gaugeCenter, binding.gaugeRight).forEach { gauge ->
            gauge.findViewById<View>(R.id.dial)?.addOnLayoutChangeListener(geometryListener)
        }
        scheduleDisplayAlignment()
        val filter = IntentFilter().apply { addAction("KEY_DOWN") }
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(p0: Context?, intent: Intent?) {
                    when (intent?.getIntExtra("KEY_CODE", 0)) {
                        // Four-way DPAD toggles belong to Android Auto, not V-Sport Gauges.
                        // Physical wheel rotation is handled by ACTION_SCROLL below.
                        281 -> handleRotaryStep(1) // Legacy rotary key event
                    }
                }
            }, filter)
        val gestureDetector =
            GestureDetector(rootView.context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 != null) {
                        val diffX = e2.x - e1.x
                        val diffY = e2.y - e1.y

                        // Set a minimum swipe distance threshold (e.g., 100 pixels)
                        if (abs(diffX) > abs(diffY) && abs(diffX) > 100) {
                            if (diffX > 0) {
                                // Swipe Right
                                setScreen(-1)
                            } else {
                                // Swipe Left
                                setScreen(1)
                            }
                            return true
                        } else if (abs(diffY) > abs(diffX) && abs(diffY) > 100) {
                            toggleShowChart(binding.showChart != true)
                        }
                    }
                    return false
                }
            })
        rootView.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                v.performClick() // Handle accessibility
            }
            true
        }
        configureRotaryInput()
        return rootView
    }


    private var alignmentPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null

    private fun scheduleDisplayAlignment() {
        if (!::binding.isInitialized || alignmentPreDrawListener != null) return
        val container = binding.dashContent
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (alignDisplaysBetweenDials()) {
                    if (container.viewTreeObserver.isAlive) {
                        container.viewTreeObserver.removeOnPreDrawListener(this)
                    }
                    alignmentPreDrawListener = null
                }
                return true
            }
        }
        alignmentPreDrawListener = listener
        container.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun applyRoundGaugeScale() {
        // Keep the outer edges of the side gauge views on the dashboard boundaries.
        // Scaling around the default center pivot pushes those edges offscreen.
        binding.gaugeLeft.pivotX = 0f
        binding.gaugeCenter.pivotX = binding.gaugeCenter.width / 2f
        binding.gaugeCenter.post { binding.gaugeCenter.pivotX = binding.gaugeCenter.width / 2f }
        binding.gaugeRight.pivotX = binding.gaugeRight.width.toFloat()
        binding.gaugeRight.post { binding.gaugeRight.pivotX = binding.gaugeRight.width.toFloat() }
        // Scale all three gauges vertically about the center of their containers.
        // Keep the side gauges' horizontal pivots at the dashboard edges.
        listOf(binding.gaugeLeft, binding.gaugeCenter, binding.gaugeRight).forEach { gauge ->
            gauge.pivotY = gauge.height / 2f
            gauge.post { gauge.pivotY = gauge.height / 2f }
        }
        // The center remains controlled by the existing persisted centerGaugeLarge setting.
        binding.largeCenter = allRoundGaugesLarge || centerGaugeLarge
        binding.allRoundLarge = allRoundGaugesLarge
        // Wait until the scaled dial geometry has been laid out.
        scheduleDisplayAlignment()
    }

    /**
     * Center both pairs of trip displays in the actual horizontal space between
     * the rendered dial circles, accounting for each dial's scale and pivot.
     * Do not infer dial edges from the FragmentContainerView thirds.
     */
    /**
     * Match View.transformMatrixToGlobal on API 28 without calling its API 29 method.
     * Parent scrolling, view position and the view's own transform are applied in
     * the same order as Android's View implementation.
     */
    private fun transformToGlobalCompat(view: View, out: Matrix) {
        if (Build.VERSION.SDK_INT >= 29) {
            view.transformMatrixToGlobal(out)
            return
        }
        val parent = view.parent
        if (parent is View) {
            transformToGlobalCompat(parent, out)
            out.preTranslate(-parent.scrollX.toFloat(), -parent.scrollY.toFloat())
        }
        out.preTranslate(view.left.toFloat(), view.top.toFloat())
        if (!view.matrix.isIdentity) out.preConcat(view.matrix)
    }

    private fun alignDisplaysBetweenDials(): Boolean {
        if (!isAdded || !::binding.isInitialized || binding.dashContent.width <= 0) return false
        val container = binding.dashContent
        val toGlobal = Matrix()
        transformToGlobalCompat(container, toGlobal)
        val fromGlobal = Matrix()
        if (!toGlobal.invert(fromGlobal)) return false

        fun dialEdges(gauge: View): Pair<Float, Float>? {
            val dial = gauge.findViewById<View>(R.id.dial) ?: return null
            if (dial.width <= 0 || dial.height <= 0) return null
            val radius = minOf(dial.width, dial.height) / 2f
            val cx = dial.width / 2f
            val cy = dial.height / 2f
            val edges = floatArrayOf(cx - radius, cy, cx + radius, cy)
            val matrix = Matrix()
            transformToGlobalCompat(dial, matrix)
            matrix.mapPoints(edges)
            fromGlobal.mapPoints(edges)
            return minOf(edges[0], edges[2]) to maxOf(edges[0], edges[2])
        }

        val left = dialEdges(binding.gaugeLeft) ?: return false
        val middle = dialEdges(binding.gaugeCenter) ?: return false
        val right = dialEdges(binding.gaugeRight) ?: return false
        val leftGapCenter = (left.second + middle.first) / 2f
        val rightGapCenter = (middle.second + right.first) / 2f

        // Each display is constrained between a movable guideline and the
        // fixed 50% guideline, so its CENTER is the average of those positions.
        val width = container.width.toFloat()
        fun setGuide(guide: View, displayCenter: Float) {
            val percent = (2f * displayCenter / width - 0.5f).coerceIn(0f, 1f)
            val params = guide.layoutParams as ConstraintLayout.LayoutParams
            if (kotlin.math.abs(params.guidePercent - percent) > 0.0001f) {
                params.guidePercent = percent
                guide.layoutParams = params
            }
        }
        setGuide(binding.line25, leftGapCenter)
        setGuide(binding.line75, rightGapCenter)
        return true
    }

    private fun configureGaugeScaleLongPress(gauge: View, allGauges: Boolean) {
        val handler = gaugePressHandler
        val touchSlop = ViewConfiguration.get(gauge.context).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var pending: Runnable? = null
        gauge.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    // Restrict activation to the central portion of the round dial.
                    val centerX = view.width / 2f
                    val centerY = view.height / 2f
                    val radius = minOf(view.width, view.height) * 0.22f
                    if ((startX - centerX) * (startX - centerX) +
                        (startY - centerY) * (startY - centerY) <= radius * radius) {
                        val action = Runnable {
                            // Persist the center state before applying either size mode.
                            // This prevents the DataStore observer from restoring a stale
                            // center-only selection after an all-gauges toggle.
                            val nextAll = if (allGauges) !allRoundGaugesLarge else false
                            val nextCenter = if (allGauges) false
                                else !allRoundGaugesLarge && !centerGaugeLarge
                            lifecycleScope.launch {
                                requireContext().dataStore.updateData { current ->
                                    current.toBuilder().setCenterGaugeLarge(nextCenter).build()
                                }
                                requireContext().getSharedPreferences(
                                    "round_gauge_display", Context.MODE_PRIVATE
                                ).edit().putBoolean("all_round_gauges_large", nextAll).apply()
                                allRoundGaugesLarge = nextAll
                                centerGaugeLarge = nextCenter
                                DiagnosticLog.event("GAUGE_SIZE", "source=long_press all=$nextAll center=$nextCenter")
                                applyRoundGaugeScale()
                            }
                        }
                        pending = action
                        handler.postDelayed(action, 3_000L)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.x - startX) > touchSlop ||
                        kotlin.math.abs(event.y - startY) > touchSlop) {
                        pending?.let(handler::removeCallbacks)
                        pending = null
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pending?.let(handler::removeCallbacks)
                    pending = null
                    if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    true
                }
                else -> true
            }
        }
    }

    private fun configureRefreshIntervalLongPress() {
        val badge = binding.cadillacVSeriesBadge
        val longPressAction = Runnable { showRefreshIntervalSlider() }
        badge.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    logoLongPressHandler.removeCallbacks(longPressAction)
                    logoLongPressHandler.postDelayed(longPressAction, 3_000L)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> logoLongPressHandler.removeCallbacks(longPressAction)
            }
            true
        }
    }

    private fun showRefreshIntervalSlider() {
        if (!isAdded || refreshIntervalOverlay != null) return

        val context = requireContext()
        val initialValue = TorqueRefresher.REFRESH_INTERVAL.toInt().coerceIn(50, 500)
        refreshIntervalInitialValue = initialValue
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val overlay = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            elevation = dp(20).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = "Gauge refresh interval controls"
            setOnClickListener { dismissRefreshIntervalOverlay(restore = true) }
        }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(22), dp(28), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.rgb(34, 34, 34))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.LTGRAY)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { }
        }

        val title = TextView(context).apply {
            text = "Gauge Refresh Interval"
            textSize = 21f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val valueLabel = TextView(context).apply {
            text = "Gauge refresh: $initialValue ms"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val slider = SeekBar(context).apply {
            max = 450
            progress = initialValue - 50
            contentDescription = "Gauge refresh interval, 50 to 500 milliseconds"
        }

        panel.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
        panel.addView(valueLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })
        panel.addView(slider, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(18) })

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val cancelButton = TextView(context).apply {
            text = "CANCEL"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.DKGRAY)
                cornerRadius = dp(6).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { dismissRefreshIntervalOverlay(restore = true) }
        }
        val saveButton = TextView(context).apply {
            text = "SAVE"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.rgb(40, 95, 145))
                cornerRadius = dp(6).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val value = 50 + slider.progress
                TorqueRefresher.REFRESH_INTERVAL = value.toLong()
                DiagnosticLog.event("REFRESH_INTERVAL", "old=$refreshIntervalInitialValue new=$value")
                val appContext = context.applicationContext
                lifecycleScope.launch {
                    appContext.dataStore.updateData { current ->
                        current.toBuilder().setRefreshIntervalMs(value).build()
                    }
                }
                dismissRefreshIntervalOverlay(restore = false)
            }
        }
        buttonRow.addView(cancelButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginEnd = dp(10)
        })
        buttonRow.addView(saveButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(buttonRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = 50 + progress
                valueLabel.text = "Gauge refresh: $value ms"
                if (quickSliderMode == "refresh") quickTouch()
                TorqueRefresher.REFRESH_INTERVAL = value.toLong()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        val panelWidth = minOf(dp(520), resources.displayMetrics.widthPixels - dp(40))
        overlay.addView(panel, FrameLayout.LayoutParams(
            panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        val overlayParams = ConstraintLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        }
        mLayoutDashboard.addView(overlay, overlayParams)
        refreshIntervalOverlay = overlay
        if (quickSliderMode == "refresh") { quickSliderControl = slider; quickSliderSave = saveButton }
    }

    private fun dismissRefreshIntervalOverlay(restore: Boolean) {
        val overlay = refreshIntervalOverlay ?: return
        if (restore) {
            TorqueRefresher.REFRESH_INTERVAL = refreshIntervalInitialValue.toLong()
        }
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        refreshIntervalOverlay = null
        if (quickSliderMode == "refresh") openQuickControls()
    }
    private fun configurePollingIntervalLongPress() {
        val clock = binding.dashboardClock
        val longPressAction = Runnable { showPollingIntervalSlider() }
        clock.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    logoLongPressHandler.removeCallbacks(longPressAction)
                    logoLongPressHandler.postDelayed(longPressAction, 3_000L)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    logoLongPressHandler.removeCallbacks(longPressAction)
            }
            true
        }
    }

    private fun showPollingIntervalSlider() {
        if (!isAdded || pollingIntervalOverlay != null || refreshIntervalOverlay != null) return
        val context = requireContext()
        val initialValue = TorqueRefresher.SAMPLE_INTERVAL.toInt().coerceIn(50, 500)
        pollingIntervalInitialValue = initialValue
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val overlay = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            elevation = dp(20).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { dismissPollingIntervalOverlay(restore = true) }
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(22), dp(28), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.rgb(34, 34, 34))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.LTGRAY)
            }
            isClickable = true
            setOnClickListener { }
        }
        val title = TextView(context).apply {
            text = "PID Polling Interval"
            textSize = 21f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val valueLabel = TextView(context).apply {
            text = "PID polling: $initialValue ms"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val slider = SeekBar(context).apply {
            max = 450
            progress = initialValue - 50
            contentDescription = "PID polling interval, 50 to 500 milliseconds"
        }
        panel.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        panel.addView(valueLabel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        panel.addView(slider, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val cancel = TextView(context).apply {
            text = "CANCEL"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.DKGRAY)
                cornerRadius = dp(6).toFloat()
            }
            isClickable = true
            setOnClickListener { dismissPollingIntervalOverlay(restore = true) }
        }
        val save = TextView(context).apply {
            text = "SAVE"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.rgb(40, 95, 145))
                cornerRadius = dp(6).toFloat()
            }
            isClickable = true
            setOnClickListener { dismissPollingIntervalOverlay(restore = false) }
        }
        buttons.addView(cancel, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(10) })
        buttons.addView(save, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(buttons, LinearLayout.LayoutParams(-1, -2))
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser && quickSliderMode != "poll") return
                if (quickSliderMode == "poll") quickTouch()
                val value = 50 + progress
                valueLabel.text = "PID polling: $value ms"
                TorqueRefresher.setGlobalPollingInterval(context, value.toLong())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        val panelWidth = minOf(dp(520), resources.displayMetrics.widthPixels - dp(40))
        overlay.addView(panel, FrameLayout.LayoutParams(panelWidth, -2, Gravity.CENTER))
        mLayoutDashboard.addView(overlay, ConstraintLayout.LayoutParams(-1, -1).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        })
        pollingIntervalOverlay = overlay
        if (quickSliderMode == "poll") { quickSliderControl = slider; quickSliderSave = save }
    }

    private fun dismissPollingIntervalOverlay(restore: Boolean) {
        val overlay = pollingIntervalOverlay ?: return
        if (restore) {
            TorqueRefresher.setGlobalPollingInterval(requireContext(), pollingIntervalInitialValue.toLong())
        }
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        pollingIntervalOverlay = null
        if (quickSliderMode == "poll") openQuickControls()
    }

    private fun setupConfiguredBar(screenIndex: Int, settings: UserPreference) {
        // The horizontal bar uses the same persisted Display configuration as
        // a round gauge. Only its renderer is different.
        val barDisplay = settings.screensList[screenIndex].horizontalGauge
        val showBar = !barDisplay.disabled && barDisplay.pid.isNotBlank()
        loadBar.view?.visibility = if (showBar) View.VISIBLE else View.GONE
        if (!showBar) return
        // Match the round gauges: pass the saved Display, including its exact
        // Torque PID identifier, straight through to TorqueRefresher.
        // The horizontal bar changes only the renderer, not PID construction.
        val configuredDisplay = barDisplay
        val barPid = configuredDisplay.pid.removePrefix("torque_")
        val barData = if (torqueRefresher.hasChanged(7, configuredDisplay)) {
            torqueRefresher.populateQuery(7, screenIndex, configuredDisplay)
        } else torqueRefresher.data[7]!!
        barData.allowInitialZeroUpdates = true
        loadBar.setupBar(
            barData,
            if (configuredDisplay.hasHorizontalMinValue()) configuredDisplay.horizontalMinValue else configuredDisplay.minValue.toDouble(),
            if (configuredDisplay.hasHorizontalMaxValue()) configuredDisplay.horizontalMaxValue else configuredDisplay.maxValue.toDouble(),
            configuredDisplay.label.ifBlank { "PID $barPid" },
            configuredDisplay.showLabel,
            configuredDisplay.wholeNumbers,
            configuredDisplay.ticksActive,
            configuredDisplay.allTimePeakActive,
            configuredDisplay.unit
        )
    }

    fun setScreen(direction: Int) {
        if (screensAnimating) return
        screensAnimating = true
        val duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
        mTitleElement.animate().alpha(0f).duration = duration
        mWrapper.animate()!!.translationX((rootView.width * -direction).toFloat()).setDuration(
            duration
        ).alpha(0f).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                lifecycleScope.launch {
                    requireContext().dataStore.updateData {
                            currentSettings ->
                        currentSettings.toBuilder().setCurrentScreen(
                            (currentSettings.screensCount +
                                    currentSettings.currentScreen +
                                    direction
                                    ) % currentSettings.screensCount
                        ).build()
                    }
                    mWrapper.translationX = (rootView.width * direction).toFloat()
                    mWrapper.alpha = 1f
                    mWrapper.animate().setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            screensAnimating = false
                        }
                    }).translationX(0f).duration = duration
                    mTitleElement.animate().alpha(1f).duration = duration
                }
            }
        })
    }

    fun toggleShowChart(showChart: Boolean) {
        if (screensAnimating) return
        screensAnimating = true
        mWrapper.animate()!!.alpha(0f).setDuration(
            300
        ).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                mWrapper.animate()!!.alpha(1f).setDuration(300).setListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            screensAnimating = false
                        }
                    }
                )
                lifecycleScope.launch {
                    context?.dataStore?.updateData { currentSettings ->
                        currentSettings.toBuilder().setShowChart(showChart).build()
                    }
                }
            }
        })
    }

    override fun setupStatusBar(sc: StatusBarController) {
        sc.hideTitle()
    }

    override fun onResume() {
        Timber.d("onResume")
        super.onResume()
        tankSummaryHandler.removeCallbacks(tankSummaryCheck)
        tankSummaryHandler.post(tankSummaryCheck)
        if (currentConnectionStatus == ConnectStatus.CONNECTING_TORQUE && !torqueStartupTimedOut) {
            torqueStartupHandler.removeCallbacks(torqueStartupTimeout)
            torqueStartupHandler.postDelayed(torqueStartupTimeout, 5_000L)
        }
        lifecycleScope.launch {
            torqueRefresher.makeExecutors(torqueService)
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            viewReady.countDown()
        }
    }

    override fun onStop() {
        super.onStop()
        mStarted = false
    }

    override fun onPause() {
        tankSummaryHandler.removeCallbacks(tankSummaryCheck)
        tankSummaryOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        tankSummaryOverlay = null
        Timber.d("onPause")
        logoLongPressHandler.removeCallbacksAndMessages(null)
        gaugePressHandler.removeCallbacksAndMessages(null)
        torqueStartupHandler.removeCallbacks(torqueStartupTimeout)
        closeQuickControls()
        dismissRefreshIntervalOverlay(restore = true)
        dismissPollingIntervalOverlay(restore = true)
        super.onPause()
        torqueRefresher.stopExecutors()
    }

    override fun onDestroy() {
        fuelTracker.close()
        super.onDestroy()
        torqueService.onDestroy(requireContext())
        torqueService.requestQuit(requireContext())
        DiagnosticLog.endSession()
    }

    override suspend fun onMediaChanged(medadata: MediaMetadata?) {
        Timber.i("Got new metadata $medadata shouldDisplay: $shouldDisplayArtwork")
        albumArtReady.await()
        if (!shouldDisplayArtwork) return
        if (medadata != null) {
            binding.backgroundBitmap = metaDataToArt(medadata)
            if (binding.backgroundBitmap != null) {
                binding.blurEffect = albumBlurEffect
                binding.colorFilter = albumColorFilter
                displayingArtwork = true
                return
            }
        }
        setupBackground(lastBackground)
    }

    private fun updateScale(largeCenter: Boolean) {
        centerGaugeLarge = largeCenter
        applyRoundGaugeScale()
    }

    private fun setupBackground(newBackground: String?) {
        lastBackground = context?.let {
            resources.getIdentifier(
                newBackground ?: "background_incar_black",
                "drawable",
                it.packageName
            )
        } ?: lastBackground
        setupBackground(lastBackground)
    }

    private fun setupBackground(resource: Int) {
        binding.blurEffect = null
        binding.colorFilter = null
        displayingArtwork = false
        binding.backgroundResource = resource
    }


    fun handleQuickKey(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (quickOverlay == null && quickSliderMode.isEmpty()) return false
            quickBack()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (quickSliderMode.isNotEmpty()) quickSliderSave?.performClick()
            else if (quickOverlay == null) openQuickControls()
            else quickSelect()
            return true
        }
        return false
    }

    private fun configureQuickClockHold() {
        val clock = binding.dashboardClock
        val action = Runnable { openQuickControls() }
        clock.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    logoLongPressHandler.removeCallbacks(action)
                    logoLongPressHandler.postDelayed(action, 3_000L)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    logoLongPressHandler.removeCallbacks(action)
            }
            true
        }
    }

    private fun quickTouch() {
        quickHandler.removeCallbacks(quickTimeout)
        // Keep diagnostics open until the user explicitly exits it.
        if (quickPage != "diagnostics") quickHandler.postDelayed(quickTimeout, 5_000L)
    }

    private fun closeQuickControls() {
        diagnosticsHandler.removeCallbacks(diagnosticsRefresh)
        diagnosticsValueViews = emptyList()
        quickHandler.removeCallbacks(quickTimeout)
        quickOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        quickOverlay = null
        quickSliderMode = ""
        quickSliderControl = null
        quickSliderSave = null
        dismissPollingIntervalOverlay(true)
        dismissRefreshIntervalOverlay(true)
    }

    private fun openQuickControls(page: String = "main") {
        diagnosticsHandler.removeCallbacks(diagnosticsRefresh)
        diagnosticsValueViews = emptyList()
        quickOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        quickOverlay = null
        quickPage = page
        quickIndex = 0
        quickSliderMode = ""
        renderQuickControls()
    }

    private fun quickBack() {
        if (quickSliderMode.isNotEmpty()) {
            if (quickSliderMode == "poll") dismissPollingIntervalOverlay(true)
            else dismissRefreshIntervalOverlay(true)
            openQuickControls()
        } else if (quickPage == "main") closeQuickControls()
        else openQuickControls(if (quickPage == "confirm") "reset" else "main")
    }

    private fun quickRotate(direction: Int) {
        quickTouch()
        if (quickSliderMode.isNotEmpty()) {
            quickSliderControl?.let { it.progress = (it.progress + direction * 5).coerceIn(0, it.max) }
            return
        }
        val count = when (quickPage) {
            "main" -> quickMain.size
            "reset" -> quickReset.size
            "sizes" -> quickSizes.size
            "confirm" -> 2
            else -> 1
        }
        quickIndex = (quickIndex + direction + count) % count
        renderQuickControls()
    }

    private fun quickSelect() {
        quickTouch()
        when (quickPage) {
            "main" -> when (quickIndex) {
                0 -> openQuickControls("reset")
                1 -> openQuickControls("sizes")
                2 -> openQuickSlider(true)
                3 -> openQuickSlider(false)
                4 -> openQuickControls("diagnostics")
                5 -> closeQuickControls()
                else -> closeQuickControls()
            }
            "reset" -> if (quickIndex == 3) openQuickControls()
                else { quickResetSelection = quickIndex; openQuickControls("confirm") }
            "confirm" -> {
                if (quickIndex == 0) DailyPeakStore.resetToday(requireContext(),
                    resetMin = quickResetSelection != 0, resetMax = quickResetSelection != 1)
                openQuickControls("reset")
            }
            "sizes" -> when (quickIndex) {
                0 -> changeQuickSize(true)
                1 -> changeQuickSize(false)
                else -> openQuickControls()
            }
            "diagnostics" -> openQuickControls()
            else -> openQuickControls()
        }
    }

    private fun changeQuickSize(all: Boolean) {
        val nextAll = if (all) !allRoundGaugesLarge else false
        val nextCenter = if (all) false else !allRoundGaugesLarge && !centerGaugeLarge
        lifecycleScope.launch {
            requireContext().dataStore.updateData { current ->
                current.toBuilder().setCenterGaugeLarge(nextCenter).build()
            }
            requireContext().getSharedPreferences("round_gauge_display", Context.MODE_PRIVATE)
                .edit().putBoolean("all_round_gauges_large", nextAll).apply()
            allRoundGaugesLarge = nextAll
            centerGaugeLarge = nextCenter
            DiagnosticLog.event("GAUGE_SIZE", "source=quick_settings all=$nextAll center=$nextCenter")
            applyRoundGaugeScale()
            renderQuickControls()
        }
    }

    private fun openQuickSlider(polling: Boolean) {
        quickOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        quickOverlay = null
        quickSliderMode = if (polling) "poll" else "refresh"
        if (polling) showPollingIntervalSlider() else showRefreshIntervalSlider()
        quickTouch()
    }

    private fun renderQuickControls() {
        if (!isAdded || quickSliderMode.isNotEmpty() || !::binding.isInitialized) return
        val context = requireContext()
        fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
        quickOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val overlay = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            // Gauges can have their own Z elevation; keep the modal above them.
            elevation = dp(100).toFloat()
            isClickable = true
            setOnClickListener { quickBack() }
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(32, 37, 44))
                cornerRadius = dp(12).toFloat()
            }
            isClickable = true
        }
        fun row(label: String, selected: Boolean = false, action: () -> Unit) {
            val view = TextView(context).apply {
                text = label
                textSize = 17f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = GradientDrawable().apply {
                    setColor(if (selected) Color.rgb(57, 85, 115) else Color.rgb(40, 46, 54))
                    cornerRadius = dp(5).toFloat()
                }
                isClickable = true
                setOnClickListener { quickTouch(); action() }
            }
            panel.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        val heading = when (quickPage) {
            "main" -> "QUICK CONTROLS"
            "reset" -> "DAILY MIN/MAX RESET"
            "sizes" -> "GAUGE SIZES"
            "confirm" -> "CONFIRM RESET"
            else -> "LIVE DIAGNOSTICS"
        }
        row(heading) { quickTouch() }
        when (quickPage) {
            "main" -> quickMain.forEachIndexed { i, label ->
                row(label, i == quickIndex) { quickIndex = i; quickSelect() }
            }
            "reset" -> quickReset.forEachIndexed { i, label ->
                row(label, i == quickIndex) { quickIndex = i; quickSelect() }
            }
            "sizes" -> quickSizes.forEachIndexed { i, label ->
                val suffix = when (i) {
                    0 -> if (allRoundGaugesLarge) " - 110%" else " - 100%"
                    1 -> if (centerGaugeLarge) " - 110%" else " - 100%"
                    else -> ""
                }
                row(label + suffix, i == quickIndex) { quickIndex = i; quickSelect() }
            }
            "confirm" -> {
                row("Clear today's " + listOf("MAX", "MIN", "MIN and MAX")[quickResetSelection] + "?") {}
                row("CONFIRM", quickIndex == 0) { quickIndex = 0; quickSelect() }
                row("CANCEL", quickIndex == 1) { quickIndex = 1; quickSelect() }
            }
            else -> {
                val state = TextView(context).apply {
                    text = "ECU: " + currentConnectionStatus.name
                    setTextColor(Color.rgb(83, 220, 143))
                    textSize = 15f
                    setPadding(dp(12), dp(5), dp(12), dp(12))
                }
                panel.addView(state)
                val labels = listOf("PID Polling Target", "Actual PID Rate",
                    "Screen Refresh", "ECU Response", "PID Failures", "Last PID Age")
                val valueViews = mutableListOf<TextView>()
                labels.chunked(2).forEach { pair ->
                    val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    pair.forEach { label ->
                        val tile = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(10), dp(8), dp(10), dp(8))
                            background = GradientDrawable().apply {
                                setColor(Color.rgb(32, 47, 63))
                                cornerRadius = dp(7).toFloat()
                            }
                        }
                        tile.addView(TextView(context).apply {
                            text = label
                            textSize = 11f
                            setTextColor(Color.rgb(170, 200, 225))
                        })
                        val value = TextView(context).apply {
                            text = "—"
                            textSize = 18f
                            setTextColor(Color.WHITE)
                        }
                        valueViews.add(value)
                        tile.addView(value)
                        line.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply {
                            setMargins(dp(3), dp(3), dp(3), dp(3))
                        })
                    }
                    panel.addView(line)
                }
                diagnosticsValueViews = valueViews
                row("Torque Connection: " + currentConnectionStatus.name) {}
                row("Data Stream: " + if (communicationFaultActive) "Fault" else "Monitoring") {}
                row("BACK") { openQuickControls() }
                val snapshot = torqueRefresher.diagnosticsSnapshot()
                diagnosticsLastCount = snapshot.successfulPolls
                diagnosticsLastSampleMs = android.os.SystemClock.elapsedRealtime()
                diagnosticsHandler.removeCallbacks(diagnosticsRefresh)
                diagnosticsHandler.post(diagnosticsRefresh)
            }
        }
        overlay.addView(panel, FrameLayout.LayoutParams(
            minOf(dp(490), resources.displayMetrics.widthPixels - dp(32)), -2, Gravity.CENTER))
        mLayoutDashboard.addView(overlay, ConstraintLayout.LayoutParams(-1, -1).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        })
        overlay.bringToFront()
        quickOverlay = overlay
        quickTouch()
    }

    private fun handleRotaryStep(direction: Int) {
        if (quickOverlay != null || quickSliderMode.isNotEmpty()) quickRotate(direction)
        else setScreen(direction)
    }

    fun configureRotaryInput() {
        rootView.setOnGenericMotionListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_SCROLL &&
                (ev.isFromSource(InputDeviceCompat.SOURCE_MOUSE) ||
                    ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER))
            ) {
                val delta = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
                handleRotaryStep(if (delta < 0) 1 else -1)
                true
            } else {
                false
            }
        }
    }

    fun setupTypeface(selectedFont: String) {
        Timber.d("font: $selectedFont")
        val font = when (selectedFont) {
            "segments" -> R.font.digital
            "seat" -> R.font.seat_metastyle_monodigit_regular
            "audi" -> R.font.auditypedisplayhigh
            "vw" -> R.font.vwtextcarui_regular
            "vw2", "cadillac_escalade" -> R.font.vwthesis_mib_regular
            "frutiger" -> R.font.frutiger
            "vw3" -> R.font.vw_digit_reg
            "skoda" -> R.font.skoda
            "larabie" -> R.font.larabie
            "ford" -> R.font.unitedsans
            "ev" -> R.font.ev
            else -> R.font.digital
        }
        settingsViewModel.setFont(font)
    }


}
