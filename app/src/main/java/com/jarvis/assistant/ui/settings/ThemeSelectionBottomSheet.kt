package com.jarvis.assistant.ui.settings

import android.graphics.Color
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.databinding.BottomSheetThemeSelectionBinding
import com.jarvis.assistant.ui.home.HomeFragment
import com.jarvis.assistant.ui.home.MainActivity

class ThemeSelectionBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetThemeSelectionBinding? = null
    private val binding get() = _binding!!
    private val preferences by lazy { JarvisApp.instance.preferences }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        BottomSheetDialog(requireContext(), com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_BottomSheet)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetThemeSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    /** The sheet itself becomes an iOS-style glass pane and the screen behind it is blurred. */
    private fun applyLiquidGlassSheet() {
        val sheetDialog = dialog as? BottomSheetDialog ?: return
        com.jarvis.assistant.ui.glass.LiquidGlass.blurBehind(sheetDialog, radiusDp = 24f, dim = 0.28f)
        sheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background =
            android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        com.jarvis.assistant.ui.glass.LiquidGlass.apply(binding.root, 32f, topOnly = true, elevationDp = 0f)
    }

    override fun onStart() {
        super.onStart()
        // The slide runs as a window animation (on the compositor), so it is smooth; the heavy glass is applied once it has landed.
        dialog?.window?.setWindowAnimations(com.jarvis.assistant.R.style.Jarvis_GlassSheet_WindowAnimation)
        if (_binding != null) {
            playEntrance()
            binding.root.postDelayed({ if (_binding != null) applyLiquidGlassSheet() }, 420L)
        }
    }

    /**
     * Bubble entrance: every block pops up in turn with a little overshoot, while a soft glow in the theme colour washes down the
     * sheet and fades. Cheap property animations only, so it stays smooth. Skipped (instant) when Liquid Glass is off.
     */
    private fun playEntrance() {
        if (com.jarvis.assistant.ui.glass.GlassMode.plain()) return
        val root = binding.root as? ViewGroup ?: return
        val d = resources.displayMetrics.density
        val targets = ArrayList<View>()
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c is androidx.core.widget.NestedScrollView) {
                val inner = c.getChildAt(0) as? ViewGroup
                if (inner != null) for (j in 0 until inner.childCount) targets.add(inner.getChildAt(j))
            } else targets.add(c)
        }
        targets.take(14).forEachIndexed { i, v ->
            v.alpha = 0f; v.scaleX = 0.92f; v.scaleY = 0.92f; v.translationY = 28f * d
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setStartDelay(110L + i * 32L).setDuration(380L)
                .setInterpolator(android.view.animation.OvershootInterpolator(0.9f)).start()
        }
        root.post {
            if (_binding == null || root.width == 0) return@post
            val accent = com.jarvis.assistant.util.ThemeManager.getThemeAccentColor()
            val glow = android.graphics.drawable.GradientDrawable().apply {
                gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
                gradientRadius = root.width * 0.85f
                setGradientCenter(0.5f, 0.0f)
                colors = intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 120), Color.TRANSPARENT)
                setBounds(0, 0, root.width, root.height)
                alpha = 0
            }
            root.overlay.add(glow)
            android.animation.ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = 900L
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener { glow.alpha = (it.animatedValue as Float * 255f).toInt() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) { root.overlay.remove(glow) }
                })
                start()
            }
        }
    }

    /** A bubble pop on the tapped option, then the sheet closes; [then] runs after it (used to recreate for a new theme). */
    private fun confirmAndClose(tapped: View?, then: (() -> Unit)? = null) {
        val act = activity
        if (tapped != null && com.jarvis.assistant.ui.glass.GlassMode.enabled()) {
            com.jarvis.assistant.ui.glass.ViewSprings.pop(tapped, 0.92f, 1f)
        }
        val delay = if (com.jarvis.assistant.ui.glass.GlassMode.enabled()) 170L else 0L
        val run = Runnable {
            if (isAdded) dismissAllowingStateLoss()
            if (then != null) {
                then()
                act?.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }
        if (delay == 0L) run.run() else (tapped ?: view)?.postDelayed(run, delay) ?: run.run()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentTheme = preferences.appTheme
        updateCheckmarks(currentTheme)
        (view as? ViewGroup)?.let { r ->
            for (i in 0 until r.childCount) (r.getChildAt(i) as? androidx.core.widget.NestedScrollView)
                ?.let { com.jarvis.assistant.ui.glass.SmoothScroll.install(it) }
        }

        val customHex = preferences.customThemeColor
        binding.etCustomHex.setText(customHex)
        try {
            binding.viewCustomPreview.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(customHex))
        } catch (_: Exception) {}

        binding.btnOrbDefault.setOnClickListener { selectOrb("default", "Default Orb", it) }
        binding.btnOrbEleptical.setOnClickListener { selectOrb("eleptical_orb", "Eleptical Orb", it) }
        binding.btnOrbAurora.setOnClickListener { selectOrb("aurora_orb", "Aurora Orb", it) }
        binding.btnOrbCrystal.setOnClickListener { selectOrb("crystal_orb", "Crystal Orb", it) }
        binding.btnOrbIronMan.setOnClickListener { selectOrb("iron_man_head", "Iron Man 3D Helmet", it) }
        binding.btnOrbFullCharacter.setOnClickListener { selectOrb("full_human", "Full Human 3D Assistant", it) }

        setupOrbSelectionState()
        setupOrbLookControls()

        binding.cardThemeLight.setOnClickListener { selectTheme("light", "Light", it) }
        binding.cardThemeDark.setOnClickListener { selectTheme("dark", "Dark", it) }
        binding.cardThemeRed.setOnClickListener { selectTheme("red", "Red", it) }
        binding.cardThemeSunset.setOnClickListener { selectTheme("sunset", "Sunset", it) }
        binding.cardThemeCrystal.setOnClickListener { selectTheme("crystal", "Crystal Mirror", it) }

        binding.btnSelectMoreColours.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), MoreThemeColoursActivity::class.java))
        }

        binding.btnApplyCustom.setOnClickListener {
            val hex = binding.etCustomHex.text.toString().trim()
            applyCustomColor(hex)
        }

        // Quick Swatches
        binding.swatch1.setOnClickListener { applyCustomColor("#000000") }
        binding.swatch2.setOnClickListener { applyCustomColor("#1E1B4B") }
        binding.swatch3.setOnClickListener { applyCustomColor("#042F2E") }
        binding.swatch4.setOnClickListener { applyCustomColor("#451A03") }
        binding.swatch5.setOnClickListener { applyCustomColor("#F0FDF4") }
        binding.swatch6.setOnClickListener { applyCustomColor("#FAF5FF") }
    }

    private fun homeFragment(): HomeFragment? =
        (activity as? MainActivity)?.supportFragmentManager?.fragments?.filterIsInstance<HomeFragment>()?.firstOrNull()

    /**
     * Visually identifies the active orb without relying on text alone. Accent colour comes from
     * ThemeManager, so built-in and custom palettes stay in sync.
     */
    private fun setupOrbSelectionState() {
        val accent = com.jarvis.assistant.util.ThemeManager.getThemeAccentColor()
        val activeStyle = preferences.orbAnimation
        val textPrimary = android.util.TypedValue().also {
            requireContext().theme.resolveAttribute(com.jarvis.assistant.R.attr.themeTextPrimary, it, true)
        }.data
        val options = listOf(
            "default" to binding.btnOrbDefault,
            "eleptical_orb" to binding.btnOrbEleptical,
            "aurora_orb" to binding.btnOrbAurora,
            "crystal_orb" to binding.btnOrbCrystal,
            "iron_man_head" to binding.btnOrbIronMan,
            "full_human" to binding.btnOrbFullCharacter
        )
        options.forEach { (style, button) ->
            val selected = style == activeStyle
            button.isSelected = selected
            button.strokeColor = android.content.res.ColorStateList.valueOf(
                if (selected) accent else androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 105)
            )
            button.strokeWidth = if (selected) dp(2) else dp(1)
            button.setTextColor(if (selected) accent else textPrimary)
            button.alpha = if (selected) 1f else 0.82f
            button.contentDescription = button.text.toString() +
                if (selected) ", currently selected" else ", select orb"
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * Glow and size of the selected orb, and the shape of the Settings icons. Every slider applies live,
     * so you see the orb and the icons change as you drag.
     */
    private fun setupOrbLookControls() {
        val style = preferences.orbAnimation
        binding.tvOrbControlsHint.text = "Applies to the selected orb: " + when (style) {
            "aurora_orb" -> "Aurora Orb"
            "crystal_orb" -> "Crystal Orb"
            "eleptical_orb" -> "Eleptical Orb"
            "iron_man_head" -> "Iron Man 3D Helmet"
            "full_human" -> "Full Human 3D Assistant"
            else -> "Default Orb"
        }

        fun glowLabel(p: Int) = "Glow: " + (if (p == 0) "off" else "$p%")
        fun sizeLabel(p: Int) = "Size: ${60 + p}%"

        val glowStart = Math.round(preferences.getOrbGlow(style) * 100f)
        binding.seekOrbGlow.progress = glowStart
        binding.tvOrbGlowValue.text = glowLabel(glowStart)
        binding.seekOrbGlow.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvOrbGlowValue.text = glowLabel(progress)
                if (!fromUser) return
                preferences.setOrbGlow(style, progress / 100f)
                homeFragment()?.applyOrbLook(style)
            }
            override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {}
        })

        val sizeStart = Math.round(preferences.getOrbSize(style) * 100f) - 60
        binding.seekOrbSize.progress = sizeStart
        binding.tvOrbSizeValue.text = sizeLabel(sizeStart)
        binding.seekOrbSize.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvOrbSizeValue.text = sizeLabel(progress)
                if (!fromUser) return
                preferences.setOrbSize(style, (60 + progress) / 100f)
                homeFragment()?.applyOrbLook(style)
            }
            override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {}
        })

        val shapeNames = arrayOf("Plain", "Rectangular", "Pillowy", "Circular")
        binding.seekIconShape.progress = preferences.settingsIconShape
        binding.tvIconShapeValue.text = "Settings icon shape: " + shapeNames[preferences.settingsIconShape]
        binding.seekIconShape.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvIconShapeValue.text = "Settings icon shape: " + shapeNames[progress.coerceIn(0, 3)]
                if (!fromUser) return
                preferences.settingsIconShape = progress
                (parentFragment as? SettingsFragment)?.applyIconShape()
            }
            override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {}
        })
    }

    private fun selectOrb(style: String, label: String, tapped: View? = null) {
        preferences.orbAnimation = style
        val mainActivity = activity as? MainActivity
        val homeFrag = mainActivity?.supportFragmentManager?.fragments
            ?.filterIsInstance<HomeFragment>()
            ?.firstOrNull()
        homeFrag?.setOrbStyle(style)
        Toast.makeText(requireContext(), "$label selected", Toast.LENGTH_SHORT).show()
        confirmAndClose(tapped)
    }

    private fun applyCustomColor(hexInput: String) {
        val hex = if (hexInput.startsWith("#")) hexInput else "#$hexInput"
        try {
            Color.parseColor(hex)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Invalid HEX color format (e.g. #1E1B4B)", Toast.LENGTH_SHORT).show()
            return
        }

        preferences.customThemeColor = hex
        preferences.customThemeColor2 = "" // a single colour, not a blend
        preferences.appTheme = "custom"
        updateCheckmarks("custom")

        Toast.makeText(requireContext(), "Custom theme set to $hex", Toast.LENGTH_SHORT).show()

        // Sync with Orb on HomeFragment
        val mainActivity = activity as? MainActivity
        val homeFrag = mainActivity?.supportFragmentManager?.fragments
            ?.filterIsInstance<HomeFragment>()
            ?.firstOrNull()
        homeFrag?.setOrbTheme("custom", hex)

        dismiss()
        activity?.recreate()
    }

    private fun selectTheme(themeKey: String, themeName: String, tapped: View? = null) {
        preferences.appTheme = themeKey
        updateCheckmarks(themeKey)
        val note = if (themeKey == "crystal") "Theme set to $themeName • change the scene in Settings › Transparent Background" else "Theme set to $themeName"
        Toast.makeText(requireContext(), note, Toast.LENGTH_LONG).show()

        // Sync with Orb on HomeFragment
        val mainActivity = activity as? MainActivity
        val homeFrag = mainActivity?.supportFragmentManager?.fragments
            ?.filterIsInstance<HomeFragment>()
            ?.firstOrNull()
        homeFrag?.setOrbTheme(themeKey, null)

        val act = activity
        confirmAndClose(tapped) { act?.recreate() }
    }

    private fun updateCheckmarks(activeTheme: String) {
        binding.ivCheckLight.visibility = if (activeTheme == "light") View.VISIBLE else View.GONE
        binding.ivCheckDark.visibility = if (activeTheme == "dark") View.VISIBLE else View.GONE
        binding.ivCheckRed.visibility = if (activeTheme == "red") View.VISIBLE else View.GONE
        binding.ivCheckSunset.visibility = if (activeTheme == "sunset") View.VISIBLE else View.GONE
        binding.ivCheckCrystal.visibility = if (activeTheme == "crystal") View.VISIBLE else View.GONE
        binding.ivCheckCustom.visibility = if (activeTheme == "custom") View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ThemeSelectionBottomSheet"
        fun newInstance() = ThemeSelectionBottomSheet()
    }
}
