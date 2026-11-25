package com.jarvis.assistant.ui.glass

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.JarvisApp
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** The backgrounds a person can pick for every Settings screen. */
enum class SettingsScene(val key: String, val label: String, val blurb: String) {
    AURORA("aurora", "Aurora", "Drifting ribbons of light"),
    NEBULA("nebula", "Nebula", "Deep-space clouds and twinkling stars"),
    WAVES("waves", "Liquid Waves", "Slow, layered ocean swells"),
    CONSTELLATION("constellation", "Constellation", "Linked stars that drift"),
    PRISM("prism", "Prism", "Rotating crystal facets"),
    SILK("silk", "Silk", "Flowing satin ribbons"),
    MESH("mesh", "Mesh Flow", "Soft colour clouds that melt together"),
    BOKEH("bokeh", "Bokeh", "Glowing orbs drifting upward"),
    RIPPLES("ripples", "Ripples", "Calm rings spreading across the glass"),
    FIREFLIES("fireflies", "Fireflies", "Tiny lights floating in the dark"),
    DUSK("dusk", "Dusk", "A slow, glowing colour wash"),
    SEE_THROUGH("seethrough", "See-through", "The screen underneath, behind glass");

    companion object {
        fun fromKey(key: String): SettingsScene? = values().firstOrNull { it.key == key }

        /** The scene to show now. "auto" means see-through on the Transparent (crystal) theme and Aurora otherwise. */
        fun effective(): SettingsScene {
            val key = try { JarvisApp.instance.preferences.settingsScene } catch (_: Throwable) { "auto" }
            return fromKey(key) ?: if (CrystalTheme.active()) SEE_THROUGH else AURORA
        }
    }
}

/**
 * Draws the animated Settings scenes. Everything is vector (gradients, paths, circles) so it is razor sharp at any
 * resolution including 4K panels, and every shader and path is built once per size, so a frame allocates nothing and
 * costs only a handful of GPU fills. Scenes run on the shared [AuroraClock] heartbeat, which pauses while scrolling.
 */
class SceneRenderer(private val density: Float) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()

    private var w = 0f
    private var h = 0f
    private var builtKey = 0L
    private var lastT = 0f

    private val blobs = arrayOfNulls<RadialGradient>(4)
    private var blobRadius = 0f
    private val waves = arrayOfNulls<LinearGradient>(4)
    private val silk = arrayOfNulls<LinearGradient>(5)
    private val facets = arrayOfNulls<LinearGradient>(7)
    private val facetPaths = Array(7) { Path() }
    private val facetRadius = FloatArray(7)

    private val starX = FloatArray(80)
    private val starY = FloatArray(80)
    private val starR = FloatArray(80)
    private val starSpeed = FloatArray(80)
    private val starPhase = FloatArray(80)

    private val nodeX = FloatArray(52)
    private val nodeY = FloatArray(52)
    private val nodeVx = FloatArray(52)
    private val nodeVy = FloatArray(52)

    // Palette scenes: the theme's two colours plus three harmonious neighbours, so every theme gets rich colour.
    private val palette = IntArray(5)
    private val meshBlobs = arrayOfNulls<RadialGradient>(5)
    private var meshRadius = 0f
    private val bokehSprites = arrayOfNulls<RadialGradient>(5)
    private val fireSprites = arrayOfNulls<RadialGradient>(3)
    private var duskA: LinearGradient? = null
    private var duskB: LinearGradient? = null
    private var duskSun: RadialGradient? = null
    private val screen = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    private var isLight = false

    private val bokehX = FloatArray(18)
    private val bokehSize = FloatArray(18)
    private val bokehSpeed = FloatArray(18)
    private val bokehPhase = FloatArray(18)
    private val flyX = FloatArray(38)
    private val flyY = FloatArray(38)
    private val flyS1 = FloatArray(38)
    private val flyS2 = FloatArray(38)
    private val flyPhase = FloatArray(38)
    private val flySize = FloatArray(38)

    private fun hue(color: Int, degrees: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[0] = (hsl[0] + degrees + 360f) % 360f
        hsl[1] = hsl[1].coerceAtLeast(0.55f)
        return ColorUtils.HSLToColor(hsl)
    }

    init {
        val extra = Random(3)
        for (i in 0 until 18) {
            bokehX[i] = extra.nextFloat(); bokehSize[i] = 18f + extra.nextFloat() * 52f
            bokehSpeed[i] = 0.014f + extra.nextFloat() * 0.03f; bokehPhase[i] = extra.nextFloat()
        }
        for (i in 0 until 38) {
            flyX[i] = 0.05f + extra.nextFloat() * 0.9f; flyY[i] = 0.05f + extra.nextFloat() * 0.9f
            flyS1[i] = 0.12f + extra.nextFloat() * 0.3f; flyS2[i] = 0.1f + extra.nextFloat() * 0.3f
            flyPhase[i] = extra.nextFloat() * 6.28f; flySize[i] = 3f + extra.nextFloat() * 5f
        }
        val rnd = Random(11)
        for (i in starX.indices) {
            starX[i] = rnd.nextFloat(); starY[i] = rnd.nextFloat()
            starR[i] = 0.6f + rnd.nextFloat() * 1.4f
            starSpeed[i] = 0.4f + rnd.nextFloat() * 1.1f
            starPhase[i] = rnd.nextFloat() * 6.28f
        }
    }

    private fun a(color: Int, alpha: Int) = ColorUtils.setAlphaComponent(color, alpha.coerceIn(0, 255))

    private fun rebuild(width: Float, height: Float, accent: Int, secondary: Int, light: Boolean) {
        val key = (width.toLong() * 31 + height.toLong()) * 31 + accent * 17L + secondary * 13L + (if (light) 1 else 0)
        if (key == builtKey) return
        builtKey = key
        w = width; h = height
        val big = max(w, h)
        val third = ColorUtils.blendARGB(accent, secondary, 0.5f)
        val k = if (light) 0.6f else 1f

        blobRadius = big * 0.62f
        val blobColors = intArrayOf(accent, secondary, third, ColorUtils.blendARGB(accent, 0xFFFFFFFF.toInt(), 0.25f))
        for (i in 0 until 4) {
            blobs[i] = RadialGradient(
                0f, 0f, blobRadius,
                intArrayOf(a(blobColors[i], (120 * k).toInt()), a(blobColors[i], (52 * k).toInt()), a(blobColors[i], 0)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
        }
        for (i in 0 until 4) {
            val col = if (i % 2 == 0) accent else secondary
            val top = h * (0.42f + 0.12f * i)
            waves[i] = LinearGradient(0f, top, 0f, h, a(col, (96 * k).toInt()), a(col, (10 * k).toInt()), Shader.TileMode.CLAMP)
        }
        for (i in 0 until 5) {
            val c1 = if (i % 2 == 0) accent else secondary
            val c2 = if (i % 2 == 0) secondary else third
            silk[i] = LinearGradient(
                0f, 0f, w, 0f, intArrayOf(a(c1, 0), a(c1, 230), a(c2, 230), a(c2, 0)), floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP
            )
        }
        val rnd = Random(5)
        for (i in 0 until 7) {
            val r = big * (0.22f + 0.2f * rnd.nextFloat())
            facetRadius[i] = r
            val sides = if (i % 2 == 0) 3 else 6
            facetPaths[i].reset()
            for (s in 0 until sides) {
                val ang = (2.0 * PI * s / sides - PI / 2).toFloat()
                val px = cos(ang) * r; val py = sin(ang) * r
                if (s == 0) facetPaths[i].moveTo(px, py) else facetPaths[i].lineTo(px, py)
            }
            facetPaths[i].close()
            val col = if (i % 2 == 0) accent else secondary
            facets[i] = LinearGradient(-r, -r, r, r, a(col, (110 * k).toInt()), a(third, (14 * k).toInt()), Shader.TileMode.CLAMP)
        }
        palette[0] = accent; palette[1] = secondary
        palette[2] = hue(accent, 55f); palette[3] = hue(secondary, -55f)
        palette[4] = ColorUtils.blendARGB(accent, 0xFFFFFFFF.toInt(), 0.3f)
        meshRadius = big * 0.58f
        for (i in 0 until 5) {
            meshBlobs[i] = RadialGradient(0f, 0f, meshRadius,
                intArrayOf(a(palette[i], (190 * k).toInt()), a(palette[i], (80 * k).toInt()), a(palette[i], 0)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            bokehSprites[i] = RadialGradient(0f, 0f, 100f,
                intArrayOf(a(palette[i], 14), a(palette[i], (60 * k).toInt()), a(palette[i], (120 * k).toInt()), a(palette[i], 0)),
                floatArrayOf(0f, 0.62f, 0.9f, 1f), Shader.TileMode.CLAMP)
        }
        for (i in 0 until 3) {
            val c = palette[(i * 2) % 5]
            fireSprites[i] = RadialGradient(0f, 0f, 100f,
                intArrayOf(a(c, 255), a(c, (110 * k).toInt()), a(c, 0)), floatArrayOf(0f, 0.22f, 1f), Shader.TileMode.CLAMP)
        }
        duskA = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(a(palette[0], (150 * k).toInt()), a(palette[2], (110 * k).toInt()), a(palette[1], (150 * k).toInt())), null, Shader.TileMode.CLAMP)
        duskB = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(a(palette[3], (150 * k).toInt()), a(palette[4], (100 * k).toInt()), a(palette[0], (150 * k).toInt())), null, Shader.TileMode.CLAMP)
        duskSun = RadialGradient(0f, 0f, big * 0.5f,
            intArrayOf(a(palette[4], (170 * k).toInt()), a(palette[4], 0)), null, Shader.TileMode.CLAMP)
        for (i in nodeX.indices) {
            nodeX[i] = rnd.nextFloat() * w; nodeY[i] = rnd.nextFloat() * h
            val ang = rnd.nextFloat() * 6.28f
            val sp = (6f + rnd.nextFloat() * 10f) * density
            nodeVx[i] = cos(ang) * sp; nodeVy[i] = sin(ang) * sp
        }
    }

    fun draw(scene: SettingsScene, canvas: Canvas, width: Float, height: Float, t: Float, light: Boolean, accent: Int, secondary: Int, ink: Int) {
        if (width <= 0f || height <= 0f) return
        rebuild(width, height, accent, secondary, light)
        val dt = (t - lastT).coerceIn(0f, 0.25f)
        lastT = t
        val k = if (light) 0.6f else 1f
        isLight = light
        when (scene) {
            SettingsScene.NEBULA -> nebula(canvas, t, k, ink)
            SettingsScene.WAVES -> waves(canvas, t)
            SettingsScene.CONSTELLATION -> constellation(canvas, dt, k, accent, ink)
            SettingsScene.PRISM -> prism(canvas, t, ink, k)
            SettingsScene.SILK -> silk(canvas, t, k)
            SettingsScene.MESH -> mesh(canvas, t)
            SettingsScene.BOKEH -> bokeh(canvas, t)
            SettingsScene.RIPPLES -> ripples(canvas, t, k)
            SettingsScene.FIREFLIES -> fireflies(canvas, t, k)
            SettingsScene.DUSK -> dusk(canvas, t)
            else -> Unit
        }
        paint.shader = null
        paint.xfermode = null
        paint.alpha = 255
        stroke.shader = null
    }

    private fun mesh(c: Canvas, t: Float) {
        // SCREEN blending lets the colours glow into one another on dark themes; light themes use plain alpha.
        paint.xfermode = if (isLight) null else screen
        for (i in 0 until 5) {
            val cx = w * (0.5f + 0.42f * sin(t * 0.05f * (i + 1) + i * 1.7f))
            val cy = h * (0.5f + 0.42f * cos(t * 0.04f * (i + 2) + i * 2.3f))
            val s = 1f + 0.18f * sin(t * 0.09f + i)
            c.save(); c.translate(cx, cy); c.scale(s, s)
            paint.shader = meshBlobs[i]
            c.drawCircle(0f, 0f, meshRadius, paint)
            c.restore()
        }
    }

    private fun bokeh(c: Canvas, t: Float) {
        for (i in 0 until 18) {
            val r = bokehSize[i] * density
            val travel = h + 2f * r
            val y = h + r - ((t * bokehSpeed[i] * h + bokehPhase[i] * travel) % travel)
            val x = w * bokehX[i] + sin(t * 0.4f + bokehPhase[i] * 6.28f) * 20f * density
            c.save(); c.translate(x, y); c.scale(r / 100f, r / 100f)
            paint.shader = bokehSprites[i % 5]
            c.drawCircle(0f, 0f, 100f, paint)
            c.restore()
        }
    }

    private fun ripples(c: Canvas, t: Float, k: Float) {
        val big = max(w, h)
        val sx = floatArrayOf(0.25f, 0.76f, 0.3f, 0.8f)
        val sy = floatArrayOf(0.3f, 0.24f, 0.74f, 0.7f)
        for (src in 0 until 4) {
            val cx = w * (sx[src] + 0.03f * sin(t * 0.07f + src))
            val cy = h * (sy[src] + 0.03f * cos(t * 0.06f + src))
            for (ring in 0 until 3) {
                val p = (t * 0.09f + src * 0.23f + ring / 3f) % 1f
                val fade = (1f - p) * (1f - p)
                val radius = p * big * 0.6f
                stroke.strokeWidth = 9f * density
                stroke.color = a(palette[src], (fade * 36 * k).toInt())
                c.drawCircle(cx, cy, radius, stroke)
                stroke.strokeWidth = 2.2f * density
                stroke.color = a(palette[src], (fade * 140 * k).toInt())
                c.drawCircle(cx, cy, radius, stroke)
            }
        }
    }

    private fun fireflies(c: Canvas, t: Float, k: Float) {
        for (i in 0 until 38) {
            val x = w * (flyX[i] + 0.07f * sin(t * flyS1[i] + flyPhase[i]))
            val y = h * (flyY[i] + 0.07f * cos(t * flyS2[i] + flyPhase[i] * 1.3f))
            val f = 0.45f + 0.55f * (0.5f + 0.5f * sin(t * (0.8f + flyS1[i] * 3f) + flyPhase[i] * 2f))
            val glow = flySize[i] * density * 4f
            c.save(); c.translate(x, y); c.scale(glow / 100f, glow / 100f)
            paint.shader = fireSprites[i % 3]
            paint.alpha = (255 * f * (if (isLight) 0.7f else 1f)).toInt()
            c.drawCircle(0f, 0f, 100f, paint)
            c.restore()
        }
        paint.shader = null
        paint.alpha = 255
    }

    private fun dusk(c: Canvas, t: Float) {
        paint.shader = duskA
        paint.alpha = 255
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = duskB
        paint.alpha = (255 * (0.5f + 0.5f * sin(t * 0.07f))).toInt()
        c.drawRect(0f, 0f, w, h, paint)
        paint.alpha = 255
        c.save(); c.translate(w * (0.5f + 0.25f * sin(t * 0.05f)), h * (0.62f + 0.08f * cos(t * 0.06f)))
        paint.shader = duskSun
        c.drawCircle(0f, 0f, max(w, h) * 0.5f, paint)
        c.restore()
    }

    private fun nebula(c: Canvas, t: Float, k: Float, ink: Int) {
        for (i in 0 until 4) {
            val cx = w * (0.5f + 0.38f * sin(t * 0.06f * (i + 1) + i * 1.9f))
            val cy = h * (0.5f + 0.36f * cos(t * 0.05f * (i + 2) + i * 1.3f))
            val s = 1f + 0.12f * sin(t * 0.11f + i)
            c.save(); c.translate(cx, cy); c.scale(s, s)
            paint.shader = blobs[i]
            c.drawCircle(0f, 0f, blobRadius, paint)
            c.restore()
        }
        paint.shader = null
        for (i in starX.indices) {
            val tw = 0.5f + 0.5f * sin(t * starSpeed[i] + starPhase[i])
            paint.color = a(ink, (20 + 120 * tw * k).toInt())
            c.drawCircle(starX[i] * w, starY[i] * h, starR[i] * density * (0.7f + 0.5f * tw), paint)
        }
    }

    private fun waves(c: Canvas, t: Float) {
        val step = 14f * density
        for (i in 0 until 4) {
            val base = h * (0.55f + 0.12f * i)
            val amp = (18f + 8f * i) * density
            val freq = (2.2f + 0.5f * i) * PI.toFloat() / w
            path.reset()
            path.moveTo(0f, h)
            var x = 0f
            while (x <= w + step) {
                val y = base + amp * sin(x * freq + t * (0.35f + 0.12f * i) + i * 1.7f) +
                    amp * 0.5f * sin(x * freq * 1.9f - t * 0.28f + i)
                path.lineTo(x, y)
                x += step
            }
            path.lineTo(w + step, h)
            path.close()
            paint.shader = waves[i]
            c.drawPath(path, paint)
        }
    }

    private fun constellation(c: Canvas, dt: Float, k: Float, accent: Int, ink: Int) {
        val link = 120f * density
        for (i in nodeX.indices) {
            nodeX[i] += nodeVx[i] * dt; nodeY[i] += nodeVy[i] * dt
            if (nodeX[i] < 0f || nodeX[i] > w) nodeVx[i] = -nodeVx[i]
            if (nodeY[i] < 0f || nodeY[i] > h) nodeVy[i] = -nodeVy[i]
        }
        stroke.strokeWidth = 1.1f * density
        for (i in nodeX.indices) {
            for (j in i + 1 until nodeX.size) {
                val d = hypot(nodeX[i] - nodeX[j], nodeY[i] - nodeY[j])
                if (d < link) {
                    stroke.color = a(accent, ((1f - d / link) * 150 * k).toInt())
                    c.drawLine(nodeX[i], nodeY[i], nodeX[j], nodeY[j], stroke)
                }
            }
        }
        paint.shader = null
        paint.color = a(ink, (150 * k).toInt())
        for (i in nodeX.indices) c.drawCircle(nodeX[i], nodeY[i], 2.2f * density, paint)
    }

    private fun prism(c: Canvas, t: Float, ink: Int, k: Float) {
        stroke.strokeWidth = 1.2f * density
        stroke.color = a(ink, (46 * k).toInt())
        for (i in 0 until 7) {
            val cx = w * (0.5f + 0.42f * sin(t * 0.045f * (i + 1) + i * 2.1f))
            val cy = h * (0.5f + 0.42f * cos(t * 0.04f * (i + 2) + i * 1.4f))
            val s = 0.9f + 0.18f * sin(t * 0.09f + i)
            c.save(); c.translate(cx, cy); c.rotate(t * (4f + i * 1.5f) * (if (i % 2 == 0) 1f else -1f)); c.scale(s, s)
            paint.shader = facets[i]
            c.drawPath(facetPaths[i], paint)
            c.drawPath(facetPaths[i], stroke)
            c.restore()
        }
    }

    private fun silk(c: Canvas, t: Float, k: Float) {
        val widths = floatArrayOf(74f, 46f, 20f)
        val alphas = floatArrayOf(34f, 62f, 120f)
        for (i in 0 until 5) {
            val y0 = h * (0.15f + 0.17f * i)
            val ph = t * (0.12f + 0.03f * i) + i * 1.3f
            path.reset()
            path.moveTo(-0.1f * w, y0 + sin(ph) * h * 0.06f)
            path.cubicTo(w * 0.25f, y0 + h * 0.22f * sin(ph + 1f), w * 0.7f, y0 - h * 0.22f * cos(ph * 0.8f + i), 1.1f * w, y0 + h * 0.1f * sin(ph * 1.2f))
            stroke.shader = silk[i]
            for (p in 0 until 3) {
                stroke.strokeWidth = widths[p] * density
                stroke.alpha = (alphas[p] * k).toInt().coerceIn(0, 255)
                c.drawPath(path, stroke)
            }
        }
        stroke.alpha = 255
    }
}
