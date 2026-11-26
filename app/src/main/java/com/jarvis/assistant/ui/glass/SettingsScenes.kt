package com.jarvis.assistant.ui.glass

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.JarvisApp
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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
    ARC_REACTOR("arc_reactor", "Arc Core", "Concentric holographic HUD core rings"),
    NEURAL_PULSE("neural_pulse", "Neural Pulse", "Synaptic signals across a living lattice"),
    QUANTUM_ORBIT("quantum_orbit", "Quantum Orbit", "Tilted 3D orbitals with gliding particles"),
    CYBER_MATRIX("cyber_matrix", "HUD Matrix", "Precision vector grid with sweeping beam"),
    SOLAR_CORONA("solar_corona", "Solar Corona", "Radiant plasma rays and luminous bloom"),
    HOLO_HELIX("holo_helix", "Holo Helix", "Twin harmonic wave ribbons with energy nodes"),
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

    private val mat = Matrix()
    private val ovalRect = RectF()
    private val arcRect = RectF()
    private val hudDash = DashPathEffect(floatArrayOf(4f * density.coerceAtLeast(1f), 9f * density.coerceAtLeast(1f)), 0f)
    private val fineDash = DashPathEffect(floatArrayOf(12f * density.coerceAtLeast(1f), 6f * density.coerceAtLeast(1f), 3f * density.coerceAtLeast(1f), 6f * density.coerceAtLeast(1f)), 0f)

    private var coreGlow: RadialGradient? = null
    private var coreHalo: RadialGradient? = null
    private var orbitGlow: RadialGradient? = null
    private val orbitGrads = arrayOfNulls<LinearGradient>(4)
    private val orbitTilts = floatArrayOf(-26f, 22f, -62f, 58f)
    private val orbitSpeeds = floatArrayOf(0.42f, -0.34f, 0.26f, -0.48f)
    private val orbitRxRatio = floatArrayOf(0.42f, 0.36f, 0.47f, 0.30f)
    private val orbitRyRatio = floatArrayOf(0.15f, 0.13f, 0.17f, 0.11f)

    private val neuralX = FloatArray(18)
    private val neuralY = FloatArray(18)
    private val neuralPhase = FloatArray(18)
    private val tmpX = FloatArray(18)
    private val tmpY = FloatArray(18)
    private val edgeA = IntArray(28)
    private val edgeB = IntArray(28)
    private var edgeCount = 0

    private var gridLines = FloatArray(0)
    private var sweepGrad: LinearGradient? = null
    private val reticleX = FloatArray(14)
    private val reticleY = FloatArray(14)

    private var coronaSweepA: SweepGradient? = null
    private var coronaSweepB: SweepGradient? = null
    private var coronaCore: RadialGradient? = null
    private val moteAngle = FloatArray(24)
    private val moteRadius = FloatArray(24)
    private val moteSpeed = FloatArray(24)
    private val moteSize = FloatArray(24)

    private val helixPathA = Path()
    private val helixPathB = Path()
    private var helixGradA: LinearGradient? = null
    private var helixGradB: LinearGradient? = null

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
        val nr = Random(17)
        for (i in neuralX.indices) {
            val col = i % 3
            val row = i / 3
            neuralX[i] = 0.14f + col * 0.36f + (nr.nextFloat() - 0.5f) * 0.16f
            neuralY[i] = 0.10f + row * 0.16f + (nr.nextFloat() - 0.5f) * 0.08f
            neuralPhase[i] = nr.nextFloat() * 6.28f
        }
        var ec = 0
        for (i in neuralX.indices) {
            for (j in i + 1 until neuralX.size) {
                if (ec >= edgeA.size) break
                val dist = hypot(neuralX[i] - neuralX[j], neuralY[i] - neuralY[j])
                if (dist < 0.40f) { edgeA[ec] = i; edgeB[ec] = j; ec++ }
            }
        }
        edgeCount = ec
        for (i in reticleX.indices) {
            reticleX[i] = 0.1f + nr.nextFloat() * 0.8f
            reticleY[i] = 0.1f + nr.nextFloat() * 0.8f
        }
        for (i in moteAngle.indices) {
            moteAngle[i] = nr.nextFloat() * 6.28f
            moteRadius[i] = 0.14f + nr.nextFloat() * 0.42f
            moteSpeed[i] = (0.08f + nr.nextFloat() * 0.18f) * (if (i % 2 == 0) 1f else -1f)
            moteSize[i] = 3f + nr.nextFloat() * 5.5f
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
        val small = min(w, h)
        coreGlow = RadialGradient(0f, 0f, small * 0.34f,
            intArrayOf(a(palette[4], (180 * k).toInt()), a(palette[0], (105 * k).toInt()), a(palette[1], (34 * k).toInt()), a(palette[0], 0)),
            floatArrayOf(0f, 0.32f, 0.68f, 1f), Shader.TileMode.CLAMP)
        coreHalo = RadialGradient(0f, 0f, big * 0.52f,
            intArrayOf(a(palette[0], (72 * k).toInt()), a(palette[2], (34 * k).toInt()), a(palette[0], 0)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        orbitGlow = RadialGradient(0f, 0f, small * 0.28f,
            intArrayOf(a(palette[4], (195 * k).toInt()), a(palette[0], (95 * k).toInt()), a(palette[1], 0)),
            floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
        for (i in 0 until 4) {
            val c1 = palette[i % 4]
            val c2 = palette[(i + 1) % 5]
            orbitGrads[i] = LinearGradient(-small * 0.5f, 0f, small * 0.5f, 0f,
                intArrayOf(a(c1, (40 * k).toInt()), a(c2, (165 * k).toInt()), a(palette[4], (190 * k).toInt()), a(c1, (40 * k).toInt())),
                floatArrayOf(0f, 0.35f, 0.68f, 1f), Shader.TileMode.CLAMP)
        }
        val cell = 42f * density.coerceAtLeast(1f)
        val cols = (w / cell).toInt() + 1
        val rows = (h / cell).toInt() + 1
        val lines = FloatArray((cols + rows) * 4)
        var idx = 0
        for (c in 0 until cols) { val x = c * cell; lines[idx++] = x; lines[idx++] = 0f; lines[idx++] = x; lines[idx++] = h }
        for (r in 0 until rows) { val y = r * cell; lines[idx++] = 0f; lines[idx++] = y; lines[idx++] = w; lines[idx++] = y }
        gridLines = lines
        val beamSpan = big * 0.28f
        sweepGrad = LinearGradient(-beamSpan, 0f, beamSpan, 0f,
            intArrayOf(a(palette[0], 0), a(palette[0], (48 * k).toInt()), a(palette[4], (96 * k).toInt()), a(palette[1], (48 * k).toInt()), a(palette[1], 0)),
            floatArrayOf(0f, 0.28f, 0.50f, 0.72f, 1f), Shader.TileMode.CLAMP)
        coronaSweepA = SweepGradient(0f, 0f,
            intArrayOf(a(palette[0], 0), a(palette[0], (115 * k).toInt()), a(palette[2], (52 * k).toInt()), a(palette[4], (125 * k).toInt()), a(palette[1], 0), a(palette[1], (105 * k).toInt()), a(palette[3], (58 * k).toInt()), a(palette[0], 0)),
            floatArrayOf(0f, 0.14f, 0.28f, 0.42f, 0.56f, 0.72f, 0.86f, 1f))
        coronaSweepB = SweepGradient(0f, 0f,
            intArrayOf(a(palette[1], 0), a(palette[3], (92 * k).toInt()), a(palette[4], 0), a(palette[2], (96 * k).toInt()), a(palette[1], 0)),
            floatArrayOf(0f, 0.25f, 0.50f, 0.75f, 1f))
        coronaCore = RadialGradient(0f, 0f, small * 0.44f,
            intArrayOf(a(palette[4], (185 * k).toInt()), a(palette[0], (95 * k).toInt()), a(palette[0], 0)),
            floatArrayOf(0f, 0.36f, 1f), Shader.TileMode.CLAMP)
        helixGradA = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(a(palette[0], (15 * k).toInt()), a(palette[0], (200 * k).toInt()), a(palette[4], (210 * k).toInt()), a(palette[2], (180 * k).toInt()), a(palette[0], (15 * k).toInt())),
            floatArrayOf(0f, 0.25f, 0.50f, 0.75f, 1f), Shader.TileMode.CLAMP)
        helixGradB = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(a(palette[1], (15 * k).toInt()), a(palette[3], (180 * k).toInt()), a(palette[1], (200 * k).toInt()), a(palette[4], (180 * k).toInt()), a(palette[1], (15 * k).toInt())),
            floatArrayOf(0f, 0.25f, 0.50f, 0.75f, 1f), Shader.TileMode.CLAMP)
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
            SettingsScene.ARC_REACTOR -> arcReactor(canvas, t, k, ink)
            SettingsScene.NEURAL_PULSE -> neuralPulse(canvas, t, k, ink)
            SettingsScene.QUANTUM_ORBIT -> quantumOrbit(canvas, t, k)
            SettingsScene.CYBER_MATRIX -> cyberMatrix(canvas, t, k, accent, ink)
            SettingsScene.SOLAR_CORONA -> solarCorona(canvas, t, k)
            SettingsScene.HOLO_HELIX -> holoHelix(canvas, t, k)
            else -> Unit
        }
        paint.shader = null
        paint.xfermode = null
        paint.alpha = 255
        stroke.shader = null
        stroke.pathEffect = null
        stroke.alpha = 255
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

    private fun arcReactor(c: Canvas, t: Float, k: Float, ink: Int) {
        val cx = w * (0.5f + 0.03f * sin(t * 0.14f))
        val cy = h * (0.43f + 0.025f * cos(t * 0.11f))
        val small = min(w, h)
        val big = max(w, h)
        val pulse = 1f + 0.06f * sin(t * 0.9f)

        c.save(); c.translate(cx, cy); c.scale(pulse, pulse)
        paint.shader = coreHalo
        c.drawCircle(0f, 0f, big * 0.52f, paint)
        paint.shader = coreGlow
        c.drawCircle(0f, 0f, small * 0.34f, paint)
        paint.shader = null
        c.restore()

        val radii = floatArrayOf(0.13f, 0.21f, 0.31f, 0.42f, 0.55f)
        val speeds = floatArrayOf(22f, -15f, 11f, -8f, 5f)
        for (ringIdx in radii.indices) {
            val r = small * radii[ringIdx]
            arcRect.set(cx - r, cy - r, cx + r, cy + r)
            stroke.pathEffect = null
            stroke.strokeWidth = 1.1f * density
            stroke.color = a(palette[ringIdx % 4], (46 * k).toInt())
            c.drawCircle(cx, cy, r, stroke)

            val rot = t * speeds[ringIdx] + ringIdx * 47f
            if (ringIdx == 1 || ringIdx == 3) {
                c.save(); c.rotate(rot, cx, cy)
                stroke.pathEffect = if (ringIdx == 1) hudDash else fineDash
                stroke.strokeWidth = (if (ringIdx == 1) 3.2f else 2.0f) * density
                stroke.color = a(palette[ringIdx % 5], (130 * k).toInt())
                c.drawCircle(cx, cy, r, stroke)
                c.restore()
            }

            stroke.pathEffect = null
            stroke.strokeWidth = (3.4f - ringIdx * 0.35f).coerceAtLeast(1.4f) * density
            stroke.color = a(palette[(ringIdx + 1) % 5], ((175 - ringIdx * 18) * k).toInt())
            val arcs = 3 + (ringIdx % 2)
            val span = 38f - ringIdx * 4f
            for (s in 0 until arcs) {
                val start = rot + s * (360f / arcs)
                c.drawArc(arcRect, start, span, false, stroke)
            }
        }
        stroke.pathEffect = null

        for (i in 0 until 8) {
            val r = small * radii[i % 4 + 1]
            val ang = Math.toRadians((t * speeds[i % 4 + 1] * 1.4f + i * 45f).toDouble())
            val px = cx + (cos(ang) * r).toFloat()
            val py = cy + (sin(ang) * r).toFloat()
            val glowR = 14f * density
            c.save(); c.translate(px, py); c.scale(glowR / 100f, glowR / 100f)
            paint.shader = fireSprites[i % 3]
            paint.alpha = (230 * k).toInt().coerceIn(0, 255)
            c.drawCircle(0f, 0f, 100f, paint)
            c.restore()
            paint.shader = null
            paint.color = a(ink, (180 * k).toInt())
            c.drawCircle(px, py, 2.0f * density, paint)
        }
        paint.alpha = 255
    }

    private fun neuralPulse(c: Canvas, t: Float, k: Float, ink: Int) {
        for (i in neuralX.indices) {
            tmpX[i] = w * (neuralX[i] + 0.025f * sin(t * 0.24f + neuralPhase[i]))
            tmpY[i] = h * (neuralY[i] + 0.022f * cos(t * 0.21f + neuralPhase[i] * 1.3f))
        }
        stroke.pathEffect = null
        for (e in 0 until edgeCount) {
            val aIdx = edgeA[e]
            val bIdx = edgeB[e]
            val x0 = tmpX[aIdx]; val y0 = tmpY[aIdx]
            val x1 = tmpX[bIdx]; val y1 = tmpY[bIdx]
            val bend = (if (e % 2 == 0) 1f else -1f) * 0.14f
            val mx = (x0 + x1) * 0.5f - (y1 - y0) * bend
            val my = (y0 + y1) * 0.5f + (x1 - x0) * bend

            path.reset()
            path.moveTo(x0, y0)
            path.quadTo(mx, my, x1, y1)
            stroke.strokeWidth = 1.4f * density
            stroke.color = a(palette[e % 4], (64 * k).toInt())
            c.drawPath(path, stroke)

            val u = ((t * (0.24f + (e % 5) * 0.05f) + e * 0.19f) % 1f)
            val inv = 1f - u
            val px = inv * inv * x0 + 2f * inv * u * mx + u * u * x1
            val py = inv * inv * y0 + 2f * inv * u * my + u * u * y1
            val glowR = 18f * density
            c.save(); c.translate(px, py); c.scale(glowR / 100f, glowR / 100f)
            paint.shader = fireSprites[e % 3]
            paint.alpha = (220 * k).toInt().coerceIn(0, 255)
            c.drawCircle(0f, 0f, 100f, paint)
            c.restore()
        }
        paint.shader = null
        for (i in neuralX.indices) {
            val pulse = 0.55f + 0.45f * sin(t * 1.1f + neuralPhase[i])
            val glowR = (16f + 8f * pulse) * density
            c.save(); c.translate(tmpX[i], tmpY[i]); c.scale(glowR / 100f, glowR / 100f)
            paint.shader = fireSprites[i % 3]
            paint.alpha = ((135 + 95 * pulse) * k).toInt().coerceIn(0, 255)
            c.drawCircle(0f, 0f, 100f, paint)
            c.restore()
            paint.shader = null
            paint.color = a(ink, ((140 + 80 * pulse) * k).toInt())
            c.drawCircle(tmpX[i], tmpY[i], 2.5f * density, paint)
        }
        paint.alpha = 255
    }

    private fun quantumOrbit(c: Canvas, t: Float, k: Float) {
        val cx = w * (0.5f + 0.04f * sin(t * 0.15f))
        val cy = h * (0.45f + 0.03f * cos(t * 0.12f))
        val small = min(w, h)

        c.save(); c.translate(cx, cy)
        paint.shader = orbitGlow
        c.drawCircle(0f, 0f, small * 0.28f, paint)
        paint.shader = null
        c.restore()

        stroke.pathEffect = null
        for (i in 0 until 4) {
            val rx = small * orbitRxRatio[i]
            val ry = small * orbitRyRatio[i]
            val tilt = orbitTilts[i] + 8f * sin(t * 0.14f + i)
            ovalRect.set(-rx, -ry, rx, ry)

            c.save(); c.translate(cx, cy); c.rotate(tilt)
            stroke.shader = orbitGrads[i]
            stroke.strokeWidth = 6f * density
            stroke.alpha = (46 * k).toInt().coerceIn(0, 255)
            c.drawOval(ovalRect, stroke)
            stroke.strokeWidth = 1.8f * density
            stroke.alpha = (185 * k).toInt().coerceIn(0, 255)
            c.drawOval(ovalRect, stroke)
            stroke.shader = null

            for (p in 0 until 3) {
                val theta = t * orbitSpeeds[i] + p * 2.094f + i * 0.7f
                val px = rx * cos(theta)
                val py = ry * sin(theta)
                val depth = 0.65f + 0.35f * sin(theta)
                val glowR = 20f * density * depth
                c.save(); c.translate(px, py); c.scale(glowR / 100f, glowR / 100f)
                paint.shader = fireSprites[(i + p) % 3]
                paint.alpha = (240 * depth * k).toInt().coerceIn(0, 255)
                c.drawCircle(0f, 0f, 100f, paint)
                c.restore()
            }
            c.restore()
        }
        paint.shader = null
        paint.alpha = 255
        stroke.alpha = 255
    }

    private fun cyberMatrix(c: Canvas, t: Float, k: Float, accent: Int, ink: Int) {
        stroke.pathEffect = null
        stroke.shader = null
        stroke.strokeWidth = 0.9f * density
        stroke.color = a(accent, (32 * k).toInt())
        if (gridLines.isNotEmpty()) c.drawLines(gridLines, stroke)

        val diag = hypot(w, h)
        val progress = ((t * 0.085f) % 1f) * 2.1f - 1.05f
        mat.setRotate(-32f)
        mat.postTranslate(w * 0.5f + progress * diag * 0.55f, h * 0.5f + progress * diag * 0.2f)
        sweepGrad?.setLocalMatrix(mat)
        paint.shader = sweepGrad
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        val arm = 6f * density
        stroke.strokeWidth = 1.4f * density
        for (i in reticleX.indices) {
            val rx = reticleX[i] * w
            val ry = reticleY[i] * h
            val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * 0.85f + i * 1.1f))
            stroke.color = a(palette[i % 4], (140 * tw * k).toInt())
            c.drawLine(rx - arm, ry, rx + arm, ry, stroke)
            c.drawLine(rx, ry - arm, rx, ry + arm, stroke)
            paint.color = a(ink, (110 * tw * k).toInt())
            c.drawCircle(rx, ry, 1.8f * density, paint)
        }
    }

    private fun solarCorona(c: Canvas, t: Float, k: Float) {
        val cx = w * (0.5f + 0.14f * sin(t * 0.09f))
        val cy = h * (0.39f + 0.09f * cos(t * 0.07f))
        val big = max(w, h)
        paint.xfermode = if (isLight) null else screen

        mat.setRotate(t * 7f)
        mat.postTranslate(cx, cy)
        coronaSweepA?.setLocalMatrix(mat)
        paint.shader = coronaSweepA
        c.drawRect(0f, 0f, w, h, paint)

        mat.setRotate(-t * 4.8f + 35f)
        mat.postTranslate(cx, cy)
        coronaSweepB?.setLocalMatrix(mat)
        paint.shader = coronaSweepB
        c.drawRect(0f, 0f, w, h, paint)

        paint.xfermode = null
        c.save(); c.translate(cx, cy)
        val breath = 1f + 0.10f * sin(t * 0.45f)
        c.scale(breath, breath)
        paint.shader = coronaCore
        c.drawCircle(0f, 0f, min(w, h) * 0.44f, paint)
        c.restore()
        paint.shader = null

        for (i in moteAngle.indices) {
            val ang = moteAngle[i] + t * moteSpeed[i]
            val r = big * moteRadius[i] * (0.94f + 0.06f * sin(t * 0.3f + i))
            val mx = cx + cos(ang) * r
            val my = cy + sin(ang) * r
            val glowR = moteSize[i] * density * 3.5f
            c.save(); c.translate(mx, my); c.scale(glowR / 100f, glowR / 100f)
            paint.shader = fireSprites[i % 3]
            paint.alpha = (195 * k).toInt().coerceIn(0, 255)
            c.drawCircle(0f, 0f, 100f, paint)
            c.restore()
        }
        paint.shader = null
        paint.alpha = 255
    }

    private fun holoHelix(c: Canvas, t: Float, k: Float) {
        val steps = 48
        val amp = w * 0.24f
        val cx = w * 0.5f
        val freq = 3.2f * PI.toFloat() / h
        val phaseShift = t * 0.55f

        helixPathA.reset()
        helixPathB.reset()
        for (i in 0..steps) {
            val u = i / steps.toFloat()
            val y = -h * 0.04f + u * h * 1.08f
            val angle = y * freq - phaseShift
            val xA = cx + amp * sin(angle)
            val xB = cx - amp * sin(angle)
            if (i == 0) {
                helixPathA.moveTo(xA, y)
                helixPathB.moveTo(xB, y)
            } else {
                helixPathA.lineTo(xA, y)
                helixPathB.lineTo(xB, y)
            }
        }

        stroke.pathEffect = null
        stroke.shader = null
        val rungs = 16
        for (r in 1 until rungs) {
            val y = h * (r / rungs.toFloat())
            val angle = y * freq - phaseShift
            val xA = cx + amp * sin(angle)
            val xB = cx - amp * sin(angle)
            val depth = 0.25f + 0.75f * kotlin.math.abs(cos(angle))
            stroke.strokeWidth = (1.6f * depth + 0.6f) * density
            stroke.color = a(palette[r % 4], (105 * depth * k).toInt())
            c.drawLine(xA, y, xB, y, stroke)

            if (r % 2 == 0) {
                val glowR = 14f * density * depth
                c.save(); c.translate(xA, y); c.scale(glowR / 100f, glowR / 100f)
                paint.shader = fireSprites[r % 3]
                paint.alpha = (215 * depth * k).toInt().coerceIn(0, 255)
                c.drawCircle(0f, 0f, 100f, paint)
                c.restore()
            }
        }
        paint.shader = null
        paint.alpha = 255

        val glowWidths = floatArrayOf(22f, 8f, 2.8f)
        val glowAlphas = floatArrayOf(38f, 95f, 210f)
        for (pass in 0 until 3) {
            stroke.strokeWidth = glowWidths[pass] * density
            stroke.alpha = (glowAlphas[pass] * k).toInt().coerceIn(0, 255)
            stroke.shader = helixGradA
            c.drawPath(helixPathA, stroke)
            stroke.shader = helixGradB
            c.drawPath(helixPathB, stroke)
        }
        stroke.shader = null
        stroke.alpha = 255
    }
}
