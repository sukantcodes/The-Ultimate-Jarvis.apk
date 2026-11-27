package com.jarvis.assistant.study

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Curated high-precision canonical STEM & Anatomy vector blueprints in pure Kotlin/JVM.
 * Whenever a student requests a foundational biology, anatomy, optics, chemistry, or earth-science diagram,
 * this atlas ensures textbook-accurate organic curves, multi-tone shading, realistic interior structures,
 * and properly sorted anchor pins so labels align in clean non-overlapping margin columns.
 */
object StudyDiagramAtlas {

    private fun poly(
        pts: List<Float>,
        ink: Ink,
        closed: Boolean = true,
        smooth: Boolean = true,
        fill: Boolean = true,
        fillInk: Ink = ink,
        shade: FillShade = FillShade.RADIAL,
        alpha: Float = 0.28f,
        stroke: Float = 1f,
        dashed: Boolean = false,
        arrow: Boolean = false,
        layer: Int = 0
    ) = DiagramSpec.Item.Poly(pts, closed, ink, smooth, fill, fillInk, dashed, arrow, shade, alpha, stroke, layer)

    private fun line(
        x1: Float, y1: Float, x2: Float, y2: Float,
        ink: Ink = Ink.INK,
        arrow: Boolean = false,
        doubleArrow: Boolean = false,
        dashed: Boolean = false,
        stroke: Float = 1f,
        layer: Int = 2
    ) = DiagramSpec.Item.Line(x1, y1, x2, y2, arrow, dashed, ink, doubleArrow, stroke, layer)

    private fun oval(
        cx: Float, cy: Float, rx: Float, ry: Float,
        ink: Ink,
        fillInk: Ink = ink,
        rot: Float = 0f,
        fill: Boolean = true,
        shade: FillShade = FillShade.RADIAL,
        alpha: Float = 0.30f,
        stroke: Float = 1f,
        dashed: Boolean = false,
        layer: Int = 0
    ) = DiagramSpec.Item.Oval(cx, cy, rx, ry, fill, ink, rot, fillInk, dashed, shade, alpha, stroke, layer)

    private fun circle(
        cx: Float, cy: Float, r: Float,
        ink: Ink,
        fillInk: Ink = ink,
        fill: Boolean = true,
        shade: FillShade = FillShade.RADIAL,
        alpha: Float = 0.32f,
        stroke: Float = 1f,
        dashed: Boolean = false,
        layer: Int = 0
    ) = DiagramSpec.Item.Circle(cx, cy, r, fill, ink, fillInk, dashed, shade, alpha, stroke, layer)

    private fun arc(
        cx: Float, cy: Float, rx: Float, ry: Float,
        start: Float, sweep: Float,
        ink: Ink,
        fillInk: Ink = ink,
        fill: Boolean = false,
        useCenter: Boolean = false,
        shade: FillShade = FillShade.RADIAL,
        alpha: Float = 0.26f,
        stroke: Float = 1f,
        dashed: Boolean = false,
        layer: Int = 2
    ) = DiagramSpec.Item.Arc(cx, cy, rx, ry, start, sweep, useCenter, fill, ink, fillInk, dashed, shade, alpha, stroke, layer)

    private fun rect(
        x: Float, y: Float, w: Float, h: Float,
        ink: Ink,
        fillInk: Ink = ink,
        rx: Float = 0f,
        fill: Boolean = true,
        shade: FillShade = FillShade.LINEAR,
        alpha: Float = 0.24f,
        stroke: Float = 1f,
        dashed: Boolean = false,
        layer: Int = 0
    ) = DiagramSpec.Item.Rect(x, y, w, h, fill, ink, rx, rx, fillInk, dashed, shade, alpha, stroke, layer)

    private fun lbl(ax: Float, ay: Float, text: String, ink: Ink, leftSide: Boolean): DiagramSpec.Item.Label =
        DiagramSpec.Item.Label(if (leftSide) 10f else 90f, ay, text, ink, anchorX = ax, anchorY = ay, badge = true)

    /**
     * Upgrades generic canonical diagrams with anatomically/scientifically faithful artwork while preserving all
     * mathematical function graphs and numerical physics/geometry specs.
     */
    fun enhance(question: String, spec: DiagramSpec): DiagramSpec {
        if (spec.graph != null) return spec
        val q = question.lowercase()
        val t = spec.title.lowercase()
        // Never override diagrams carrying custom physics numbers or labelled triangle vertices
        val hasNumericLabels = spec.items.filterIsInstance<DiagramSpec.Item.Label>().any { l ->
            Regex("\\d+\\.?\\d*\\s*(cm|mm|m|km|v|a|w|n|j|kg|s|hz|°|ohm|ω|Ω)", RegexOption.IGNORE_CASE).containsMatchIn(l.text) ||
                l.text.length <= 2
        }
        if (hasNumericLabels && spec.title.isBlank()) return spec

        return match(t) ?: match(q) ?: spec
    }

    fun match(rawText: String): DiagramSpec? {
        val t = rawText.lowercase()
        return when {
            hasAny(t, "brain", "cerebrum", "cerebellum", "medulla", "मस्तिष्क", "दिमाग", "dimag", "mastishk") -> humanBrain()
            hasAny(t, "heart", "cardiac", "ventricle", "atrium", "aorta", "हृदय", "hriday", "dil ka") -> humanHeart()
            hasAny(t, "human eye", "eye diagram", "parts of eye", "structure of eye", "cornea", "retina", "आँख", "aankh", "netra") -> humanEye()
            hasAny(t, "neuron", "nerve cell", "dendrite", "myelin", "axon", "तंत्रिका") -> neuron()
            hasAny(t, "plant cell", "पादप कोशिका") -> plantCell()
            hasAny(t, "animal cell", "eukaryotic cell", "जंतु कोशिका") -> animalCell()
            hasAny(t, "nephron", "bowman", "glomerulus") -> nephron()
            hasAny(t, "kidney", "renal", "excretory", "गुर्दा", "वृक्क") -> humanKidney()
            hasAny(t, "lung", "respiratory", "trachea", "bronch", "alveoli", "फेफड़", "श्वसन") -> humanLungs()
            hasAny(t, "mitochondri") -> mitochondria()
            hasAny(t, "chloroplast", "thylakoid", "grana") -> chloroplast()
            hasAny(t, "digestive", "alimentary", "stomach", "small intestine", "पाचन") -> digestiveSystem()
            hasAny(t, "human ear", "ear diagram", "parts of ear", "cochlea", "tympanic", "eardrum", "कान") -> humanEar()
            hasAny(t, "reflex arc", "spinal reflex", "knee jerk", "प्रतीप चाप") -> reflexArc()
            hasAny(t, "stomata", "guard cell", "leaf cross section") -> leafStomata()
            hasAny(t, "dna", "double helix", "nucleotide") -> dnaHelix()
            hasAny(t, "flower", "stamen", "pistil", "carpel", "ovary", "पुष्प", "फूल") -> flowerStructure()
            // Physics
            hasAny(t, "glass slab", "lateral displacement", "refraction through slab") -> glassSlabRefraction()
            hasAny(t, "prism", "dispersion", "vibgyor", "spectrum") -> glassPrism()
            hasAny(t, "concave lens", "diverging lens") -> concaveLensRays()
            hasAny(t, "convex lens", "converging lens") -> convexLensRays()
            hasAny(t, "concave mirror") -> concaveMirrorRays()
            hasAny(t, "ac generator", "alternating current generator", "dynamo") -> acGenerator()
            hasAny(t, "solenoid", "electromagnet") -> solenoidField()
            hasAny(t, "electric circuit", "ohm's law circuit", "ohm law circuit", "series circuit", "simple circuit") -> electricCircuit()
            hasAny(t, "magnetic field", "bar magnet", "magnetic lines") -> barMagnetField()
            hasAny(t, "transverse wave", "wavelength", "amplitude", "wave diagram") -> transverseWave()
            hasAny(t, "inclined plane", "free body", "wedge") -> inclinedPlane()
            hasAny(t, "pendulum") -> simplePendulum()
            hasAny(t, "dc motor", "electric motor") -> dcMotor()
            // Chemistry
            hasAny(t, "benzene", "c6h6", "aromatic ring") -> benzeneRing()
            hasAny(t, "water molecule", "h2o structure", "bent structure of water") -> waterMolecule()
            hasAny(t, "distillation") -> simpleDistillation()
            hasAny(t, "electrolysis", "electrolytic cell") -> electrolysisCell()
            hasAny(t, "ph scale", "ph chart", "acid base scale") -> phScale()
            hasAny(t, "bohr", "structure of atom", "atomic structure", "atom model", "nucleus and electron") -> bohrAtom()
            // Mathematics
            hasAny(t, "pythagoras", "pythagorean theorem") -> pythagorasSquares()
            hasAny(t, "trigonometry", "right triangle", "right-angled triangle", "sin cos tan") -> rightTriangleTrig()
            hasAny(t, "parts of circle", "parts of a circle", "sector and segment", "tangent and secant", "chord and diameter") -> circleParts()
            hasAny(t, "cone", "slant height") -> cone3D()
            hasAny(t, "cylinder") -> cylinder3D()
            hasAny(t, "venn diagram", "set intersection") -> vennDiagram()
            // Computer Science
            hasAny(t, "binary tree", "binary search tree", "bst") -> binaryTree()
            hasAny(t, "linked list") -> linkedList()
            hasAny(t, "stack and queue", "lifo", "fifo") -> stackAndQueue()
            hasAny(t, "osi model", "7 layer", "seven layer", "tcp/ip") -> osiModel()
            hasAny(t, "cpu", "von neumann", "block diagram of computer", "computer architecture") -> cpuArchitecture()
            hasAny(t, "neural network", "perceptron", "deep learning architecture") -> neuralNetwork()
            hasAny(t, "logic gate", "and or not gate") -> logicGates()
            // Geography / Earth & Space Science
            hasAny(t, "solar system", "planets", "heliocentric", "सौर मंडल") -> solarSystem()
            hasAny(t, "carbon cycle", "कार्बन चक्र") -> carbonCycle()
            hasAny(t, "layers of earth", "layers of the earth", "interior of earth", "earth crust", "mantle and core") -> earthLayers()
            hasAny(t, "water cycle", "hydrologic cycle", "evaporation and condensation", "जल चक्र") -> waterCycle()
            else -> null
        }
    }

    private fun hasAny(text: String, vararg keys: String): Boolean = keys.any { it in text }

    // ---------------------------------------------------------------------------------------------------------
    // 1. Human Brain (Sagittal + Lateral Lobes, Gyri Convolutions, Corpus Callosum, Cerebellum Arbor Vitae)
    // ---------------------------------------------------------------------------------------------------------

    fun humanBrain(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()

        // Cranial halo subtle boundary
        items += oval(51f, 44f, 34f, 32f, Ink.FAINT, fill = false, stroke = 0.7f, dashed = true, layer = 0)

        // Primary Cerebrum Silhouette (Frontal -> Parietal -> Occipital -> Temporal base)
        items += poly(
            listOf(
                20f, 44f, 21f, 31f, 28f, 20f, 40f, 14f, 56f, 13f,
                71f, 19f, 80f, 31f, 82f, 44f, 77f, 55f, 67f, 57f,
                57f, 56f, 49f, 61f, 37f, 61f, 27f, 55f, 21f, 49f
            ),
            ink = Ink.INK, fillInk = Ink.ROSE, shade = FillShade.RADIAL, alpha = 0.24f, stroke = 1.25f, layer = 0
        )

        // Frontal, Parietal & Occipital functional region colour washes
        items += poly(
            listOf(21f, 44f, 22f, 31f, 29f, 21f, 42f, 15f, 48f, 24f, 44f, 43f, 30f, 47f, 22f, 48f),
            ink = Ink.WARN, fillInk = Ink.WARN, alpha = 0.16f, stroke = 0.55f, layer = 1
        )
        items += poly(
            listOf(42f, 15f, 56f, 14f, 69f, 20f, 67f, 36f, 54f, 38f, 45f, 36f, 48f, 24f),
            ink = Ink.GOLD, fillInk = Ink.GOLD, alpha = 0.18f, stroke = 0.55f, layer = 1
        )
        items += poly(
            listOf(69f, 20f, 79f, 31f, 81f, 44f, 76f, 54f, 65f, 54f, 64f, 38f),
            ink = Ink.TEAL, fillInk = Ink.TEAL, alpha = 0.20f, stroke = 0.55f, layer = 1
        )

        // Temporal Lobe overlapping ventral fold
        items += poly(
            listOf(33f, 48f, 44f, 44f, 58f, 44f, 65f, 49f, 62f, 58f, 50f, 62f, 37f, 60f, 31f, 54f),
            ink = Ink.INK, fillInk = Ink.VIOLET, shade = FillShade.LINEAR, alpha = 0.22f, stroke = 1.0f, layer = 0
        )

        // Brainstem (Midbrain -> anterior curved Pons -> Medulla Oblongata -> Spinal Cord)
        items += poly(
            listOf(46f, 48f, 52f, 48f, 55f, 58f, 57f, 70f, 58f, 88f, 51f, 88f, 50f, 74f, 46f, 66f, 44f, 59f, 47f, 53f),
            ink = Ink.INK, fillInk = Ink.ORANGE, shade = FillShade.LINEAR, alpha = 0.28f, stroke = 1.1f, layer = 0
        )

        // Cerebellum (posterior cranial fossa oval lobe)
        items += oval(66f, 63f, 11.5f, 9.2f, ink = Ink.INK, fillInk = Ink.VIOLET, rot = -12f, shade = FillShade.RADIAL, alpha = 0.34f, stroke = 1.15f, layer = 0)

        // Corpus Callosum (C-shaped commissural arch)
        items += poly(
            listOf(36f, 37f, 42f, 29f, 54f, 27f, 63f, 32f, 61f, 38f, 52f, 33f, 42f, 35f, 38f, 40f),
            ink = Ink.INK, fillInk = Ink.GOLD, shade = FillShade.LINEAR, alpha = 0.55f, stroke = 1.0f, layer = 0
        )

        // Thalamus, Hypothalamus, & Pituitary Gland inside diencephalon
        items += oval(50f, 40f, 6.2f, 4.2f, ink = Ink.ACCENT, fillInk = Ink.ACCENT, alpha = 0.42f, stroke = 0.95f, layer = 0)
        items += poly(
            listOf(43f, 42f, 48f, 44f, 46f, 50f, 43f, 50f, 41f, 46f),
            ink = Ink.TEAL, fillInk = Ink.TEAL, alpha = 0.46f, stroke = 0.9f, layer = 0
        )
        items += line(44.5f, 50f, 44.5f, 55.5f, ink = Ink.INK, stroke = 0.9f, layer = 2)
        items += circle(44.5f, 57.5f, 2.5f, ink = Ink.INK, fillInk = Ink.WARN, alpha = 0.65f, stroke = 0.9f, layer = 0)

        // Realistic Cortical Sulci & Gyri convolutions (organic wavy curves across lobes)
        items += poly(listOf(48f, 15f, 46f, 24f, 47f, 33f, 44f, 41f), Ink.INK, closed = false, fill = false, stroke = 0.95f, layer = 2) // Central sulcus
        items += poly(listOf(32f, 48f, 45f, 44f, 59f, 44f, 66f, 48f), Ink.INK, closed = false, fill = false, stroke = 1.0f, layer = 2)  // Lateral sulcus
        val sulci = listOf(
            listOf(25f, 31f, 31f, 28f, 36f, 33f, 33f, 38f),
            listOf(27f, 24f, 34f, 21f, 40f, 25f),
            listOf(24f, 40f, 30f, 38f, 33f, 43f),
            listOf(36f, 18f, 41f, 20f, 42f, 27f),
            listOf(52f, 16f, 56f, 22f, 53f, 28f),
            listOf(60f, 17f, 62f, 24f, 66f, 27f),
            listOf(67f, 23f, 65f, 31f, 70f, 34f),
            listOf(73f, 30f, 71f, 38f, 75f, 42f),
            listOf(70f, 42f, 74f, 47f, 69f, 52f),
            listOf(38f, 52f, 46f, 50f, 53f, 53f),
            listOf(42f, 57f, 50f, 56f, 57f, 58f)
        )
        for (s in sulci) {
            items += poly(s, Ink.ROSE, closed = false, fill = false, stroke = 0.72f, layer = 2)
        }

        // Cerebellum Arbor Vitae ("Tree of Life" white matter arborization) & folia ripples
        items += poly(listOf(59f, 64f, 65f, 62f, 71f, 58f), Ink.INK, closed = false, fill = false, stroke = 0.8f, layer = 2)
        items += poly(listOf(60f, 65f, 68f, 65f, 75f, 64f), Ink.INK, closed = false, fill = false, stroke = 0.8f, layer = 2)
        items += poly(listOf(60f, 66f, 66f, 69f, 71f, 71f), Ink.INK, closed = false, fill = false, stroke = 0.8f, layer = 2)
        items += arc(66f, 63f, 9.5f, 7.2f, -30f, 110f, Ink.VIOLET, fill = false, stroke = 0.7f, layer = 2)
        items += line(53.5f, 52f, 54.5f, 86f, Ink.BROWN, dashed = true, stroke = 0.75f, layer = 2) // Central canal/tract

        // 12 Sorted External Anatomical Callouts (Left 6 + Right 6)
        items += lbl(30f, 28f, "Frontal Lobe", Ink.WARN, leftSide = true)
        items += lbl(42f, 32f, "Corpus Callosum", Ink.GOLD, leftSide = true)
        items += lbl(47f, 40f, "Thalamus", Ink.ACCENT, leftSide = true)
        items += lbl(43f, 47f, "Hypothalamus", Ink.TEAL, leftSide = true)
        items += lbl(44.5f, 57.5f, "Pituitary Gland", Ink.WARN, leftSide = true)
        items += lbl(46f, 61f, "Pons", Ink.ORANGE, leftSide = true)

        items += lbl(59f, 20f, "Parietal Lobe", Ink.GOLD, leftSide = false)
        items += lbl(75f, 39f, "Occipital Lobe", Ink.TEAL, leftSide = false)
        items += lbl(59f, 51f, "Temporal Lobe", Ink.VIOLET, leftSide = false)
        items += lbl(69f, 64f, "Cerebellum", Ink.VIOLET, leftSide = false)
        items += lbl(55f, 72f, "Medulla Oblongata", Ink.BROWN, leftSide = false)
        items += lbl(55f, 84f, "Spinal Cord", Ink.BROWN, leftSide = false)

        return DiagramSpec("Human Brain (Sagittal & Lobes)", items)
    }

    // ---------------------------------------------------------------------------------------------------------
    // 2. Human Heart (4 Chambers, Septum, Valves, Great Vessels, Flow Arrows)
    // ---------------------------------------------------------------------------------------------------------

    fun humanHeart(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        // Great vessels entering top
        items += rect(34f, 12f, 7f, 20f, Ink.ACCENT, fillInk = Ink.ACCENT, rx = 2.5f, alpha = 0.35f, stroke = 1.0f) // Sup Vena Cava
        items += rect(35f, 64f, 6.5f, 18f, Ink.ACCENT, fillInk = Ink.ACCENT, rx = 2.5f, alpha = 0.35f, stroke = 1.0f) // Inf Vena Cava
        // Aorta arch with 3 branches
        items += poly(
            listOf(49f, 34f, 47f, 18f, 55f, 12f, 65f, 16f, 66f, 27f, 59f, 24f, 54f, 26f, 54f, 38f),
            Ink.WARN, fillInk = Ink.WARN, alpha = 0.38f, stroke = 1.15f
        )
        items += line(52f, 13f, 51f, 6f, Ink.WARN, stroke = 1.4f, layer = 2)
        items += line(56f, 12f, 56f, 5f, Ink.WARN, stroke = 1.4f, layer = 2)
        items += line(60f, 13f, 62f, 7f, Ink.WARN, stroke = 1.4f, layer = 2)

        // Pulmonary Artery T-branch
        items += poly(
            listOf(47f, 44f, 46f, 29f, 34f, 26f, 34f, 31f, 44f, 33f, 54f, 29f, 67f, 27f, 67f, 32f, 54f, 34f),
            Ink.TEAL, fillInk = Ink.ACCENT, alpha = 0.30f, stroke = 1.05f
        )

        // Outer Myocardium Heart Contour
        items += poly(
            listOf(33f, 33f, 48f, 28f, 64f, 31f, 74f, 45f, 71f, 66f, 56f, 83f, 39f, 76f, 30f, 57f),
            Ink.INK, fillInk = Ink.ROSE, shade = FillShade.RADIAL, alpha = 0.24f, stroke = 1.3f
        )

        // Right Atrium + Right Ventricle (Deoxygenated Blue)
        items += oval(38f, 41f, 6.5f, 7.2f, Ink.ACCENT, fillInk = Ink.ACCENT, alpha = 0.38f, stroke = 0.95f)
        items += poly(
            listOf(35f, 51f, 47f, 50f, 49f, 76f, 40f, 71f, 34f, 58f),
            Ink.ACCENT, fillInk = Ink.ACCENT, alpha = 0.38f, stroke = 0.95f
        )
        // Left Atrium + Left Ventricle (Oxygenated Red)
        items += oval(62f, 42f, 6.5f, 7.2f, Ink.WARN, fillInk = Ink.WARN, alpha = 0.38f, stroke = 0.95f)
        items += poly(
            listOf(52f, 50f, 66f, 51f, 67f, 65f, 56f, 79f, 52f, 76f),
            Ink.WARN, fillInk = Ink.WARN, alpha = 0.40f, stroke = 1.0f
        )

        // Interventricular Septum & Valves
        items += rect(49f, 50f, 3f, 27f, Ink.ROSE, fillInk = Ink.ROSE, rx = 1.2f, alpha = 0.45f, stroke = 0.9f, layer = 2)
        items += poly(listOf(35f, 49f, 41f, 52f, 46f, 49f), Ink.GOLD, closed = false, fill = false, stroke = 1.15f, layer = 2)
        items += poly(listOf(53f, 49f, 59f, 52f, 65f, 49f), Ink.GOLD, closed = false, fill = false, stroke = 1.15f, layer = 2)

        // Pulmonary Veins & Blood flow arrows
        items += line(76f, 40f, 67f, 41f, Ink.WARN, arrow = true, stroke = 1.1f, layer = 2)
        items += line(76f, 45f, 67f, 45f, Ink.WARN, arrow = true, stroke = 1.1f, layer = 2)
        items += line(37.5f, 16f, 37.5f, 36f, Ink.ACCENT, arrow = true, stroke = 0.95f, layer = 2)
        items += line(39f, 45f, 41f, 56f, Ink.ACCENT, arrow = true, stroke = 0.95f, layer = 2)
        items += line(44f, 65f, 46f, 35f, Ink.ACCENT, arrow = true, stroke = 0.95f, layer = 2)
        items += line(61f, 45f, 60f, 56f, Ink.WARN, arrow = true, stroke = 0.95f, layer = 2)
        items += line(56f, 68f, 52f, 36f, Ink.WARN, arrow = true, stroke = 0.95f, layer = 2)

        items += lbl(37.5f, 20f, "Superior Vena Cava", Ink.ACCENT, leftSide = true)
        items += lbl(38f, 40f, "Right Atrium", Ink.ACCENT, leftSide = true)
        items += lbl(41f, 51f, "Tricuspid Valve", Ink.GOLD, leftSide = true)
        items += lbl(40f, 64f, "Right Ventricle", Ink.ACCENT, leftSide = true)
        items += lbl(38f, 75f, "Inferior Vena Cava", Ink.ACCENT, leftSide = true)

        items += lbl(56f, 13f, "Aorta", Ink.WARN, leftSide = false)
        items += lbl(62f, 29f, "Pulmonary Artery", Ink.TEAL, leftSide = false)
        items += lbl(67f, 43f, "Left Atrium", Ink.WARN, leftSide = false)
        items += lbl(59f, 51f, "Mitral (Bicuspid) Valve", Ink.GOLD, leftSide = false)
        items += lbl(50.5f, 63f, "Septum", Ink.ROSE, leftSide = false)
        items += lbl(61f, 70f, "Left Ventricle", Ink.WARN, leftSide = false)

        return DiagramSpec("Human Heart (Longitudinal Section)", items)
    }

    // ---------------------------------------------------------------------------------------------------------
    // 3. Human Eye (Sclera, Choroid, Retina, Cornea, Lens, Iris, Optic Nerve, Refraction Rays)
    // ---------------------------------------------------------------------------------------------------------

    fun humanEye(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        // Eyeball concentric coats: Outer Sclera, Middle Choroid, Inner Retina
        items += circle(52f, 50f, 27f, Ink.INK, fillInk = Ink.FAINT, alpha = 0.16f, stroke = 1.3f, layer = 0)
        items += arc(52f, 50f, 25f, 25f, -138f, 276f, Ink.ROSE, fillInk = Ink.ROSE, fill = true, alpha = 0.14f, stroke = 1.15f, layer = 0)
        items += arc(52f, 50f, 23f, 23f, -135f, 270f, Ink.GOLD, fillInk = Ink.GOLD, fill = true, alpha = 0.18f, stroke = 1.0f, layer = 0)

        // Bulging Cornea (anterior dome left side)
        items += arc(31f, 50f, 11f, 15.5f, 112f, 136f, Ink.TEAL, fillInk = Ink.TEAL, fill = true, alpha = 0.24f, stroke = 1.25f, layer = 0)

        // Iris diaphragms & central Pupil
        items += rect(29f, 36f, 3.2f, 9.5f, Ink.ACCENT, fillInk = Ink.ACCENT, rx = 1.4f, alpha = 0.65f, stroke = 0.9f, layer = 0)
        items += rect(29f, 54.5f, 3.2f, 9.5f, Ink.ACCENT, fillInk = Ink.ACCENT, rx = 1.4f, alpha = 0.65f, stroke = 0.9f, layer = 0)

        // Biconvex Crystalline Lens + Ciliary suspensory fibres
        items += oval(36f, 50f, 4.4f, 10.5f, Ink.INK, fillInk = Ink.TEAL, alpha = 0.38f, stroke = 1.1f, layer = 0)
        items += line(36f, 30f, 36f, 39.5f, Ink.NOTE, dashed = true, stroke = 0.8f, layer = 2)
        items += line(36f, 60.5f, 36f, 70f, Ink.NOTE, dashed = true, stroke = 0.8f, layer = 2)

        // Optic Nerve exiting right
        items += poly(
            listOf(76f, 45f, 89f, 48f, 89f, 57f, 76f, 55f),
            Ink.INK, fillInk = Ink.GOLD, alpha = 0.35f, stroke = 1.1f, layer = 0
        )

        // Incoming parallel rays refracted to Fovea on Retina
        items += line(12f, 43f, 35f, 43f, Ink.GOLD, arrow = true, stroke = 1.0f, layer = 2)
        items += line(35f, 43f, 75f, 50f, Ink.GOLD, arrow = true, stroke = 1.0f, layer = 2)
        items += line(12f, 57f, 35f, 57f, Ink.GOLD, arrow = true, stroke = 1.0f, layer = 2)
        items += line(35f, 57f, 75f, 50f, Ink.GOLD, arrow = true, stroke = 1.0f, layer = 2)

        items += lbl(21f, 46f, "Cornea", Ink.TEAL, leftSide = true)
        items += lbl(30.5f, 39f, "Iris", Ink.ACCENT, leftSide = true)
        items += lbl(30.5f, 50f, "Pupil", Ink.INK, leftSide = true)
        items += lbl(36f, 56f, "Crystalline Lens", Ink.TEAL, leftSide = true)
        items += lbl(36f, 67f, "Ciliary Muscles", Ink.ROSE, leftSide = true)

        items += lbl(55f, 24f, "Sclera", Ink.INK, leftSide = false)
        items += lbl(67f, 30f, "Choroid", Ink.ROSE, leftSide = false)
        items += lbl(71f, 40f, "Retina", Ink.GOLD, leftSide = false)
        items += lbl(60f, 55f, "Vitreous Humor", Ink.ACCENT, leftSide = false)
        items += lbl(84f, 52f, "Optic Nerve", Ink.GOLD, leftSide = false)

        return DiagramSpec("Human Eye (Cross-Section)", items)
    }

    // ---------------------------------------------------------------------------------------------------------
    // 4. Neuron (Nerve Cell)
    // ---------------------------------------------------------------------------------------------------------

    fun neuron(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        // Star-like Cell Body (Soma)
        items += poly(
            listOf(22f, 42f, 27f, 34f, 36f, 36f, 42f, 47f, 38f, 57f, 28f, 61f, 22f, 53f),
            Ink.VIOLET, fillInk = Ink.VIOLET, alpha = 0.28f, stroke = 1.15f, layer = 0
        )
        // Dendrite tree branches left
        val dendrites = listOf(
            listOf(22f, 42f, 15f, 35f, 10f, 30f),
            listOf(15f, 35f, 11f, 40f),
            listOf(27f, 34f, 24f, 24f, 20f, 18f),
            listOf(24f, 24f, 31f, 21f),
            listOf(36f, 36f, 40f, 26f, 45f, 21f),
            listOf(22f, 53f, 14f, 54f, 9f, 59f),
            listOf(28f, 61f, 23f, 70f, 19f, 75f),
            listOf(23f, 70f, 30f, 74f)
        )
        for (d in dendrites) items += poly(d, Ink.VIOLET, closed = false, fill = false, stroke = 0.95f, layer = 2)

        // Nucleus & Nucleolus inside Soma
        items += circle(30f, 48f, 5.8f, Ink.INK, fillInk = Ink.ACCENT, alpha = 0.45f, stroke = 1.0f, layer = 0)
        items += circle(30.5f, 48f, 2.2f, Ink.INK, fillInk = Ink.VIOLET, alpha = 0.8f, stroke = 0.8f, layer = 2)

        // Core Axon fiber extending right
        items += line(40f, 50f, 80f, 50f, Ink.INK, stroke = 1.6f, layer = 2)

        // Myelinated Schwann capsules separated by Nodes of Ranvier
        for (sx in listOf(45f, 55.5f, 66f)) {
            items += rect(sx, 45.5f, 8.5f, 9f, Ink.GOLD, fillInk = Ink.GOLD, rx = 4.2f, alpha = 0.36f, stroke = 1.0f, layer = 0)
            items += oval(sx + 4.2f, 47.5f, 1.4f, 0.9f, Ink.INK, fillInk = Ink.INK, alpha = 0.7f, stroke = 0.6f, layer = 2)
        }
        // Impulse travel arrow
        items += line(46f, 39f, 72f, 39f, Ink.TEAL, arrow = true, stroke = 1.05f, layer = 2)

        // Branched Axon Terminals + Synaptic knobs
        val terms = listOf(
            listOf(80f, 50f, 85f, 40f, 90f, 36f),
            listOf(80f, 50f, 87f, 47f, 91f, 46f),
            listOf(80f, 50f, 86f, 56f, 91f, 58f),
            listOf(80f, 50f, 83f, 62f, 88f, 66f)
        )
        for (tr in terms) {
            items += poly(tr, Ink.ROSE, closed = false, fill = false, stroke = 0.95f, layer = 2)
            items += circle(tr[tr.size - 2], tr[tr.size - 1], 1.5f, Ink.WARN, fillInk = Ink.WARN, alpha = 0.75f, stroke = 0.8f, layer = 2)
        }

        items += lbl(14f, 30f, "Dendrites", Ink.VIOLET, leftSide = true)
        items += lbl(30f, 48f, "Nucleus", Ink.ACCENT, leftSide = true)
        items += lbl(29f, 57f, "Cell Body (Soma)", Ink.VIOLET, leftSide = true)

        items += lbl(59f, 39f, "Nerve Impulse", Ink.TEAL, leftSide = false)
        items += lbl(59.5f, 46f, "Myelin Sheath", Ink.GOLD, leftSide = false)
        items += lbl(64.5f, 50f, "Node of Ranvier", Ink.INK, leftSide = false)
        items += lbl(42f, 51f, "Axon", Ink.BROWN, leftSide = false)
        items += lbl(90f, 58f, "Axon Terminals", Ink.ROSE, leftSide = false)

        return DiagramSpec("Structure of a Neuron", items)
    }

    // ---------------------------------------------------------------------------------------------------------
    // 5. Plant Cell & Animal Cell
    // ---------------------------------------------------------------------------------------------------------

    fun plantCell(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        // Outer rigid hexagonal Cell Wall + Plasma Membrane
        val wall = listOf(28f, 16f, 72f, 16f, 84f, 50f, 72f, 84f, 28f, 84f, 16f, 50f)
        items += poly(wall, Ink.RESULT, smooth = false, fill = true, fillInk = Ink.RESULT, shade = FillShade.LINEAR, alpha = 0.22f, stroke = 1.4f, layer = 0)
        val mem = listOf(30f, 20f, 70f, 20f, 80f, 50f, 70f, 80f, 30f, 80f, 20f, 50f)
        items += poly(mem, Ink.GOLD, smooth = false, fill = true, fillInk = Ink.GOLD, shade = FillShade.RADIAL, alpha = 0.14f, stroke = 1.0f, layer = 0)

        // Large central Vacuole
        items += poly(
            listOf(42f, 28f, 67f, 26f, 72f, 44f, 66f, 64f, 44f, 62f, 39f, 42f),
            Ink.TEAL, fillInk = Ink.TEAL, shade = FillShade.RADIAL, alpha = 0.28f, stroke = 1.05f, layer = 0
        )

        // Nucleus + Nucleolus + ER
        items += circle(33f, 58f, 9.5f, Ink.VIOLET, fillInk = Ink.VIOLET, alpha = 0.36f, stroke = 1.1f, layer = 0)
        items += circle(33.5f, 58f, 3.4f, Ink.INK, fillInk = Ink.VIOLET, alpha = 0.78f, stroke = 0.8f, layer = 2)
        items += poly(listOf(24f, 49f, 29f, 46f, 37f, 46f, 42f, 50f), Ink.ACCENT, closed = false, fill = false, stroke = 0.85f, layer = 2)

        // Chloroplasts with stacked Grana discs
        for ((cx, cy, ang) in listOf(Triple(35f, 31f, -20f), Triple(68f, 70f, 18f), Triple(50f, 72f, -8f))) {
            items += oval(cx, cy, 5.5f, 3.0f, Ink.RESULT, fillInk = Ink.RESULT, rot = ang, alpha = 0.55f, stroke = 0.95f, layer = 0)
            items += line(cx - 2.5f, cy, cx + 2.5f, cy, Ink.INK, stroke = 0.75f, layer = 2)
        }
        // Mitochondria with cristae zigzag
        for ((mx, my) in listOf(58f to 23f, 32f to 73f)) {
            items += oval(mx, my, 4.8f, 2.5f, Ink.WARN, fillInk = Ink.ORANGE, alpha = 0.48f, stroke = 0.9f, layer = 0)
            items += poly(listOf(mx - 3f, my, mx - 1.5f, my - 1.1f, mx, my + 1.1f, mx + 1.5f, my - 1.1f, mx + 3f, my), Ink.WARN, closed = false, fill = false, stroke = 0.65f, layer = 2)
        }
        // Golgi Apparatus
        items += arc(71f, 55f, 4.5f, 2.2f, 20f, 140f, Ink.ORANGE, stroke = 0.9f, layer = 2)
        items += arc(72f, 58f, 4.0f, 2.0f, 20f, 140f, Ink.ORANGE, stroke = 0.9f, layer = 2)

        items += lbl(22f, 30f, "Cell Wall", Ink.RESULT, leftSide = true)
        items += lbl(25f, 38f, "Plasma Membrane", Ink.GOLD, leftSide = true)
        items += lbl(35f, 31f, "Chloroplast", Ink.RESULT, leftSide = true)
        items += lbl(33f, 58f, "Nucleus & Nucleolus", Ink.VIOLET, leftSide = true)
        items += lbl(32f, 73f, "Mitochondrion", Ink.WARN, leftSide = true)

        items += lbl(56f, 44f, "Central Vacuole", Ink.TEAL, leftSide = false)
        items += lbl(71f, 56f, "Golgi Apparatus", Ink.ORANGE, leftSide = false)
        items += lbl(68f, 70f, "Cytoplasm", Ink.GOLD, leftSide = false)

        return DiagramSpec("Plant Cell (Ultrastructure)", items)
    }

    fun animalCell(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += poly(
            listOf(24f, 36f, 34f, 20f, 54f, 17f, 74f, 26f, 80f, 48f, 74f, 71f, 54f, 82f, 32f, 77f, 21f, 58f),
            Ink.ACCENT, fillInk = Ink.GOLD, shade = FillShade.RADIAL, alpha = 0.18f, stroke = 1.25f, layer = 0
        )
        items += circle(50f, 48f, 11f, Ink.VIOLET, fillInk = Ink.VIOLET, alpha = 0.36f, stroke = 1.15f, layer = 0)
        items += circle(51f, 48f, 4f, Ink.INK, fillInk = Ink.VIOLET, alpha = 0.78f, stroke = 0.8f, layer = 2)
        // ER folds around nucleus
        items += arc(50f, 48f, 15f, 14f, 115f, 120f, Ink.TEAL, stroke = 0.95f, layer = 2)
        items += arc(50f, 48f, 18f, 16f, 125f, 95f, Ink.TEAL, stroke = 0.95f, layer = 2)
        // Mitochondria
        for ((mx, my, ang) in listOf(Triple(35f, 33f, -25f), Triple(68f, 62f, 20f))) {
            items += oval(mx, my, 5.2f, 2.7f, Ink.WARN, fillInk = Ink.ORANGE, rot = ang, alpha = 0.45f, stroke = 0.9f, layer = 0)
        }
        // Lysosomes & Centrosome
        items += circle(65f, 34f, 3.2f, Ink.ROSE, fillInk = Ink.ROSE, alpha = 0.5f, stroke = 0.85f, layer = 0)
        items += rect(34f, 61f, 3f, 1.5f, Ink.RESULT, fillInk = Ink.RESULT, rx = 0.6f, alpha = 0.7f, layer = 2)
        items += rect(36f, 59f, 1.5f, 3f, Ink.RESULT, fillInk = Ink.RESULT, rx = 0.6f, alpha = 0.7f, layer = 2)

        items += lbl(35f, 33f, "Mitochondrion", Ink.WARN, leftSide = true)
        items += lbl(34f, 48f, "Endoplasmic Reticulum", Ink.TEAL, leftSide = true)
        items += lbl(35f, 61f, "Centrioles", Ink.RESULT, leftSide = true)
        items += lbl(26f, 70f, "Plasma Membrane", Ink.ACCENT, leftSide = true)

        items += lbl(65f, 34f, "Lysosome", Ink.ROSE, leftSide = false)
        items += lbl(50f, 48f, "Nucleus & Nucleolus", Ink.VIOLET, leftSide = false)
        items += lbl(68f, 62f, "Cytoplasm", Ink.GOLD, leftSide = false)

        return DiagramSpec("Animal Cell (Ultrastructure)", items)
    }

    // ---------------------------------------------------------------------------------------------------------
    // 6. Human Kidney & Nephron
    // ---------------------------------------------------------------------------------------------------------

    fun humanKidney(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += poly(
            listOf(34f, 34f, 38f, 20f, 56f, 17f, 70f, 29f, 74f, 50f, 70f, 71f, 55f, 83f, 39f, 79f, 34f, 64f, 42f, 53f, 42f, 46f),
            Ink.INK, fillInk = Ink.ROSE, shade = FillShade.RADIAL, alpha = 0.30f, stroke = 1.25f, layer = 0
        )
        // Medullary Pyramids
        for (py in listOf(listOf(55f, 25f, 62f, 30f, 51f, 39f), listOf(64f, 38f, 67f, 49f, 52f, 49f), listOf(64f, 59f, 57f, 72f, 51f, 57f))) {
            items += poly(py, Ink.WARN, fillInk = Ink.WARN, smooth = false, alpha = 0.42f, stroke = 0.9f, layer = 0)
        }
        // Renal Pelvis & Ureter
        items += poly(listOf(42f, 47f, 51f, 44f, 51f, 56f, 43f, 55f, 37f, 68f, 34f, 88f, 30f, 88f, 33f, 64f), Ink.GOLD, fillInk = Ink.GOLD, alpha = 0.42f, stroke = 1.0f, layer = 0)
        items += line(24f, 48f, 46f, 48f, Ink.WARN, arrow = true, stroke = 1.6f, layer = 2)
        items += line(46f, 53f, 24f, 53f, Ink.ACCENT, arrow = true, stroke = 1.6f, layer = 2)

        items += lbl(36f, 28f, "Renal Cortex", Ink.ROSE, leftSide = true)
        items += lbl(30f, 48f, "Renal Artery", Ink.WARN, leftSide = true)
        items += lbl(30f, 53f, "Renal Vein", Ink.ACCENT, leftSide = true)
        items += lbl(32f, 80f, "Ureter", Ink.GOLD, leftSide = true)
        items += lbl(61f, 31f, "Renal Medulla (Pyramid)", Ink.WARN, leftSide = false)
        items += lbl(47f, 50f, "Renal Pelvis", Ink.GOLD, leftSide = false)

        return DiagramSpec("Human Kidney (Longitudinal Section)", items)
    }

    fun nephron(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        // Bowman's capsule cup + Glomerulus knot
        items += arc(30f, 32f, 8.5f, 8.5f, 50f, 260f, Ink.GOLD, fillInk = Ink.GOLD, fill = true, alpha = 0.25f, stroke = 1.15f, layer = 0)
        items += oval(30f, 32f, 4.5f, 4.2f, Ink.WARN, fillInk = Ink.WARN, alpha = 0.45f, stroke = 1.0f, layer = 0)
        items += line(18f, 28f, 27f, 30f, Ink.WARN, arrow = true, stroke = 1.2f, layer = 2)
        items += line(27f, 34f, 18f, 37f, Ink.WARN, arrow = true, stroke = 0.9f, layer = 2)
        // PCT -> Loop of Henle -> DCT -> Collecting Duct
        items += poly(
            listOf(37f, 32f, 46f, 28f, 51f, 35f, 48f, 45f, 48f, 76f, 54f, 82f, 59f, 76f, 59f, 40f, 65f, 30f, 74f, 33f),
            Ink.TEAL, closed = false, fill = false, stroke = 1.3f, layer = 2
        )
        items += rect(73f, 24f, 4.5f, 62f, Ink.ORANGE, fillInk = Ink.ORANGE, rx = 2f, alpha = 0.34f, stroke = 1.05f, layer = 0)

        items += lbl(20f, 28f, "Afferent Arteriole", Ink.WARN, leftSide = true)
        items += lbl(30f, 32f, "Glomerulus & Bowman's Capsule", Ink.GOLD, leftSide = true)
        items += lbl(48f, 64f, "Loop of Henle", Ink.TEAL, leftSide = true)
        items += lbl(47f, 32f, "PCT", Ink.TEAL, leftSide = false)
        items += lbl(66f, 30f, "DCT", Ink.TEAL, leftSide = false)
        items += lbl(75f, 55f, "Collecting Duct", Ink.ORANGE, leftSide = false)

        return DiagramSpec("Structure of a Nephron", items)
    }

    // ---------------------------------------------------------------------------------------------------------
    // 7. Human Respiratory System (Lungs)
    // ---------------------------------------------------------------------------------------------------------

    fun humanLungs(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        // Trachea & Cartilaginous rings
        items += rect(47.5f, 14f, 5f, 26f, Ink.TEAL, fillInk = Ink.TEAL, rx = 2f, alpha = 0.28f, stroke = 1.05f, layer = 0)
        for (ry in 17..37 step 4) items += line(47.5f, ry.toFloat(), 52.5f, ry.toFloat(), Ink.INK, stroke = 0.85f, layer = 2)

        // Right & Left Lungs lobes
        items += poly(
            listOf(46f, 35f, 38f, 28f, 27f, 36f, 22f, 58f, 26f, 75f, 43f, 74f, 45f, 54f),
            Ink.INK, fillInk = Ink.ROSE, shade = FillShade.RADIAL, alpha = 0.32f, stroke = 1.2f, layer = 0
        )
        items += poly(
            listOf(54f, 35f, 62f, 28f, 73f, 36f, 78f, 58f, 74f, 75f, 57f, 74f, 59f, 58f, 55f, 48f),
            Ink.INK, fillInk = Ink.ROSE, shade = FillShade.RADIAL, alpha = 0.32f, stroke = 1.2f, layer = 0
        )
        // Bronchial trees
        items += poly(listOf(50f, 39f, 40f, 47f, 32f, 57f), Ink.TEAL, closed = false, fill = false, stroke = 1.0f, layer = 2)
        items += poly(listOf(40f, 47f, 35f, 44f), Ink.TEAL, closed = false, fill = false, stroke = 0.8f, layer = 2)
        items += poly(listOf(36f, 52f, 40f, 64f), Ink.TEAL, closed = false, fill = false, stroke = 0.8f, layer = 2)
        items += poly(listOf(50f, 39f, 61f, 47f, 69f, 58f), Ink.TEAL, closed = false, fill = false, stroke = 1.0f, layer = 2)
        items += poly(listOf(61f, 47f, 67f, 44f), Ink.TEAL, closed = false, fill = false, stroke = 0.8f, layer = 2)
        items += circle(31f, 58f, 2.2f, Ink.WARN, fillInk = Ink.WARN, alpha = 0.55f, stroke = 0.7f, layer = 2)
        items += circle(70f, 59f, 2.2f, Ink.WARN, fillInk = Ink.WARN, alpha = 0.55f, stroke = 0.7f, layer = 2)

        // Muscular Diaphragm Dome
        items += arc(50f, 92f, 30f, 16f, -155f, 130f, Ink.ORANGE, fillInk = Ink.ORANGE, fill = true, alpha = 0.30f, stroke = 1.25f, layer = 0)

        items += lbl(50f, 16f, "Larynx", Ink.TEAL, leftSide = true)
        items += lbl(47.5f, 28f, "Trachea (Windpipe)", Ink.TEAL, leftSide = true)
        items += lbl(42f, 45f, "Bronchus", Ink.INK, leftSide = true)
        items += lbl(31f, 58f, "Alveoli", Ink.WARN, leftSide = true)
        items += lbl(67f, 45f, "Bronchioles", Ink.TEAL, leftSide = false)
        items += lbl(74f, 63f, "Left Lung", Ink.ROSE, leftSide = false)
        items += lbl(56f, 80f, "Diaphragm", Ink.ORANGE, leftSide = false)

        return DiagramSpec("Human Respiratory System", items)
    }

    // ---------------------------------------------------------------------------------------------------------
    // 8. DNA Double Helix & Flower Structure
    // ---------------------------------------------------------------------------------------------------------

    fun dnaHelix(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        val s1 = ArrayList<Float>()
        val s2 = ArrayList<Float>()
        val rungColors = listOf(Ink.WARN, Ink.RESULT, Ink.GOLD, Ink.TEAL)
        for (i in 0..36) {
            val t = i / 36f
            val y = 14f + t * 74f
            val rad = t * 3.0 * PI
            val x1 = 50f + 15f * sin(rad).toFloat()
            val x2 = 50f - 15f * sin(rad).toFloat()
            s1.add(x1); s1.add(y)
            s2.add(x2); s2.add(y)
            if (i % 3 == 1 && i < 36) {
                val midX = (x1 + x2) * 0.5f
                val c1 = rungColors[i % rungColors.size]
                val c2 = rungColors[(i + 1) % rungColors.size]
                items += line(x1, y, midX, y, c1, stroke = 1.3f, layer = 2)
                items += line(midX, y, x2, y, c2, stroke = 1.3f, layer = 2)
            }
        }
        items += poly(s1, Ink.ACCENT, closed = false, fill = false, stroke = 1.6f, layer = 0)
        items += poly(s2, Ink.VIOLET, closed = false, fill = false, stroke = 1.6f, layer = 0)

        items += lbl(38f, 24f, "Sugar-Phosphate Backbone", Ink.ACCENT, leftSide = true)
        items += lbl(44f, 48f, "Complementary Base Pairs", Ink.GOLD, leftSide = true)
        items += lbl(62f, 36f, "Adenine — Thymine (A=T)", Ink.WARN, leftSide = false)
        items += lbl(56f, 64f, "Guanine — Cytosine (G≡C)", Ink.TEAL, leftSide = false)

        return DiagramSpec("DNA Double Helix", items)
    }

    fun flowerStructure(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        // Petals
        items += poly(listOf(44f, 68f, 22f, 52f, 22f, 28f, 36f, 24f, 46f, 48f), Ink.ROSE, fillInk = Ink.ROSE, alpha = 0.28f, stroke = 1.1f, layer = 0)
        items += poly(listOf(56f, 68f, 78f, 52f, 78f, 28f, 64f, 24f, 54f, 48f), Ink.ROSE, fillInk = Ink.ROSE, alpha = 0.28f, stroke = 1.1f, layer = 0)
        // Central Pistil (Stigma -> Style -> Ovary + Ovules)
        items += poly(
            listOf(47f, 24f, 53f, 24f, 52f, 29f, 52f, 52f, 58f, 62f, 56f, 72f, 44f, 72f, 42f, 62f, 48f, 52f, 48f, 29f),
            Ink.RESULT, fillInk = Ink.GOLD, shade = FillShade.RADIAL, alpha = 0.32f, stroke = 1.15f, layer = 0
        )
        items += oval(50f, 63f, 2.2f, 2.6f, Ink.VIOLET, fillInk = Ink.VIOLET, alpha = 0.55f, layer = 2)
        // Stamens (Filament + Anther)
        for ((ax, side) in listOf(38f to -1, 62f to 1)) {
            items += poly(listOf(50f + side * 6f, 66f, ax + side * 2f, 48f, ax, 32f), Ink.GOLD, closed = false, fill = false, stroke = 1.0f, layer = 2)
            items += oval(ax, 30f, 3.2f, 2.0f, Ink.ORANGE, fillInk = Ink.ORANGE, alpha = 0.65f, stroke = 0.9f, layer = 0)
        }
        // Sepals & Pedicel
        items += poly(listOf(44f, 72f, 29f, 70f, 36f, 77f, 46f, 76f), Ink.RESULT, fillInk = Ink.RESULT, alpha = 0.45f, stroke = 1.0f, layer = 0)
        items += poly(listOf(56f, 72f, 71f, 70f, 64f, 77f, 54f, 76f), Ink.RESULT, fillInk = Ink.RESULT, alpha = 0.45f, stroke = 1.0f, layer = 0)
        items += rect(48f, 76f, 4f, 14f, Ink.RESULT, fillInk = Ink.RESULT, rx = 1.5f, alpha = 0.45f, stroke = 1.0f, layer = 0)

        items += lbl(38f, 30f, "Anther", Ink.ORANGE, leftSide = true)
        items += lbl(40f, 48f, "Filament", Ink.GOLD, leftSide = true)
        items += lbl(25f, 42f, "Petal", Ink.ROSE, leftSide = true)
        items += lbl(33f, 73f, "Sepal", Ink.RESULT, leftSide = true)
        items += lbl(50f, 25f, "Stigma", Ink.RESULT, leftSide = false)
        items += lbl(50f, 40f, "Style", Ink.RESULT, leftSide = false)
        items += lbl(55f, 64f, "Ovary & Ovule", Ink.VIOLET, leftSide = false)
        items += lbl(50f, 84f, "Pedicel", Ink.RESULT, leftSide = false)

        return DiagramSpec("Structure of a Flower (L.S.)", items)
    }

    // ---------------------------------------------------------------------------------------------------------
    // 9. Optics (Glass Prism Dispersion & Convex Lens Ray Diagram) & Chemistry/Earth Classics
    // ---------------------------------------------------------------------------------------------------------

    fun glassPrism(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += poly(listOf(50f, 22f, 70f, 72f, 30f, 72f), Ink.TEAL, smooth = false, fill = true, fillInk = Ink.TEAL, shade = FillShade.LINEAR, alpha = 0.24f, stroke = 1.35f, layer = 0)
        items += line(12f, 58f, 38f, 47f, Ink.INK, arrow = true, stroke = 1.4f, layer = 2)
        val spectrum = listOf(
            Ink.WARN to (47f to 42f to (86f to 36f)),
            Ink.ORANGE to (48f to 46f to (86f to 43f)),
            Ink.GOLD to (49f to 50f to (86f to 50f)),
            Ink.RESULT to (50f to 54f to (86f to 57f)),
            Ink.ACCENT to (51f to 58f to (86f to 64f)),
            Ink.VIOLET to (52f to 62f to (86f to 71f))
        )
        for ((ink, pts) in spectrum) {
            val (yMid, endPt) = pts
            items += line(38f, 47f, 60f, yMid.second, ink, stroke = 1.0f, layer = 2)
            items += line(60f, yMid.second, endPt.first, endPt.second, ink, arrow = true, stroke = 1.1f, layer = 2)
        }
        items += lbl(22f, 53f, "White Light", Ink.INK, leftSide = true)
        items += lbl(50f, 34f, "Glass Prism", Ink.TEAL, leftSide = true)
        items += lbl(84f, 36f, "Red (Least Deviated)", Ink.WARN, leftSide = false)
        items += lbl(84f, 54f, "Visible Spectrum", Ink.GOLD, leftSide = false)
        items += lbl(84f, 71f, "Violet (Most Deviated)", Ink.VIOLET, leftSide = false)
        return DiagramSpec("Dispersion Through a Glass Prism", items)
    }

    fun convexLensRays(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += line(8f, 52f, 92f, 52f, Ink.NOTE, stroke = 1.0f, layer = 2)
        items += arc(57f, 52f, 12f, 34f, 125f, 110f, Ink.TEAL, fillInk = Ink.TEAL, fill = true, alpha = 0.24f, stroke = 1.25f, layer = 0)
        items += arc(43f, 52f, 12f, 34f, -55f, 110f, Ink.TEAL, fillInk = Ink.TEAL, fill = true, alpha = 0.24f, stroke = 1.25f, layer = 0)
        items += line(50f, 20f, 50f, 84f, Ink.FAINT, dashed = true, stroke = 0.8f, layer = 2)
        // Object at beyond 2F1 (x=20)
        items += line(20f, 52f, 20f, 32f, Ink.WARN, arrow = true, stroke = 1.4f, layer = 2)
        // Incident + Refracted Rays
        items += line(20f, 32f, 50f, 32f, Ink.ACCENT, arrow = true, stroke = 1.0f, layer = 2)
        items += line(50f, 32f, 74f, 68f, Ink.ACCENT, arrow = true, stroke = 1.0f, layer = 2)
        items += line(20f, 32f, 74f, 68f, Ink.RESULT, arrow = true, stroke = 1.0f, layer = 2)
        // Inverted Image between F2 and 2F2
        items += line(70f, 52f, 70f, 65f, Ink.VIOLET, arrow = true, stroke = 1.4f, layer = 2)

        items += lbl(20f, 40f, "Object (AB)", Ink.WARN, leftSide = true)
        items += lbl(36f, 52f, "Focus (F₁)", Ink.INK, leftSide = true)
        items += lbl(50f, 28f, "Convex Lens", Ink.TEAL, leftSide = false)
        items += lbl(63f, 52f, "Focus (F₂)", Ink.INK, leftSide = false)
        items += lbl(70f, 62f, "Real Inverted Image", Ink.VIOLET, leftSide = false)
        return DiagramSpec("Convex Lens Ray Diagram", items)
    }

    fun bohrAtom(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        for ((dx, dy, col) in listOf(Triple(48f, 49f, Ink.WARN), Triple(52f, 49f, Ink.ACCENT), Triple(50f, 52f, Ink.WARN), Triple(50f, 47f, Ink.ACCENT))) {
            items += circle(dx, dy, 2.4f, Ink.INK, fillInk = col, alpha = 0.82f, stroke = 0.8f, layer = 0)
        }
        items += circle(50f, 50f, 13f, Ink.TEAL, fill = false, stroke = 1.0f, layer = 0)
        items += circle(50f, 50f, 23f, Ink.ACCENT, fill = false, stroke = 1.0f, layer = 0)
        items += circle(50f, 50f, 33f, Ink.VIOLET, fill = false, dashed = true, stroke = 1.0f, layer = 0)
        val electrons = listOf(50f to 37f, 50f to 63f, 27f to 50f, 73f to 50f, 50f to 27f, 50f to 73f, 73f to 27f, 27f to 73f)
        for ((ex, ey) in electrons) {
            items += circle(ex, ey, 2.1f, Ink.INK, fillInk = Ink.GOLD, alpha = 0.9f, stroke = 0.8f, layer = 2)
        }
        items += lbl(48f, 49f, "Nucleus (Protons + Neutrons)", Ink.WARN, leftSide = true)
        items += lbl(38f, 54f, "K Shell (n=1)", Ink.TEAL, leftSide = true)
        items += lbl(73f, 50f, "L Shell (n=2)", Ink.ACCENT, leftSide = false)
        items += lbl(73f, 27f, "Electron", Ink.GOLD, leftSide = false)
        items += lbl(68f, 78f, "M Shell (n=3)", Ink.VIOLET, leftSide = false)
        return DiagramSpec("Bohr Model of an Atom", items)
    }

    fun earthLayers(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += circle(50f, 50f, 35f, Ink.BROWN, fillInk = Ink.BROWN, alpha = 0.30f, stroke = 1.3f, layer = 0)
        items += circle(50f, 50f, 31f, Ink.WARN, fillInk = Ink.ORANGE, alpha = 0.34f, stroke = 1.0f, layer = 0)
        items += circle(50f, 50f, 18f, Ink.ORANGE, fillInk = Ink.GOLD, alpha = 0.42f, stroke = 1.0f, layer = 0)
        items += circle(50f, 50f, 9f, Ink.GOLD, fillInk = Ink.GOLD, alpha = 0.72f, stroke = 1.1f, layer = 0)
        items += lbl(50f, 50f, "Inner Core (Solid)", Ink.GOLD, leftSide = true)
        items += lbl(38f, 58f, "Outer Core (Liquid)", Ink.ORANGE, leftSide = true)
        items += lbl(70f, 36f, "Mantle", Ink.WARN, leftSide = false)
        items += lbl(83f, 52f, "Crust (Lithosphere)", Ink.BROWN, leftSide = false)
        return DiagramSpec("Internal Layers of the Earth", items)
    }

    fun waterCycle(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += circle(22f, 24f, 7f, Ink.GOLD, fillInk = Ink.GOLD, alpha = 0.65f, stroke = 1.1f, layer = 0)
        items += oval(64f, 24f, 14f, 7f, Ink.ACCENT, fillInk = Ink.FAINT, alpha = 0.40f, stroke = 1.1f, layer = 0)
        items += rect(16f, 72f, 68f, 14f, Ink.ACCENT, fillInk = Ink.TEAL, rx = 5f, alpha = 0.34f, stroke = 1.0f, layer = 0)
        items += line(32f, 68f, 44f, 34f, Ink.TEAL, arrow = true, stroke = 1.1f, layer = 2)
        items += line(40f, 68f, 52f, 34f, Ink.TEAL, arrow = true, stroke = 1.1f, layer = 2)
        items += line(58f, 32f, 58f, 68f, Ink.ACCENT, dashed = true, arrow = true, stroke = 1.0f, layer = 2)
        items += line(68f, 32f, 68f, 68f, Ink.ACCENT, dashed = true, arrow = true, stroke = 1.0f, layer = 2)
        items += line(76f, 66f, 46f, 76f, Ink.RESULT, arrow = true, stroke = 1.1f, layer = 2)

        items += lbl(22f, 24f, "Solar Radiation", Ink.GOLD, leftSide = true)
        items += lbl(38f, 52f, "Evaporation", Ink.TEAL, leftSide = true)
        items += lbl(64f, 24f, "Condensation (Clouds)", Ink.ACCENT, leftSide = false)
        items += lbl(68f, 48f, "Precipitation (Rain)", Ink.ACCENT, leftSide = false)
        items += lbl(52f, 78f, "Collection & Runoff", Ink.RESULT, leftSide = false)
        return DiagramSpec("The Water Cycle", items)
    }

    fun leafStomata(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += circle(50f, 50f, 30f, Ink.RESULT, fillInk = Ink.RESULT, alpha = 0.14f, stroke = 1.0f, layer = 0)
        // Bean-shaped Left & Right Guard Cells surrounding central Stomatal Pore
        items += poly(listOf(48f, 28f, 34f, 32f, 30f, 50f, 34f, 68f, 48f, 72f, 44f, 58f, 44f, 42f), Ink.INK, fillInk = Ink.RESULT, alpha = 0.38f, stroke = 1.25f, layer = 0)
        items += poly(listOf(52f, 28f, 66f, 32f, 70f, 50f, 66f, 68f, 52f, 72f, 56f, 58f, 56f, 42f), Ink.INK, fillInk = Ink.RESULT, alpha = 0.38f, stroke = 1.25f, layer = 0)
        // Central open Stomatal Pore
        items += oval(50f, 50f, 4.2f, 15f, Ink.TEAL, fillInk = Ink.TEAL, alpha = 0.22f, stroke = 1.1f, layer = 0)
        // Nuclei + Chloroplasts inside Guard Cells
        items += circle(37f, 50f, 3.6f, Ink.VIOLET, fillInk = Ink.VIOLET, alpha = 0.65f, layer = 2)
        items += circle(63f, 50f, 3.6f, Ink.VIOLET, fillInk = Ink.VIOLET, alpha = 0.65f, layer = 2)
        for ((cx, cy) in listOf(38f to 38f, 39f to 62f, 62f to 38f, 61f to 62f)) {
            items += oval(cx, cy, 2.5f, 1.8f, Ink.RESULT, fillInk = Ink.RESULT, alpha = 0.72f, layer = 2)
        }
        items += lbl(35f, 36f, "Subsidiary Cell", Ink.RESULT, leftSide = true)
        items += lbl(37f, 50f, "Nucleus", Ink.VIOLET, leftSide = true)
        items += lbl(39f, 62f, "Chloroplast", Ink.RESULT, leftSide = true)
        items += lbl(63f, 44f, "Guard Cell", Ink.INK, leftSide = false)
        items += lbl(50f, 54f, "Stomatal Pore", Ink.TEAL, leftSide = false)
        return DiagramSpec("Open Stomata (Leaf Epidermis)", items)
    }

    // ---------------------------------------------------------------------------------------------------------
    // 10. Physics Additional Diagrams (Concave Lens/Mirror, Circuits, Magnetism, Waves, Mechanics, Motor)
    // ---------------------------------------------------------------------------------------------------------

    fun concaveLensRays(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += line(8f, 52f, 92f, 52f, Ink.NOTE, stroke = 1.0f, layer = 2)
        items += poly(listOf(46f, 20f, 54f, 20f, 51f, 52f, 54f, 84f, 46f, 84f, 49f, 52f), Ink.TEAL, fillInk = Ink.TEAL, smooth = true, alpha = 0.26f, stroke = 1.2f, layer = 0)
        items += line(22f, 52f, 22f, 30f, Ink.WARN, arrow = true, stroke = 1.4f, layer = 2)
        items += line(22f, 30f, 50f, 30f, Ink.ACCENT, arrow = true, stroke = 1.0f, layer = 2)
        items += line(50f, 30f, 78f, 16f, Ink.ACCENT, arrow = true, stroke = 1.0f, layer = 2)
        items += line(50f, 30f, 34f, 38f, Ink.ACCENT, dashed = true, stroke = 0.9f, layer = 2)
        items += line(22f, 30f, 74f, 64f, Ink.RESULT, arrow = true, stroke = 1.0f, layer = 2)
        items += line(38f, 52f, 38f, 40f, Ink.VIOLET, arrow = true, stroke = 1.3f, layer = 2)

        items += lbl(22f, 38f, "Object (AB)", Ink.WARN, leftSide = true)
        items += lbl(32f, 52f, "Focus (F₁)", Ink.INK, leftSide = true)
        items += lbl(38f, 44f, "Virtual Image (A'B')", Ink.VIOLET, leftSide = true)
        items += lbl(50f, 26f, "Concave Lens", Ink.TEAL, leftSide = false)
        items += lbl(68f, 22f, "Diverging Ray", Ink.ACCENT, leftSide = false)
        return DiagramSpec("Concave Lens Ray Diagram", items)
    }

    fun concaveMirrorRays(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += line(10f, 52f, 88f, 52f, Ink.NOTE, stroke = 1.0f, layer = 2)
        items += arc(50f, 52f, 26f, 34f, -55f, 110f, Ink.TEAL, fill = false, stroke = 1.6f, layer = 0)
        for (yy in 24..80 step 7) {
            val dx = 73f + (1f - kotlin.math.abs(yy - 52f) / 34f) * 3f
            items += line(dx, yy.toFloat(), dx + 3.5f, yy - 2.5f, Ink.NOTE, stroke = 0.7f, layer = 2)
        }
        items += line(24f, 52f, 24f, 32f, Ink.WARN, arrow = true, stroke = 1.4f, layer = 2)
        items += line(24f, 32f, 72f, 32f, Ink.ACCENT, arrow = true, stroke = 1.0f, layer = 2)
        items += line(72f, 32f, 38f, 68f, Ink.ACCENT, arrow = true, stroke = 1.0f, layer = 2)
        items += line(24f, 32f, 54f, 52f, Ink.RESULT, arrow = true, stroke = 1.0f, layer = 2)
        items += line(54f, 52f, 24f, 70f, Ink.RESULT, arrow = true, stroke = 1.0f, layer = 2)
        items += line(44f, 52f, 44f, 62f, Ink.VIOLET, arrow = true, stroke = 1.3f, layer = 2)

        items += lbl(24f, 39f, "Object (Beyond C)", Ink.WARN, leftSide = true)
        items += lbl(36f, 52f, "Centre of Curvature (C)", Ink.INK, leftSide = true)
        items += lbl(44f, 58f, "Real Inverted Image", Ink.VIOLET, leftSide = true)
        items += lbl(54f, 52f, "Focus (F)", Ink.RESULT, leftSide = false)
        items += lbl(75f, 35f, "Concave Mirror", Ink.TEAL, leftSide = false)
        return DiagramSpec("Concave Mirror Ray Diagram", items)
    }

    fun electricCircuit(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        // Main rectangular circuit loop (top resistance R + A, bottom battery + switch)
        items += rect(24f, 28f, 52f, 44f, Ink.INK, fill = false, rx = 3f, stroke = 1.15f, layer = 0)
        // Zigzag Resistor along top branch
        items += poly(listOf(42f, 28f, 44f, 23f, 47f, 33f, 50f, 23f, 53f, 33f, 56f, 23f, 58f, 28f), Ink.ORANGE, closed = false, smooth = false, fill = false, stroke = 1.4f, layer = 2)
        // Voltmeter parallel loop above Resistor
        items += poly(listOf(38f, 28f, 38f, 14f, 62f, 14f, 62f, 28f), Ink.VIOLET, closed = false, smooth = false, fill = false, stroke = 0.95f, layer = 2)
        items += circle(50f, 14f, 4.8f, Ink.VIOLET, fillInk = Ink.VIOLET, alpha = 0.30f, stroke = 1.1f, layer = 0)
        // Ammeter inline left branch
        items += circle(24f, 50f, 5.0f, Ink.TEAL, fillInk = Ink.TEAL, alpha = 0.32f, stroke = 1.1f, layer = 0)
        // Battery cells bottom left
        items += line(36f, 66f, 36f, 78f, Ink.WARN, stroke = 1.6f, layer = 2)
        items += line(40f, 69f, 40f, 75f, Ink.ACCENT, stroke = 2.1f, layer = 2)
        // Plug key Switch bottom right
        items += circle(60f, 72f, 2.2f, Ink.RESULT, fillInk = Ink.RESULT, alpha = 0.7f, layer = 2)
        items += circle(66f, 72f, 2.2f, Ink.RESULT, fillInk = Ink.RESULT, alpha = 0.7f, layer = 2)
        // Conventional current arrows
        items += line(68f, 28f, 74f, 28f, Ink.WARN, arrow = true, stroke = 1.1f, layer = 2)
        items += line(76f, 42f, 76f, 56f, Ink.WARN, arrow = true, stroke = 1.1f, layer = 2)

        items += lbl(24f, 50f, "Ammeter (A)", Ink.TEAL, leftSide = true)
        items += lbl(38f, 72f, "DC Battery (+/-)", Ink.WARN, leftSide = true)
        items += lbl(50f, 14f, "Voltmeter (V)", Ink.VIOLET, leftSide = false)
        items += lbl(50f, 28f, "Resistor (R)", Ink.ORANGE, leftSide = false)
        items += lbl(76f, 48f, "Current (I)", Ink.WARN, leftSide = false)
        items += lbl(63f, 72f, "Switch Key (K)", Ink.RESULT, leftSide = false)
        return DiagramSpec("Electric Circuit (Ohm's Law Setup)", items)
    }

    fun barMagnetField(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += rect(34f, 45f, 16f, 10f, Ink.WARN, fillInk = Ink.WARN, rx = 1.5f, alpha = 0.55f, stroke = 1.2f, layer = 0)
        items += rect(50f, 45f, 16f, 10f, Ink.ACCENT, fillInk = Ink.ACCENT, rx = 1.5f, alpha = 0.55f, stroke = 1.2f, layer = 0)
        // Symmetric upper & lower magnetic field loops N -> S
        for ((topY, extX) in listOf(31f to 22f, 19f to 14f)) {
            items += poly(listOf(34f, 46f, extX, 44f, 50f, topY, 100f - extX, 44f, 66f, 46f), Ink.TEAL, closed = false, fill = false, arrow = true, stroke = 1.0f, layer = 2)
            items += poly(listOf(34f, 54f, extX, 56f, 50f, 100f - topY, 100f - extX, 56f, 66f, 54f), Ink.TEAL, closed = false, fill = false, arrow = true, stroke = 1.0f, layer = 2)
        }
        items += line(16f, 50f, 33f, 50f, Ink.TEAL, arrow = true, stroke = 1.0f, layer = 2)
        items += line(84f, 50f, 67f, 50f, Ink.TEAL, arrow = true, stroke = 1.0f, layer = 2)

        items += lbl(40f, 50f, "North Pole (N)", Ink.WARN, leftSide = true)
        items += lbl(20f, 36f, "Magnetic Field Lines", Ink.TEAL, leftSide = true)
        items += lbl(60f, 50f, "South Pole (S)", Ink.ACCENT, leftSide = false)
        items += lbl(50f, 19f, "Direction: N to S Outside", Ink.TEAL, leftSide = false)
        return DiagramSpec("Magnetic Field Lines of a Bar Magnet", items)
    }

    fun transverseWave(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += line(14f, 50f, 86f, 50f, Ink.NOTE, dashed = true, stroke = 1.0f, layer = 2)
        val pts = ArrayList<Float>()
        for (i in 0..48) {
            val t = i / 48f
            val x = 16f + t * 68f
            val y = 50f - 20f * sin(t * 4.0 * PI).toFloat()
            pts.add(x); pts.add(y)
        }
        items += poly(pts, Ink.ACCENT, closed = false, fill = false, stroke = 1.5f, layer = 0)
        // Crest, Trough, Amplitude & Wavelength guides
        items += line(24.5f, 50f, 24.5f, 30f, Ink.WARN, arrow = true, doubleArrow = true, stroke = 1.1f, layer = 2)
        items += circle(58.5f, 30f, 2.1f, Ink.RESULT, fillInk = Ink.RESULT, alpha = 0.85f, layer = 2)
        items += circle(41.5f, 70f, 2.1f, Ink.VIOLET, fillInk = Ink.VIOLET, alpha = 0.85f, layer = 2)
        items += line(24.5f, 23f, 58.5f, 23f, Ink.TEAL, arrow = true, doubleArrow = true, stroke = 1.1f, layer = 2)

        items += lbl(24.5f, 40f, "Amplitude (A)", Ink.WARN, leftSide = true)
        items += lbl(41.5f, 70f, "Trough", Ink.VIOLET, leftSide = true)
        items += lbl(41.5f, 23f, "Wavelength (λ)", Ink.TEAL, leftSide = false)
        items += lbl(58.5f, 30f, "Crest", Ink.RESULT, leftSide = false)
        items += lbl(80f, 50f, "Mean Position", Ink.INK, leftSide = false)
        return DiagramSpec("Transverse Wave Properties", items)
    }

    fun inclinedPlane(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        // Ramp Wedge (right triangle 30 deg approx)
        items += poly(listOf(22f, 76f, 78f, 76f, 78f, 40f), Ink.INK, smooth = false, fill = true, fillInk = Ink.FAINT, alpha = 0.25f, stroke = 1.3f, layer = 0)
        // Block on incline
        items += poly(listOf(44f, 62f, 56f, 54f, 49f, 43f, 37f, 51f), Ink.TEAL, smooth = false, fill = true, fillInk = Ink.TEAL, alpha = 0.35f, stroke = 1.2f, layer = 0)
        // Force vectors from center of mass (46.5, 52.5)
        items += line(46.5f, 52.5f, 46.5f, 80f, Ink.WARN, arrow = true, stroke = 1.3f, layer = 2) // Weight mg
        items += line(46.5f, 52.5f, 35f, 35f, Ink.ACCENT, arrow = true, stroke = 1.3f, layer = 2) // Normal N
        items += line(46.5f, 52.5f, 58f, 45f, Ink.RESULT, arrow = true, stroke = 1.2f, layer = 2) // Friction f
        items += line(46.5f, 52.5f, 32f, 62f, Ink.ORANGE, dashed = true, arrow = true, stroke = 1.0f, layer = 2) // mg sin θ
        items += line(46.5f, 52.5f, 57f, 69f, Ink.VIOLET, dashed = true, arrow = true, stroke = 1.0f, layer = 2) // mg cos θ
        items += arc(22f, 76f, 11f, 11f, -33f, 33f, Ink.GOLD, stroke = 1.1f, layer = 2)

        items += lbl(35f, 35f, "Normal Reaction (N)", Ink.ACCENT, leftSide = true)
        items += lbl(32f, 62f, "Component mg sin θ", Ink.ORANGE, leftSide = true)
        items += lbl(29f, 74f, "Angle θ", Ink.GOLD, leftSide = true)
        items += lbl(58f, 45f, "Friction (f)", Ink.RESULT, leftSide = false)
        items += lbl(57f, 69f, "Component mg cos θ", Ink.VIOLET, leftSide = false)
        items += lbl(46.5f, 80f, "Weight (mg)", Ink.WARN, leftSide = false)
        return DiagramSpec("Forces on an Inclined Plane", items)
    }

    fun simplePendulum(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += rect(34f, 16f, 32f, 4f, Ink.BROWN, fillInk = Ink.BROWN, alpha = 0.45f, stroke = 1.1f, layer = 0)
        items += line(50f, 20f, 50f, 74f, Ink.FAINT, dashed = true, stroke = 0.9f, layer = 2)
        items += line(50f, 20f, 68f, 68f, Ink.INK, stroke = 1.3f, layer = 2)
        items += arc(50f, 20f, 54f, 54f, 68f, 44f, Ink.TEAL, dashed = true, stroke = 0.95f, layer = 2)
        items += circle(50f, 74f, 5.0f, Ink.FAINT, fillInk = Ink.FAINT, alpha = 0.25f, dashed = true, layer = 0)
        items += circle(68f, 68f, 5.5f, Ink.ACCENT, fillInk = Ink.GOLD, shade = FillShade.RADIAL, alpha = 0.72f, stroke = 1.2f, layer = 0)
        items += line(68f, 68f, 68f, 86f, Ink.WARN, arrow = true, stroke = 1.1f, layer = 2)
        items += line(68f, 68f, 60f, 47f, Ink.RESULT, arrow = true, stroke = 1.1f, layer = 2)
        items += arc(50f, 20f, 15f, 15f, 69f, 21f, Ink.VIOLET, stroke = 1.1f, layer = 2)

        items += lbl(50f, 20f, "Rigid Pivot", Ink.BROWN, leftSide = true)
        items += lbl(50f, 74f, "Mean Position (O)", Ink.INK, leftSide = true)
        items += lbl(54f, 34f, "Amplitude Angle (θ)", Ink.VIOLET, leftSide = false)
        items += lbl(60f, 47f, "Tension (T)", Ink.RESULT, leftSide = false)
        items += lbl(68f, 68f, "Pendulum Bob", Ink.GOLD, leftSide = false)
        items += lbl(68f, 84f, "Gravity (mg)", Ink.WARN, leftSide = false)
        return DiagramSpec("Simple Pendulum", items)
    }

    fun dcMotor(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += rect(16f, 36f, 13f, 26f, Ink.WARN, fillInk = Ink.WARN, rx = 2f, alpha = 0.42f, stroke = 1.2f, layer = 0)
        items += rect(71f, 36f, 13f, 26f, Ink.ACCENT, fillInk = Ink.ACCENT, rx = 2f, alpha = 0.42f, stroke = 1.2f, layer = 0)
        // Rotating Rectangular Armature ABCD
        items += poly(listOf(36f, 62f, 40f, 32f, 64f, 32f, 60f, 62f), Ink.ORANGE, smooth = false, fill = true, fillInk = Ink.GOLD, alpha = 0.16f, stroke = 1.4f, layer = 0)
        items += arc(48f, 68f, 4.5f, 3f, 100f, 160f, Ink.TEAL, stroke = 1.5f, layer = 2)
        items += arc(52f, 68f, 4.5f, 3f, -80f, 160f, Ink.TEAL, stroke = 1.5f, layer = 2)
        items += rect(41f, 67f, 3f, 3f, Ink.INK, fillInk = Ink.INK, alpha = 0.8f, layer = 2)
        items += rect(56f, 67f, 3f, 3f, Ink.INK, fillInk = Ink.INK, alpha = 0.8f, layer = 2)
        items += poly(listOf(41f, 69f, 32f, 69f, 32f, 84f, 68f, 84f, 68f, 69f, 59f, 69f), Ink.INK, closed = false, smooth = false, fill = false, stroke = 1.0f, layer = 2)
        items += line(38f, 47f, 38f, 36f, Ink.RESULT, arrow = true, stroke = 1.15f, layer = 2)
        items += line(62f, 47f, 62f, 58f, Ink.RESULT, arrow = true, stroke = 1.15f, layer = 2)

        items += lbl(22.5f, 49f, "North Pole (N)", Ink.WARN, leftSide = true)
        items += lbl(40f, 36f, "Armature Coil (ABCD)", Ink.ORANGE, leftSide = true)
        items += lbl(41f, 68f, "Carbon Brush", Ink.INK, leftSide = true)
        items += lbl(77.5f, 49f, "South Pole (S)", Ink.ACCENT, leftSide = false)
        items += lbl(50f, 68f, "Split-Ring Commutator", Ink.TEAL, leftSide = false)
        items += lbl(50f, 84f, "DC Battery Supply", Ink.RESULT, leftSide = false)
        return DiagramSpec("Electric DC Motor Principle", items)
    }

    // ---------------------------------------------------------------------------------------------------------
    // 11. Chemistry Diagrams (Benzene, Water H2O, Distillation, Electrolysis, pH Scale)
    // ---------------------------------------------------------------------------------------------------------

    fun benzeneRing(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        val outer = ArrayList<Float>()
        val inner = ArrayList<Float>()
        val rHex = 22f
        for (k in 0 until 6) {
            val a = Math.toRadians((k * 60 - 90).toDouble())
            val vx = 50f + rHex * cos(a).toFloat()
            val vy = 50f + rHex * sin(a).toFloat()
            outer.add(vx); outer.add(vy)
            val hx = 50f + (rHex + 11f) * cos(a).toFloat()
            val hy = 50f + (rHex + 11f) * sin(a).toFloat()
            items += line(vx, vy, hx, hy, Ink.NOTE, stroke = 1.0f, layer = 2)
            items += circle(hx, hy, 2.2f, Ink.ACCENT, fillInk = Ink.ACCENT, alpha = 0.72f, stroke = 0.8f, layer = 2)
            val ix = 50f + (rHex * 0.78f) * cos(a).toFloat()
            val iy = 50f + (rHex * 0.78f) * sin(a).toFloat()
            inner.add(ix); inner.add(iy)
        }
        items += poly(outer, Ink.INK, smooth = false, fill = true, fillInk = Ink.TEAL, shade = FillShade.RADIAL, alpha = 0.16f, stroke = 1.45f, layer = 0)
        // Delocalized conjugated pi-ring
        items += circle(50f, 50f, 13.5f, Ink.VIOLET, fillInk = Ink.VIOLET, alpha = 0.14f, dashed = true, stroke = 1.2f, layer = 2)
        items += arc(50f, 28f, 6f, 6f, 30f, 120f, Ink.GOLD, stroke = 1.0f, layer = 2)

        items += lbl(50f, 28f, "Carbon Ring (sp²)", Ink.INK, leftSide = true)
        items += lbl(38f, 50f, "Delocalized π Electron Ring", Ink.VIOLET, leftSide = true)
        items += lbl(69f, 39f, "Hydrogen Atom (H)", Ink.ACCENT, leftSide = false)
        items += lbl(50f, 34f, "Bond Angle 120°", Ink.GOLD, leftSide = false)
        return DiagramSpec("Benzene Structure (C₆H₆)", items)
    }

    fun waterMolecule(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += circle(50f, 38f, 11f, Ink.INK, fillInk = Ink.WARN, shade = FillShade.RADIAL, alpha = 0.50f, stroke = 1.25f, layer = 0)
        val rad = Math.toRadians(52.25)
        val dx = (23f * sin(rad)).toFloat()
        val dy = (23f * cos(rad)).toFloat()
        items += line(50f, 38f, 50f - dx, 38f + dy, Ink.INK, stroke = 1.6f, layer = 0)
        items += line(50f, 38f, 50f + dx, 38f + dy, Ink.INK, stroke = 1.6f, layer = 0)
        items += circle(50f - dx, 38f + dy, 6.8f, Ink.INK, fillInk = Ink.ACCENT, shade = FillShade.RADIAL, alpha = 0.55f, stroke = 1.15f, layer = 0)
        items += circle(50f + dx, 38f + dy, 6.8f, Ink.INK, fillInk = Ink.ACCENT, shade = FillShade.RADIAL, alpha = 0.55f, stroke = 1.15f, layer = 0)
        items += arc(50f, 38f, 16f, 16f, 38f, 104.5f, Ink.GOLD, stroke = 1.1f, layer = 2)
        // Lone pair electrons on Oxygen
        items += circle(44f, 23f, 1.4f, Ink.VIOLET, fillInk = Ink.VIOLET, alpha = 0.9f, layer = 2)
        items += circle(48f, 22f, 1.4f, Ink.VIOLET, fillInk = Ink.VIOLET, alpha = 0.9f, layer = 2)
        items += circle(52f, 22f, 1.4f, Ink.VIOLET, fillInk = Ink.VIOLET, alpha = 0.9f, layer = 2)
        items += circle(56f, 23f, 1.4f, Ink.VIOLET, fillInk = Ink.VIOLET, alpha = 0.9f, layer = 2)

        items += lbl(46f, 22f, "2 Lone Pairs", Ink.VIOLET, leftSide = true)
        items += lbl(50f - dx, 38f + dy, "Hydrogen (δ⁺)", Ink.ACCENT, leftSide = true)
        items += lbl(50f, 38f, "Oxygen (δ⁻)", Ink.WARN, leftSide = false)
        items += lbl(50f, 54f, "Bent Angle: 104.5°", Ink.GOLD, leftSide = false)
        items += lbl(50f + dx, 38f + dy, "Covalent O–H Bond", Ink.INK, leftSide = false)
        return DiagramSpec("Water Molecule (H₂O Geometry)", items)
    }

    fun simpleDistillation(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        // Boiling Round-Bottom Flask
        items += circle(30f, 56f, 11f, Ink.TEAL, fillInk = Ink.ACCENT, shade = FillShade.RADIAL, alpha = 0.24f, stroke = 1.25f, layer = 0)
        items += rect(27.5f, 30f, 5f, 16f, Ink.TEAL, fill = false, stroke = 1.15f, layer = 0)
        // Bunsen burner flame
        items += poly(listOf(27f, 78f, 30f, 68f, 33f, 78f), Ink.ORANGE, smooth = true, fill = true, fillInk = Ink.GOLD, alpha = 0.68f, stroke = 1.0f, layer = 0)
        items += rect(28f, 78f, 4f, 8f, Ink.NOTE, fillInk = Ink.NOTE, alpha = 0.5f, stroke = 1.0f, layer = 0)
        // Thermometer stem
        items += line(30f, 20f, 30f, 42f, Ink.WARN, stroke = 1.25f, layer = 2)
        // Liebig Condenser tube + water jacket
        items += line(32.5f, 36f, 68f, 60f, Ink.TEAL, stroke = 1.1f, layer = 2)
        items += poly(listOf(38f, 36f, 64f, 53f, 61f, 59f, 35f, 42f), Ink.ACCENT, smooth = false, fill = true, fillInk = Ink.ACCENT, alpha = 0.22f, stroke = 1.0f, layer = 0)
        // Receiving Flask
        items += poly(listOf(71f, 58f, 75f, 58f, 81f, 76f, 65f, 76f), Ink.TEAL, smooth = false, fill = true, fillInk = Ink.RESULT, alpha = 0.28f, stroke = 1.2f, layer = 0)

        items += lbl(30f, 24f, "Thermometer", Ink.WARN, leftSide = true)
        items += lbl(30f, 56f, "Distillation Flask", Ink.ACCENT, leftSide = true)
        items += lbl(30f, 74f, "Bunsen Burner", Ink.ORANGE, leftSide = true)
        items += lbl(50f, 46f, "Liebig Condenser", Ink.ACCENT, leftSide = false)
        items += lbl(73f, 70f, "Distillate Receiver", Ink.RESULT, leftSide = false)
        return DiagramSpec("Simple Distillation Apparatus", items)
    }

    fun electrolysisCell(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += rect(26f, 42f, 48f, 36f, Ink.INK, fill = false, rx = 3f, stroke = 1.3f, layer = 0)
        items += rect(27f, 50f, 46f, 27f, Ink.TEAL, fillInk = Ink.TEAL, alpha = 0.22f, stroke = 0.6f, layer = 1)
        items += rect(36f, 36f, 5f, 34f, Ink.WARN, fillInk = Ink.BROWN, rx = 1.5f, alpha = 0.55f, stroke = 1.1f, layer = 0) // Anode +
        items += rect(59f, 36f, 5f, 34f, Ink.ACCENT, fillInk = Ink.BROWN, rx = 1.5f, alpha = 0.55f, stroke = 1.1f, layer = 0) // Cathode -
        items += poly(listOf(38.5f, 36f, 38.5f, 20f, 61.5f, 20f, 61.5f, 36f), Ink.INK, closed = false, smooth = false, fill = false, stroke = 1.1f, layer = 2)
        items += circle(50f, 20f, 5f, Ink.GOLD, fillInk = Ink.GOLD, alpha = 0.35f, stroke = 1.1f, layer = 0) // Battery source
        // Gas bubbles rising
        for ((bx, by) in listOf(35f to 54f, 42f to 58f, 36f to 64f, 58f to 54f, 65f to 60f, 58f to 65f)) {
            items += circle(bx, by, 1.3f, Ink.ACCENT, fillInk = Ink.FAINT, alpha = 0.7f, layer = 2)
        }

        items += lbl(38.5f, 40f, "Anode (+ Electrode)", Ink.WARN, leftSide = true)
        items += lbl(32f, 62f, "Electrolyte Solution", Ink.TEAL, leftSide = true)
        items += lbl(50f, 20f, "DC Power Supply", Ink.GOLD, leftSide = false)
        items += lbl(61.5f, 40f, "Cathode (- Electrode)", Ink.ACCENT, leftSide = false)
        items += lbl(64f, 58f, "Evolved Gas Bubbles", Ink.TEAL, leftSide = false)
        return DiagramSpec("Electrolytic Cell Setup", items)
    }

    fun phScale(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        val inks = listOf(Ink.WARN, Ink.WARN, Ink.ORANGE, Ink.ORANGE, Ink.GOLD, Ink.GOLD, Ink.RESULT, Ink.TEAL, Ink.TEAL, Ink.ACCENT, Ink.ACCENT, Ink.VIOLET, Ink.VIOLET)
        val segW = 70f / inks.size
        inks.forEachIndexed { i, ink ->
            items += rect(15f + i * segW, 44f, segW, 12f, ink, fillInk = ink, rx = 1.5f, alpha = 0.65f, stroke = 0.9f, layer = 0)
        }
        items += line(50f, 36f, 50f, 64f, Ink.INK, stroke = 1.3f, layer = 2)
        items += lbl(20f, 50f, "Strong Acid (pH 0–3)", Ink.WARN, leftSide = true)
        items += lbl(36f, 50f, "Weak Acid (pH 4–6)", Ink.ORANGE, leftSide = true)
        items += lbl(50f, 40f, "Neutral (pH 7)", Ink.RESULT, leftSide = false)
        items += lbl(65f, 50f, "Weak Alkali (pH 8–10)", Ink.TEAL, leftSide = false)
        items += lbl(80f, 50f, "Strong Alkali (pH 11–14)", Ink.VIOLET, leftSide = false)
        return DiagramSpec("pH Scale (0 to 14)", items)
    }

    // ---------------------------------------------------------------------------------------------------------
    // 12. Mathematics & Solid Geometry Diagrams
    // ---------------------------------------------------------------------------------------------------------

    fun rightTriangleTrig(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += poly(listOf(28f, 70f, 72f, 70f, 72f, 26f), Ink.INK, smooth = false, fill = true, fillInk = Ink.ACCENT, alpha = 0.14f, stroke = 1.35f, layer = 0)
        // Coloured sides & 90 deg square
        items += line(28f, 70f, 72f, 70f, Ink.TEAL, stroke = 2.0f, layer = 2)
        items += line(72f, 70f, 72f, 26f, Ink.WARN, stroke = 2.0f, layer = 2)
        items += line(28f, 70f, 72f, 26f, Ink.VIOLET, stroke = 2.0f, layer = 2)
        items += rect(66f, 64f, 6f, 6f, Ink.INK, fill = false, stroke = 1.0f, layer = 2)
        items += arc(28f, 70f, 11f, 11f, -45f, 45f, Ink.GOLD, stroke = 1.2f, layer = 2)

        items += lbl(49f, 48f, "Hypotenuse (H)", Ink.VIOLET, leftSide = true)
        items += lbl(36f, 66f, "Angle θ", Ink.GOLD, leftSide = true)
        items += lbl(72f, 48f, "Opposite / Height (P)", Ink.WARN, leftSide = false)
        items += lbl(50f, 70f, "Adjacent / Base (B)", Ink.TEAL, leftSide = false)
        return DiagramSpec("Right-Angled Triangle Trigonometry", items)
    }

    fun pythagorasSquares(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        // 3-4-5 triangle centered with squares on adjacent sides
        items += poly(listOf(42f, 60f, 62f, 60f, 62f, 34f), Ink.INK, smooth = false, fill = true, fillInk = Ink.GOLD, alpha = 0.35f, stroke = 1.3f, layer = 0)
        items += rect(42f, 60f, 20f, 20f, Ink.TEAL, fillInk = Ink.TEAL, alpha = 0.25f, stroke = 1.1f, layer = 0) // Base b^2
        items += rect(62f, 34f, 26f, 26f, Ink.WARN, fillInk = Ink.WARN, alpha = 0.25f, stroke = 1.1f, layer = 0) // Height a^2
        items += poly(listOf(42f, 60f, 62f, 34f, 36f, 14f, 16f, 40f), Ink.VIOLET, smooth = false, fill = true, fillInk = Ink.VIOLET, alpha = 0.22f, stroke = 1.1f, layer = 0) // Hypotenuse c^2
        items += lbl(29f, 37f, "Area = c²", Ink.VIOLET, leftSide = true)
        items += lbl(52f, 70f, "Area = b²", Ink.TEAL, leftSide = true)
        items += lbl(75f, 47f, "Area = a²", Ink.WARN, leftSide = false)
        items += lbl(56f, 52f, "a² + b² = c²", Ink.INK, leftSide = false)
        return DiagramSpec("Pythagorean Theorem (a² + b² = c²)", items)
    }

    fun circleParts(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += circle(50f, 50f, 26f, Ink.INK, fillInk = Ink.FAINT, alpha = 0.12f, stroke = 1.35f, layer = 0)
        // Shaded Sector (0 to 50 deg)
        items += arc(50f, 50f, 26f, 26f, -50f, 50f, Ink.GOLD, fillInk = Ink.GOLD, fill = true, useCenter = true, alpha = 0.36f, stroke = 1.1f, layer = 0)
        items += line(24f, 50f, 76f, 50f, Ink.ACCENT, stroke = 1.3f, layer = 2) // Diameter
        items += line(50f, 50f, 35f, 29f, Ink.WARN, stroke = 1.3f, layer = 2) // Radius
        items += line(31f, 68f, 69f, 68f, Ink.TEAL, stroke = 1.3f, layer = 2) // Chord
        items += line(24f, 18f, 76f, 18f, Ink.VIOLET, stroke = 1.25f, layer = 2) // Tangent touching top
        items += circle(50f, 50f, 2.2f, Ink.INK, fillInk = Ink.INK, alpha = 0.9f, layer = 2)

        items += lbl(42f, 39f, "Radius (r)", Ink.WARN, leftSide = true)
        items += lbl(36f, 50f, "Diameter (d)", Ink.ACCENT, leftSide = true)
        items += lbl(41f, 68f, "Chord", Ink.TEAL, leftSide = true)
        items += lbl(50f, 21f, "Tangent", Ink.VIOLET, leftSide = false)
        items += lbl(68f, 40f, "Sector", Ink.GOLD, leftSide = false)
        return DiagramSpec("Parts of a Circle", items)
    }

    fun cone3D(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += poly(listOf(50f, 18f, 72f, 72f, 28f, 72f), Ink.INK, smooth = false, fill = true, fillInk = Ink.TEAL, shade = FillShade.LINEAR, alpha = 0.22f, stroke = 1.3f, layer = 0)
        items += oval(50f, 72f, 22f, 8f, Ink.INK, fillInk = Ink.TEAL, alpha = 0.32f, stroke = 1.2f, layer = 0)
        items += line(50f, 18f, 50f, 72f, Ink.WARN, dashed = true, stroke = 1.15f, layer = 2)
        items += line(50f, 72f, 72f, 72f, Ink.ACCENT, stroke = 1.25f, layer = 2)
        items += lbl(50f, 46f, "Height (h)", Ink.WARN, leftSide = true)
        items += lbl(50f, 18f, "Apex / Vertex", Ink.INK, leftSide = false)
        items += lbl(61f, 45f, "Slant Height (l)", Ink.TEAL, leftSide = false)
        items += lbl(61f, 72f, "Radius (r)", Ink.ACCENT, leftSide = false)
        return DiagramSpec("Right Circular Cone (3D)", items)
    }

    fun cylinder3D(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += rect(32f, 26f, 36f, 48f, Ink.INK, fillInk = Ink.ACCENT, alpha = 0.18f, stroke = 1.2f, layer = 0)
        items += oval(50f, 26f, 18f, 7f, Ink.INK, fillInk = Ink.ACCENT, alpha = 0.34f, stroke = 1.25f, layer = 0)
        items += oval(50f, 74f, 18f, 7f, Ink.INK, fillInk = Ink.ACCENT, alpha = 0.26f, stroke = 1.2f, layer = 0)
        items += line(50f, 26f, 50f, 74f, Ink.WARN, dashed = true, stroke = 1.1f, layer = 2)
        items += line(50f, 26f, 68f, 26f, Ink.TEAL, stroke = 1.2f, layer = 2)
        items += lbl(50f, 50f, "Height (h)", Ink.WARN, leftSide = true)
        items += lbl(59f, 26f, "Radius (r)", Ink.TEAL, leftSide = false)
        items += lbl(50f, 74f, "Circular Base (πr²)", Ink.ACCENT, leftSide = false)
        return DiagramSpec("Right Circular Cylinder (3D)", items)
    }

    fun vennDiagram(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += rect(16f, 22f, 68f, 56f, Ink.INK, fillInk = Ink.FAINT, rx = 8f, alpha = 0.10f, stroke = 1.2f, layer = 0)
        items += circle(41f, 50f, 18f, Ink.ACCENT, fillInk = Ink.ACCENT, alpha = 0.28f, stroke = 1.3f, layer = 0)
        items += circle(59f, 50f, 18f, Ink.ROSE, fillInk = Ink.ROSE, alpha = 0.28f, stroke = 1.3f, layer = 0)
        items += lbl(20f, 26f, "Universal Set (U)", Ink.INK, leftSide = true)
        items += lbl(33f, 50f, "Set A", Ink.ACCENT, leftSide = true)
        items += lbl(50f, 50f, "Intersection (A ∩ B)", Ink.VIOLET, leftSide = false)
        items += lbl(67f, 50f, "Set B", Ink.ROSE, leftSide = false)
        return DiagramSpec("Venn Diagram (Sets A & B)", items)
    }

    // ---------------------------------------------------------------------------------------------------------
    // 13. Computer Science Diagrams (Trees, Linked Lists, Stack/Queue, OSI, CPU, Neural Net, Logic Gates)
    // ---------------------------------------------------------------------------------------------------------

    fun binaryTree(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        // Branch edges first behind/structure
        items += line(50f, 24f, 32f, 44f, Ink.NOTE, stroke = 1.2f, layer = 2)
        items += line(50f, 24f, 68f, 44f, Ink.NOTE, stroke = 1.2f, layer = 2)
        items += line(32f, 44f, 22f, 68f, Ink.NOTE, stroke = 1.2f, layer = 2)
        items += line(32f, 44f, 42f, 68f, Ink.NOTE, stroke = 1.2f, layer = 2)
        items += line(68f, 44f, 58f, 68f, Ink.NOTE, stroke = 1.2f, layer = 2)
        items += line(68f, 44f, 78f, 68f, Ink.NOTE, stroke = 1.2f, layer = 2)

        items += circle(50f, 24f, 6.2f, Ink.VIOLET, fillInk = Ink.VIOLET, alpha = 0.42f, stroke = 1.25f, layer = 0)
        for ((nx, ny) in listOf(32f to 44f, 68f to 44f)) {
            items += circle(nx, ny, 5.5f, Ink.ACCENT, fillInk = Ink.ACCENT, alpha = 0.36f, stroke = 1.15f, layer = 0)
        }
        for ((lx, ly) in listOf(22f to 68f, 42f to 68f, 58f to 68f, 78f to 68f)) {
            items += circle(lx, ly, 5.0f, Ink.RESULT, fillInk = Ink.RESULT, alpha = 0.36f, stroke = 1.1f, layer = 0)
        }

        items += lbl(50f, 24f, "Root Node (Level 0)", Ink.VIOLET, leftSide = true)
        items += lbl(32f, 44f, "Left Subtree (Internal)", Ink.ACCENT, leftSide = true)
        items += lbl(22f, 68f, "Leaf Nodes (Level 2)", Ink.RESULT, leftSide = true)
        items += lbl(68f, 44f, "Right Subtree", Ink.ACCENT, leftSide = false)
        items += lbl(42f, 34f, "Parent–Child Edge", Ink.NOTE, leftSide = false)
        return DiagramSpec("Binary Tree Data Structure", items)
    }

    fun linkedList(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        for (i in 0 until 3) {
            val bx = 16f + i * 26f
            items += rect(bx, 42f, 18f, 14f, Ink.ACCENT, fillInk = Ink.ACCENT, rx = 3f, alpha = 0.24f, stroke = 1.2f, layer = 0)
            items += line(bx + 11f, 42f, bx + 11f, 56f, Ink.INK, stroke = 1.0f, layer = 2)
            items += circle(bx + 14.5f, 49f, 1.5f, Ink.INK, fillInk = Ink.INK, alpha = 0.9f, layer = 2)
            items += line(bx + 16f, 49f, bx + 26f, 49f, Ink.TEAL, arrow = true, stroke = 1.25f, layer = 2)
        }
        items += rect(86f, 44f, 10f, 10f, Ink.WARN, fillInk = Ink.WARN, rx = 2.5f, alpha = 0.28f, stroke = 1.0f, layer = 0)
        items += line(21f, 28f, 21f, 42f, Ink.VIOLET, arrow = true, stroke = 1.2f, layer = 2)

        items += lbl(21f, 28f, "Head Pointer", Ink.VIOLET, leftSide = true)
        items += lbl(22f, 49f, "Data Field", Ink.ACCENT, leftSide = true)
        items += lbl(42f, 49f, "Next Pointer Ref", Ink.TEAL, leftSide = false)
        items += lbl(90f, 49f, "NULL Terminator", Ink.WARN, leftSide = false)
        return DiagramSpec("Singly Linked List", items)
    }

    fun stackAndQueue(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        // Stack container left
        items += poly(listOf(22f, 30f, 22f, 76f, 42f, 76f, 42f, 30f), Ink.ACCENT, closed = false, smooth = false, fill = false, stroke = 1.4f, layer = 0)
        for ((idx, sy) in listOf(64f, 52f, 40f).withIndex()) {
            val c = listOf(Ink.TEAL, Ink.ACCENT, Ink.VIOLET)[idx]
            items += rect(24f, sy, 16f, 9f, c, fillInk = c, rx = 2f, alpha = 0.36f, stroke = 1.0f, layer = 0)
        }
        items += line(32f, 20f, 32f, 37f, Ink.WARN, arrow = true, stroke = 1.2f, layer = 2)

        // Queue horizontal pipe right
        items += line(56f, 44f, 86f, 44f, Ink.RESULT, stroke = 1.3f, layer = 0)
        items += line(56f, 60f, 86f, 60f, Ink.RESULT, stroke = 1.3f, layer = 0)
        for ((idx, qx) in listOf(60f, 71f).withIndex()) {
            val c = if (idx == 0) Ink.RESULT else Ink.GOLD
            items += rect(qx, 46f, 9f, 12f, c, fillInk = c, rx = 2f, alpha = 0.38f, stroke = 1.0f, layer = 0)
        }
        items += line(88f, 52f, 80f, 52f, Ink.TEAL, arrow = true, stroke = 1.2f, layer = 2)
        items += line(60f, 52f, 51f, 52f, Ink.WARN, arrow = true, stroke = 1.2f, layer = 2)

        items += lbl(32f, 24f, "Stack Push/Pop (Top)", Ink.WARN, leftSide = true)
        items += lbl(32f, 56f, "LIFO Stack", Ink.ACCENT, leftSide = true)
        items += lbl(55f, 52f, "Dequeue (Front)", Ink.WARN, leftSide = false)
        items += lbl(75f, 52f, "FIFO Queue", Ink.RESULT, leftSide = false)
        items += lbl(84f, 52f, "Enqueue (Rear)", Ink.TEAL, leftSide = false)
        return DiagramSpec("Stack (LIFO) vs Queue (FIFO)", items)
    }

    fun osiModel(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        val layers = listOf(
            "7. Application Layer" to Ink.VIOLET,
            "6. Presentation Layer" to Ink.ACCENT,
            "5. Session Layer" to Ink.TEAL,
            "4. Transport Layer (TCP/UDP)" to Ink.RESULT,
            "3. Network Layer (IP)" to Ink.GOLD,
            "2. Data Link Layer (MAC)" to Ink.ORANGE,
            "1. Physical Layer (Bits)" to Ink.WARN
        )
        layers.forEachIndexed { idx, (name, ink) ->
            val y = 14f + idx * 10.5f
            items += rect(32f, y, 36f, 8.8f, ink, fillInk = ink, rx = 2.5f, alpha = 0.30f, stroke = 1.05f, layer = 0)
            items += lbl(50f, y + 4.4f, name, ink, leftSide = idx % 2 == 0)
        }
        return DiagramSpec("OSI 7-Layer Reference Model", items)
    }

    fun cpuArchitecture(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += rect(14f, 42f, 14f, 16f, Ink.TEAL, fillInk = Ink.TEAL, rx = 3f, alpha = 0.28f, stroke = 1.1f, layer = 0) // Input
        items += rect(36f, 22f, 28f, 44f, Ink.ACCENT, fillInk = Ink.ACCENT, rx = 5f, alpha = 0.16f, stroke = 1.35f, layer = 0) // CPU container
        items += rect(40f, 28f, 20f, 12f, Ink.VIOLET, fillInk = Ink.VIOLET, rx = 3f, alpha = 0.32f, stroke = 1.0f, layer = 0) // Control Unit
        items += rect(40f, 46f, 20f, 14f, Ink.WARN, fillInk = Ink.WARN, rx = 3f, alpha = 0.32f, stroke = 1.0f, layer = 0) // ALU
        items += rect(38f, 72f, 24f, 14f, Ink.GOLD, fillInk = Ink.GOLD, rx = 3f, alpha = 0.32f, stroke = 1.1f, layer = 0) // Memory
        items += rect(72f, 42f, 14f, 16f, Ink.RESULT, fillInk = Ink.RESULT, rx = 3f, alpha = 0.28f, stroke = 1.1f, layer = 0) // Output

        items += line(28f, 50f, 36f, 50f, Ink.INK, arrow = true, stroke = 1.2f, layer = 2)
        items += line(64f, 50f, 72f, 50f, Ink.INK, arrow = true, stroke = 1.2f, layer = 2)
        items += line(50f, 66f, 50f, 72f, Ink.GOLD, arrow = true, doubleArrow = true, stroke = 1.2f, layer = 2)

        items += lbl(21f, 50f, "Input Device", Ink.TEAL, leftSide = true)
        items += lbl(50f, 34f, "Control Unit (CU)", Ink.VIOLET, leftSide = true)
        items += lbl(50f, 53f, "ALU (Arithmetic Logic)", Ink.WARN, leftSide = true)
        items += lbl(50f, 24f, "Central Processing Unit", Ink.ACCENT, leftSide = false)
        items += lbl(79f, 50f, "Output Device", Ink.RESULT, leftSide = false)
        items += lbl(50f, 79f, "Primary Memory (RAM)", Ink.GOLD, leftSide = false)
        return DiagramSpec("Von Neumann CPU Architecture", items)
    }

    fun neuralNetwork(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        val xCols = listOf(28f, 50f, 72f)
        val yLayers = listOf(listOf(34f, 50f, 66f), listOf(26f, 42f, 58f, 74f), listOf(40f, 60f))
        val colors = listOf(Ink.TEAL, Ink.VIOLET, Ink.RESULT)
        // Dense synaptic connections first
        for (l in 0 until 2) {
            for (y1 in yLayers[l]) {
                for (y2 in yLayers[l + 1]) {
                    items += line(xCols[l], y1, xCols[l + 1], y2, Ink.NOTE, stroke = 0.7f, layer = 2)
                }
            }
        }
        yLayers.forEachIndexed { l, ys ->
            for (cy in ys) {
                items += circle(xCols[l], cy, 4.4f, colors[l], fillInk = colors[l], alpha = 0.45f, stroke = 1.15f, layer = 0)
            }
        }
        items += lbl(28f, 34f, "Input Layer", Ink.TEAL, leftSide = true)
        items += lbl(39f, 48f, "Weighted Synapses", Ink.NOTE, leftSide = true)
        items += lbl(50f, 26f, "Hidden Layer", Ink.VIOLET, leftSide = false)
        items += lbl(72f, 50f, "Output Layer", Ink.RESULT, leftSide = false)
        return DiagramSpec("Artificial Neural Network", items)
    }

    fun logicGates(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        // AND Gate top
        items += line(24f, 24f, 36f, 24f, Ink.ACCENT, stroke = 1.1f, layer = 2)
        items += line(24f, 32f, 36f, 32f, Ink.ACCENT, stroke = 1.1f, layer = 2)
        items += poly(listOf(36f, 20f, 50f, 20f, 56f, 28f, 50f, 36f, 36f, 36f), Ink.INK, smooth = false, fill = true, fillInk = Ink.ACCENT, alpha = 0.28f, stroke = 1.2f, layer = 0)
        items += line(56f, 28f, 72f, 28f, Ink.RESULT, arrow = true, stroke = 1.1f, layer = 2)
        // OR Gate middle
        items += line(24f, 46f, 38f, 46f, Ink.ACCENT, stroke = 1.1f, layer = 2)
        items += line(24f, 54f, 38f, 54f, Ink.ACCENT, stroke = 1.1f, layer = 2)
        items += poly(listOf(36f, 42f, 50f, 44f, 58f, 50f, 50f, 56f, 36f, 58f, 40f, 50f), Ink.INK, smooth = true, fill = true, fillInk = Ink.VIOLET, alpha = 0.28f, stroke = 1.2f, layer = 0)
        items += line(58f, 50f, 72f, 50f, Ink.RESULT, arrow = true, stroke = 1.1f, layer = 2)
        // NOT Gate bottom
        items += line(24f, 74f, 38f, 74f, Ink.ACCENT, stroke = 1.1f, layer = 2)
        items += poly(listOf(38f, 66f, 54f, 74f, 38f, 82f), Ink.INK, smooth = false, fill = true, fillInk = Ink.WARN, alpha = 0.28f, stroke = 1.2f, layer = 0)
        items += circle(56.2f, 74f, 2.1f, Ink.INK, fillInk = Ink.WARN, alpha = 0.5f, stroke = 1.0f, layer = 0)
        items += line(58.5f, 74f, 72f, 74f, Ink.RESULT, arrow = true, stroke = 1.1f, layer = 2)

        items += lbl(26f, 28f, "Inputs (A, B)", Ink.ACCENT, leftSide = true)
        items += lbl(26f, 74f, "Input (A)", Ink.ACCENT, leftSide = true)
        items += lbl(46f, 28f, "AND Gate (A · B)", Ink.ACCENT, leftSide = false)
        items += lbl(48f, 50f, "OR Gate (A + B)", Ink.VIOLET, leftSide = false)
        items += lbl(46f, 74f, "NOT Gate (Ā)", Ink.WARN, leftSide = false)
        return DiagramSpec("Basic Digital Logic Gates", items)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Additional High-Demand Textbook STEM Blueprints (Biology, Physics, Earth & Space)
    // ---------------------------------------------------------------------------------------------------------

    fun mitochondria(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        // Outer smooth phospholipid envelope
        items += oval(50f, 50f, 30f, 17f, Ink.ORANGE, fillInk = Ink.ORANGE, shade = FillShade.RADIAL, alpha = 0.22f, stroke = 1.3f, layer = 0)
        // Intermembrane channel + folded serpentine Inner Membrane (Cristae finger projections)
        items += poly(
            listOf(
                23f, 50f, 25f, 40f, 32f, 37f, 34f, 47f, 38f, 47f, 40f, 37f,
                46f, 37f, 48f, 46f, 52f, 46f, 54f, 37f, 60f, 37f, 62f, 46f,
                66f, 46f, 68f, 38f, 75f, 43f, 76f, 50f, 75f, 57f, 68f, 62f,
                65f, 54f, 61f, 54f, 59f, 63f, 53f, 63f, 51f, 54f, 47f, 54f,
                45f, 63f, 39f, 63f, 37f, 54f, 33f, 54f, 31f, 62f, 25f, 59f
            ),
            ink = Ink.WARN, fillInk = Ink.ROSE, shade = FillShade.LINEAR, alpha = 0.28f, stroke = 1.1f, layer = 0
        )
        // Circular Mitochondrial DNA loops, Ribosomes & F0-F1 Oxysome particles
        items += circle(65f, 50f, 3.2f, Ink.VIOLET, fill = false, stroke = 1.0f, layer = 2)
        items += circle(44f, 50f, 1.3f, Ink.TEAL, fillInk = Ink.TEAL, alpha = 0.85f, layer = 2)
        items += circle(56f, 51f, 1.3f, Ink.TEAL, fillInk = Ink.TEAL, alpha = 0.85f, layer = 2)
        items += circle(36f, 48f, 1.2f, Ink.GOLD, fillInk = Ink.GOLD, alpha = 0.90f, layer = 2)
        items += circle(50f, 46f, 1.2f, Ink.GOLD, fillInk = Ink.GOLD, alpha = 0.90f, layer = 2)
        items += circle(46f, 56f, 1.4f, Ink.ACCENT, fillInk = Ink.ACCENT, alpha = 0.75f, layer = 2)

        items += lbl(26f, 40f, "Outer Membrane", Ink.ORANGE, leftSide = true)
        items += lbl(30f, 50f, "Intermembrane Space", Ink.WARN, leftSide = true)
        items += lbl(38f, 42f, "Cristae (Inner Folds)", Ink.ROSE, leftSide = true)
        items += lbl(46f, 56f, "70S Ribosomes", Ink.ACCENT, leftSide = true)
        items += lbl(56f, 37f, "Inner Membrane", Ink.WARN, leftSide = false)
        items += lbl(56f, 51f, "Matrix", Ink.TEAL, leftSide = false)
        items += lbl(50f, 46f, "ATP Synthase (F1)", Ink.GOLD, leftSide = false)
        items += lbl(65f, 50f, "Mitochondrial DNA", Ink.VIOLET, leftSide = false)
        return DiagramSpec("Structure of Mitochondrion", items)
    }

    fun chloroplast(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += oval(50f, 50f, 30f, 18f, Ink.RESULT, fillInk = Ink.RESULT, shade = FillShade.RADIAL, alpha = 0.22f, stroke = 1.35f, layer = 0)
        items += oval(50f, 50f, 27.6f, 15.8f, Ink.TEAL, fillInk = Ink.TEAL, alpha = 0.14f, stroke = 1.0f, layer = 0)
        // Three Grana (coin-like Thylakoid stacks) + Stroma Lamellae channels
        items += line(31f, 46f, 70f, 46f, Ink.RESULT, stroke = 1.15f, layer = 2)
        items += line(31f, 54f, 69f, 54f, Ink.RESULT, stroke = 1.15f, layer = 2)
        for (gx in listOf(34f, 50f, 65f)) {
            for (gy in listOf(41f, 44.5f, 48f, 51.5f, 55f)) {
                items += rect(gx - 4.5f, gy, 9f, 2.7f, Ink.RESULT, fillInk = Ink.RESULT, rx = 1.2f, alpha = 0.52f, stroke = 0.8f, layer = 0)
            }
        }
        // Starch granule & plastid DNA loop
        items += oval(42f, 58f, 4.4f, 2.6f, Ink.GOLD, fillInk = Ink.GOLD, alpha = 0.55f, stroke = 0.9f, layer = 0)
        items += circle(58f, 41f, 2.5f, Ink.VIOLET, fill = false, stroke = 0.95f, layer = 2)

        items += lbl(26f, 38f, "Outer Membrane", Ink.RESULT, leftSide = true)
        items += lbl(29f, 46f, "Inner Membrane", Ink.TEAL, leftSide = true)
        items += lbl(42f, 58f, "Starch Granule", Ink.GOLD, leftSide = true)
        items += lbl(34f, 49f, "Granum (Stack)", Ink.RESULT, leftSide = false)
        items += lbl(65f, 43f, "Thylakoid Disc", Ink.RESULT, leftSide = false)
        items += lbl(58f, 46f, "Stroma Lamella", Ink.TEAL, leftSide = false)
        items += lbl(58f, 41f, "Chloroplast DNA", Ink.VIOLET, leftSide = false)
        items += lbl(50f, 61f, "Stroma Fluid", Ink.NOTE, leftSide = false)
        return DiagramSpec("Structure of Chloroplast", items)
    }

    fun digestiveSystem(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        // Buccal Cavity + Esophagus tube
        items += oval(46f, 14f, 6f, 3.5f, Ink.ROSE, fillInk = Ink.ROSE, alpha = 0.30f, stroke = 1.1f, layer = 0)
        items += rect(46f, 17f, 4f, 18f, Ink.ROSE, fillInk = Ink.ROSE, rx = 1.5f, alpha = 0.28f, stroke = 1.0f, layer = 0)
        // Liver wedge (left side of view) + Gall bladder
        items += poly(listOf(30f, 35f, 48f, 34f, 44f, 44f, 31f, 46f), Ink.BROWN, fillInk = Ink.BROWN, alpha = 0.38f, stroke = 1.15f, layer = 0)
        items += oval(41f, 45f, 2.5f, 3.5f, Ink.RESULT, fillInk = Ink.RESULT, alpha = 0.58f, stroke = 0.9f, layer = 0)
        // J-shaped Stomach & Leaf-shaped Pancreas
        items += poly(listOf(49f, 35f, 59f, 37f, 64f, 46f, 58f, 53f, 48f, 50f, 48f, 43f), Ink.WARN, fillInk = Ink.WARN, alpha = 0.28f, stroke = 1.15f, layer = 0)
        items += oval(53f, 53f, 8.5f, 2.4f, Ink.GOLD, fillInk = Ink.GOLD, rot = -6f, alpha = 0.45f, stroke = 0.95f, layer = 0)
        // Colon (Large intestine frame) enclosing convoluted Small intestine
        items += poly(listOf(33f, 75f, 33f, 58f, 67f, 58f, 67f, 78f, 56f, 79f), Ink.ORANGE, closed = false, fill = false, stroke = 3.2f, layer = 0)
        items += poly(
            listOf(41f, 61f, 59f, 62f, 40f, 66f, 59f, 67f, 40f, 71f, 59f, 72f, 48f, 76f),
            Ink.TEAL, closed = false, fill = false, stroke = 2.0f, layer = 2
        )
        items += rect(48f, 77f, 4f, 10f, Ink.BROWN, fillInk = Ink.BROWN, rx = 1.5f, alpha = 0.34f, stroke = 1.0f, layer = 0)

        items += lbl(42f, 14f, "Buccal Cavity", Ink.ROSE, leftSide = true)
        items += lbl(38f, 39f, "Liver", Ink.BROWN, leftSide = true)
        items += lbl(41f, 45f, "Gall Bladder", Ink.RESULT, leftSide = true)
        items += lbl(33f, 65f, "Large Intestine", Ink.ORANGE, leftSide = true)
        items += lbl(48f, 26f, "Oesophagus", Ink.ROSE, leftSide = false)
        items += lbl(60f, 44f, "Stomach", Ink.WARN, leftSide = false)
        items += lbl(56f, 53f, "Pancreas", Ink.GOLD, leftSide = false)
        items += lbl(52f, 68f, "Small Intestine", Ink.TEAL, leftSide = false)
        items += lbl(50f, 84f, "Rectum & Anus", Ink.BROWN, leftSide = false)
        return DiagramSpec("Human Digestive System", items)
    }

    fun humanEar(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        // Outer Pinna cartilage + Auditory Canal
        items += poly(listOf(28f, 32f, 20f, 40f, 20f, 58f, 27f, 65f, 30f, 56f, 27f, 46f), Ink.ROSE, fillInk = Ink.ROSE, alpha = 0.26f, stroke = 1.25f, layer = 0)
        items += rect(27f, 45f, 18f, 9f, Ink.ROSE, fillInk = Ink.ROSE, rx = 2f, alpha = 0.16f, stroke = 1.0f, layer = 0)
        // Tympanic Membrane (Eardrum) + Ossicles chain
        items += oval(46f, 49.5f, 2.2f, 6.2f, Ink.WARN, fillInk = Ink.WARN, rot = -12f, alpha = 0.45f, stroke = 1.15f, layer = 0)
        items += line(46f, 47f, 51f, 41f, Ink.GOLD, stroke = 1.4f, layer = 2) // Malleus
        items += line(51f, 41f, 56f, 44f, Ink.GOLD, stroke = 1.4f, layer = 2) // Incus
        items += line(56f, 44f, 60f, 46f, Ink.GOLD, stroke = 1.4f, layer = 2) // Stapes
        // Semicircular canals + Snail Cochlea + Auditory nerve + Eustachian tube
        items += arc(63f, 38f, 4.5f, 5.5f, 160f, 220f, Ink.TEAL, stroke = 1.3f, layer = 2)
        items += arc(66f, 40f, 5.5f, 4.5f, -80f, 200f, Ink.TEAL, stroke = 1.2f, layer = 2)
        items += circle(67f, 52f, 6.5f, Ink.ACCENT, fillInk = Ink.ACCENT, alpha = 0.30f, stroke = 1.2f, layer = 0)
        items += arc(67f, 52f, 4.2f, 4.2f, 0f, 270f, Ink.INK, stroke = 1.0f, layer = 2)
        items += arc(67f, 52f, 2.1f, 2.1f, 40f, 250f, Ink.INK, stroke = 0.9f, layer = 2)
        items += line(73f, 50f, 82f, 47f, Ink.VIOLET, stroke = 2.0f, arrow = true, layer = 2)
        items += line(50f, 56f, 42f, 76f, Ink.ORANGE, stroke = 2.2f, layer = 2)

        items += lbl(22f, 46f, "Pinna (Auricle)", Ink.ROSE, leftSide = true)
        items += lbl(36f, 49.5f, "Auditory Canal", Ink.NOTE, leftSide = true)
        items += lbl(46f, 49.5f, "Tympanic Membrane", Ink.WARN, leftSide = true)
        items += lbl(44f, 69f, "Eustachian Tube", Ink.ORANGE, leftSide = true)
        items += lbl(52f, 41f, "Ear Ossicles", Ink.GOLD, leftSide = false)
        items += lbl(65f, 36f, "Semicircular Canals", Ink.TEAL, leftSide = false)
        items += lbl(67f, 52f, "Cochlea", Ink.ACCENT, leftSide = false)
        items += lbl(79f, 48f, "Auditory Nerve", Ink.VIOLET, leftSide = false)
        return DiagramSpec("Human Ear Anatomy", items)
    }

    fun reflexArc(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        // Stimulus flame & Receptor block on left
        items += poly(listOf(22f, 65f, 25f, 58f, 28f, 65f, 26f, 70f, 23f, 70f), Ink.ORANGE, fillInk = Ink.WARN, alpha = 0.65f, stroke = 1.0f, layer = 0)
        items += rect(27f, 51f, 7f, 10f, Ink.TEAL, fillInk = Ink.TEAL, rx = 2f, alpha = 0.36f, stroke = 1.0f, layer = 0)
        // Effector muscle below
        items += oval(32f, 76f, 9f, 4.5f, Ink.ROSE, fillInk = Ink.ROSE, alpha = 0.38f, stroke = 1.1f, layer = 0)
        // Spinal cord cross-section on right with H-shaped Gray Matter
        items += oval(66f, 48f, 13f, 16f, Ink.NOTE, fillInk = Ink.FAINT, alpha = 0.24f, stroke = 1.25f, layer = 0)
        items += poly(
            listOf(60f, 38f, 63f, 44f, 69f, 44f, 72f, 38f, 74f, 41f, 71f, 48f, 74f, 55f, 71f, 58f, 68f, 52f, 64f, 52f, 61f, 58f, 58f, 55f, 61f, 48f, 58f, 41f),
            Ink.ROSE, fillInk = Ink.ROSE, alpha = 0.45f, stroke = 1.0f, layer = 0
        )
        // Sensory pathway (Dorsal) + Ganglion + Relay neuron + Motor pathway (Ventral)
        items += poly(listOf(31f, 51f, 38f, 36f, 50f, 36f, 61f, 41f), Ink.ACCENT, closed = false, fill = false, arrow = true, stroke = 1.5f, layer = 2)
        items += oval(47f, 36f, 4.5f, 3.0f, Ink.ACCENT, fillInk = Ink.ACCENT, alpha = 0.45f, stroke = 1.0f, layer = 0)
        items += line(62f, 42f, 62f, 53f, Ink.GOLD, arrow = true, stroke = 1.5f, layer = 2)
        items += poly(listOf(61f, 54f, 48f, 66f, 40f, 74f), Ink.RESULT, closed = false, fill = false, arrow = true, stroke = 1.5f, layer = 2)

        items += lbl(25f, 63f, "Heat Stimulus", Ink.ORANGE, leftSide = true)
        items += lbl(30f, 54f, "Receptor (Skin)", Ink.TEAL, leftSide = true)
        items += lbl(39f, 41f, "Sensory Neuron", Ink.ACCENT, leftSide = true)
        items += lbl(32f, 76f, "Effector Muscle", Ink.ROSE, leftSide = true)
        items += lbl(47f, 36f, "Dorsal Root Ganglion", Ink.ACCENT, leftSide = false)
        items += lbl(69f, 48f, "Spinal Cord (Gray Matter)", Ink.ROSE, leftSide = false)
        items += lbl(62f, 48f, "Relay Interneuron", Ink.GOLD, leftSide = false)
        items += lbl(48f, 66f, "Motor Neuron", Ink.RESULT, leftSide = false)
        return DiagramSpec("Human Reflex Arc Pathway", items)
    }

    fun solarSystem(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += circle(22f, 50f, 7.2f, Ink.ORANGE, fillInk = Ink.GOLD, shade = FillShade.RADIAL, alpha = 0.72f, stroke = 1.2f, layer = 0)
        val radii = listOf(13f, 18f, 24f, 30f, 36f, 43f, 51f, 57f, 62f)
        for ((idx, r) in radii.withIndex()) {
            items += arc(22f, 50f, r, r * 0.62f, -48f, 96f, if (idx == 4) Ink.NOTE else Ink.FAINT, dashed = idx == 4, stroke = if (idx == 4) 1.4f else 0.75f, layer = 2)
        }
        val planets = listOf(
            Triple(35f, 49f, 1.5f to Ink.NOTE),
            Triple(39.5f, 44f, 2.1f to Ink.ORANGE),
            Triple(45.5f, 53f, 2.4f to Ink.ACCENT),
            Triple(51.5f, 43f, 1.9f to Ink.WARN),
            Triple(63.5f, 54f, 4.4f to Ink.BROWN),
            Triple(71f, 41f, 3.6f to Ink.GOLD),
            Triple(77f, 56f, 2.7f to Ink.TEAL),
            Triple(81f, 45f, 2.6f to Ink.VIOLET)
        )
        for ((px, py, meta) in planets) {
            items += circle(px, py, meta.second, meta.second, fillInk = meta.second, alpha = 0.65f, stroke = 0.9f, layer = 0)
        }
        // Saturn ring
        items += oval(71f, 41f, 6.2f, 1.9f, Ink.ORANGE, rot = -22f, fill = false, stroke = 1.15f, layer = 2)

        items += lbl(22f, 50f, "Sun (Star)", Ink.GOLD, leftSide = true)
        items += lbl(35f, 49f, "Mercury & Venus", Ink.ORANGE, leftSide = true)
        items += lbl(45.5f, 53f, "Earth & Mars", Ink.ACCENT, leftSide = true)
        items += lbl(57.5f, 48f, "Asteroid Belt", Ink.NOTE, leftSide = false)
        items += lbl(63.5f, 54f, "Jupiter", Ink.BROWN, leftSide = false)
        items += lbl(71f, 41f, "Saturn & Rings", Ink.GOLD, leftSide = false)
        items += lbl(79f, 50f, "Uranus & Neptune", Ink.TEAL, leftSide = false)
        return DiagramSpec("The Solar System (Orbits)", items)
    }

    fun carbonCycle(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        items += rect(34f, 14f, 32f, 12f, Ink.TEAL, fillInk = Ink.TEAL, rx = 4f, alpha = 0.28f, stroke = 1.15f, layer = 0)
        items += rect(20f, 44f, 20f, 13f, Ink.RESULT, fillInk = Ink.RESULT, rx = 3f, alpha = 0.30f, stroke = 1.1f, layer = 0)
        items += rect(43f, 44f, 16f, 13f, Ink.VIOLET, fillInk = Ink.VIOLET, rx = 3f, alpha = 0.28f, stroke = 1.1f, layer = 0)
        items += rect(63f, 44f, 19f, 13f, Ink.ORANGE, fillInk = Ink.ORANGE, rx = 3f, alpha = 0.30f, stroke = 1.1f, layer = 0)
        items += rect(32f, 71f, 36f, 13f, Ink.BROWN, fillInk = Ink.BROWN, rx = 4f, alpha = 0.30f, stroke = 1.15f, layer = 0)

        items += line(36f, 26f, 28f, 44f, Ink.RESULT, arrow = true, stroke = 1.4f, layer = 2) // Photosynthesis
        items += line(40f, 50f, 43f, 50f, Ink.ACCENT, arrow = true, stroke = 1.2f, layer = 2) // Food chain
        items += line(51f, 44f, 50f, 26f, Ink.WARN, arrow = true, stroke = 1.3f, layer = 2) // Respiration
        items += line(72f, 44f, 62f, 26f, Ink.ORANGE, arrow = true, stroke = 1.4f, layer = 2) // Combustion
        items += line(49f, 57f, 49f, 71f, Ink.BROWN, arrow = true, stroke = 1.2f, layer = 2) // Decay
        items += line(62f, 71f, 71f, 57f, Ink.BROWN, arrow = true, stroke = 1.2f, layer = 2) // Fossil fuel extraction

        items += lbl(32f, 35f, "Photosynthesis (CO2 In)", Ink.RESULT, leftSide = true)
        items += lbl(30f, 50f, "Green Plants", Ink.RESULT, leftSide = true)
        items += lbl(49f, 64f, "Decomposers & Soil", Ink.BROWN, leftSide = true)
        items += lbl(50f, 20f, "Atmospheric CO2", Ink.TEAL, leftSide = false)
        items += lbl(50f, 35f, "Respiration (CO2 Out)", Ink.WARN, leftSide = false)
        items += lbl(72f, 50f, "Combustion / Industry", Ink.ORANGE, leftSide = false)
        return DiagramSpec("The Carbon Cycle", items)
    }

    fun glassSlabRefraction(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        // Rectangular Glass Slab
        items += rect(26f, 38f, 48f, 24f, Ink.TEAL, fillInk = Ink.TEAL, rx = 1.5f, alpha = 0.24f, stroke = 1.35f, layer = 0)
        // Dotted Normals at entry (42, 38) and exit (54, 62)
        items += line(42f, 22f, 42f, 56f, Ink.NOTE, dashed = true, stroke = 0.9f, layer = 2)
        items += line(54f, 44f, 54f, 80f, Ink.NOTE, dashed = true, stroke = 0.9f, layer = 2)
        // Incident, Refracted, and Emergent Rays
        items += line(24f, 20f, 42f, 38f, Ink.GOLD, arrow = true, stroke = 1.5f, layer = 2)
        items += line(42f, 38f, 54f, 62f, Ink.WARN, arrow = true, stroke = 1.5f, layer = 2)
        items += line(54f, 62f, 74f, 82f, Ink.ACCENT, arrow = true, stroke = 1.5f, layer = 2)
        // Dashed undeviated original path + lateral displacement span
        items += line(42f, 38f, 78f, 74f, Ink.NOTE, dashed = true, stroke = 1.0f, layer = 2)
        items += line(68f, 64f, 63f, 71f, Ink.VIOLET, doubleArrow = true, stroke = 1.1f, layer = 2)
        items += arc(42f, 38f, 8f, 8f, -135f, 45f, Ink.GOLD, stroke = 1.0f, layer = 2)
        items += arc(42f, 38f, 7f, 7f, 63f, 27f, Ink.WARN, stroke = 1.0f, layer = 2)

        items += lbl(33f, 29f, "Incident Ray (∠i)", Ink.GOLD, leftSide = true)
        items += lbl(42f, 26f, "Normal N1", Ink.NOTE, leftSide = true)
        items += lbl(48f, 50f, "Refracted Ray (∠r)", Ink.WARN, leftSide = true)
        items += lbl(68f, 50f, "Glass Slab (n > 1)", Ink.TEAL, leftSide = false)
        items += lbl(65f, 67f, "Lateral Displacement d", Ink.VIOLET, leftSide = false)
        items += lbl(66f, 75f, "Emergent Ray (∠e = ∠i)", Ink.ACCENT, leftSide = false)
        return DiagramSpec("Refraction Through Glass Slab", items)
    }

    fun acGenerator(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        // North and South Magnet Poles
        items += rect(20f, 26f, 13f, 24f, Ink.WARN, fillInk = Ink.WARN, rx = 2f, alpha = 0.34f, stroke = 1.2f, layer = 0)
        items += rect(67f, 26f, 13f, 24f, Ink.ACCENT, fillInk = Ink.ACCENT, rx = 2f, alpha = 0.34f, stroke = 1.2f, layer = 0)
        items += line(33f, 33f, 67f, 33f, Ink.NOTE, arrow = true, dashed = true, stroke = 0.85f, layer = 2)
        items += line(33f, 43f, 67f, 43f, Ink.NOTE, arrow = true, dashed = true, stroke = 0.85f, layer = 2)
        // Rotating Armature ABCD + axle + Slip Rings + Brushes + Galvanometer load
        items += poly(listOf(40f, 28f, 60f, 24f, 60f, 48f, 40f, 52f), Ink.ORANGE, smooth = false, fill = true, fillInk = Ink.GOLD, alpha = 0.22f, stroke = 1.5f, layer = 0)
        items += arc(50f, 22f, 8f, 4f, 190f, 160f, Ink.RESULT, arrow = false, stroke = 1.2f, layer = 2)
        items += line(46f, 51f, 46f, 62f, Ink.ORANGE, stroke = 1.2f, layer = 2)
        items += line(54f, 49f, 54f, 70f, Ink.ORANGE, stroke = 1.2f, layer = 2)
        items += oval(50f, 62f, 8f, 2.5f, Ink.GOLD, fillInk = Ink.GOLD, alpha = 0.45f, stroke = 1.15f, layer = 0)
        items += oval(50f, 70f, 8f, 2.5f, Ink.GOLD, fillInk = Ink.GOLD, alpha = 0.45f, stroke = 1.15f, layer = 0)
        // Carbon Brushes B1 & B2 + external Galvanometer
        items += rect(38f, 60.5f, 4f, 3.5f, Ink.BROWN, fillInk = Ink.BROWN, alpha = 0.85f, layer = 0)
        items += rect(58f, 68.5f, 4f, 3.5f, Ink.BROWN, fillInk = Ink.BROWN, alpha = 0.85f, layer = 0)
        items += poly(listOf(38f, 62f, 28f, 62f, 28f, 82f, 72f, 82f, 72f, 70f, 62f, 70f), Ink.TEAL, smooth = false, fill = false, stroke = 1.15f, layer = 2)
        items += circle(50f, 82f, 4.5f, Ink.TEAL, fillInk = Ink.TEAL, alpha = 0.35f, stroke = 1.15f, layer = 0)

        items += lbl(26f, 38f, "North Pole (N)", Ink.WARN, leftSide = true)
        items += lbl(40f, 62f, "Brush B1 & Ring S1", Ink.BROWN, leftSide = true)
        items += lbl(50f, 82f, "External Load / G", Ink.TEAL, leftSide = true)
        items += lbl(73f, 38f, "South Pole (S)", Ink.ACCENT, leftSide = false)
        items += lbl(54f, 30f, "Armature Coil ABCD", Ink.ORANGE, leftSide = false)
        items += lbl(58f, 70f, "Slip Ring S2 & B2", Ink.GOLD, leftSide = false)
        return DiagramSpec("AC Electric Generator", items)
    }

    fun solenoidField(): DiagramSpec {
        val items = ArrayList<DiagramSpec.Item>()
        // Insulating core cylinder
        items += rect(32f, 38f, 36f, 16f, Ink.TEAL, fillInk = Ink.TEAL, rx = 2f, alpha = 0.16f, stroke = 1.0f, layer = 0)
        // Helical Copper Windings
        for (i in 0..6) {
            val cx = 35f + i * 5f
            items += oval(cx, 46f, 1.8f, 9.2f, Ink.ORANGE, fill = false, stroke = 1.35f, layer = 2)
        }
        // Uniform parallel magnetic field inside solenoid (S -> N)
        items += line(20f, 46f, 80f, 46f, Ink.ACCENT, arrow = true, stroke = 1.4f, layer = 2)
        items += line(24f, 42f, 76f, 42f, Ink.ACCENT, arrow = true, stroke = 1.05f, layer = 2)
        items += line(24f, 50f, 76f, 50f, Ink.ACCENT, arrow = true, stroke = 1.05f, layer = 2)
        // External closed field loops
        items += arc(50f, 34f, 24f, 16f, 185f, 170f, Ink.VIOLET, dashed = true, stroke = 1.05f, layer = 2)
        items += arc(50f, 58f, 24f, 16f, 5f, 170f, Ink.VIOLET, dashed = true, stroke = 1.05f, layer = 2)
        // Battery & circuit loop at bottom
        items += poly(listOf(35f, 55f, 35f, 78f, 46f, 78f), Ink.NOTE, smooth = false, fill = false, stroke = 1.1f, layer = 2)
        items += poly(listOf(65f, 55f, 65f, 78f, 54f, 78f), Ink.NOTE, smooth = false, fill = false, stroke = 1.1f, layer = 2)
        items += line(48f, 73f, 48f, 83f, Ink.WARN, stroke = 1.6f, layer = 2)
        items += line(52f, 75f, 52f, 81f, Ink.ACCENT, stroke = 1.6f, layer = 2)

        items += lbl(26f, 46f, "South Pole (S)", Ink.ACCENT, leftSide = true)
        items += lbl(45f, 38f, "Copper Solenoid Coil", Ink.ORANGE, leftSide = true)
        items += lbl(50f, 78f, "DC Battery Supply", Ink.WARN, leftSide = true)
        items += lbl(76f, 46f, "North Pole (N)", Ink.WARN, leftSide = false)
        items += lbl(55f, 46f, "Uniform Interior Field", Ink.ACCENT, leftSide = false)
        items += lbl(50f, 20f, "External Flux Loops", Ink.VIOLET, leftSide = false)
        return DiagramSpec("Magnetic Field of a Solenoid", items)
    }
}
