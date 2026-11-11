package com.jarvis.assistant.tools.files

import java.io.File

enum class DocKind { TEXT, CODE, MARKDOWN, TABLE, DATA, MARKUP, DOCUMENT, PRESENTATION, PDF, ARCHIVE, IMAGE }

/** One labelled part of a document: a page, a sheet, a slide, "Body", "Header"... */
data class DocSection(val label: String, val text: String)

data class TableData(val name: String, val rows: List<List<String>>)

class UnsupportedFileException(message: String) : Exception(message)
class ExtractionFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The result of reading a file. Nothing is dropped silently: when a limit was hit [truncated] is true and a
 * warning says so; when the file holds pages that need OCR [needsOcr] is true.
 */
data class ExtractedDocument(
    val name: String,
    val mime: String,
    val kind: DocKind,
    val sections: List<DocSection>,
    val warnings: List<String> = emptyList(),
    val needsOcr: Boolean = false,
    val truncated: Boolean = false,
    val pageCount: Int = 0,
    val tables: List<TableData> = emptyList(),
    val meta: Map<String, String> = emptyMap(),
    val sizeBytes: Long = 0L
) {
    /** All text, with "[label]" lines in front of labelled sections. */
    val text: String by lazy {
        if (sections.size == 1 && sections[0].label.isBlank()) sections[0].text
        else sections.joinToString("\n\n") { if (it.label.isBlank()) it.text else "[${it.label}]\n${it.text}" }
    }

    val hasText: Boolean get() = sections.any { it.text.isNotBlank() }

    fun withSections(newSections: List<DocSection>, extraWarnings: List<String> = emptyList(), needsOcr: Boolean = this.needsOcr) =
        copy(sections = newSections, warnings = warnings + extraWarnings, needsOcr = needsOcr)
}

data class ExtractionLimits(
    val maxChars: Int = 4_000_000,
    val maxBytes: Long = 25L * 1024 * 1024,
    val maxZipEntries: Int = 5_000,
    val maxZipTotalBytes: Long = 300L * 1024 * 1024,
    val maxZipEntryBytes: Long = 100L * 1024 * 1024,
    val maxXmlPartBytes: Long = 64L * 1024 * 1024,
    val maxPdfPages: Int = 1_500,
    val maxSheetRows: Int = 200_000,
    val maxNestedFiles: Int = 20,
    val maxNestedBytes: Long = 5L * 1024 * 1024,
    val maxZipDepth: Int = 2,
    val maxInflated: Long = 200L * 1024 * 1024
)

data class ExtractOptions(val extractArchiveText: Boolean = false, val depth: Int = 0)

class FileSource(val name: String, val mime: String?, val bytes: ByteArray)

class ExtractionContext(val limits: ExtractionLimits, val extractor: DocumentExtractor, val options: ExtractOptions)

/** Add a handler to teach the extractor a new format. */
interface FileHandler {
    val id: String
    fun canHandle(name: String, mime: String?, head: ByteArray): Boolean
    fun extract(src: FileSource, ctx: ExtractionContext): ExtractedDocument
}

/** Recognises text in images and scanned pages. Implemented by the Android layer (for example ML Kit). */
interface OcrEngine {
    suspend fun recognize(bytes: ByteArray, mime: String): String?
}

/** Draws PDF pages as PNG images so they can be sent to OCR or a vision model. Implemented on Android with PdfRenderer. */
interface PdfPageRenderer {
    suspend fun pageCount(pdf: File): Int
    /** [pageIndex] is 0-based. Returns PNG bytes or null when the page can not be drawn. */
    suspend fun renderPage(pdf: File, pageIndex: Int, maxDimension: Int): ByteArray?
}

enum class AttachmentStatus { QUEUED, PROCESSING, READY, UNSUPPORTED, FAILED }

data class AttachmentInfo(
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val status: AttachmentStatus,
    val detail: String = ""
)

object Mimes {
    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun guess(name: String): String = when (ext(name)) {
        "txt", "text", "log" -> "text/plain"; "md", "markdown", "mdx" -> "text/markdown"
        "csv" -> "text/csv"; "tsv" -> "text/tab-separated-values"
        "json" -> "application/json"; "xml" -> "application/xml"
        "html", "htm", "xhtml" -> "text/html"
        "pdf" -> "application/pdf"; "zip" -> "application/zip"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; "bmp" -> "image/bmp"
        "css" -> "text/css"; "js", "mjs" -> "text/javascript"
        "kt", "kts", "java", "py", "c", "h", "cpp", "go", "rs", "rb", "php", "swift", "sh", "sql", "ts" -> "text/plain"
        else -> "application/octet-stream"
    }
}
