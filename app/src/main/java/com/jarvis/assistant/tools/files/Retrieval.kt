package com.jarvis.assistant.tools.files

import kotlin.math.ln

data class Chunk(val id: Int, val source: String, val label: String, val text: String, val start: Int)

/** Splits documents into overlapping pieces at paragraph / sentence boundaries. Every character ends up in some chunk. */
object Chunker {
    fun chunk(source: String, label: String, text: String, target: Int = 1500, overlap: Int = 150, firstId: Int = 0): List<Chunk> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<Chunk>(); var pos = 0; var id = firstId
        while (pos < text.length) {
            var end = minOf(text.length, pos + target)
            if (end < text.length) {
                val window = text.substring(pos + target / 2, end)
                val cut = listOf(window.lastIndexOf("\n\n"), window.lastIndexOf('\n'), window.lastIndexOf(". "), window.lastIndexOf(' ')).firstOrNull { it > 0 }
                if (cut != null) end = pos + target / 2 + cut + 1
            }
            out += Chunk(id++, source, label, text.substring(pos, end), pos)
            if (end >= text.length) break
            pos = maxOf(end - overlap, pos + 1)
        }
        return out
    }

    fun chunk(doc: ExtractedDocument, target: Int = 1500, overlap: Int = 150): List<Chunk> {
        val all = ArrayList<Chunk>()
        for (s in doc.sections) all += chunk(doc.name, s.label, s.text, target, overlap, all.size)
        return all
    }
}

data class Hit(val chunk: Chunk, val score: Double)

/** BM25 keyword ranking over chunks. Works offline, with no embeddings model. */
class ChunkIndex(val chunks: List<Chunk>) {
    private val k1 = 1.4; private val b = 0.75
    private val docs: List<Map<String, Int>> = chunks.map { c -> tokens(c.text + " " + c.label).groupingBy { it }.eachCount() }
    private val lens = docs.map { it.values.sum() }
    private val avg = if (lens.isEmpty()) 1.0 else lens.average().coerceAtLeast(1.0)
    private val df: Map<String, Int> = HashMap<String, Int>().also { m -> docs.forEach { d -> d.keys.forEach { m[it] = (m[it] ?: 0) + 1 } } }

    fun search(query: String, limit: Int = 8): List<Hit> {
        val q = tokens(query).distinct(); if (q.isEmpty() || chunks.isEmpty()) return emptyList()
        val n = chunks.size.toDouble()
        return chunks.indices.mapNotNull { i ->
            var s = 0.0
            for (t in q) { val f = docs[i][t] ?: continue; val d = df[t] ?: 1; val idf = ln(1 + (n - d + 0.5) / (d + 0.5)); s += idf * f * (k1 + 1) / (f + k1 * (1 - b + b * lens[i] / avg)) }
            if (s > 0) Hit(chunks[i], s) else null
        }.sortedByDescending { it.score }.take(limit)
    }

    companion object {
        fun tokens(s: String): List<String> = Regex("[\\p{L}\\p{N}_]+").findAll(s.lowercase()).map { it.value }.filter { it.length > 1 }.toList()
    }
}

/** Result of fitting a document into a model's context window. Always says what was left out. */
data class PackedContext(val text: String, val includedChunks: Int, val totalChunks: Int, val complete: Boolean, val note: String)

object ContextPacker {
    /**
     * Whole document when it fits [budgetChars]. Otherwise the chunks that best match [query] (in document order),
     * with an explicit note. Never cuts quietly.
     */
    fun pack(doc: ExtractedDocument, query: String?, budgetChars: Int): PackedContext {
        val chunks = Chunker.chunk(doc, target = minOf(1500, maxOf(200, budgetChars / 3)), overlap = 0)
        val full = doc.text
        if (full.length <= budgetChars) return PackedContext(full, chunks.size, chunks.size, true, "The whole document is included.")
        if (query.isNullOrBlank()) {
            val head = chunks.takeWhileSum(budgetChars)
            return PackedContext(head.joinToString("\n…\n") { it.text }, head.size, chunks.size, false,
                "Only the first ${head.size} of ${chunks.size} parts fit. Ask about a specific topic, or use search_document, to reach the rest.")
        }
        val hits = ChunkIndex(chunks).search(query, chunks.size)
        var used = 0; val chosen = ArrayList<Chunk>()
        for (h in hits) { if (used + h.chunk.text.length > budgetChars) continue; chosen += h.chunk; used += h.chunk.text.length }
        chosen.sortBy { it.id }
        val body = chosen.joinToString("\n…\n") { (if (it.label.isNotBlank()) "[${it.label}] " else "") + it.text }
        return PackedContext(body, chosen.size, chunks.size, false, "Included the ${chosen.size} most relevant of ${chunks.size} parts for \"$query\". The rest of the document was not included.")
    }

    private fun List<Chunk>.takeWhileSum(budget: Int): List<Chunk> { var u = 0; val o = ArrayList<Chunk>(); for (c in this) { if (u + c.text.length > budget && o.isNotEmpty()) break; o += c; u += c.text.length }; return o }
}

object FileComparer {
    /** Line-based comparison: counts and a bounded list of changed lines. */
    fun compare(a: ExtractedDocument, b: ExtractedDocument, maxLines: Int = 80): String {
        val la = a.text.lines(); val lb = b.text.lines()
        val sa = la.toHashSet(); val sb = lb.toHashSet()
        val removed = la.filter { it.isNotBlank() && it !in sb }; val added = lb.filter { it.isNotBlank() && it !in sa }
        val sb2 = StringBuilder("${a.name}: ${la.size} lines, ${a.text.length} characters\n${b.name}: ${lb.size} lines, ${b.text.length} characters\n")
        if (removed.isEmpty() && added.isEmpty()) return sb2.append("The two files have the same lines (order may differ).").toString()
        sb2.append("Only in ${a.name}: ${removed.size} lines; only in ${b.name}: ${added.size} lines\n")
        removed.take(maxLines).forEach { sb2.append("- ").append(it.take(200)).append('\n') }
        if (removed.size > maxLines) sb2.append("… ${removed.size - maxLines} more removed lines not shown\n")
        added.take(maxLines).forEach { sb2.append("+ ").append(it.take(200)).append('\n') }
        if (added.size > maxLines) sb2.append("… ${added.size - maxLines} more added lines not shown\n")
        return sb2.toString()
    }
}

/** What a given model can take as input. Set per provider/model by the app, never guessed from the name alone. */
data class ModelCapabilities(val textContextChars: Int = 60_000, val vision: Boolean = false, val nativePdf: Boolean = false, val maxImageBytes: Long = 5L * 1024 * 1024)

enum class AttachmentRoute { TEXT, IMAGE_NATIVE, OCR_THEN_TEXT, UNREADABLE }

data class AttachmentPlan(val info: AttachmentInfo, val route: AttachmentRoute, val packed: PackedContext?, val explanation: String)

/** Decides how each attachment reaches the model and tells the user honestly when something can not be read. */
class AttachmentPlanner(private val extractor: DocumentExtractor = DocumentExtractor(), private val ocr: OcrEngine? = null) {
    suspend fun plan(src: FileSource, caps: ModelCapabilities, query: String?): AttachmentPlan {
        val mime = src.mime ?: Mimes.guess(src.name)
        fun info(s: AttachmentStatus, d: String) = AttachmentInfo(src.name, mime, src.bytes.size.toLong(), s, d)
        val doc = try { extractor.extract(src) } catch (e: UnsupportedFileException) {
            return AttachmentPlan(info(AttachmentStatus.UNSUPPORTED, e.message ?: "Unsupported"), AttachmentRoute.UNREADABLE, null, e.message ?: "Unsupported file type.")
        } catch (e: ExtractionFailedException) {
            return AttachmentPlan(info(AttachmentStatus.FAILED, e.message ?: "Failed"), AttachmentRoute.UNREADABLE, null, e.message ?: "The file could not be read.")
        }
        if (doc.kind == DocKind.IMAGE) {
            if (caps.vision && src.bytes.size <= caps.maxImageBytes) return AttachmentPlan(info(AttachmentStatus.READY, "Sent as an image."), AttachmentRoute.IMAGE_NATIVE, null, "The selected model can look at images.")
            val engine = ocr
            val text = if (engine != null) runCatching { engine.recognize(src.bytes, mime) }.getOrNull() else null
            if (!text.isNullOrBlank()) return pack(src, doc.withSections(listOf(DocSection("OCR text", text)), listOf("Text was read from the image with OCR; it may contain mistakes."), false), caps, query, AttachmentRoute.OCR_THEN_TEXT, ::info)
            val why = if (src.bytes.size > caps.maxImageBytes && caps.vision) "The image is larger than ${caps.maxImageBytes / 1_048_576} MB." else if (!caps.vision && ocr == null) "The selected model can not see images and no OCR is available." else "No text was found in the image."
            return AttachmentPlan(info(AttachmentStatus.UNSUPPORTED, why), AttachmentRoute.UNREADABLE, null, "$why Choose an image-capable model to analyse this picture.")
        }
        if (doc.needsOcr && !doc.hasText) {
            val why = "This looks like a scanned document with no text layer. ${if (ocr == null) "OCR is not available here." else "OCR needs the pages rendered first."}"
            return AttachmentPlan(info(AttachmentStatus.UNSUPPORTED, why), AttachmentRoute.UNREADABLE, null, why)
        }
        return pack(src, doc, caps, query, AttachmentRoute.TEXT, ::info)
    }

    private fun pack(src: FileSource, doc: ExtractedDocument, caps: ModelCapabilities, query: String?, route: AttachmentRoute, info: (AttachmentStatus, String) -> AttachmentInfo): AttachmentPlan {
        val packed = ContextPacker.pack(doc, query, caps.textContextChars)
        val notes = (doc.warnings + packed.note).filter { it.isNotBlank() }.joinToString(" ")
        return AttachmentPlan(info(AttachmentStatus.READY, notes), route, packed, notes)
    }
}
