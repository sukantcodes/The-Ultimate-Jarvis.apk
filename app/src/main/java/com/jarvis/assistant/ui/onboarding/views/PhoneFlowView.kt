package com.jarvis.assistant.ui.onboarding.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import com.jarvis.assistant.ui.onboarding.Palette
import com.jarvis.assistant.ui.onboarding.dp
import com.jarvis.assistant.ui.onboarding.withAlpha

enum class Overlay { NONE, MENU, DIALOG, TOGGLE }

/**
 * One screen of the scripted walk-through drawn by [PhoneFlowView].
 * [target] is the row to tap (NONE, TOGGLE) or the menu item to tap (MENU). DIALOG taps its own button.
 */
data class DemoScene(
    val title: String,
    val rows: List<String>,
    val target: Int,
    val caption: String,
    val overlay: Overlay = Overlay.NONE,
    val menuItems: List<String> = emptyList(),
    val dialogTitle: String = "",
    val dialogBody: String = "",
    val dialogButton: String = ""
)

/**
 * A stylised phone replaying a scripted sequence: a finger moves to the target, taps, and the screen reacts.
 * It illustrates the steps; it does not control the real device.
 */
class PhoneFlowView(context: Context) : LoopingView(context, 12_000L) {

    private var scenes: List<DemoScene> = emptyList()
    private var pinned = -1
    private var lastReported = -1

    /** Called with the index of the scene now showing, so the caption and step chips can follow. */
    var onSceneChanged: ((Int) -> Unit)? = null

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fingerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val phoneRect = RectF()
    private val screenRect = RectF()

    fun setScenes(list: List<DemoScene>) {
        scenes = list
        pinned = -1
        lastReported = -1
        setLoopPeriod(maxOf(1, list.size) * SCENE_MS)
        invalidate()
        if (!isLooping && list.isNotEmpty()) notifyScene(0)
    }

    /** Replays one scene on a loop (or goes back to the full sequence when [index] is -1). */
    fun pinScene(index: Int) {
        pinned = if (index in scenes.indices) index else -1
        if (pinned >= 0) notifyScene(pinned)
        invalidate()
    }

    private fun notifyScene(index: Int) {
        if (index != lastReported) {
            lastReported = index
            onSceneChanged?.invoke(index)
        }
    }

    override fun onPhase(phase: Float) {
        if (scenes.isEmpty() || pinned >= 0) return
        notifyScene(sceneIndex(phase))
    }

    private fun sceneIndex(p: Float): Int = (p * scenes.size).toInt().coerceIn(0, scenes.size - 1)

    private fun seg(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

    private fun ease(x: Float): Float = x * x * (3f - 2f * x)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(dp(220), widthMeasureSpec)
        val h = resolveSize(dp(300), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (scenes.isEmpty() || width == 0 || height == 0) return

        val index: Int
        val t: Float
        if (!isLooping) {
            index = if (pinned >= 0) pinned else 0
            t = 0.93f
        } else if (pinned >= 0) {
            index = pinned
            t = (phase * scenes.size) % 1f
        } else {
            index = sceneIndex(phase)
            t = (phase * scenes.size) % 1f
        }
        val scene = scenes[index]

        // Phone body, kept at a 1:2 aspect and centred.
        val ph = minOf(height * 0.96f, width * 1.9f)
        val pw = ph / 2f
        phoneRect.set((width - pw) / 2f, (height - ph) / 2f, (width + pw) / 2f, (height + ph) / 2f)
        val corner = pw * 0.12f
        bodyPaint.color = 0xFF080D1C.toInt()
        canvas.drawRoundRect(phoneRect, corner, corner, bodyPaint)
        strokePaint.color = withAlpha(Palette.CYAN, 120)
        strokePaint.strokeWidth = dp(1).toFloat() * 1.5f
        canvas.drawRoundRect(phoneRect, corner, corner, strokePaint)

        val inset = pw * 0.045f
        screenRect.set(phoneRect.left + inset, phoneRect.top + inset * 2.2f, phoneRect.right - inset, phoneRect.bottom - inset * 1.6f)
        bodyPaint.color = 0xFF111A33.toInt()
        canvas.drawRoundRect(screenRect, corner * 0.7f, corner * 0.7f, bodyPaint)

        canvas.save()
        canvas.clipRect(screenRect)

        val sw = screenRect.width()
        val sh = screenRect.height()
        val appBarH = sh * 0.095f
        val appBarBottom = screenRect.top + appBarH
        val enter = ease(seg(t, 0f, 0.1f))

        // App bar
        textPaint.color = withAlpha(Palette.TEXT, (255 * enter).toInt())
        textPaint.textSize = pw * 0.075f
        textPaint.isFakeBoldText = true
        val title = TextUtils.ellipsize(scene.title, android.text.TextPaint(textPaint), sw * 0.62f, TextUtils.TruncateAt.END)
        canvas.drawText(title, 0, title.length, screenRect.left + sw * 0.06f, screenRect.top + appBarH * 0.66f, textPaint)
        textPaint.isFakeBoldText = false

        val dotsX = screenRect.right - sw * 0.08f
        if (scene.overlay == Overlay.MENU) {
            fingerPaint.color = withAlpha(Palette.TEXT, 230)
            for (k in 0..2) canvas.drawCircle(dotsX, screenRect.top + appBarH * (0.30f + k * 0.18f), pw * 0.014f, fingerPaint)
        }

        // Rows
        val rowH = sh * 0.082f
        val rowGap = sh * 0.014f
        val firstRowTop = appBarBottom + rowGap
        val highlightRow = if (scene.overlay != Overlay.MENU && scene.overlay != Overlay.DIALOG) scene.target else -1
        val tapDone = when (scene.overlay) {
            Overlay.TOGGLE -> t > 0.55f
            Overlay.NONE -> t > 0.55f
            else -> false
        }
        textPaint.textSize = pw * 0.058f
        for (i in scene.rows.indices) {
            val top = firstRowTop + i * (rowH + rowGap)
            if (top > screenRect.bottom) break
            rect.set(screenRect.left + sw * 0.04f, top, screenRect.right - sw * 0.04f, top + rowH)
            bodyPaint.color = withAlpha(Palette.TEXT, (22 * enter).toInt())
            canvas.drawRoundRect(rect, rowH * 0.3f, rowH * 0.3f, bodyPaint)
            if (i == highlightRow && tapDone) {
                strokePaint.color = withAlpha(Palette.CYAN, 230)
                canvas.drawRoundRect(rect, rowH * 0.3f, rowH * 0.3f, strokePaint)
            }
            textPaint.color = withAlpha(if (i == highlightRow && tapDone) Palette.CYAN else Palette.TEXT, (235 * enter).toInt())
            val maxText = rect.width() * (if (scene.overlay == Overlay.TOGGLE && i == scene.target) 0.62f else 0.88f)
            val label = TextUtils.ellipsize(scene.rows[i], android.text.TextPaint(textPaint), maxText, TextUtils.TruncateAt.END)
            canvas.drawText(label, 0, label.length, rect.left + rect.width() * 0.05f, rect.centerY() + pw * 0.02f, textPaint)

            if (scene.overlay == Overlay.TOGGLE && i == scene.target) drawSwitch(canvas, rect, seg(t, 0.58f, 0.75f))
        }

        // Targets for the finger
        var targetX = screenRect.centerX()
        var targetY = screenRect.centerY()
        var tapAt = 0.6f
        var fingerFrom = 0.12f
        var fingerTo = 0.55f

        when (scene.overlay) {
            Overlay.NONE, Overlay.TOGGLE -> {
                val row = scene.target.coerceAtLeast(0)
                targetX = screenRect.right - sw * 0.25f
                targetY = firstRowTop + row * (rowH + rowGap) + rowH / 2f
            }
            Overlay.MENU -> drawMenuAndPickTarget(canvas, scene, t, dotsX, appBarH, pw, sh).let { (x, y) ->
                // Two-stage finger: first the ⋮ button, then the menu entry.
                targetX = x
                targetY = y
                tapAt = 0.75f
                fingerFrom = 0.45f
                fingerTo = 0.7f
            }
            Overlay.DIALOG -> {
                val dialogTarget = drawDialog(canvas, scene, t, pw)
                targetX = dialogTarget.first
                targetY = dialogTarget.second
                tapAt = 0.7f
                fingerFrom = 0.25f
                fingerTo = 0.65f
            }
        }
        canvas.restore()

        // Finger and tap ripple (drawn over the bezel clip so it never looks cut off)
        val startX = screenRect.right - sw * 0.1f
        val startY = screenRect.bottom - sh * 0.12f
        var fx = startX
        var fy = startY
        var rippleT = 0f
        if (scene.overlay == Overlay.MENU) {
            val barTapX = dotsX
            val barTapY = screenRect.top + appBarH * 0.5f
            val toBar = ease(seg(t, 0.1f, 0.35f))
            val toItem = ease(seg(t, fingerFrom, fingerTo))
            if (t < 0.45f) {
                fx = startX + (barTapX - startX) * toBar
                fy = startY + (barTapY - startY) * toBar
                rippleT = seg(t, 0.35f, 0.45f)
            } else {
                fx = barTapX + (targetX - barTapX) * toItem
                fy = barTapY + (targetY - barTapY) * toItem
                rippleT = seg(t, tapAt, tapAt + 0.1f)
            }
        } else {
            val move = ease(seg(t, fingerFrom, fingerTo))
            fx = startX + (targetX - startX) * move
            fy = startY + (targetY - startY) * move
            rippleT = seg(t, tapAt - 0.05f, tapAt + 0.08f)
        }
        val fingerAlpha = (255 * seg(t, 0.08f, 0.16f)).toInt()
        if (rippleT > 0f && rippleT < 1f) {
            strokePaint.color = withAlpha(Palette.CYAN, ((1f - rippleT) * 220).toInt())
            canvas.drawCircle(fx, fy, pw * (0.04f + 0.12f * rippleT), strokePaint)
        }
        fingerPaint.color = withAlpha(Palette.CYAN, (fingerAlpha * 0.35f).toInt())
        canvas.drawCircle(fx, fy, pw * 0.07f, fingerPaint)
        fingerPaint.color = withAlpha(0xFFFFFFFF.toInt(), fingerAlpha)
        canvas.drawCircle(fx, fy, pw * 0.032f, fingerPaint)
    }

    private fun drawSwitch(canvas: Canvas, row: RectF, progress: Float) {
        val w = row.height() * 1.15f
        val h = row.height() * 0.5f
        val left = row.right - w - row.height() * 0.3f
        val top = row.centerY() - h / 2f
        rect.set(left, top, left + w, top + h)
        val on = ease(progress)
        bodyPaint.color = blend(withAlpha(Palette.TEXT, 70), Palette.MINT, on)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, bodyPaint)
        val knobX = left + h / 2f + (w - h) * on
        fingerPaint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(knobX, top + h / 2f, h * 0.38f, fingerPaint)
    }

    /** Draws the popup menu and returns where the finger should land on it. */
    private fun drawMenuAndPickTarget(canvas: Canvas, scene: DemoScene, t: Float, dotsX: Float, appBarH: Float, pw: Float, sh: Float): Pair<Float, Float> {
        val items = scene.menuItems
        val itemH = sh * 0.075f
        val menuW = pw * 0.62f
        val menuLeft = screenRect.right - menuW - pw * 0.03f
        val menuTop = screenRect.top + appBarH * 0.9f
        val menuH = itemH * items.size
        val open = ease(seg(t, 0.4f, 0.5f))
        val pickIndex = scene.target.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (open > 0f && items.isNotEmpty()) {
            rect.set(menuLeft, menuTop, menuLeft + menuW, menuTop + menuH * open)
            bodyPaint.color = 0xFF1B2748.toInt()
            canvas.drawRoundRect(rect, pw * 0.03f, pw * 0.03f, bodyPaint)
            strokePaint.color = withAlpha(Palette.CYAN, 110)
            canvas.drawRoundRect(rect, pw * 0.03f, pw * 0.03f, strokePaint)
            textPaint.textSize = pw * 0.052f
            for (i in items.indices) {
                val y = menuTop + itemH * i
                if (y + itemH > menuTop + menuH * open + 1f) break
                val picked = i == pickIndex && t > 0.78f
                if (picked) {
                    rect.set(menuLeft + pw * 0.01f, y + itemH * 0.06f, menuLeft + menuW - pw * 0.01f, y + itemH * 0.94f)
                    bodyPaint.color = withAlpha(Palette.CYAN, 60)
                    canvas.drawRoundRect(rect, pw * 0.02f, pw * 0.02f, bodyPaint)
                }
                textPaint.color = if (picked) Palette.CYAN else Palette.TEXT
                val label = TextUtils.ellipsize(items[i], android.text.TextPaint(textPaint), menuW * 0.9f, TextUtils.TruncateAt.END)
                canvas.drawText(label, 0, label.length, menuLeft + menuW * 0.06f, y + itemH * 0.62f, textPaint)
            }
        }
        return Pair(menuLeft + menuW * 0.5f, menuTop + itemH * pickIndex + itemH * 0.5f)
    }

    private fun drawDialog(canvas: Canvas, scene: DemoScene, t: Float, pw: Float): Pair<Float, Float> {
        val show = ease(seg(t, 0.1f, 0.25f))
        val w = screenRect.width() * 0.86f
        val h = screenRect.height() * 0.40f
        val left = screenRect.centerX() - w / 2f
        val top = screenRect.centerY() - h / 2f
        bodyPaint.color = withAlpha(0xFF000000.toInt(), (130 * show).toInt())
        canvas.drawRect(screenRect, bodyPaint)
        rect.set(left, top, left + w, top + h)
        bodyPaint.color = withAlpha(0xFF1B2748.toInt(), (255 * show).toInt())
        canvas.drawRoundRect(rect, pw * 0.05f, pw * 0.05f, bodyPaint)

        textPaint.color = withAlpha(Palette.TEXT, (255 * show).toInt())
        textPaint.textSize = pw * 0.06f
        textPaint.isFakeBoldText = true
        val titleText = TextUtils.ellipsize(scene.dialogTitle, android.text.TextPaint(textPaint), w * 0.88f, TextUtils.TruncateAt.END)
        canvas.drawText(titleText, 0, titleText.length, left + w * 0.06f, top + h * 0.22f, textPaint)
        textPaint.isFakeBoldText = false
        textPaint.textSize = pw * 0.046f
        textPaint.color = withAlpha(Palette.TEXT_DIM, (255 * show).toInt())
        val layout = android.text.StaticLayout.Builder
            .obtain(scene.dialogBody, 0, scene.dialogBody.length, android.text.TextPaint(textPaint), (w * 0.88f).toInt())
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(left + w * 0.06f, top + h * 0.30f)
        layout.draw(canvas)
        canvas.restore()

        val btnW = w * 0.5f
        val btnH = h * 0.22f
        val btnLeft = left + w - btnW - w * 0.05f
        val btnTop = top + h - btnH - h * 0.08f
        rect.set(btnLeft, btnTop, btnLeft + btnW, btnTop + btnH)
        val pressed = t > 0.75f
        bodyPaint.color = if (pressed) withAlpha(Palette.CYAN, (230 * show).toInt()) else withAlpha(Palette.CYAN, (70 * show).toInt())
        canvas.drawRoundRect(rect, btnH / 2f, btnH / 2f, bodyPaint)
        textPaint.color = if (pressed) 0xFF04101C.toInt() else withAlpha(Palette.CYAN, (255 * show).toInt())
        textPaint.textSize = pw * 0.05f
        textPaint.isFakeBoldText = true
        val btn = TextUtils.ellipsize(scene.dialogButton, android.text.TextPaint(textPaint), btnW * 0.9f, TextUtils.TruncateAt.END)
        canvas.drawText(btn, 0, btn.length, rect.centerX() - textPaint.measureText(btn, 0, btn.length) / 2f, rect.centerY() + pw * 0.018f, textPaint)
        textPaint.isFakeBoldText = false
        return Pair(rect.centerX(), rect.centerY())
    }

    private fun blend(from: Int, to: Int, amount: Float): Int {
        fun mix(a: Int, b: Int) = (a + (b - a) * amount).toInt().coerceIn(0, 255)
        return android.graphics.Color.argb(
            mix(android.graphics.Color.alpha(from), android.graphics.Color.alpha(to)),
            mix(android.graphics.Color.red(from), android.graphics.Color.red(to)),
            mix(android.graphics.Color.green(from), android.graphics.Color.green(to)),
            mix(android.graphics.Color.blue(from), android.graphics.Color.blue(to))
        )
    }

    private companion object {
        const val SCENE_MS = 3_800L
    }
}
