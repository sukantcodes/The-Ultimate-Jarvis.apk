package com.jarvis.assistant.ui.onboarding.steps

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.onboarding.LiveStatus
import com.jarvis.assistant.onboarding.OnboardingStatusReader
import com.jarvis.assistant.onboarding.OnboardingStep
import com.jarvis.assistant.ui.onboarding.Palette
import com.jarvis.assistant.ui.onboarding.StepFragment
import com.jarvis.assistant.ui.onboarding.StepScaffold
import com.jarvis.assistant.ui.onboarding.dp
import com.jarvis.assistant.ui.onboarding.glass
import com.jarvis.assistant.ui.onboarding.lp
import com.jarvis.assistant.ui.onboarding.pill
import com.jarvis.assistant.ui.onboarding.primaryButton
import com.jarvis.assistant.ui.onboarding.restyle
import com.jarvis.assistant.ui.onboarding.secondaryButton
import com.jarvis.assistant.ui.onboarding.text
import com.jarvis.assistant.ui.onboarding.verticalLayout
import com.jarvis.assistant.ui.onboarding.views.DemoScene
import com.jarvis.assistant.ui.onboarding.views.Overlay
import com.jarvis.assistant.ui.onboarding.views.PhoneFlowView
import com.jarvis.assistant.util.PermissionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Stage 5: Accessibility. Three parts: allow Restricted settings (guidance only, Android gives no way to query or grant it),
 * enable the real JARVIS service (the person flips the switch in Android Settings; it is re-read on return and polled
 * while visible), and an illustrated tour of what the service does. The phone animations are illustrations, not remote control.
 */
class AccessibilityStepFragment : StepFragment() {

    override val step = OnboardingStep.ACCESSIBILITY

    private lateinit var enableStatus: TextView
    private lateinit var enableNote: TextView
    private lateinit var primary: android.widget.Button
    private lateinit var capCaption: TextView
    private lateinit var capPhone: PhoneFlowView
    private val chips = mutableListOf<TextView>()

    private data class Capability(val label: String, val scene: DemoScene)

    private val capabilities = listOf(
        Capability(
            "Read the screen",
            DemoScene("WhatsApp", listOf("Search", "Riya · Lunch at 2?", "Mom · Call me"), 1,
                "JARVIS reads the labels and text on the current screen to understand where it is.")
        ),
        Capability(
            "Navigate apps",
            DemoScene("YouTube", listOf("Home", "Shorts", "Subscriptions", "Library"), 2,
                "It can tap tabs and buttons to move through an app, the way you would.")
        ),
        Capability(
            "Find input fields",
            DemoScene("Message", listOf("Type a message", "Attach", "Send"), 0,
                "It recognises text boxes, so it knows where your words should go.")
        ),
        Capability(
            "Type anywhere",
            DemoScene("Search", listOf("hello world|", "Suggestions"), 0,
                "Universal typing: dictated or spoken text is inserted into the focused field of any app.")
        ),
        Capability(
            "Tap and click",
            DemoScene("Video", listOf("Play", "Subscribe", "Share"), 1,
                "It can press on-screen buttons that have no shortcut of their own.")
        ),
        Capability(
            "Only when you ask",
            DemoScene("JARVIS", listOf("You: open YouTube", "JARVIS: opening it now", "Done"), 0,
                "It acts on commands you give, and stops as soon as you switch the service off in Android settings.")
        )
    )

    override fun build(scaffold: StepScaffold) {
        val ctx = requireContext()
        chips.clear()
        scaffold.heading(
            "Accessibility",
            "This is how JARVIS operates other apps for you. It is a powerful Android permission, so you switch it on " +
                "yourself in Android Settings. JARVIS can't and won't turn it on for you."
        )

        addRestrictedCard(scaffold)
        addEnableCard(scaffold)
        addCapabilityCard(scaffold)

        primary = primaryButton(ctx, "Continue") { advance(vm.live.value.accessibilityEnabled) }
        standardFooter(primary, "Skip for now")

        onLive { render(it) }
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // The service can take a moment to connect after the switch is flipped, so poll gently while visible.
                while (true) {
                    delay(1500)
                    if (!vm.live.value.accessibilityConnected) vm.refresh()
                }
            }
        }
    }

    // ---- 1. Restricted settings ---------------------------------------------------------------------------

    private fun addRestrictedCard(scaffold: StepScaffold) {
        val ctx = requireContext()
        val likely = OnboardingStatusReader.likelyRestrictedSettings(ctx)
        val card = card(ctx)
        card.addView(sectionTitle("1  Allow restricted settings", if (likely) "LIKELY NEEDED" else "PROBABLY NOT NEEDED", if (likely) Palette.AMBER else Palette.TEXT_DIM))
        val explain = if (likely) {
            "Android 13 and newer blocks Accessibility for apps installed outside the Play Store until you allow it. " +
                "If the JARVIS switch in the next step is greyed out, do this first."
        } else if (Build.VERSION.SDK_INT < 33) {
            "Your Android version doesn't have this restriction, so you can skip ahead to step 2."
        } else {
            "This copy of JARVIS was installed from the Play Store, which normally isn't restricted. If the switch in step 2 is greyed out, do this."
        }
        card.addView(text(ctx, explain, 13.5f, Palette.TEXT_DIM), lp(topDp = 8, context = ctx))

        val phone = PhoneFlowView(ctx)
        val caption = text(ctx, "", 13.5f, Palette.CYAN)
        phone.setScenes(
            listOf(
                DemoScene("App info", listOf("JARVIS", "Notifications", "Permissions", "Storage"), 0,
                    "Open JARVIS's App info page (the button below takes you there)."),
                DemoScene("App info", listOf("JARVIS", "Notifications", "Permissions", "Storage"), 0,
                    "Tap the three-dot menu at the top right, then \"Allow restricted settings\". If you don't see it, you don't need it.",
                    overlay = Overlay.MENU, menuItems = listOf("Allow restricted settings", "Uninstall updates")),
                DemoScene("App info", listOf("JARVIS", "Notifications", "Permissions", "Storage"), 0,
                    "Confirm with your PIN or fingerprint if Android asks, then return here. The wording differs a little on each phone.",
                    overlay = Overlay.DIALOG, dialogTitle = "Allow restricted settings?",
                    dialogBody = "Only allow this for apps you trust.", dialogButton = "Allow")
            )
        )
        phone.onSceneChanged = { i ->
            caption.text = when (i) {
                0 -> "Open JARVIS's App info page (the button below takes you there)."
                1 -> "Tap the three-dot menu at the top right, then \"Allow restricted settings\". If you don't see it, you don't need it."
                else -> "Confirm with your PIN or fingerprint if Android asks, then return here. The wording differs a little on each phone."
            }
        }
        card.addView(phone, lp(heightPx = ctx.dp(300), topDp = 12, context = ctx))
        card.addView(caption, lp(topDp = 8, context = ctx))
        card.addView(secondaryButton(ctx, "Open JARVIS App info") { openAppSettings() }, lp(topDp = 12, context = ctx))
        card.addView(
            text(ctx, "Android doesn't let apps check this setting. Step 2 shows whether it worked.", 12.5f, Palette.TEXT_DIM),
            lp(topDp = 8, context = ctx)
        )
        scaffold.addBody(card, 16)
    }

    // ---- 2. Enable the service ----------------------------------------------------------------------------

    private fun addEnableCard(scaffold: StepScaffold) {
        val ctx = requireContext()
        val card = card(ctx)
        enableStatus = pill(ctx, "CHECKING", Palette.TEXT_DIM)
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(text(ctx, "2  Enable the JARVIS service", 16f, Palette.TEXT, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(enableStatus)
        card.addView(head, lp())

        val label = serviceLabel()
        val desc = ctx.getString(com.jarvis.assistant.R.string.accessibility_service_desc)
        val serviceBox = verticalLayout(ctx).apply {
            background = glass(ctx, 14, Palette.VIOLET)
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        }
        serviceBox.addView(text(ctx, "In Android's list, look for:", 12f, Palette.TEXT_DIM), lp())
        serviceBox.addView(text(ctx, label, 17f, Palette.TEXT, bold = true), lp(topDp = 2, context = ctx))
        serviceBox.addView(text(ctx, desc, 13f, Palette.TEXT_DIM), lp(topDp = 6, context = ctx))
        card.addView(serviceBox, lp(topDp = 12, context = ctx))

        val caption = text(ctx, "", 13.5f, Palette.CYAN)
        val phone = PhoneFlowView(ctx)
        val captions = listOf(
            "Open Accessibility settings and find JARVIS under Downloaded or Installed apps.",
            "Turn the switch on. Android will ask you to confirm: you can read exactly what it can see and do.",
            "Tap Allow. You come back here and JARVIS re-checks automatically."
        )
        phone.setScenes(
            listOf(
                DemoScene("Accessibility", listOf("TalkBack", "Downloaded apps", "Installed apps"), 1, captions[0]),
                DemoScene(label, listOf("Use $label"), 0, captions[1], overlay = Overlay.TOGGLE),
                DemoScene(label, listOf("Use $label"), 0, captions[2], overlay = Overlay.DIALOG,
                    dialogTitle = "Allow full control?", dialogBody = "Android lists what this service can see and do.", dialogButton = "Allow")
            )
        )
        phone.onSceneChanged = { i -> caption.text = captions[i.coerceIn(0, captions.lastIndex)] }
        card.addView(phone, lp(heightPx = ctx.dp(300), topDp = 12, context = ctx))
        card.addView(caption, lp(topDp = 8, context = ctx))

        card.addView(
            secondaryButton(ctx, "Open Accessibility settings") { PermissionHelper.openAccessibilitySettings(requireContext()) },
            lp(topDp = 12, context = ctx)
        )
        enableNote = text(ctx, "", 13.5f, Palette.TEXT_DIM)
        card.addView(enableNote, lp(topDp = 10, context = ctx))
        scaffold.addBody(card, 16)
    }

    // ---- 3. What it can do --------------------------------------------------------------------------------

    private fun addCapabilityCard(scaffold: StepScaffold) {
        val ctx = requireContext()
        val card = card(ctx)
        card.addView(text(ctx, "3  What it lets JARVIS do", 16f, Palette.TEXT, bold = true), lp())
        card.addView(
            text(ctx, "Tap a capability to see it. These are illustrations; real actions only happen when you give JARVIS a command.", 13.5f, Palette.TEXT_DIM),
            lp(topDp = 6, context = ctx)
        )

        val scroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        capabilities.forEachIndexed { i, cap ->
            val chip = pill(ctx, cap.label, Palette.TEXT_DIM).apply {
                setOnClickListener { selectCapability(i) }
                setPadding(ctx.dp(14), ctx.dp(9), ctx.dp(14), ctx.dp(9))
            }
            chips += chip
            row.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dp(8) })
        }
        scroll.addView(row)
        card.addView(scroll, lp(topDp = 12, context = ctx))

        capPhone = PhoneFlowView(ctx)
        capPhone.setScenes(capabilities.map { it.scene })
        capCaption = text(ctx, "", 13.5f, Palette.CYAN)
        capPhone.onSceneChanged = { i -> highlight(i) }
        card.addView(capPhone, lp(heightPx = ctx.dp(300), topDp = 12, context = ctx))
        card.addView(capCaption, lp(topDp = 8, context = ctx))
        scaffold.addBody(card, 16)
        highlight(0)
    }

    private fun selectCapability(index: Int) {
        capPhone.pinScene(index)
        highlight(index)
    }

    private fun highlight(index: Int) {
        val i = index.coerceIn(0, capabilities.lastIndex)
        capCaption.text = capabilities[i].scene.caption
        chips.forEachIndexed { k, chip -> restyle(chip, capabilities[k].label, if (k == i) Palette.CYAN else Palette.TEXT_DIM) }
    }

    // ---- live state ---------------------------------------------------------------------------------------

    private fun render(live: LiveStatus) {
        when {
            live.accessibilityConnected -> {
                restyle(enableStatus, "ACTIVE", Palette.MINT)
                enableNote.text = "JARVIS's Accessibility service is running. It was verified just now."
                enableNote.setTextColor(Palette.MINT)
            }
            live.accessibilityEnabled -> {
                restyle(enableStatus, "ENABLED", Palette.CYAN)
                enableNote.text = "Android lists the service as enabled; waiting for it to connect…"
                enableNote.setTextColor(Palette.CYAN)
            }
            else -> {
                restyle(enableStatus, "OFF", Palette.AMBER)
                enableNote.text = "Not enabled yet. Open Accessibility settings, switch JARVIS on, then come back. If the switch is greyed out, do step 1."
                enableNote.setTextColor(Palette.TEXT_DIM)
            }
        }
        primary.text = if (live.accessibilityEnabled) "Continue" else "Continue without Accessibility"
    }

    private fun serviceLabel(): String = try {
        val pm = requireContext().packageManager
        val info = pm.getServiceInfo(ComponentName(requireContext(), JarvisAccessibilityService::class.java), PackageManager.GET_META_DATA)
        info.loadLabel(pm).toString().ifBlank { "JARVIS" }
    } catch (_: Exception) {
        "JARVIS"
    }

    private fun card(ctx: android.content.Context): LinearLayout = verticalLayout(ctx).apply {
        background = glass(ctx, 22)
        setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
    }

    private fun sectionTitle(title: String, badge: String, color: Int): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(text(ctx, title, 16f, Palette.TEXT, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(pill(ctx, badge, color))
        }
    }
}
