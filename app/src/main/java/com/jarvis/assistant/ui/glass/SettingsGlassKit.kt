package com.jarvis.assistant.ui.glass

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.util.JarvisHandlesInsets

/**
 * Gives the older Settings sub-screens (API connection, Persona, Privacy, Keys, Tools ...) exactly the same look as the
 * category screens: the shared scene behind them ([GlassBackdropView]) and every card turned into the same lens-glass pane.
 *
 * It works on the screen after it is built, so none of those screens' own code changes. A card is any view tagged
 * [CARD_TAG]. A screen that is not in [TARGETS] is untouched.
 */
object SettingsGlassKit {

    const val CARD_TAG = "jarvis_glass_card"

    private val TARGETS = setOf(
        "ApiConnectionActivity", "CustomProviderEditorActivity", "FaceLockActivity", "HandsFreeActivity",
        "KeyManagerActivity", "MayaModeActivity", "MoreThemeColoursActivity", "PersonaActivity",
        "PrivacyShieldActivity", "ScreenUnlockActivity", "ToolSettingsActivity", "WorkRoutingActivity",
        "CrystalBackgroundActivity", "VoiceEnrollmentActivity", "UdnActivity"
    )

    fun isTarget(activity: Activity): Boolean =
        activity.javaClass.simpleName in TARGETS && activity.javaClass.name.startsWith("com.jarvis.assistant")

    fun upgrade(activity: Activity) {
        if (!isTarget(activity)) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.getTag(R.id.settings_glass_kit_tag) != null) return
        val original = content.getChildAt(0) ?: return
        content.setTag(R.id.settings_glass_kit_tag, true)

        content.removeView(original)
        val wrapper = FrameLayout(activity)
        val backdrop = GlassBackdropView(activity).apply {
            id = R.id.glassBackdrop
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        wrapper.addView(backdrop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        original.background = null
        wrapper.addView(original, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(wrapper, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        if (!GlassMode.plain()) {
            glassify(activity, original)
            // Some screens rebuild their cards later (key lists, tool lists); wrap those the moment they appear.
            var pending = false
            wrapper.viewTreeObserver.addOnGlobalLayoutListener {
                if (!pending) {
                    pending = true
                    wrapper.post { pending = false; glassify(activity, original) }
                }
            }
        }

        // The backdrop reaches the screen edges; only the content is kept clear of the camera, bars and keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(wrapper) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            original.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(wrapper)
    }

    /** Wraps every tagged card in a lens-glass pane, keeping its place, margins and weight. */
    private fun glassify(activity: Activity, root: View) {
        val cards = ArrayList<View>()
        collect(root, cards)
        if (cards.isEmpty()) return
        val inflater = LayoutInflater.from(activity)
        for (card in cards) {
            val parent = card.parent as? ViewGroup ?: continue
            // Constraint/relative parents point at the card by id, so those cards keep their normal background instead.
            if (parent is ConstraintLayout || parent is android.widget.RelativeLayout) continue
            val index = parent.indexOfChild(card)
            val lp = card.layoutParams
            val tall = lp.height == ViewGroup.LayoutParams.MATCH_PARENT
            parent.removeViewAt(index)
            val glass = inflater.inflate(R.layout.view_glass_card, parent, false) as ViewGroup
            card.background = null
            glass.addView(
                card,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (tall) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            parent.addView(glass, index, lp)
        }
    }

    private fun collect(view: View, out: MutableList<View>) {
        if (view.tag == CARD_TAG) { if (view.parent !is LiquidGlassLayout) out.add(view); return }
        if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i), out)
    }
}
