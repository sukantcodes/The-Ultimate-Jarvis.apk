package com.jarvis.assistant.ui.onboarding.steps

import android.content.Intent
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.jarvis.assistant.audio.JarvisWakeWordGrammar
import com.jarvis.assistant.onboarding.LiveStatus
import com.jarvis.assistant.onboarding.OnboardingStep
import com.jarvis.assistant.onboarding.PermissionKey
import com.jarvis.assistant.ui.offline.OfflineModelActivity
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
import com.jarvis.assistant.ui.onboarding.views.OrbView
import com.jarvis.assistant.ui.onboarding.views.WaveformView
import com.jarvis.assistant.ui.settings.PersonaActivity
import com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity

/**
 * Stage 7: how talking to JARVIS works. The orb and waveform are an illustration of the three states (idle, listening,
 * speaking). Every status pill is read live from the real components, and the claims about offline behaviour are
 * deliberately narrow: only speech recognition and wake detection run on-device; AI answers need Gemini unless the
 * separate offline model has been downloaded.
 */
class VoiceStepFragment : StepFragment() {

    override val step = OnboardingStep.VOICE

    private lateinit var orb: OrbView
    private lateinit var wave: WaveformView
    private lateinit var modeLabel: TextView
    private val modeChips = mutableListOf<TextView>()
    private lateinit var micPill: TextView
    private lateinit var speechPill: TextView
    private lateinit var offlineAiPill: TextView
    private lateinit var enrollPill: TextView
    private var mode = 1

    private val modes = listOf(
        Triple("Idle", OrbView.Mode.IDLE, "JARVIS is waiting. Say a wake phrase or tap the orb on the home screen."),
        Triple("Listening", OrbView.Mode.LISTENING, "It hears you. Your speech is turned into text, using the microphone you allowed."),
        Triple("Speaking", OrbView.Mode.SPEAKING, "It answers out loud. The answer comes from the AI you configured.")
    )

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        mode = savedInstanceState?.getInt("mode", 1) ?: 1
    }

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("mode", mode)
    }

    override fun build(scaffold: StepScaffold) {
        val ctx = requireContext()
        modeChips.clear()
        scaffold.heading("Voice", "Talking is the main way to use JARVIS. Here is how it works and what is ready on this phone.")

        // --- Interaction demo
        val demo = card(ctx)
        orb = OrbView(ctx)
        demo.addView(orb, lp(heightPx = ctx.dp(190), context = ctx))
        wave = WaveformView(ctx)
        demo.addView(wave, lp(heightPx = ctx.dp(64), topDp = 4, context = ctx))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        modes.forEachIndexed { i, m ->
            val chip = pill(ctx, m.first, Palette.TEXT_DIM).apply {
                setPadding(ctx.dp(14), ctx.dp(9), ctx.dp(14), ctx.dp(9))
                setOnClickListener { selectMode(i) }
            }
            modeChips += chip
            row.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(4); marginEnd = ctx.dp(4) })
        }
        demo.addView(row, lp(topDp = 10, context = ctx))
        modeLabel = text(ctx, "", 13.5f, Palette.CYAN, gravity = Gravity.CENTER)
        demo.addView(modeLabel, lp(topDp = 10, context = ctx))
        scaffold.addBody(demo, 12)

        // --- Wake word
        val wake = card(ctx)
        wake.addView(text(ctx, "Wake word", 16f, Palette.TEXT, bold = true), lp())
        wake.addView(
            text(
                ctx,
                "JARVIS can wait for a spoken phrase such as " +
                    JarvisWakeWordGrammar.phrases.take(4).joinToString(", ") { "“$it”" } +
                    ". It matches a fixed list of phrases with an on-device speech model; it is not a separately trained wake-word network, " +
                    "so noisy rooms and accents can affect it.",
                13.5f, Palette.TEXT_DIM
            ),
            lp(topDp = 6, context = ctx)
        )
        micPill = pill(ctx, "…", Palette.TEXT_DIM)
        wake.addView(micPill, lp(topDp = 10, context = ctx))
        scaffold.addBody(wake, 14)

        // --- Offline recognition
        val offline = card(ctx)
        offline.addView(text(ctx, "Offline speech recognition", 16f, Palette.TEXT, bold = true), lp())
        offline.addView(
            text(
                ctx,
                "Speech recognition and wake detection can run on this phone with the bundled speech model. That does not make JARVIS's " +
                    "answers offline: replies still use the internet and your Gemini key, unless you also download the optional offline AI model " +
                    "(a small model, about 484 MB, that is far less capable).",
                13.5f, Palette.TEXT_DIM
            ),
            lp(topDp = 6, context = ctx)
        )
        speechPill = pill(ctx, "…", Palette.TEXT_DIM)
        offlineAiPill = pill(ctx, "…", Palette.TEXT_DIM)
        offline.addView(speechPill, lp(topDp = 10, context = ctx))
        offline.addView(offlineAiPill, lp(topDp = 6, context = ctx))
        offline.addView(secondaryButton(ctx, "Offline JARVIS settings") { open(OfflineModelActivity::class.java) }, lp(topDp = 12, context = ctx))
        scaffold.addBody(offline, 14)

        // --- Voice security
        val security = card(ctx)
        security.addView(text(ctx, "Voice security", 16f, Palette.TEXT, bold = true), lp())
        security.addView(
            text(
                ctx,
                "You can enroll your voice so JARVIS pays attention to you rather than to anyone nearby. It is a convenience layer, " +
                    "not a replacement for your phone's lock screen.",
                13.5f, Palette.TEXT_DIM
            ),
            lp(topDp = 6, context = ctx)
        )
        enrollPill = pill(ctx, "…", Palette.TEXT_DIM)
        security.addView(enrollPill, lp(topDp = 10, context = ctx))
        security.addView(secondaryButton(ctx, "Set up my voice") { open(VoiceEnrollmentActivity::class.java) }, lp(topDp = 12, context = ctx))
        scaffold.addBody(security, 14)

        // --- Voice settings
        val settings = card(ctx)
        settings.addView(text(ctx, "Voice settings", 16f, Palette.TEXT, bold = true), lp())
        settings.addView(text(ctx, "Pick the persona and the voice JARVIS speaks with. You can change it any time from Settings.", 13.5f, Palette.TEXT_DIM), lp(topDp = 6, context = ctx))
        settings.addView(secondaryButton(ctx, "Persona and voice") { open(PersonaActivity::class.java) }, lp(topDp = 12, context = ctx))
        scaffold.addBody(settings, 14)

        standardFooter(primaryButton(ctx, "Continue") { advance(vm.live.value.let { it.isGranted(PermissionKey.MICROPHONE) && it.speechModelReady }) }, "Skip for now")

        selectMode(mode)
        onLive { render(it) }
    }

    private fun selectMode(index: Int) {
        mode = index.coerceIn(0, modes.lastIndex)
        val m = modes[mode]
        orb.mode = m.second
        wave.active = mode != 0
        wave.style = if (mode == 2) 1 else 0
        modeLabel.text = m.third
        modeChips.forEachIndexed { i, chip -> restyle(chip, modes[i].first, if (i == mode) Palette.CYAN else Palette.TEXT_DIM) }
    }

    private fun render(live: LiveStatus) {
        if (live.isGranted(PermissionKey.MICROPHONE)) restyle(micPill, "MICROPHONE ALLOWED", Palette.MINT)
        else restyle(micPill, "MICROPHONE NOT ALLOWED", Palette.AMBER)
        if (live.speechModelReady) restyle(speechPill, "ON-DEVICE SPEECH MODEL READY", Palette.MINT)
        else restyle(speechPill, "SPEECH MODEL NOT READY YET", Palette.AMBER)
        if (live.offlineModelInstalled) restyle(offlineAiPill, "OFFLINE AI MODEL INSTALLED", Palette.MINT)
        else restyle(offlineAiPill, "OFFLINE AI MODEL NOT DOWNLOADED (OPTIONAL)", Palette.TEXT_DIM)
        if (live.ownerVoiceEnrolled) restyle(enrollPill, "YOUR VOICE IS ENROLLED", Palette.MINT)
        else restyle(enrollPill, "VOICE NOT ENROLLED (OPTIONAL)", Palette.TEXT_DIM)
    }

    private fun open(cls: Class<*>) {
        try {
            startActivity(Intent(requireContext(), cls))
        } catch (_: Exception) {
            android.widget.Toast.makeText(requireContext(), "Couldn't open that screen.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun card(ctx: android.content.Context): LinearLayout = verticalLayout(ctx).apply {
        background = glass(ctx, 22)
        setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
    }
}
