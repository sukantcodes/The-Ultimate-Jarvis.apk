package com.jarvis.assistant.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.databinding.FragmentHomeBinding
import com.jarvis.assistant.ui.memory.MemoryActivity
import com.jarvis.assistant.util.LocationWeatherHelper
import com.jarvis.assistant.util.PermissionHelper
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel get() = (requireActivity() as MainActivity).viewModel
    private val preferences by lazy { JarvisApp.instance.preferences }

    private var isPageLoaded = false
    private var loadedAuroraOrb = false
    private var loadedElepticalOrb = false
    private var pendingOrbState: String? = null
    private var pendingThemeKey: String? = null
    private var pendingCustomHex: String? = null
    private var pendingOrbStyle: String? = null
    private var lastAudioPushTime = 0L
    private var taskFallbackPanel: android.widget.LinearLayout? = null
    private var taskFallbackText: android.widget.TextView? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            fetchLiveWeather()
        } else {
            binding.tvWeatherCondition.text = "Location Permission Needed"
            binding.tvWeatherDetails.text = "Tap here to grant location for accurate weather"
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    private var appliedLandscapeLayout: Boolean? = null
    private var landscapeGuideId: Int = View.NO_ID

    /**
     * Reflows the Home dashboard when the available app window changes shape.
     * Landscape uses a true two-pane composition: the live orb owns the left
     * stage while every Home option remains visible in a dedicated right rail.
     * Portrait restores the original XML constraints without accumulating edits.
     */
    private fun applyResponsiveHomeLayout(width: Int, height: Int) {
        val root = binding.root as? androidx.constraintlayout.widget.ConstraintLayout ?: return
        val landscape = width > height && width >= dp(600)
        if (appliedLandscapeLayout == landscape) return
        appliedLandscapeLayout = landscape

        if (!landscape) {
            androidx.constraintlayout.widget.ConstraintSet().apply {
                clone(requireContext(), R.layout.fragment_home)
                applyTo(root)
            }
            return
        }

        val guideId = View.generateViewId().also { landscapeGuideId = it }
        val landscapeConstraints = androidx.constraintlayout.widget.ConstraintSet().apply {
            clone(root)
            create(guideId, androidx.constraintlayout.widget.ConstraintSet.VERTICAL_GUIDELINE)
            setGuidelinePercent(guideId, 0.585f)

            // Remove the portrait-only horizontal split and build a full-height orb stage.
            setVisibility(R.id.orbAreaEnd, View.GONE)
            clear(R.id.orbContainer)
            constrainWidth(R.id.orbContainer, 0)
            constrainHeight(R.id.orbContainer, 0)
            connect(R.id.orbContainer, androidx.constraintlayout.widget.ConstraintSet.START,
                androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.START)
            connect(R.id.orbContainer, androidx.constraintlayout.widget.ConstraintSet.END,
                guideId, androidx.constraintlayout.widget.ConstraintSet.START)
            connect(R.id.orbContainer, androidx.constraintlayout.widget.ConstraintSet.TOP,
                androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.TOP)
            connect(R.id.orbContainer, androidx.constraintlayout.widget.ConstraintSet.BOTTOM,
                androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)

            // Keep the header in the orb pane; the menu remains reachable at the far right.
            clear(R.id.layoutTitle)
            constrainWidth(R.id.layoutTitle, 0)
            connect(R.id.layoutTitle, androidx.constraintlayout.widget.ConstraintSet.START,
                R.id.btnTopChat, androidx.constraintlayout.widget.ConstraintSet.END, dp(8))
            connect(R.id.layoutTitle, androidx.constraintlayout.widget.ConstraintSet.END,
                guideId, androidx.constraintlayout.widget.ConstraintSet.START)
            connect(R.id.layoutTitle, androidx.constraintlayout.widget.ConstraintSet.TOP,
                androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.TOP, dp(8))

            clear(R.id.btnTopSettings)
            connect(R.id.btnTopSettings, androidx.constraintlayout.widget.ConstraintSet.START,
                guideId, androidx.constraintlayout.widget.ConstraintSet.START, dp(8))
            connect(R.id.btnTopSettings, androidx.constraintlayout.widget.ConstraintSet.TOP,
                androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.TOP, dp(8))

            // Right-side dashboard rail: status, weather, shortcuts, then quick actions.
            val railViews = intArrayOf(R.id.layoutStatusIndicator, R.id.cardWeather, R.id.homeLaunchpad, R.id.layoutActionGrid)
            railViews.forEach { id ->
                clear(id)
                constrainWidth(id, 0)
                connect(id, androidx.constraintlayout.widget.ConstraintSet.START,
                    guideId, androidx.constraintlayout.widget.ConstraintSet.START, dp(12))
                connect(id, androidx.constraintlayout.widget.ConstraintSet.END,
                    androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.END, dp(12))
            }

            connect(R.id.layoutStatusIndicator, androidx.constraintlayout.widget.ConstraintSet.TOP,
                androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.TOP, dp(12))
            connect(R.id.cardWeather, androidx.constraintlayout.widget.ConstraintSet.TOP,
                R.id.layoutStatusIndicator, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, dp(8))
            connect(R.id.homeLaunchpad, androidx.constraintlayout.widget.ConstraintSet.TOP,
                R.id.cardWeather, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, dp(8))
            connect(R.id.layoutActionGrid, androidx.constraintlayout.widget.ConstraintSet.TOP,
                R.id.homeLaunchpad, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, dp(8))
            connect(R.id.layoutActionGrid, androidx.constraintlayout.widget.ConstraintSet.BOTTOM,
                androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, dp(8))

            constrainHeight(R.id.homeLaunchpad, dp(64))
            constrainHeight(R.id.layoutActionGrid, dp(84))
            constrainHeight(R.id.cardWeather, androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT)
            constrainHeight(R.id.layoutStatusIndicator, androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT)

            // Landscape is intentionally edge-to-edge within the app window, not a clipped portrait canvas.
            applyTo(root)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyFragmentBackground(binding.root)
        binding.root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            applyResponsiveHomeLayout(right - left, bottom - top)
        }
        createTaskFallbackPanel()

        // Top Navigation
        binding.btnTopChat.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_CHAT)
        }

        binding.btnTopSettings.setOnClickListener {
            (activity as? MainActivity)?.openNavigationDrawer()
        }

        binding.btnDismissLiveError.setOnClickListener {
            hideLiveErrorOverlay {
                viewModel.clearLiveError()
            }
        }

        setupOrbWebView()
        if (savedInstanceState == null) playHomeEntrance()
        startStatusPulse()

        // Action Buttons
        binding.btnScreenShareQuick.setOnClickListener {
            (activity as? MainActivity)?.toggleScreenShare()
        }

        binding.btnSavedMemories.setOnClickListener {
            startActivity(Intent(requireContext(), MemoryActivity::class.java))
        }

        binding.btnConversationLanguageQuick.setOnClickListener {
            showLanguageDialog()
        }

        // Home launchpad shortcuts for frequently used JARVIS modules.
        binding.btnHomeAutomation.setOnClickListener {
            startActivity(Intent(requireContext(), com.jarvis.assistant.studio.ui.AutomationStudioActivity::class.java))
        }

        binding.btnHomeOffline.setOnClickListener {
            startActivity(Intent(requireContext(), com.jarvis.assistant.ui.offline.OfflineModelActivity::class.java))
        }

        binding.btnHomeMaya.setOnClickListener {
            startActivity(Intent(requireContext(), com.jarvis.assistant.ui.settings.MayaModeActivity::class.java))
        }

        // Weather Card Click -> Refresh or Request Permission
        binding.cardWeather.setOnClickListener {
            if (!PermissionHelper.hasLocationPermission(requireContext())) {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            } else {
                fetchLiveWeather()
            }
        }

        updateHomeHeaderAndVoiceStatus()
        observeViewModel()
        checkLocationAndFetchWeather()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupOrbWebView() {
        binding.orbWebView.apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isPageLoaded = true
                    if (!loadedAuroraOrb) {
                        val working = com.jarvis.assistant.automation.agent.AgentTaskEngine.taskState.value != null
                        view?.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setWorking($working);", null)
                    }

                    // Sync Theme
                    val currentTheme = pendingThemeKey ?: preferences.appTheme
                    val customHex = pendingCustomHex ?: preferences.customThemeColor
                    setOrbTheme(currentTheme, customHex)
                    setOrbStyle(pendingOrbStyle ?: preferences.orbAnimation)
                    applyOrbLook()

                    // Sync State
                    val taskActive = com.jarvis.assistant.automation.agent.AgentTaskEngine.taskState.value != null
                    val state = pendingOrbState ?: when (viewModel.conversationState.value) {
                        ConversationState.LISTENING -> "listening"
                        ConversationState.SPEAKING -> "speaking"
                        ConversationState.THINKING -> if (loadedElepticalOrb && taskActive) "working" else "thinking"
                        ConversationState.IDLE -> if (loadedElepticalOrb && taskActive) "working" else "idle"
                    }
                    setOrbState(state)
                }
            }

            // Single tap detection triggers mic/conversation without interrupting 3D rotation drag.
            // onSingleTapUp fires on finger-up (never after a long press or a drag), so the orb responds
            // instantly instead of waiting out the ~300 ms double-tap window of onSingleTapConfirmed.
            val gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    (activity as? MainActivity)?.handleMicOrOrbClick()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    (activity as? MainActivity)?.handleHoldMicOrOrb()
                }
            })

            setOnTouchListener { v, event ->
                gestureDetector.onTouchEvent(event)
                v.onTouchEvent(event)
            }

            // Load the orb the user actually selected straight away. Previously the default orb was
            // loaded first and then replaced, which flashed the wrong orb and built two WebGL contexts.
            loadOrbPage(preferences.orbAnimation)
        }
    }

    /**
     * Loads the page for [style] into the shared orb WebView and records which page family is active.
     * Every page uses the same hardware-accelerated renderer; relative three.min.js paths resolve
     * because all pages live next to it in assets.
     */
    private fun loadOrbPage(style: String) {
        // Settings can retain a HomeFragment instance after its view has been destroyed.
        // Keep the requested style in pendingOrbStyle; setupOrbWebView() will load the
        // persisted selection when a fresh Home view is created.
        if (!isAdded || _binding == null) {
            pendingOrbStyle = style
            isPageLoaded = false
            return
        }

        val wantsAurora = style == "aurora_orb"
        val wantsEleptical = style == "eleptical_orb"
        loadedAuroraOrb = wantsAurora
        loadedElepticalOrb = wantsEleptical
        isPageLoaded = false
        binding.orbWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        binding.orbWebView.loadUrl(
            when {
                wantsAurora -> "file:///android_asset/aurora_orb.html?hud=0&state=idle"
                wantsEleptical -> "file:///android_asset/eleptical_orb.html"
                else -> "file:///android_asset/orb.html?embedded=1"
            }
        )
    }

    fun setOrbState(state: String) {
        pendingOrbState = state
        if (!isPageLoaded) return
        activity?.runOnUiThread {
            binding.orbWebView.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setState('$state');", null)
        }
    }

    /**
     * Applies the glow and size chosen in Settings > Theme & 3D Orb Colors for the selected orb.
     * Size scales the whole orb stage; glow drives the halo behind it and the orb page's own glow.
     */
    fun applyOrbLook(style: String = preferences.orbAnimation) {
        if (_binding == null) return
        val size = preferences.getOrbSize(style)
        val glow = preferences.getOrbGlow(style)
        binding.orbWebView.scaleX = size
        binding.orbWebView.scaleY = size
        binding.orbHaloRing.scaleX = size
        binding.orbHaloRing.scaleY = size
        // The Elliptical Orb paints its own elliptical halo inside the page; the round native halo
        // behind it would double the glow and not match its shape.
        binding.orbHaloRing.alpha = if (style == "eleptical_orb") 0f else (glow / 1.5f).coerceIn(0f, 1f)
        if (isPageLoaded) {
            activity?.runOnUiThread {
                if (_binding != null) {
                    binding.orbWebView.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setGlow && window.JarvisOrb.setGlow($glow);", null)
                }
            }
        }
    }

    fun setOrbStyle(style: String) {
        // The settings sheet may find a stale HomeFragment whose view lifecycle has ended.
        // Persisting the preference is handled by the caller; defer all view work until
        // setupOrbWebView() runs for the next valid Home view.
        pendingOrbStyle = style
        if (!isAdded || _binding == null) {
            isPageLoaded = false
            return
        }

        applyOrbLook(style)
        val wantsAurora = style == "aurora_orb"
        val wantsEleptical = style == "eleptical_orb"
        if (wantsAurora != loadedAuroraOrb || wantsEleptical != loadedElepticalOrb) {
            loadOrbPage(style)
            return
        }
        if (!isPageLoaded || wantsAurora || wantsEleptical) return
        val safeStyle = style.filter { it.isLetterOrDigit() || it == '_' }
        activity?.runOnUiThread {
            if (_binding == null || !isAdded) return@runOnUiThread
            binding.orbWebView.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setStyle('$safeStyle');", null)
        }
    }

    /** The soft halo behind the orb follows the theme colour instead of staying fixed blue. */
    private fun tintOrbHalo() {
        if (_binding == null) return
        binding.orbHaloRing.backgroundTintList =
            android.content.res.ColorStateList.valueOf(com.jarvis.assistant.util.ThemeManager.getThemeAccentColor())
    }

    fun setOrbTheme(themeKey: String, customHex: String?) {
        pendingThemeKey = themeKey
        pendingCustomHex = customHex
        tintOrbHalo()
        if (!isPageLoaded || loadedAuroraOrb) return
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            if (loadedElepticalOrb) {
                // Exact light/dark flag + the app's two accent colours (custom themes included).
                val (isLight, first, second) = com.jarvis.assistant.util.ThemeManager.getOrbThemeSpec(themeKey)
                binding.orbWebView.evaluateJavascript(
                    "window.JarvisOrb && window.JarvisOrb.setAppTheme && window.JarvisOrb.setAppTheme($isLight,'$first','$second');",
                    null
                )
                return@runOnUiThread
            }
            if (themeKey == "custom" && !customHex.isNullOrBlank()) {
                val second = preferences.customThemeColor2.takeIf { it.isNotBlank() } ?: customHex
                val safeFirst = customHex.filter { it.isLetterOrDigit() || it == '#' }
                val safeSecond = second.filter { it.isLetterOrDigit() || it == '#' }
                binding.orbWebView.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setBackground('$safeFirst','$safeSecond');", null)
            } else {
                val mapped = when (themeKey) {
                    "dark" -> "dark"
                    "red" -> "red"
                    "sunset" -> "sunset"
                    else -> "light"
                }
                binding.orbWebView.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setTheme('$mapped');", null)
            }
        }
    }

    private fun pushExternalAudio(amp: Float, bass: Float, high: Float) {
        if (!isPageLoaded) return
        val now = System.currentTimeMillis()
        if (now - lastAudioPushTime < 33L) return // ~30 calls per second throttle
        lastAudioPushTime = now
        // NaN/Infinity would be pasted into the JS call as bare identifiers; keep values finite and in range.
        val a = amp.takeIf { it.isFinite() }?.coerceIn(0f, 2f) ?: 0f
        val b = bass.takeIf { it.isFinite() }?.coerceIn(0f, 2f) ?: 0f
        val h = high.takeIf { it.isFinite() }?.coerceIn(0f, 2f) ?: 0f
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            if (loadedAuroraOrb) binding.orbWebView.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setAudioLevel($a);", null) else binding.orbWebView.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setExternalAudio($a, $b, $h);", null)
        }
    }

    override fun onResume() {
        super.onResume()
        com.jarvis.assistant.util.ThemeManager.applyFragmentBackground(binding.root)
        binding.orbWebView.onResume()
        binding.orbWebView.resumeTimers()
        renderTaskFallback(com.jarvis.assistant.automation.agent.AgentTaskEngine.taskState.value)

        // Sync theme with settings
        setOrbTheme(preferences.appTheme, preferences.customThemeColor)
        setOrbStyle(preferences.orbAnimation)
        applyOrbLook()
        updateHomeHeaderAndVoiceStatus()

        if (PermissionHelper.hasLocationPermission(requireContext())) {
            fetchLiveWeather()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.orbWebView.onPause()
        binding.orbWebView.pauseTimers()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            binding.orbWebView.visibility = View.VISIBLE
            binding.orbWebView.onResume()
            binding.orbWebView.resumeTimers()
            com.jarvis.assistant.util.ThemeManager.applyFragmentBackground(binding.root)
            setOrbTheme(preferences.appTheme, preferences.customThemeColor)
            updateHomeHeaderAndVoiceStatus()
        } else if (hidden && _binding != null) {
            binding.orbWebView.onPause()
            binding.orbWebView.pauseTimers()
            binding.orbWebView.visibility = View.GONE
            binding.root.visibility = View.GONE
        }
    }

    private fun checkLocationAndFetchWeather() {
        if (PermissionHelper.hasLocationPermission(requireContext())) {
            fetchLiveWeather()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun fetchLiveWeather() {
        binding.tvWeatherCondition.text = "Locating • Fetching weather..."
        viewLifecycleOwner.lifecycleScope.launch {
            val weather = LocationWeatherHelper.fetchWeather(requireContext())
            if (_binding != null && weather != null) {
                binding.tvTemperature.text = weather.temperature
                binding.tvWeatherCondition.text = weather.condition
                binding.tvWeatherDetails.text = weather.details
                binding.ivWeatherIcon.setImageResource(weather.iconResId)
                // Keep Jarvis's context aligned with the city shown on Home.
                preferences.userLocation = weather.cityName
            } else if (_binding != null) {
                binding.tvWeatherCondition.text = "Live Weather • Tap to retry"
            }
        }
    }

    private var statusPulse: android.animation.ObjectAnimator? = null

    /** Cards rise in one after another from the bottom, so Home feels alive when it opens. */
    private fun playHomeEntrance() {
        val motionScale = com.jarvis.assistant.util.ResponsiveMotionMetrics.scaleFactor(requireContext())
        val rise = dp((26f * motionScale).toInt()).toFloat()
        val entranceDuration = (460f * (0.9f + motionScale * 0.1f)).toLong().coerceIn(420L, 560L)
        val ordered = listOf(
            binding.layoutTitle, binding.layoutStatusIndicator, binding.cardWeather,
            binding.homeLaunchpad, binding.layoutActionGrid
        )
        ordered.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = rise
            view.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay((120f * motionScale + index * 90f * motionScale).toLong())
                .setDuration(entranceDuration)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .start()
        }
    }

    /** A slow breathing glow on the status dot. Stopped with the view so nothing keeps running behind it. */
    private fun startStatusPulse() {
        statusPulse?.cancel()
        statusPulse = android.animation.ObjectAnimator.ofFloat(binding.dotStatusGlow, View.ALPHA, 1f, 0.35f).apply {
            duration = 1100L
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun updateHomeHeaderAndVoiceStatus() {
        if (_binding == null) return
        val userName = preferences.userName.ifBlank { "Sukant" }
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timeGreeting = when (hour) {
            in 4..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Welcome back"
        }
        binding.tvGreeting.text = "$timeGreeting, $userName"

        val currentLang = preferences.language.ifBlank { "Auto" }
        binding.tvQuickLanguageStatus.text = if (currentLang.contains("Auto", ignoreCase = true)) "Auto" else currentLang
    }

    private fun showLanguageDialog() {
        val languages = com.jarvis.assistant.data.model.GeminiConstants.SUPPORTED_LANGUAGES.toTypedArray()
        val currentLang = preferences.language
        val currentIndex = languages.indexOfFirst { it.equals(currentLang, ignoreCase = true) }.let { if (it >= 0) it else 0 }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Jarvis_MaterialDialog)
            .setTitle("Select Conversation Language")
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val chosen = languages[which]
                preferences.language = chosen
                binding.tvQuickLanguageStatus.text = chosen
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    com.jarvis.assistant.automation.agent.AgentTaskEngine.taskState.collect { snapshot ->
                        renderTaskFallback(snapshot)
                        if (isPageLoaded && !loadedAuroraOrb) {
                            binding.orbWebView.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setWorking(${snapshot != null});", null)
                        }
                    }
                }
                launch {
                    val screenCapture = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance()
                    combine(screenCapture.isSharing, screenCapture.hasReceivedFrame) { sharing, hasFrame -> sharing to hasFrame }
                        .collect { (sharing, hasFrame) ->
                        binding.tvQuickScreenShareStatus.text = when {
                            !sharing -> "Share screen"
                            !hasFrame -> "Starting…"
                            else -> "Stop sharing"
                        }
                        binding.tvQuickScreenShareStatus.setTextColor(
                            if (sharing && hasFrame) Color.parseColor("#10B981")
                            else ContextCompat.getColor(requireContext(), R.color.text_subtle)
                        )
                    }
                }

                launch {
                    combine(
                        viewModel.isSessionOn,
                        viewModel.isMicMuted,
                        viewModel.conversationState,
                        com.jarvis.assistant.background.JarvisConversationService.isStandby
                    ) { isOn, isMuted, state, isStandby ->
                        HomeStatusState(isOn, isMuted, state, isStandby)
                    }.collect { status ->
                        if (!status.isOn) {
                            setOrbState("idle")
                            binding.tvListeningStatus.text = "JARVIS OFF • TAP TO START"
                            binding.tvListeningStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_subtle))
                            binding.tvHoldHint.text = "Tap mic or orb to power on"
                            binding.tvHoldHint.visibility = View.VISIBLE
                            binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                Color.parseColor("#64748B")
                            )
                        } else if (status.isMuted) {
                            setOrbState("idle")
                            binding.tvListeningStatus.text = "MIC MUTED (OFF) • TAP TO TALK"
                            binding.tvListeningStatus.setTextColor(Color.parseColor("#EF4444"))
                            binding.tvHoldHint.text = "Microphone is muted • Tap mic to unmute"
                            binding.tvHoldHint.visibility = View.VISIBLE
                            binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                Color.parseColor("#EF4444")
                            )
                        } else if (status.isStandby) {
                            setOrbState("idle")
                            binding.tvListeningStatus.text = "STANDBY • SAY 'HEY JARVIS'"
                            binding.tvListeningStatus.setTextColor(Color.parseColor("#3B82F6"))
                            binding.tvHoldHint.text = "Listening for wake word • Hold mic to power off"
                            binding.tvHoldHint.visibility = View.VISIBLE
                            binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                Color.parseColor("#3B82F6")
                            )
                        } else {
                            binding.tvHoldHint.visibility = View.VISIBLE
                            when (status.state) {
                                ConversationState.LISTENING -> {
                                    setOrbState("listening")
                                    binding.tvListeningStatus.text = "MIC ON • LISTENING..."
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#10B981"))
                                    binding.tvHoldHint.text = "Speak now • Tap to mute • Hold to power off"
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                        Color.parseColor("#10B981")
                                    )
                                }
                                ConversationState.SPEAKING -> {
                                    setOrbState("speaking")
                                    binding.tvListeningStatus.text = "JARVIS SPEAKING..."
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#06B6D4"))
                                    binding.tvHoldHint.text = "Tap mic to interrupt"
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                        Color.parseColor("#06B6D4")
                                    )
                                }
                                ConversationState.THINKING -> {
                                    val taskActive = com.jarvis.assistant.automation.agent.AgentTaskEngine.taskState.value != null
                                    setOrbState(if (loadedElepticalOrb && taskActive) "working" else "thinking")
                                    binding.tvListeningStatus.text = if (loadedElepticalOrb && taskActive) "WORKING..." else "THINKING..."
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#F59E0B"))
                                    binding.tvHoldHint.text = "Processing request..."
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                        Color.parseColor("#F59E0B")
                                    )
                                }
                                ConversationState.IDLE -> {
                                    val taskActive = com.jarvis.assistant.automation.agent.AgentTaskEngine.taskState.value != null
                                    setOrbState(if (loadedElepticalOrb && taskActive) "working" else "idle")
                                    binding.tvListeningStatus.text = if (loadedElepticalOrb && taskActive) "WORKING..." else "MIC ON • READY"
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#10B981"))
                                    binding.tvHoldHint.text = "Tap to mute • Hold to power off"
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                        Color.parseColor("#10B981")
                                    )
                                }
                            }
                        }
                    }
                }

                launch {
                    viewModel.audioFrequency.collect { freq ->
                        val amp = freq.amplitude
                        val bass = freq.bass
                        val high = freq.high
                        pushExternalAudio(amp, bass, high)
                    }
                }

                launch {
                    viewModel.liveError.collect { error ->
                        if (error == null) {
                            hideLiveErrorOverlay()
                        } else {
                            showLiveErrorOverlay()
                            binding.tvLiveErrorTitle.text = error.title
                            binding.tvLiveErrorMessage.text = error.message
                            binding.btnFixLiveError.text = error.actionText

                            when (error.errorCode) {
                                "API_KEY_INVALID", "API_KEY_MISSING" -> {
                                    binding.ivLiveErrorIcon.setImageResource(R.drawable.ic_warning)
                                    binding.ivLiveErrorIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                                    binding.tvLiveErrorTitle.setTextColor(Color.parseColor("#EF4444"))
                                    binding.btnFixLiveError.setOnClickListener {
                                        startActivity(Intent(requireContext(), com.jarvis.assistant.ui.settings.ApiConnectionActivity::class.java))
                                    }
                                }
                                "ACCESSIBILITY_DISABLED" -> {
                                    binding.ivLiveErrorIcon.setImageResource(R.drawable.ic_settings_shield)
                                    binding.ivLiveErrorIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                                    binding.tvLiveErrorTitle.setTextColor(Color.parseColor("#F59E0B"))
                                    binding.btnFixLiveError.setOnClickListener {
                                        PermissionHelper.openAccessibilitySettings(requireContext())
                                    }
                                }
                                "PERMISSIONS_MISSING" -> {
                                    binding.ivLiveErrorIcon.setImageResource(R.drawable.ic_settings_shield)
                                    binding.ivLiveErrorIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                                    binding.tvLiveErrorTitle.setTextColor(Color.parseColor("#F59E0B"))
                                    binding.btnFixLiveError.setOnClickListener {
                                        startActivity(Intent(requireContext(), com.jarvis.assistant.ui.permission.PermissionActivity::class.java))
                                    }
                                }
                                else -> {
                                    binding.ivLiveErrorIcon.setImageResource(R.drawable.ic_warning)
                                    binding.ivLiveErrorIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                                    binding.tvLiveErrorTitle.setTextColor(Color.parseColor("#EF4444"))
                                    binding.btnFixLiveError.setOnClickListener {
                                        (activity as? MainActivity)?.handleMicOrOrbClick()
                                    }
                                }
                            }
                        }
                    }
                }

                launch {
                    viewModel.connectionStatus.collect { status ->
                        if (status.startsWith("ERROR")) {
                            binding.tvListeningStatus.text = status
                            binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                Color.parseColor("#EF4444")
                            )
                        }
                    }
                }
            }
        }
    }

    private fun showLiveErrorOverlay() {
        if (_binding == null) return
        val card = binding.cardLiveError
        if (card.visibility == View.VISIBLE && card.alpha == 1f) return
        card.alpha = 0f
        card.scaleX = 0.85f
        card.scaleY = 0.85f
        card.visibility = View.VISIBLE
        card.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(240)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.15f))
            .start()
    }

    private fun hideLiveErrorOverlay(onEnd: (() -> Unit)? = null) {
        if (_binding == null) {
            onEnd?.invoke()
            return
        }
        val card = binding.cardLiveError
        if (card.visibility != View.VISIBLE) {
            onEnd?.invoke()
            return
        }
        card.animate()
            .alpha(0f)
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(180)
            .withEndAction {
                if (_binding != null) {
                    card.visibility = View.GONE
                }
                onEnd?.invoke()
            }
            .start()
    }

    private fun createTaskFallbackPanel() {
        val root = binding.root as? androidx.constraintlayout.widget.ConstraintLayout ?: return
        val panel = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(12, 20, 35))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), android.graphics.Color.rgb(34, 211, 238))
            }
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }
        panel.addView(android.widget.TextView(requireContext()).apply {
            text = "JARVIS AGENT PROGRESS"
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.rgb(103, 232, 249))
        })
        val details = android.widget.TextView(requireContext()).apply {
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            maxLines = 4
            setPadding(0, dp(5), 0, dp(2))
        }
        panel.addView(details)
        panel.addView(android.widget.Button(requireContext()).apply {
            text = "Enable floating overlay"
            isAllCaps = false
            setOnClickListener {
                try {
                    startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${requireContext().packageName}")
                    ))
                } catch (_: Exception) {
                    android.widget.Toast.makeText(requireContext(), "Open Android Settings → Apps → JARVIS → Display over other apps.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }, android.widget.LinearLayout.LayoutParams(-2, dp(44)))
        val params = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(0, -2).apply {
            startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            topMargin = dp(70)
            marginStart = dp(16)
            marginEnd = dp(16)
        }
        root.addView(panel, params)
        taskFallbackPanel = panel
        taskFallbackText = details
    }

    private fun renderTaskFallback(snapshot: com.jarvis.assistant.automation.agent.AgentTaskSnapshot?) {
        val panel = taskFallbackPanel ?: return
        val show = snapshot != null && !android.provider.Settings.canDrawOverlays(requireContext())
        panel.visibility = if (show) View.VISIBLE else View.GONE
        if (!show || snapshot == null) return
        val activeStep = snapshot.steps.firstOrNull { it.stepId == snapshot.currentStepId }?.title ?: snapshot.currentStatus
        val checklist = snapshot.steps.take(4).joinToString("\n") { step ->
            val mark = if (step.status == com.jarvis.assistant.automation.agent.TaskStepStatus.COMPLETED) "✓" else "○"
            "$mark ${step.title}"
        }
        taskFallbackText?.text = "${snapshot.originalGoal}\n${snapshot.progressPercentage}% · ${snapshot.currentStatus.replace('_', ' ')}\nCurrent: $activeStep\n$checklist"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        statusPulse?.cancel()
        statusPulse = null
        // A newly-created WebView starts on the built-in page; force the selected
        // optional orb to be loaded again when Home is recreated.
        isPageLoaded = false
        loadedAuroraOrb = false
        loadedElepticalOrb = false
        _binding = null
    }
}

private data class HomeStatusState(
    val isOn: Boolean,
    val isMuted: Boolean,
    val state: ConversationState,
    val isStandby: Boolean
)
