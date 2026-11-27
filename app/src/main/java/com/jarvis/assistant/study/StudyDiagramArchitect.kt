package com.jarvis.assistant.study

import com.google.gson.JsonParser

/**
 * Pure-Kotlin prompt & parser engine for generating studio-grade vector diagrams of ANY topic on demand.
 * Separates visual geometry planning from prose narration so complex circuits, mechanical systems, biology,
 * architecture, chemistry apparatus, math geometry, and custom diagrams render with exact alignment and crisp labels.
 */
object StudyDiagramArchitect {

    fun systemPrompt(profile: StudyProfile): String = """
You are an expert technical illustrator & textbook STEM diagram architect for a digital whiteboard.
Your ONLY job is to output ONE ultra-clean, mathematically aligned, richly detailed JSON diagram object (no markdown, no code fences, no extra text).

${profile.levelHint()}
${profile.languageRule()}

OUTPUT FORMAT (strictly one JSON object):
{"title":"Concise Descriptive Title","items":[...],"graph":optional}

COORDINATE SYSTEM & COMPOSITION:
- Use `0..100` floating-point coordinates (y grows downward).
- Keep core artwork centered in `x = 18..82, y = 12..88` when using external callouts so callout rails have plenty of margin on left (`x = 6..16`) and right (`x = 84..94`).
- For flowcharts, architectures, block diagrams, cycles, circuits, and geometry figures that use shape-embedded `"text"` or `"place":"inline"` labels (without external callouts), use the full `x = 8..92, y = 12..88` canvas!
- Align joints accurately: connected wires, tubes, arrows, flasks, lenses, and nodes MUST touch at exact shared coordinates (zero floating gaps, zero sloppy overlaps).

VECTOR PRIMITIVES ("t"):
1. Smooth SVG Path ("t":"path"): BEST for organic anatomy, glassware, waves, curved tubes, mechanical profiles, wings, leaves, vessels, arcs!
   {"t":"path","d":"M 25 40 C 25 20, 75 20, 75 40 Q 75 70, 50 82 C 25 70, 25 55, 25 40 Z","fill":true,"color":"ink","fillColor":"rose","shade":"radial","layer":"outline"}
   Supports M/m, L/l, H/h, V/v, C/c, S/s, Q/q, T/t, A/a, Z/z!
2. Geometric Shapes (support optional `"text":"..."` centered INSIDE the shape for boxes, nodes, layers, sets, cells, poles):
   - {"t":"rect","x":..,"y":..,"w":..,"h":..,"rx":3,"fill":true,"color":"accent","fillColor":"accent","shade":"linear","text":"Control Unit"}
   - {"t":"circle","cx":..,"cy":..,"r":..,"fill":true,"color":"violet","fillColor":"violet","shade":"radial","text":"Nucleus"}
   - {"t":"oval","cx":..,"cy":..,"rx":..,"ry":..,"rotate":deg,"fill":true,"color":"teal","fillColor":"teal","shade":"radial"}
   - {"t":"arc","cx":..,"cy":..,"rx":..,"ry":..,"start":deg,"sweep":deg,"sector":false,"fill":false,"color":"gold"}
   - {"t":"poly","pts":[[x,y],...],"closed":true,"smooth":true|false,"fill":true,"color":"ink","fillColor":"teal","text":"Optional"}
3. Precision STEM & Engineering Hardware Components (ALWAYS use these instead of rough hand-drawn approximations!):
   - Circuit: "resistor", "capacitor", "inductor" (also "turns":6), "battery" ("cells":2), "switch" ("open":true|false), "diode" ("led":true|false) with x1,y1,x2,y2,text
   - Meters/Bulbs/Ground: "meter" ("cx":..,"cy":..,"r":5,"symbol":"A"|"V"|"G"), "bulb" ("cx":..,"cy":..,"r":5,"text":..), "ground" ("x":..,"y":..)
   - Mechanics: "spring" (x1,y1,x2,y2,"coils":7), "gear" ("cx":..,"cy":..,"r":10,"teeth":12), "pulley" ("cx":..,"cy":..,"r":7,"mountUp":true)
   - Lab Glassware: "flask" ("cx":..,"cy":..,"w":18,"h":28,"style":"round"|"conical"|"beaker"|"test_tube","liquid":0.45,"liquidColor":"teal"), "burner" ("cx":..,"y":botY,"h":18), "thermometer" ("x":..,"y1":..,"y2":..), "condenser" (x1,y1,x2,y2,"w":7.5)
   - Flowchart/CS: "diamond" ("cx":..,"cy":..,"w":24,"h":16,"text":"x > 0?"), "cylinder" ("x":..,"y":..,"w":20,"h":24,"text":"Database")
   - Annotations/Vectors: "arrow" / "line" (x1,y1,x2,y2,"dashed":false,"doubleArrow":false,"text":"optional edge label"), "curved_arrow" (x1,y1,cx,cy,x2,y2,"text":"optional"), "dimension" (x1,y1,x2,y2,"offset":5,"text":"5 cm"), "angle" (cx,cy,r,start,sweep,"text":"θ")
4. Labels ("t":"label"):
   - USE "place":"inside" or put "text" directly on rect/circle/diamond/cylinder for labels that belong INSIDE shapes!
   - USE "place":"inline" (without ax/ay) for short angle symbols ("θ", "90°"), triangle vertices ("A", "B", "C"), polarity signs, or arrow labels!
   - USE "place":"callout" WITH exact target pin coordinates ("ax", "ay") touching the specific structure, and x=8..14 (left) or x=86..92 (right) for anatomical/apparatus callouts! Keep callouts non-overlapping and concise (1-4 words).
5. Colors ("color", "fillColor"): "ink", "accent", "red", "green", "gold", "violet", "teal", "rose", "orange", "brown", "gray", "note".
6. Layers ("layer"): "outline" (outer structure), "fill" (interior wash), "detail" (internal fibers, arrows, axes, textures), "label".

QUALITY BAR:
- Build 20-65 finely crafted, layered primitives covering EVERY important small detail, sub-part, directional flow, and dimension of the subject.
- Never let labels overlap arrows or shapes; put box/node text inside the shapes, put vertex/measurement text inline, and put anatomical/apparatus part names on clean left/right callout rails with exact (ax, ay) anchors.
""".trimIndent()

    fun userPrompt(
        title: String,
        question: String,
        context: String = "",
        draftJson: String = ""
    ): String {
        val sb = StringBuilder()
        sb.append("Design a textbook-perfect, richly detailed studio diagram for:\n")
        if (title.isNotBlank()) sb.append("Diagram Title: ").append(title.trim()).append('\n')
        sb.append("Student Question / Request:\n").append(question.trim()).append('\n')
        if (context.isNotBlank()) {
            sb.append("\nTeaching Context & Exact Values to Preserve:\n").append(context.trim().take(1500)).append('\n')
        }
        if (draftJson.isNotBlank()) {
            sb.append("\nUpgrade this rough draft into crisp, accurately aligned vector artwork with smooth Bezier paths, precision components, and perfect label placement:\n")
            sb.append(draftJson.take(2500)).append('\n')
        }
        sb.append("\nOutput ONLY the single JSON object.")
        return sb.toString()
    }

    /** Extracts and parses one [DiagramSpec] from the model reply along with its canonical JSON representation. */
    fun parseReply(raw: String): Pair<DiagramSpec, String>? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val jsonStr = raw.substring(start, end + 1)
        val obj = try {
            JsonParser.parseString(jsonStr).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val spec = DiagramSpec.parse(obj) ?: return null
        if (spec.items.isEmpty() && spec.graph == null) return null
        return spec to jsonStr
    }

    /** True when a streamed diagram is rough/sparse and benefits from a dedicated precision pass. */
    fun needsPrecisionUpgrade(spec: DiagramSpec?): Boolean {
        if (spec == null) return false
        if (spec.graph != null && spec.items.isEmpty()) return false
        val shapeCount = spec.items.count { it !is DiagramSpec.Item.Label && it !is DiagramSpec.Item.Dot }
        val hasVectorPaths = spec.items.any { it is DiagramSpec.Item.VectorPath }
        val hasShapeText = spec.items.any {
            (it is DiagramSpec.Item.Rect && it.text.isNotBlank()) ||
                (it is DiagramSpec.Item.Circle && it.text.isNotBlank()) ||
                (it is DiagramSpec.Item.Poly && it.text.isNotBlank())
        }
        return shapeCount < 14 || (!hasVectorPaths && !hasShapeText && shapeCount < 22)
    }
}
