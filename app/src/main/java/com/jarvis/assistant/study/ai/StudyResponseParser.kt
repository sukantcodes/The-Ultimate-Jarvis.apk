package com.jarvis.assistant.study.ai

import com.jarvis.assistant.study.model.EducationLevel
import com.jarvis.assistant.study.model.StudyDiagramSpec
import com.jarvis.assistant.study.model.StudyJsonCodec
import com.jarvis.assistant.study.model.StudyStep
import com.jarvis.assistant.study.model.StudySubject
import com.jarvis.assistant.study.model.VerificationStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * Incremental streaming parser for Study Board AI responses.
 *
 * Supports two wire formats transparently:
 * 1. Streaming NDJSON lines (`{"kind":"meta",...}`, `{"kind":"step",...}`, `{"kind":"final",...}`)
 *    so Step 1 is validated and rendered immediately while Step 2+ are still arriving.
 * 2. Standard single JSON object (`{"subject":...,"steps":[{...},{...}],"final_answer":...}`)
 *    streamed chunk-by-chunk: extracts metadata when `"steps"` opens and emits each step `{...}`
 *    the moment its closing brace arrives.
 *
 * Tolerates markdown code fences, partial trailing chunks, malformed escapes, and missing optional fields.
 */
class StudyResponseParser {

    sealed class ParsedEvent {
        data class Meta(
            val subject: StudySubject,
            val topic: String,
            val chapter: String,
            val difficulty: String,
            val educationLevel: EducationLevel,
            val question: String,
            val givenValues: List<String>,
            val requiredQuantity: String,
            val formulas: List<String>,
            val definitions: List<String>
        ) : ParsedEvent()

        data class Step(val step: StudyStep) : ParsedEvent()

        data class Final(
            val finalAnswer: String,
            val verificationStatus: VerificationStatus,
            val verificationNote: String
        ) : ParsedEvent()
    }

    private val rawBuffer = StringBuilder()
    private var scanPos = 0
    private var ndjsonMode: Boolean? = null
    private var emittedMeta = false
    private var emittedFinal = false
    private var emittedStepCount = 0

    // State for monolithic JSON step extraction
    private var stepsArrayStart = -1
    private var stepsArrayEnd = -1
    private var stepScanPos = -1

    fun feed(delta: String): List<ParsedEvent> {
        if (delta.isEmpty()) return emptyList()
        rawBuffer.append(delta)
        val events = ArrayList<ParsedEvent>()

        if (ndjsonMode == null) {
            detectMode()
        }

        if (ndjsonMode == true) {
            parseNdjsonLines(events, flushTrailing = false)
        } else if (ndjsonMode == false) {
            parseMonolithicIncremental(events)
        }
        return events
    }

    fun finish(): List<ParsedEvent> {
        val events = ArrayList<ParsedEvent>()
        if (ndjsonMode == null) detectMode()

        if (ndjsonMode == true) {
            parseNdjsonLines(events, flushTrailing = true)
        } else {
            parseMonolithicIncremental(events)
            if (!emittedMeta || !emittedFinal) {
                tryParseCompleteDocument(events)
            }
        }
        return events
    }

    fun rawText(): String = rawBuffer.toString()

    private fun detectMode() {
        val cleaned = stripMarkdownFences(rawBuffer.toString()).trimStart()
        if (cleaned.isEmpty()) return
        val firstBrace = cleaned.indexOf('{')
        if (firstBrace < 0) return
        val sample = cleaned.substring(firstBrace)
        // Wait until we see enough characters to distinguish NDJSON (`"kind"`) from monolithic (`"steps"` / `"subject"`)
        if (sample.contains("\"kind\"")) {
            ndjsonMode = true
        } else if (sample.contains("\"steps\"") || sample.contains("\"subject\"") || sample.contains("\"final_answer\"") || sample.contains("\"finalAnswer\"")) {
            ndjsonMode = false
        } else if (sample.indexOf('\n') > 0 && sample.indexOf('}') in 1 until sample.indexOf('\n')) {
            ndjsonMode = true
        }
    }

    private fun parseNdjsonLines(out: MutableList<ParsedEvent>, flushTrailing: Boolean) {
        while (scanPos < rawBuffer.length) {
            val nl = rawBuffer.indexOf('\n', scanPos)
            val end = if (nl >= 0) nl else if (flushTrailing) rawBuffer.length else break
            val line = rawBuffer.substring(scanPos, end).trim()
            scanPos = if (nl >= 0) nl + 1 else rawBuffer.length
            if (line.isEmpty() || line.startsWith("```")) continue

            val jsonCandidate = extractBalancedJson(line) ?: continue
            val obj = tryParseObject(jsonCandidate) ?: continue
            dispatchLineObject(obj, out)
        }
    }

    private fun dispatchLineObject(obj: JSONObject, out: MutableList<ParsedEvent>) {
        val kind = obj.optString("kind").lowercase().trim()
        when (kind) {
            "meta", "header", "metadata" -> {
                if (!emittedMeta) {
                    emittedMeta = true
                    out += parseMeta(obj)
                }
            }
            "step" -> {
                val step = StudyJsonCodec.decodeStep(obj, emittedStepCount)
                emittedStepCount++
                out += ParsedEvent.Step(step.copy(stepIndex = emittedStepCount - 1))
            }
            "final", "answer", "result" -> {
                if (!emittedFinal) {
                    emittedFinal = true
                    out += parseFinal(obj)
                }
            }
            else -> {
                if (obj.has("steps")) {
                    // Single monolithic JSON happened to arrive on one line
                    if (!emittedMeta) {
                        emittedMeta = true
                        out += parseMeta(obj)
                    }
                    val steps = obj.optJSONArray("steps")
                    if (steps != null) {
                        for (i in emittedStepCount until steps.length()) {
                            val stepObj = steps.optJSONObject(i) ?: continue
                            val step = StudyJsonCodec.decodeStep(stepObj, i)
                            emittedStepCount++
                            out += ParsedEvent.Step(step.copy(stepIndex = i))
                        }
                    }
                    if (!emittedFinal && (obj.has("final_answer") || obj.has("finalAnswer"))) {
                        emittedFinal = true
                        out += parseFinal(obj)
                    }
                } else if (obj.has("boardText") || obj.has("board_text") || obj.has("content")) {
                    val step = StudyJsonCodec.decodeStep(obj, emittedStepCount)
                    emittedStepCount++
                    out += ParsedEvent.Step(step.copy(stepIndex = emittedStepCount - 1))
                }
            }
        }
    }

    private fun parseMonolithicIncremental(out: MutableList<ParsedEvent>) {
        val text = rawBuffer.toString()
        if (stepsArrayStart < 0) {
            val stepsKeyIdx = findJsonKeyOutsideStrings(text, "steps")
            if (stepsKeyIdx >= 0) {
                val bracket = text.indexOf('[', stepsKeyIdx)
                if (bracket >= 0) {
                    stepsArrayStart = bracket
                    stepScanPos = bracket + 1
                    if (!emittedMeta) {
                        val headerStub = buildPartialRootHeader(text, bracket)
                        headerStub?.let {
                            emittedMeta = true
                            out += parseMeta(it)
                        }
                    }
                }
            }
        }

        if (stepsArrayStart >= 0 && stepsArrayEnd < 0) {
            val extracted = extractCompletedArrayObjects(text, stepScanPos)
            stepScanPos = extracted.nextPos
            if (extracted.arrayClosed) {
                stepsArrayEnd = extracted.nextPos
            }
            for (stepJson in extracted.objects) {
                val stepObj = tryParseObject(stepJson) ?: continue
                val step = StudyJsonCodec.decodeStep(stepObj, emittedStepCount)
                emittedStepCount++
                out += ParsedEvent.Step(step.copy(stepIndex = emittedStepCount - 1))
            }
        }
    }

    private fun tryParseCompleteDocument(out: MutableList<ParsedEvent>) {
        val cleaned = stripMarkdownFences(rawBuffer.toString()).trim()
        val firstBrace = cleaned.indexOf('{')
        val lastBrace = cleaned.lastIndexOf('}')
        if (firstBrace < 0 || lastBrace <= firstBrace) return
        val slice = cleaned.substring(firstBrace, lastBrace + 1)
        val root = tryParseObject(slice) ?: return

        if (!emittedMeta) {
            emittedMeta = true
            out += parseMeta(root)
        }
        val steps = root.optJSONArray("steps")
        if (steps != null) {
            for (i in emittedStepCount until steps.length()) {
                val stepObj = steps.optJSONObject(i) ?: continue
                val step = StudyJsonCodec.decodeStep(stepObj, i)
                emittedStepCount++
                out += ParsedEvent.Step(step.copy(stepIndex = i))
            }
        }
        if (!emittedFinal) {
            val ans = root.optString("final_answer").ifBlank { root.optString("finalAnswer") }
            if (ans.isNotBlank() || root.has("final_answer") || root.has("finalAnswer")) {
                emittedFinal = true
                out += parseFinal(root)
            }
        }
    }

    private fun parseMeta(obj: JSONObject): ParsedEvent.Meta {
        val subject = StudySubject.fromLabel(obj.optString("subject"))
        val topic = obj.optString("topic")
        val chapter = obj.optString("chapter")
        val difficulty = obj.optString("difficulty")
        val level = EducationLevel.fromId(
            obj.optString("education_level").ifBlank { obj.optString("educationLevel") }
        )
        val question = obj.optString("question")
        val given = StudyJsonCodec.readStringList(obj.optJSONArray("given_values") ?: obj.optJSONArray("givenValues"))
        val required = obj.optString("required_quantity").ifBlank {
            obj.optString("requiredQuantity").ifBlank { obj.optString("required_values") }
        }
        val formulas = StudyJsonCodec.readStringList(obj.optJSONArray("formulas") ?: obj.optJSONArray("formula"))
            .ifEmpty {
                obj.optString("formula").takeIf { it.isNotBlank() && !it.startsWith("[") }?.let { listOf(it) }.orEmpty()
            }
        val definitions = StudyJsonCodec.readStringList(obj.optJSONArray("definitions"))
        return ParsedEvent.Meta(
            subject = subject,
            topic = topic,
            chapter = chapter,
            difficulty = difficulty,
            educationLevel = level,
            question = question,
            givenValues = given,
            requiredQuantity = required,
            formulas = formulas,
            definitions = definitions
        )
    }

    private fun parseFinal(obj: JSONObject): ParsedEvent.Final {
        val answer = obj.optString("final_answer").ifBlank { obj.optString("finalAnswer") }
        val statusStr = obj.optString("verification_status").ifBlank { obj.optString("verificationStatus") }
        val status = when {
            statusStr.equals("verified", ignoreCase = true) -> VerificationStatus.VERIFIED
            statusStr.equals("uncertain", ignoreCase = true) || statusStr.equals("unverified", ignoreCase = true) -> VerificationStatus.UNCERTAIN
            answer.isNotBlank() -> VerificationStatus.VERIFIED
            else -> VerificationStatus.UNCERTAIN
        }
        val note = obj.optString("verification_note").ifBlank { obj.optString("verificationNote") }
        return ParsedEvent.Final(answer, status, note)
    }

    private fun buildPartialRootHeader(text: String, stepsBracketPos: Int): JSONObject? {
        val firstBrace = text.indexOf('{')
        if (firstBrace < 0 || stepsBracketPos <= firstBrace) return null
        val prefix = text.substring(firstBrace, stepsBracketPos)
        // Strip trailing `"steps":` before `[` so we can close `}` cleanly and parse root metadata.
        val stepsKeyIdx = prefix.lastIndexOf("\"steps\"")
        val headerBody = (if (stepsKeyIdx > 0) prefix.substring(0, stepsKeyIdx) else prefix)
            .trimEnd()
            .trimEnd(',')
        return tryParseObject("$headerBody}")
    }

    private class ArrayScanResult(val objects: List<String>, val nextPos: Int, val arrayClosed: Boolean)

    private fun extractCompletedArrayObjects(text: String, startPos: Int): ArrayScanResult {
        val objects = ArrayList<String>()
        var i = startPos
        var inString = false
        var escaped = false
        var depth = 0
        var objStart = -1
        var lastSafePos = startPos
        var closed = false

        while (i < text.length) {
            val ch = text[i]
            if (escaped) {
                escaped = false
                i++
                continue
            }
            if (inString) {
                if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
                i++
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) objStart = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && objStart >= 0) {
                            objects += text.substring(objStart, i + 1)
                            objStart = -1
                            lastSafePos = i + 1
                        }
                    }
                }
                ']' -> {
                    if (depth == 0) {
                        lastSafePos = i + 1
                        closed = true
                        break
                    }
                }
            }
            i++
        }
        return ArrayScanResult(objects, lastSafePos, closed)
    }

    private fun findJsonKeyOutsideStrings(text: String, key: String): Int {
        val target = "\"$key\""
        var idx = text.indexOf(target)
        while (idx >= 0) {
            val after = idx + target.length
            var j = after
            while (j < text.length && text[j].isWhitespace()) j++
            if (j < text.length && text[j] == ':') return idx
            idx = text.indexOf(target, idx + 1)
        }
        return -1
    }

    private fun extractBalancedJson(line: String): String? {
        val start = line.indexOf('{')
        val end = line.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return line.substring(start, end + 1)
    }

    private fun stripMarkdownFences(raw: String): String {
        return raw.replace(Regex("```[a-zA-Z0-9_-]*\\s*"), "")
            .replace("```", "")
    }

    private fun tryParseObject(json: String): JSONObject? {
        val cleaned = json.replace(Regex(",\\s*([}\\]])"), "$1")
        return try {
            JSONObject(cleaned)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** Convenience parser for a standalone Doubt Mode JSON response. */
        fun parseDoubtJson(raw: String): JSONObject? {
            val stripped = raw.replace(Regex("```[a-zA-Z0-9_-]*\\s*"), "").replace("```", "").trim()
            val start = stripped.indexOf('{')
            val end = stripped.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val candidate = stripped.substring(start, end + 1).replace(Regex(",\\s*([}\\]])"), "$1")
            return try {
                JSONObject(candidate)
            } catch (_: Exception) {
                null
            }
        }

        /** Convenience parser for OCR / PDF question candidates JSON. */
        fun parseQuestionCandidatesJson(raw: String): JSONObject? = parseDoubtJson(raw)
    }
}
