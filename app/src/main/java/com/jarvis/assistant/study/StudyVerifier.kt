package com.jarvis.assistant.study

/**
 * Re-computes the pure arithmetic inside a step, such as "10 / 2 = 5" or "x = 3 x 4 = 12". A side that contains a variable,
 * a unit or anything it does not fully understand is skipped, so this check can only ever catch a real arithmetic error;
 * it never claims more than it checked.
 */
object StudyVerifier {

    sealed class Result {
        object NotApplicable : Result()
        data class Ok(val comparisons: Int) : Result()
        data class Mismatch(val left: String, val right: String, val leftValue: Double, val rightValue: Double) : Result() {
            fun describe(): String = "$left = ${trim(leftValue)} but $right = ${trim(rightValue)}"
        }
    }

    private val ARROWS = Regex("\\\\(Rightarrow|implies|rightarrow|to|Longrightarrow|therefore)|⇒|→|⟹|∴")
    private val SEGMENT_BREAK = Regex(ARROWS.pattern + "|[\\n;]")
    private val APPROX = Regex("\\\\(approx|simeq|sim)|≈|≃")
    private val OTHER_RELATIONS = Regex("\\\\(neq|ne|leq|geq|le|ge|lt|gt|propto|equiv)|[<>≠≤≥∝≡]")

    fun check(content: String): Result {
        val text = content.trim()
        if (text.isEmpty() || !text.any { it.isDigit() }) return Result.NotApplicable
        if (OTHER_RELATIONS.containsMatchIn(text)) return Result.NotApplicable
        if (Regex("\\d,\\d").containsMatchIn(text)) return Result.NotApplicable
        if (text.contains('%')) return Result.NotApplicable

        var comparisons = 0
        val segments = text.split(SEGMENT_BREAK).map { it.trim() }.filter { it.isNotEmpty() }
        for (segment in segments) {
            val approx = APPROX.containsMatchIn(segment)
            val unified = if (approx) segment.replace(APPROX, "=") else segment
            val sides = unified.split('=').map { it.trim() }
            if (sides.size < 2) continue
            val numeric = ArrayList<Triple<String, Double, Double?>>()
            for (side in sides) {
                if (side.isEmpty()) continue
                val v = StudyExpression.evaluate(side) ?: continue
                numeric.add(Triple(side, v, StudyExpression.literalHalfUnit(side)))
            }
            if (numeric.size < 2) continue
            for (i in 1 until numeric.size) {
                val (ls, lv, lh) = numeric[0]
                val (rs, rv, rh) = numeric[i]
                comparisons++
                if (!StudyExpression.close(lv, rv, rh ?: lh, approx)) {
                    return Result.Mismatch(ls, rs, lv, rv)
                }
            }
        }
        return if (comparisons > 0) Result.Ok(comparisons) else Result.NotApplicable
    }

    private fun trim(v: Double): String {
        val r = Math.round(v * 1_000_000.0) / 1_000_000.0
        return if (r == Math.floor(r) && Math.abs(r) < 1e12) r.toLong().toString() else r.toString()
    }
}
