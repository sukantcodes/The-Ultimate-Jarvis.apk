package com.jarvis.assistant.ui.premium

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * JARVIS premium popup.
 *
 * A floating 3D glass card with a rim light, a tilted entrance, an orbiting 3D orb badge,
 * gradient-filled display type, raised "keycap" buttons that press down, and tactile option rows.
 * Colours come from the active JARVIS theme accents so the popup always matches the app.
 *
 * Use the builder ([create]) for custom content, or the one-line helpers [confirm], [info],
 * [choice] and [input].
 */
internal class PremiumDialog private constructor(private val ctx: Context) {

    val palette = PremiumPalette.from(ctx)

    private var eyebrowText: String? = null
    private var titleText: CharSequence = ""
    private var messageText: CharSequence? = null
    private var danger = false
    private val bodyViews = ArrayList<View>()
    private var positiveLabel: String? = null
    private var positiveAction: (() -> Boolean)? = null
    private var negativeLabel: String? = null
    private var negativeAction: (() -> Unit)? = null
    private var focusView: View? = null

    private var dialog: Dialog? = null
    private var card: View? = null
    private var orb: View? = null
    private var closing = false

    fun eyebrow(text: String) = apply { eyebrowText = text }
    fun title(text: CharSequence) = apply { titleText = text }
    fun message(text: CharSequence?) = apply { messageText = text }
    fun destructive(value: Boolean = true) = apply { danger = value }
    fun add(view: View) = apply { bodyViews.add(view) }
    fun focus(view: View) = apply { focusView = view }

    /** [action] returns true to close the popup, false to keep it open (for example failed validation). */
    fun positive(label: String, action: () -> Boolean = { true }) = apply {
        positiveLabel = label
        positiveAction = action
    }

    fun negative(label: String = "Cancel", action: (() -> Unit)? = null) = apply {
        negativeLabel = label
        negativeAction = action
    }

    fun dismiss() {
        val d = dialog ?: return
        val c = card
        if (closing || !d.isShowing) return
        closing = true
        if (c == null) {
            d.dismiss()
            return
        }
        orb?.animate()?.alpha(0f)?.scaleX(0.6f)?.scaleY(0.6f)?.setDuration(140L)?.start()
        c.animate()
            .alpha(0f).scaleX(0.94f).scaleY(0.94f).rotationX(-8f)
            .translationY(ctx.dp(10f))
            .setDuration(170L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { if (d.isShowing) d.dismiss() }
            .start()
    }

    fun show(): PremiumDialog {
        val a1 = if (danger) PremiumPalette.DANGER_1 else palette.accent1
        val a2 = if (danger) PremiumPalette.DANGER_2 else palette.accent2

        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setCanceledOnTouchOutside(true)
        dialog = d

        val badgeSize = ctx.dp(76f).toInt()
        val overhang = badgeSize / 2

        val root = FrameLayout(ctx).apply {
            clipChildren = false
            clipToPadding = false
        }

        val cardView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = glassCardDrawable(ctx, a1, a2)
            setPadding(ctx.dp(22f).toInt(), overhang + ctx.dp(8f).toInt(), ctx.dp(22f).toInt(), ctx.dp(20f).toInt())
            elevation = ctx.dp(22f)
            clipChildren = false
            cameraDistance = ctx.dp(9000f)
            if (Build.VERSION.SDK_INT >= 28) {
                outlineAmbientShadowColor = Color.argb(230, Color.red(a1), Color.green(a1), Color.blue(a1))
                outlineSpotShadowColor = Color.argb(230, Color.red(a2), Color.green(a2), Color.blue(a2))
            }
        }
        card = cardView

        val screenW = ctx.resources.displayMetrics.widthPixels
        val cardW = min(screenW - ctx.dp(36f).toInt(), ctx.dp(420f).toInt())
        root.addView(
            cardView,
            FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = overhang; bottomMargin = ctx.dp(10f).toInt() }
        )

        val orbView = PremiumOrbView(ctx, a1, a2, palette.accent3)
        orb = orbView
        root.addView(
            orbView,
            FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        )

        eyebrowText?.let {
            cardView.addView(
                label(ctx, it.uppercase(), 11f, brighten(a1, 0.25f), "sans-serif-medium", 0.26f),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }

        val titleView = label(ctx, titleText.toString(), 25f, Color.WHITE, "sans-serif-black", 0.015f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, ctx.dp(5f).toInt(), 0, 0)
        }
        gradientText(titleView, intArrayOf(Color.WHITE, brighten(a1, 0.55f), brighten(a2, 0.45f)))
        cardView.addView(titleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        messageText?.let {
            val m = TextView(ctx).apply {
                text = it
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(176, 189, 216))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setLineSpacing(ctx.dp(3f), 1f)
                setPadding(0, ctx.dp(10f).toInt(), 0, 0)
            }
            cardView.addView(m, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (v in bodyViews) {
            body.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (bodyViews.isNotEmpty()) {
            cardView.addView(
                scrollOf(ctx, body, 0.5f),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = ctx.dp(18f).toInt() }
            )
        }

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        negativeLabel?.let { text ->
            val b = keycapButton(ctx, text, primary = false, a1 = a1, a2 = a2)
            b.setOnClickListener {
                negativeAction?.invoke()
                dismiss()
            }
            buttons.addView(b, LinearLayout.LayoutParams(0, ctx.dp(54f).toInt(), 1f).apply {
                if (positiveLabel != null) marginEnd = ctx.dp(6f).toInt()
            })
        }
        positiveLabel?.let { text ->
            val b = keycapButton(ctx, text, primary = true, a1 = a1, a2 = a2)
            b.setOnClickListener {
                val close = positiveAction?.invoke() ?: true
                if (close) dismiss() else shake(cardView)
            }
            buttons.addView(b, LinearLayout.LayoutParams(0, ctx.dp(54f).toInt(), 1.25f).apply {
                if (negativeLabel != null) marginStart = ctx.dp(6f).toInt()
            })
        }
        if (buttons.childCount > 0) {
            cardView.addView(
                buttons,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = ctx.dp(22f).toInt() }
            )
        }

        d.setContentView(root)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.CENTER)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.66f)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    val attrs = w.attributes
                    attrs.blurBehindRadius = 26
                    w.attributes = attrs
                } catch (_: Throwable) {
                }
            }
        }

        // Entrance: a tilted card swings up into place, the orb pops, content cascades in.
        cardView.alpha = 0f
        cardView.scaleX = 0.86f
        cardView.scaleY = 0.86f
        cardView.rotationX = 16f
        cardView.translationY = ctx.dp(36f)
        orbView.alpha = 0f
        orbView.scaleX = 0f
        orbView.scaleY = 0f
        for (i in 0 until body.childCount) {
            body.getChildAt(i).apply {
                alpha = 0f
                translationY = ctx.dp(14f)
            }
        }
        buttons.alpha = 0f
        buttons.translationY = ctx.dp(14f)

        d.show()
        cardView.post {
            cardView.animate()
                .alpha(1f).scaleX(1f).scaleY(1f).rotationX(0f).translationY(0f)
                .setDuration(460L).setInterpolator(OvershootInterpolator(0.9f)).start()
            orbView.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(150L).setDuration(520L).setInterpolator(OvershootInterpolator(1.7f)).start()
            for (i in 0 until body.childCount) {
                body.getChildAt(i).animate()
                    .alpha(1f).translationY(0f)
                    .setStartDelay(170L + 45L * i).setDuration(340L)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
            buttons.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(220L + 45L * body.childCount).setDuration(340L)
                .setInterpolator(DecelerateInterpolator()).start()
        }

        focusView?.let { f ->
            f.requestFocus()
            d.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            )
        }
        return this
    }

    private fun shake(view: View) {
        view.animate().cancel()
        val amp = ctx.dp(9f)
        view.animate().translationX(amp).setDuration(55L).withEndAction {
            view.animate().translationX(-amp).setDuration(80L).withEndAction {
                view.animate().translationX(0f).setDuration(70L)
                    .setInterpolator(OvershootInterpolator(2f)).start()
            }.start()
        }.start()
    }

    companion object {
        fun create(ctx: Context) = PremiumDialog(ctx)

        /** Yes / no popup. Pass [destructive] for irreversible actions (red palette). */
        fun confirm(
            ctx: Context,
            eyebrow: String,
            title: String,
            message: String,
            confirmLabel: String,
            destructive: Boolean = false,
            onConfirm: () -> Unit
        ) {
            create(ctx).eyebrow(eyebrow).title(title).message(message).destructive(destructive)
                .negative("Cancel")
                .positive(confirmLabel) { onConfirm(); true }
                .show()
        }

        /** Read-only popup with one close button. */
        fun info(ctx: Context, eyebrow: String, title: String, message: CharSequence, closeLabel: String = "Close") {
            val d = create(ctx).eyebrow(eyebrow).title(title)
            val text = TextView(ctx).apply {
                this.text = message
                setTextColor(Color.rgb(206, 216, 238))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setLineSpacing(ctx.dp(3f), 1f)
                setPadding(ctx.dp(14f).toInt(), ctx.dp(12f).toInt(), ctx.dp(14f).toInt(), ctx.dp(12f).toInt())
                background = fieldBackground(ctx, d.palette.accent1, focused = false)
            }
            d.add(text).positive(closeLabel).show()
        }

        /**
         * Single choice list. With [instant] a tap picks and closes at once; otherwise the user
         * confirms with [confirmLabel].
         */
        fun choice(
            ctx: Context,
            eyebrow: String,
            title: String,
            message: String? = null,
            items: List<CharSequence>,
            selected: Int,
            instant: Boolean,
            confirmLabel: String = "Save",
            onPick: (Int) -> Unit
        ) {
            val d = create(ctx).eyebrow(eyebrow).title(title).message(message)
            val list = PremiumOptionList(ctx, d.palette, items, selected) { index ->
                if (instant) {
                    onPick(index)
                    d.dismiss()
                }
            }
            d.add(list.view).negative("Cancel")
            if (!instant) d.positive(confirmLabel) { onPick(list.selectedIndex); true }
            d.show()
        }

        /**
         * Text entry with a live gradient preview of what is being typed, shown in a display
         * font like the name card.
         */
        fun input(
            ctx: Context,
            eyebrow: String,
            title: String,
            message: String?,
            initial: String,
            hint: String,
            onSave: (String) -> Unit
        ) {
            val d = create(ctx).eyebrow(eyebrow).title(title).message(message)
            val a1 = d.palette.accent1
            val a2 = d.palette.accent2

            val preview = TextView(ctx).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                typeface = Typeface.create("sans-serif-black", Typeface.ITALIC)
                letterSpacing = 0.02f
                setPadding(0, ctx.dp(4f).toInt(), 0, ctx.dp(12f).toInt())
            }
            val field = styledField(ctx, a1, hint, initial, password = false, capitalize = true)

            fun refresh() {
                val typed = field.text.toString().trim()
                val shown = if (typed.isEmpty()) hint.uppercase() else typed
                preview.text = shown
                preview.setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    when {
                        shown.length <= 10 -> 36f
                        shown.length <= 16 -> 30f
                        else -> 24f
                    }
                )
                preview.alpha = if (typed.isEmpty()) 0.4f else 1f
                val w = preview.paint.measureText(shown)
                val viewW = if (preview.width > 0) preview.width.toFloat()
                else (minOf(ctx.resources.displayMetrics.widthPixels - ctx.dp(36f), ctx.dp(420f)) - ctx.dp(44f))
                val startX = ((viewW - w) / 2f).coerceAtLeast(0f)
                preview.paint.shader = LinearGradient(
                    startX, 0f, startX + w, 0f,
                    intArrayOf(Color.WHITE, brighten(a1, 0.5f), brighten(a2, 0.4f)),
                    null, Shader.TileMode.CLAMP
                )
                preview.invalidate()
            }
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = refresh()
            })
            var lastWidth = -1
            preview.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (v.width != lastWidth) {
                    lastWidth = v.width
                    refresh()
                }
            }
            refresh()

            d.add(preview).add(field).focus(field).negative("Cancel")
                .positive("Save") {
                    val value = field.text.toString().trim()
                    if (value.isBlank()) false else { onSave(value); true }
                }
                .show()
            field.setSelection(field.text.length)
        }

        // ---- reusable pieces for custom popups --------------------------------------------------

        fun fieldLabel(ctx: Context, text: String, accent: Int): View =
            label(ctx, text.uppercase(), 10.5f, brighten(accent, 0.2f), "sans-serif-medium", 0.2f).apply {
                gravity = Gravity.START
                setPadding(ctx.dp(4f).toInt(), ctx.dp(14f).toInt(), 0, ctx.dp(6f).toInt())
            }

        fun styledField(ctx: Context, accent: Int, hint: String, initial: String, password: Boolean, capitalize: Boolean = false): EditText =
            EditText(ctx).apply {
                setText(initial)
                this.hint = hint
                setHintTextColor(Color.argb(120, 200, 212, 240))
                setTextColor(Color.WHITE)
                highlightColor = Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = 0.03f
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setSingleLine()
                inputType = if (password) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else if (capitalize) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                } else {
                    InputType.TYPE_CLASS_TEXT
                }
                includeFontPadding = false
                setPadding(ctx.dp(18f).toInt(), ctx.dp(14f).toInt(), ctx.dp(18f).toInt(), ctx.dp(14f).toInt())
                minHeight = ctx.dp(56f).toInt()
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), fieldBackground(ctx, accent, focused = true))
                    addState(intArrayOf(), fieldBackground(ctx, accent, focused = false))
                }
            }

        fun checkRow(ctx: Context, palette: PremiumPalette, text: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
            var state = checked
            val box = View(ctx)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(16f).toInt(), ctx.dp(12f).toInt(), ctx.dp(16f).toInt(), ctx.dp(15f).toInt())
                isClickable = true
                isFocusable = true
            }
            val label = TextView(ctx).apply {
                this.text = text
                setTextColor(Color.rgb(222, 230, 248))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(ctx.dp(14f).toInt(), 0, 0, 0)
            }
            fun paint() {
                box.background = checkBoxDrawable(ctx, palette, state)
                row.background = rowDrawable(ctx, palette, selected = false)
            }
            row.addView(box, LinearLayout.LayoutParams(ctx.dp(24f).toInt(), ctx.dp(24f).toInt()))
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener {
                state = !state
                paint()
                onChange(state)
            }
            pressable(row)
            paint()
            return row
        }

        fun spacer(ctx: Context, heightDp: Float): View =
            View(ctx).apply { minimumHeight = ctx.dp(heightDp).toInt() }

        fun scrollOf(ctx: Context, content: View, maxFraction: Float): View {
            val maxPx = (ctx.resources.displayMetrics.heightPixels * maxFraction).toInt()
            return MaxHeightScrollView(ctx, maxPx).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
    }
}

// =================================================================================================
// Option list
// =================================================================================================

/** Tactile single-choice list: raised rows, the chosen one glows in the accent gradient. */
internal class PremiumOptionList(
    private val ctx: Context,
    private val palette: PremiumPalette,
    private val labels: List<CharSequence>,
    selected: Int,
    private val onChange: (Int) -> Unit
) {
    var selectedIndex: Int = selected.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
        private set

    private val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val rows = ArrayList<LinearLayout>()
    private val dots = ArrayList<View>()
    private val texts = ArrayList<TextView>()

    val view: View

    init {
        for ((i, text) in labels.withIndex()) {
            val dot = View(ctx)
            val tv = TextView(ctx).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                setPadding(ctx.dp(14f).toInt(), 0, 0, 0)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(16f).toInt(), 0, ctx.dp(16f).toInt(), ctx.dp(3f).toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener { select(i, fromUser = true) }
            }
            row.addView(dot, LinearLayout.LayoutParams(ctx.dp(22f).toInt(), ctx.dp(22f).toInt()))
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            pressable(row)
            column.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(54f).toInt())
                    .apply { if (i > 0) topMargin = ctx.dp(8f).toInt() }
            )
            rows.add(row)
            dots.add(dot)
            texts.add(tv)
        }
        paintAll()
        view = column
    }

    fun select(index: Int, fromUser: Boolean = false) {
        if (index !in labels.indices) return
        selectedIndex = index
        paintAll()
        if (fromUser) onChange(index)
    }

    private fun paintAll() {
        for (i in rows.indices) {
            val on = i == selectedIndex
            rows[i].background = rowDrawable(ctx, palette, on)
            dots[i].background = radioDrawable(ctx, on)
            texts[i].setTextColor(if (on) Color.WHITE else Color.rgb(214, 223, 245))
            texts[i].typeface = Typeface.create("sans-serif-medium", if (on) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}

// =================================================================================================
// Palette
// =================================================================================================

internal class PremiumPalette private constructor(val accent1: Int, val accent2: Int, val accent3: Int) {
    companion object {
        val DANGER_1: Int = Color.rgb(255, 99, 120)
        val DANGER_2: Int = Color.rgb(222, 48, 104)

        fun from(ctx: Context): PremiumPalette = PremiumPalette(
            readable(themeColor(ctx, R.attr.themeAccent1, Color.rgb(64, 190, 255))),
            readable(themeColor(ctx, R.attr.themeAccent2, Color.rgb(150, 112, 255))),
            readable(themeColor(ctx, R.attr.themeAccent3, Color.rgb(36, 214, 196)))
        )

        /** Accents that are too dark vanish on the dark glass, so lift them. */
        private fun readable(color: Int): Int =
            if (ColorUtils.calculateLuminance(color) < 0.22) blend(color, Color.WHITE, 0.42f) else color
    }
}

// =================================================================================================
// Orbiting 3D badge
// =================================================================================================

/** A glossy sphere with a tilted orbit ring and a travelling spark; floats above the card. */
private class PremiumOrbView(ctx: Context, private val c1: Int, private val c2: Int, private val c3: Int) : View(ctx) {
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sphere = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spec = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringRect = RectF()
    private val specRect = RectF()
    private var phase = 0f
    private var animator: ValueAnimator? = null
    private var cx = 0f
    private var cy = 0f
    private var r = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        r = min(w, h) * 0.27f
        glow.shader = RadialGradient(
            cx, cy, r * 2.3f,
            intArrayOf(Color.argb(150, Color.red(c1), Color.green(c1), Color.blue(c1)), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        sphere.shader = RadialGradient(
            cx - r * 0.38f, cy - r * 0.45f, r * 1.7f,
            intArrayOf(Color.WHITE, blend(c1, Color.WHITE, 0.25f), c1, c2, blend(c2, Color.BLACK, 0.7f)),
            floatArrayOf(0f, 0.16f, 0.45f, 0.78f, 1f), Shader.TileMode.CLAMP
        )
        spec.color = Color.argb(185, 255, 255, 255)
        ring.strokeWidth = ctxDp(1.6f)
        specRect.set(cx - r * 0.62f, cy - r * 0.74f, cx - r * 0.02f, cy - r * 0.34f)
        ringRect.set(cx - r * 1.75f, cy - r * 0.55f, cx + r * 1.75f, cy + r * 0.55f)
    }

    private fun ctxDp(v: Float) = v * resources.displayMetrics.density

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 5200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (r <= 0f) return
        val bob = sin(phase * 6.28318f) * ctxDp(2.2f)
        canvas.save()
        canvas.translate(0f, bob)
        canvas.drawCircle(cx, cy, r * 2.3f, glow)

        canvas.save()
        canvas.rotate(-22f, cx, cy)
        // Back half of the ring passes behind the sphere, front half in front: this is what reads as 3D.
        ring.color = Color.argb(70, Color.red(c3), Color.green(c3), Color.blue(c3))
        canvas.drawArc(ringRect, 180f, 180f, false, ring)
        canvas.restore()

        canvas.drawCircle(cx, cy, r, sphere)
        canvas.drawOval(specRect, spec)

        canvas.save()
        canvas.rotate(-22f, cx, cy)
        ring.color = Color.argb(215, Color.red(c3), Color.green(c3), Color.blue(c3))
        canvas.drawArc(ringRect, 0f, 180f, false, ring)
        val a = phase * 6.28318f
        val dx = cx + ringRect.width() / 2f * cos(a)
        val dy = cy + ringRect.height() / 2f * sin(a)
        val front = sin(a) > 0f
        dotPaint.color = Color.argb(if (front) 255 else 110, 255, 255, 255)
        canvas.drawCircle(dx, dy, ctxDp(if (front) 3.2f else 2.2f), dotPaint)
        canvas.restore()
        canvas.restore()
    }
}

// =================================================================================================
// Layout helper
// =================================================================================================

private class MaxHeightScrollView(ctx: Context, private val maxHeightPx: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST))
    }
}

// =================================================================================================
// Drawables and small helpers
// =================================================================================================

internal fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

internal fun blend(from: Int, to: Int, amount: Float): Int = ColorUtils.blendARGB(from, to, amount.coerceIn(0f, 1f))

internal fun brighten(color: Int, amount: Float): Int = blend(color, Color.WHITE, amount)

private fun themeColor(ctx: Context, attr: Int, fallback: Int): Int {
    val value = TypedValue()
    if (!ctx.theme.resolveAttribute(attr, value, true)) return fallback
    return if (value.resourceId != 0) {
        try { ctx.getColor(value.resourceId) } catch (_: Exception) { fallback }
    } else value.data
}

private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

private fun label(ctx: Context, text: String, sizeSp: Float, color: Int, family: String, tracking: Float): TextView =
    TextView(ctx).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = Typeface.create(family, Typeface.NORMAL)
        letterSpacing = tracking
        includeFontPadding = false
    }

/** Fills text with a horizontal gradient that follows the centred text, not the whole view width. */
private fun gradientText(tv: TextView, colors: IntArray) {
    fun paintGradient() {
        val w = tv.width.toFloat()
        if (w <= 0f) return
        val textW = tv.paint.measureText(tv.text.toString()).coerceAtMost(w)
        val start = (w - textW) / 2f
        tv.paint.shader = LinearGradient(start, 0f, start + textW, 0f, colors, null, Shader.TileMode.CLAMP)
        tv.invalidate()
    }
    tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> paintGradient() }
}

/** Presses a view down like a physical key and springs it back. Clicks still go through. */
private fun pressable(view: View) {
    val ctx = view.context
    view.setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().translationY(ctx.dp(2.5f)).scaleX(0.985f).scaleY(0.985f)
                .setDuration(70L).setInterpolator(DecelerateInterpolator()).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(180L).setInterpolator(OvershootInterpolator(2.2f)).start()
        }
        false
    }
}

private fun keycapButton(ctx: Context, text: String, primary: Boolean, a1: Int, a2: Int): TextView =
    TextView(ctx).apply {
        this.text = text.uppercase()
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.WHITE else Color.rgb(214, 224, 247))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        letterSpacing = 0.09f
        includeFontPadding = false
        setPadding(ctx.dp(12f).toInt(), 0, ctx.dp(12f).toInt(), ctx.dp(4f).toInt())
        isClickable = true
        isFocusable = true
        maxLines = 1
        background = if (primary) {
            keycap(ctx, blend(a1, Color.BLACK, 0.10f), blend(a2, Color.BLACK, 0.30f), blend(a2, Color.BLACK, 0.68f), ctx.dp(18f), glossAlpha = 95)
        } else {
            keycap(ctx, Color.argb(70, 255, 255, 255), Color.argb(26, 255, 255, 255), Color.argb(90, 0, 0, 0), ctx.dp(18f), glossAlpha = 30)
        }
        pressable(this)
    }

/** A raised key: darker edge visible under a gradient face with a gloss highlight on top. */
private fun keycap(ctx: Context, top: Int, bottom: Int, edge: Int, radius: Float, glossAlpha: Int): Drawable {
    val edgeShape = GradientDrawable().apply { setColor(edge); cornerRadius = radius }
    val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
        cornerRadius = radius
        setStroke(ctx.dp(1f).toInt(), Color.argb(60, 255, 255, 255))
    }
    val gloss = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.argb(glossAlpha, 255, 255, 255), Color.TRANSPARENT)
    ).apply { cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f) }
    val layers = LayerDrawable(arrayOf(edgeShape, face, gloss))
    val lift = ctx.dp(4f).toInt()
    layers.setLayerInset(1, 0, 0, 0, lift)
    layers.setLayerInset(2, ctx.dp(1f).toInt(), ctx.dp(1f).toInt(), ctx.dp(1f).toInt(), lift)
    layers.setLayerGravity(2, Gravity.TOP)
    layers.setLayerHeight(2, ctx.dp(22f).toInt())
    return layers
}

private fun glassCardDrawable(ctx: Context, a1: Int, a2: Int): Drawable {
    val radius = ctx.dp(30f)
    val rim = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.argb(235, 255, 255, 255), withAlpha(a1, 170), Color.argb(40, 255, 255, 255), withAlpha(a2, 210))
    ).apply { cornerRadius = radius }
    val body = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.rgb(34, 41, 82), Color.rgb(17, 21, 47), Color.rgb(9, 12, 30))
    ).apply { cornerRadius = radius - ctx.dp(1.6f) }
    val tint = GradientDrawable(
        GradientDrawable.Orientation.BL_TR,
        intArrayOf(withAlpha(a2, 46), Color.TRANSPARENT, withAlpha(a1, 40))
    ).apply { cornerRadius = radius - ctx.dp(1.6f) }
    val sheen = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.argb(46, 255, 255, 255), Color.TRANSPARENT)
    ).apply {
        val r = radius - ctx.dp(1.6f)
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }
    val layers = LayerDrawable(arrayOf(rim, body, tint, sheen))
    val inset = ctx.dp(1.6f).toInt()
    layers.setLayerInset(1, inset, inset, inset, inset)
    layers.setLayerInset(2, inset, inset, inset, inset)
    layers.setLayerInset(3, inset, inset, inset, inset)
    layers.setLayerGravity(3, Gravity.TOP)
    layers.setLayerHeight(3, ctx.dp(110f).toInt())
    return layers
}

private fun rowDrawable(ctx: Context, palette: PremiumPalette, selected: Boolean): Drawable {
    val radius = ctx.dp(18f)
    val lift = ctx.dp(3f).toInt()
    return if (selected) {
        val edge = GradientDrawable().apply { setColor(blend(palette.accent2, Color.BLACK, 0.62f)); cornerRadius = radius }
        val face = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(blend(palette.accent1, Color.BLACK, 0.12f), blend(palette.accent2, Color.BLACK, 0.26f))
        ).apply {
            cornerRadius = radius
            setStroke(ctx.dp(1.2f).toInt(), Color.argb(150, 255, 255, 255))
        }
        val gloss = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(80, 255, 255, 255), Color.TRANSPARENT)
        ).apply { cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f) }
        LayerDrawable(arrayOf(edge, face, gloss)).apply {
            setLayerInset(1, 0, 0, 0, lift)
            setLayerInset(2, ctx.dp(1f).toInt(), ctx.dp(1f).toInt(), ctx.dp(1f).toInt(), lift)
            setLayerGravity(2, Gravity.TOP)
            setLayerHeight(2, ctx.dp(20f).toInt())
        }
    } else {
        val edge = GradientDrawable().apply { setColor(Color.argb(110, 0, 0, 0)); cornerRadius = radius }
        val face = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(30, 255, 255, 255), Color.argb(10, 255, 255, 255))
        ).apply {
            cornerRadius = radius
            setStroke(ctx.dp(1f).toInt(), Color.argb(38, 255, 255, 255))
        }
        LayerDrawable(arrayOf(edge, face)).apply { setLayerInset(1, 0, 0, 0, lift) }
    }
}

private fun radioDrawable(ctx: Context, on: Boolean): Drawable {
    val outer = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke(ctx.dp(2f).toInt(), if (on) Color.WHITE else Color.argb(105, 200, 212, 240))
    }
    if (!on) return outer
    val inner = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.WHITE)
    }
    return LayerDrawable(arrayOf(outer, inner)).apply {
        val i = ctx.dp(5f).toInt()
        setLayerInset(1, i, i, i, i)
    }
}

private fun checkBoxDrawable(ctx: Context, palette: PremiumPalette, on: Boolean): Drawable {
    val box = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (on) intArrayOf(palette.accent1, palette.accent2) else intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT)
    ).apply {
        cornerRadius = ctx.dp(8f)
        setStroke(ctx.dp(2f).toInt(), if (on) Color.WHITE else Color.argb(105, 200, 212, 240))
    }
    if (!on) return box
    val tick = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = ctx.dp(2f)
        setColor(Color.WHITE)
    }
    return LayerDrawable(arrayOf(box, tick)).apply {
        val i = ctx.dp(7f).toInt()
        setLayerInset(1, i, i, i, i)
    }
}

private fun fieldBackground(ctx: Context, accent: Int, focused: Boolean): Drawable =
    GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.argb(if (focused) 44 else 28, 255, 255, 255), Color.argb(if (focused) 18 else 8, 255, 255, 255))
    ).apply {
        cornerRadius = ctx.dp(17f)
        setStroke(
            ctx.dp(if (focused) 2f else 1f).toInt(),
            if (focused) withAlpha(brighten(accent, 0.1f), 235) else Color.argb(48, 255, 255, 255)
        )
    }
