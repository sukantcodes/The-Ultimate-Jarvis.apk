package com.jarvis.assistant.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.databinding.BottomSheetThemeSelectionBinding
import com.jarvis.assistant.ui.home.HomeFragment
import com.jarvis.assistant.ui.home.MainActivity

class ThemeSelectionBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetThemeSelectionBinding? = null
    private val binding get() = _binding!!
    private val preferences by lazy { JarvisApp.instance.preferences }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetThemeSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentTheme = preferences.appTheme
        updateCheckmarks(currentTheme)

        val customHex = preferences.customThemeColor
        binding.etCustomHex.setText(customHex)
        try {
            binding.viewCustomPreview.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(customHex))
        } catch (_: Exception) {}

        binding.btnOrbDefault.setOnClickListener { selectOrb("default", "Default Orb") }
        binding.btnOrbAurora.setOnClickListener { selectOrb("aurora_orb", "Aurora Orb") }
        binding.btnOrbIronMan.setOnClickListener { selectOrb("iron_man_head", "Iron Man 3D Helmet") }
        binding.btnOrbFullCharacter.setOnClickListener { selectOrb("full_human", "Full Human 3D Assistant") }

        setupOrbLookControls()

        binding.cardThemeLight.setOnClickListener { selectTheme("light", "Light") }
        binding.cardThemeDark.setOnClickListener { selectTheme("dark", "Dark") }
        binding.cardThemeRed.setOnClickListener { selectTheme("red", "Red") }
        binding.cardThemeSunset.setOnClickListener { selectTheme("sunset", "Sunset") }

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
     * Glow and size of the selected orb, and the shape of the Settings icons. Every slider applies live,
     * so you see the orb and the icons change as you drag.
     */
    private fun setupOrbLookControls() {
        val style = preferences.orbAnimation
        binding.tvOrbControlsHint.text = "Applies to the selected orb: " + when (style) {
            "aurora_orb" -> "Aurora Orb"
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

    private fun selectOrb(style: String, label: String) {
        preferences.orbAnimation = style
        val mainActivity = activity as? MainActivity
        val homeFrag = mainActivity?.supportFragmentManager?.fragments
            ?.filterIsInstance<HomeFragment>()
            ?.firstOrNull()
        homeFrag?.setOrbStyle(style)
        Toast.makeText(requireContext(), "$label selected", Toast.LENGTH_SHORT).show()
        dismiss()
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

    private fun selectTheme(themeKey: String, themeName: String) {
        preferences.appTheme = themeKey
        updateCheckmarks(themeKey)
        Toast.makeText(requireContext(), "Theme set to $themeName", Toast.LENGTH_SHORT).show()

        // Sync with Orb on HomeFragment
        val mainActivity = activity as? MainActivity
        val homeFrag = mainActivity?.supportFragmentManager?.fragments
            ?.filterIsInstance<HomeFragment>()
            ?.firstOrNull()
        homeFrag?.setOrbTheme(themeKey, null)

        dismiss()
        activity?.recreate()
    }

    private fun updateCheckmarks(activeTheme: String) {
        binding.ivCheckLight.visibility = if (activeTheme == "light") View.VISIBLE else View.GONE
        binding.ivCheckDark.visibility = if (activeTheme == "dark") View.VISIBLE else View.GONE
        binding.ivCheckRed.visibility = if (activeTheme == "red") View.VISIBLE else View.GONE
        binding.ivCheckSunset.visibility = if (activeTheme == "sunset") View.VISIBLE else View.GONE
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
