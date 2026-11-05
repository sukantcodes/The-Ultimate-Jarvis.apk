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
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import com.jarvis.assistant.databinding.FragmentUpdatesBinding

class UpdatesFragment : Fragment() {

    private var _binding: FragmentUpdatesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentUpdatesBinding.inflate(inflater, container, false)
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

    private fun renderFeatureUpdates() {
        val cyan = Color.rgb(0, 224, 255)
        val violet = Color.rgb(174, 91, 255)
        val pink = Color.rgb(255, 65, 180)
        val blue = Color.rgb(65, 137, 255)
        val mint = Color.rgb(55, 255, 190)
        val amber = Color.rgb(255, 190, 65)

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
        updates.forEachIndexed { index, item ->
            list.addView(createUpdateCard(item, index + 1))
        }
    }

    private fun createUpdateCard(item: FeatureNotice, number: Int): View {
        val context = requireContext()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(25, 29, 48), Color.rgb(13, 17, 31))
            ).apply {
                cornerRadius = dp(19).toFloat()
                setStroke(dp(1), (item.accent and 0x00FFFFFF) or (0x66000000))
            }
            elevation = dp(5).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(13)
            }
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
        card.addView(topLine)

        val heading = TextView(context).apply {
            text = item.title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(10), 0, dp(6))
        }
        card.addView(heading)

        val description = TextView(context).apply {
            text = item.details
            textSize = 13f
            setTextColor(Color.rgb(190, 199, 218))
            setLineSpacing(dp(3).toFloat(), 1.04f)
        }
        card.addView(description)
        return card
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (_binding != null) {
            binding.root.visibility = if (hidden) View.GONE else View.VISIBLE
            if (!hidden) com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
