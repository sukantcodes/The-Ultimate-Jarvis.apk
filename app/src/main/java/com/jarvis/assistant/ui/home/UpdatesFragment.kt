package com.jarvis.assistant.ui.home

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.widget.ProgressBar
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.databinding.FragmentUpdatesBinding
import com.jarvis.assistant.updates.ManualUpdateInstaller

class UpdatesFragment : Fragment() {

    private var _binding: FragmentUpdatesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentUpdatesBinding.inflate(inflater, container, false)
        renderVersionBadge()
        renderFeatureUpdates()
        return binding.root
    }

    private data class FeatureNotice(
        val category: String,
        val title: String,
        val details: String,
        val accent: Int,
        val tag: String = "NEW"
    )

    // Shared accent palette, kept identical to the previous design.
    private val cyan = Color.rgb(0, 224, 255)
    private val violet = Color.rgb(174, 91, 255)
    private val pink = Color.rgb(255, 65, 180)
    private val blue = Color.rgb(65, 137, 255)
    private val mint = Color.rgb(55, 255, 190)
    private val amber = Color.rgb(255, 190, 65)

    private fun renderVersionBadge() {
        val badge = binding.tvVersionBadge
        badge.text = "v" + BuildConfig.VERSION_NAME
        badge.contentDescription = "JARVIS version " + BuildConfig.VERSION_NAME
    }

    private fun renderFeatureUpdates() {
        val updates = listOf(
            FeatureNotice("DEVICE CARE", "Junk Clean", "Scan accessible shared phone storage for cleanup candidates. Review the results before confirming deletion.", cyan),
            FeatureNotice("DEVICE CARE", "Boost", "View memory and device status with shortcuts to Android's app-management controls.", violet),
            FeatureNotice("DEVICE CARE", "Battery Saver", "Quick access to Android battery-saving controls and battery information.", mint),
            FeatureNotice("DEVICE CARE", "Large Files Cleanup", "Find large files in accessible shared storage and choose what you want to remove.", blue),
            FeatureNotice("DEVICE CARE", "WhatsApp Clean", "Scan accessible WhatsApp media folders for reviewable cleanup candidates.", pink),
            FeatureNotice("DEVICE CARE", "Photos Clean", "Browse accessible photo files and review selections before deleting.", amber),
            FeatureNotice("DEVICE CARE", "App Uninstaller", "Open Android's uninstall confirmation flow for apps you choose.", violet),
            FeatureNotice("VISUAL EXPERIENCE", "Snaptube-style cleanup animations", "Integrated the supplied Lottie animation assets for supported cleanup and scan states.", cyan),
            FeatureNotice("DEVICE ACCESS", "Phone-wide storage scan", "Cleanup scans can inspect accessible shared storage across folders, not only JARVIS files. Android private app data remains protected by the OS.", mint),
            FeatureNotice("AGENT TASKS", "Verified actions and recovery", "JARVIS observes the current screen, checks action results, tracks progress, and stops safely when the UI is unclear.", violet),
            FeatureNotice("FLOATING AVATAR", "Always-on or working-only display", "Choose an avatar, double-tap for compact task progress, drag it anywhere on screen, and choose a dock edge in Settings.", pink),
            FeatureNotice("TASK PROGRESS", "Four-step compact status", "The task HUD shows the goal, recent steps, a running indicator, completed checks, and a subdued dark progress line.", blue),
            FeatureNotice("AUTOMATION", "Quiet task automation", "When enabled, task narration is muted while ordinary conversation stays audible.", amber),
            FeatureNotice("WHATSAPP", "Saved contact lookup", "Contact aliases are supported. Grant Contacts permission first; JARVIS reports missing permission separately from a name mismatch.", mint),
            FeatureNotice("PHONE CALLS", "Permission-aware calling", "Normal calls require Contacts and Phone permissions. WhatsApp calls use WhatsApp and do not place a cellular call.", cyan),
            FeatureNotice("MUSIC", "Spotify search and playback checks", "Searches can inspect results and verify playback before reporting success. Device behavior depends on the Spotify screen and account.", violet),
            FeatureNotice("PERMISSIONS", "Per-feature access status", "The System Authorizations page lists microphone, location, notifications, overlay, Accessibility, contacts, phone, camera, Bluetooth, battery, and brightness access.", pink),
            FeatureNotice("AI PERSONA", "Operator profile preview", "Preview your selected assistant voice and the name it will use before activating the persona.", blue),
            FeatureNotice("APPEARANCE", "Sunset theme by default", "Fresh installs start with the sunset theme; you can still select other themes in Settings.", amber),
            FeatureNotice("DIAGNOSTIC", "Google Flow / AI Studio", "Recent captured logs could not verify the prompt input in Chrome. Some editor fields may not be exposed to Accessibility; a fresh screen capture is needed to investigate.", amber, "INFO"),
            FeatureNotice("DIAGNOSTIC", "Instagram bulk-action safety", "Bulk tasks track list progress and require end-of-list evidence. A task is not marked complete without verified completion evidence.", amber, "INFO")
        )

        val list = binding.updatesList
        list.removeAllViews()
        var currentCategory: String? = null
        updates.forEachIndexed { index, item ->
            if (item.category != currentCategory) {
                currentCategory = item.category
                list.addView(createCategoryHeader(item.category, item.accent))
            }
            list.addView(createUpdateCard(item, index + 1))
        }
        list.addView(createManualUpdateCard())
    }

    /**
     * Small chapter label with a hairline rule, shown when the category changes so the
     * list reads as clear sections.
     */
    private fun createCategoryHeader(category: String, accent: Int): View {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(6))
        }
        val label = TextView(context).apply {
            text = category
            textSize = 11f
            letterSpacing = 0.14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent)
        }
        val rule = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 1f
                setColor((accent and 0x00FFFFFF) or (0x40FFFFFF))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f).apply {
                marginStart = dp(10)
            }
        }
        row.addView(label)
        row.addView(rule)
        return row
    }

    private fun createManualUpdateCard(): View {
        val context = requireContext()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(themeColor(com.jarvis.assistant.R.attr.themeCardGlassStart), themeColor(com.jarvis.assistant.R.attr.themeCardGlassEnd))
            ).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), themeColor(com.jarvis.assistant.R.attr.themeAccent))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
            elevation = dp(5).toFloat()
        }
        val eyebrow = TextView(context).apply {
            text = "OFFICIAL JARVIS UPDATES"
            textSize = 10f
            letterSpacing = 0.12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(com.jarvis.assistant.R.attr.themeAccent))
        }
        val heading = TextView(context).apply {
            text = "Check and install updates"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(com.jarvis.assistant.R.attr.themeTextPrimary))
            setPadding(0, dp(8), 0, dp(6))
        }
        val detail = TextView(context).apply {
            text = "Download a newer release from the JARVIS update server. The APK is verified before Android asks you to confirm installation."
            textSize = 13f
            setTextColor(themeColor(com.jarvis.assistant.R.attr.themeTextSecondary))
            setLineSpacing(dp(3).toFloat(), 1.03f)
        }
        val installedRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(0))
        }
        val installedLabel = TextView(context).apply {
            text = "INSTALLED"
            textSize = 9f
            letterSpacing = 0.12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(com.jarvis.assistant.R.attr.themeTextMuted))
        }
        val installedValue = TextView(context).apply {
            text = "v" + BuildConfig.VERSION_NAME + "  ·  code " + BuildConfig.VERSION_CODE
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(com.jarvis.assistant.R.attr.themeTextSecondary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }
        installedRow.addView(installedLabel)
        installedRow.addView(installedValue)
        val status = TextView(context).apply {
            text = "Ready to check for updates."
            textSize = 12f
            setTextColor(themeColor(com.jarvis.assistant.R.attr.themeAccent))
            setPadding(0, dp(10), 0, dp(7))
        }
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(themeColor(com.jarvis.assistant.R.attr.themeAccent))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf((themeColor(com.jarvis.assistant.R.attr.themeAccent) and 0x00FFFFFF) or (0x33000000))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5)
            ).apply { bottomMargin = dp(10) }
        }
        val install = Button(context).apply {
            text = "Install Update"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(themeColor(com.jarvis.assistant.R.attr.themeAccent))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)
            )
            stateListAnimator = null
        }
        card.addView(eyebrow)
        card.addView(heading)
        card.addView(detail)
        card.addView(installedRow)
        card.addView(status)
        card.addView(progress)
        card.addView(install)

        install.setOnClickListener {
            install.isEnabled = false
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = true
            status.text = "Starting secure update check..."
            viewLifecycleOwner.lifecycleScope.launch {
                ManualUpdateInstaller.install(requireContext()) { state ->
                    if (_binding == null) return@install
                    when (state) {
                        is ManualUpdateInstaller.State.Message -> {
                            status.text = state.text
                            if (state.progress != null) {
                                progress.isIndeterminate = false
                                progress.progress = state.progress
                            } else {
                                progress.isIndeterminate = true
                            }
                        }
                        is ManualUpdateInstaller.State.Failure -> {
                            status.text = state.text
                            progress.visibility = View.GONE
                            install.isEnabled = true
                        }
                        ManualUpdateInstaller.State.InstallerLaunched -> {
                            status.text = "Android's installer is open. Review the details and confirm if you want to update JARVIS."
                            progress.visibility = View.GONE
                            install.isEnabled = true
                        }
                    }
                }
            }
        }
        return card
    }

    private fun createUpdateCard(item: FeatureNotice, number: Int): View {
        val context = requireContext()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(themeColor(com.jarvis.assistant.R.attr.themeCardGlassStart), themeColor(com.jarvis.assistant.R.attr.themeCardGlassEnd))
            ).apply {
                cornerRadius = dp(19).toFloat()
                setStroke(dp(1), (item.accent and 0x00FFFFFF) or (0x66000000))
            }
            elevation = dp(5).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        // Accent edge bar on the left for visual punch.
        val edge = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(item.accent)
            }
            layoutParams = LinearLayout.LayoutParams(dp(4), dp(42)).apply {
                marginEnd = dp(12)
                gravity = Gravity.TOP
            }
            setPadding(0, 0, 0, 0)
        }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val topLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val category = TextView(context).apply {
            text = item.category
            textSize = 10f
            letterSpacing = 0.12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(item.accent)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tag = TextView(context).apply {
            text = item.tag
            textSize = 9f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor((item.accent and 0x00FFFFFF) or (0x33000000))
                setStroke(dp(1), item.accent)
            }
        }
        topLine.addView(category)
        topLine.addView(tag)
        body.addView(topLine)

        val heading = TextView(context).apply {
            text = item.title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(com.jarvis.assistant.R.attr.themeTextPrimary))
            setPadding(0, dp(10), 0, dp(6))
        }
        body.addView(heading)

        val description = TextView(context).apply {
            text = item.details
            textSize = 13f
            setTextColor(themeColor(com.jarvis.assistant.R.attr.themeTextSecondary))
            setLineSpacing(dp(3).toFloat(), 1.04f)
        }
        body.addView(description)

        val cardRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        cardRow.addView(edge)
        cardRow.addView(body)
        card.addView(cardRow)
        return card
    }

    private fun themeColor(attribute: Int): Int {
        val value = TypedValue()
        requireContext().theme.resolveAttribute(attribute, value, true)
        return value.data
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyFragmentBackground(binding.root)
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) com.jarvis.assistant.util.ThemeManager.applyFragmentBackground(binding.root)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (_binding != null) {
            binding.root.visibility = if (hidden) View.GONE else View.VISIBLE
            if (!hidden) com.jarvis.assistant.util.ThemeManager.applyFragmentBackground(binding.root)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}