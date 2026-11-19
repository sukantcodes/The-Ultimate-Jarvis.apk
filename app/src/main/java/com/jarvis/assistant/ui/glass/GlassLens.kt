package com.jarvis.assistant.ui.glass

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.ViewParent
import kotlin.math.min

/**
 * The magnifying lens inside every liquid-glass pane.
 *
 * Apple's liquid glass is not a frosted sheet: what sits behind a pane is ENLARGED (and bent hardest at the rim), so the
 * pane reads as a piece of shaped glass instead of a blur. The app's backdrop is a deterministic light field
 * ([AuroraField]) drawn in screen coordinates, so a pane can redraw that same field through a lens:
 *
 *  - the field is sampled magnified about the centre of the pane ([ZOOM]) — the very same thing the AGSL shader in
 *    [LiquidGlassLayout] does to a real screen capture, just against the field instead of a bitmap,
 *  - the pane's own bright rim (drawn on top by the host drawable) hides the seam where the magnified image meets the
 *    un-magnified field outside it, exactly the way the edge of a real lens does.
 *
 * Nothing is captured and nothing is allocated per frame. A pane only has to be redrawn while the backdrop drifts,
 * which [LiquidGlass] arranges through [AuroraClock]; on a screen with no backdrop there is nothing to magnify and every
 * call here is skipped.
 */
internal object GlassLens {

    /**
     * How much the light behind a pane is enlarged. 1.22 is clearly a lens at phone size without turning the backdrop
     * into a smear; it matches what the capture-based shader does on the dock, header and composer.
     */
    const val ZOOM = 1.22f

    /**
     * The field inside a pane is a touch quieter than the open backdrop, so a card never glows brighter than the screen
     * it sits on while still showing an image of its own.
     */
    private const val INTENSITY = 0.46f

    private val location = IntArray(2)
    private val clipPath = Path()

    /** True when [host] actually floats over the drifting field; without one there is nothing to magnify. */
    fun hasField(host: View): Boolean {
        var parent: ViewParent? = host.parent
        while (parent != null) {
            if (parent is View && parent.background is AuroraBackdropDrawable) return true
            parent = parent.parent
        }
        return false
    }

    /**
     * Draws the magnified field inside [outer], in the host's own coordinates. [strength] is the user's Liquid Glass
     * strength (0..1), so turning the effect down also calms the lens.
     */
    fun draw(canvas: Canvas, host: View, outer: RectF, radiusPx: Float, light: Boolean, strength: Float) {
        if (outer.isEmpty) return
        val metrics = host.resources.displayMetrics
        val screenW = metrics.widthPixels.toFloat()
        val screenH = metrics.heightPixels.toFloat()
        if (screenW <= 0f || screenH <= 0f) return

        host.getLocationOnScreen(location)
        val originX = location[0].toFloat()
        val originY = location[1].toFloat()
        // The lens is centred on the pane, in screen coordinates (the field is drawn in screen space below).
        val pivotX = originX + outer.centerX()
        val pivotY = originY + outer.centerY()
        val r = min(radiusPx, min(outer.width(), outer.height()) * 0.5f)

        clipPath.reset()
        clipPath.addRoundRect(outer, r, r, Path.Direction.CW)

        val restore = canvas.save()
        // Clip while the canvas is still in the host's own coordinates, then move into screen coordinates: the field is
        // drawn in screen space, and the clip stays exactly on the pane.
        canvas.clipPath(clipPath)
        canvas.translate(-originX, -originY)
        canvas.scale(ZOOM, ZOOM, pivotX, pivotY)
        AuroraField.draw(canvas, screenW, screenH, light, (INTENSITY * (0.7f + 0.3f * strength)).coerceIn(0f, 1f))
        canvas.restoreToCount(restore)
    }
}
