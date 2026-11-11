package com.jarvis.assistant.tools.files

import java.io.File

/**
 * Reads files into text for the model. Formats are handled by [FileHandler]s tried in order, so new formats can be
 * added without touching existing ones. Limits are enforced and reported (never silently): see [ExtractedDocument].
 */
class DocumentExtractor(
    private val handlers: List<FileHandler> = defaultHandlers(),
    val limits: ExtractionLimits = ExtractionLimits()
) {
    fun canExtract(name: String): Boolean = handlers.any { it.id != "text-sniffed" && it.canHandle(name, null, ByteArray(0)) } || Mimes.ext(name) in setOf("docx", "xlsx", "pptx", "pdf", "zip")

    fun extract(file: File, options: ExtractOptions = ExtractOptions()): ExtractedDocument {
        if (!file.isFile) throw UnsupportedFileException("${file.name} is not a file.")
        if (file.length() > limits.maxBytes) throw ExtractionFailedException("${file.name} is ${file.length() / 1_048_576} MB, larger than the ${limits.maxBytes / 1_048_576} MB limit.")
        return extract(FileSource(file.name, null, file.readBytes()), options, file)
    }

    fun extract(src: FileSource, options: ExtractOptions = ExtractOptions(), file: File? = null): ExtractedDocument {
        if (src.bytes.size > limits.maxBytes) throw ExtractionFailedException("${src.name} is larger than the ${limits.maxBytes / 1_048_576} MB limit.")
        val head = src.bytes.copyOf(minOf(src.bytes.size, 8192))
        val handler = handlers.firstOrNull { it.canHandle(src.name, src.mime, head) }
            ?: throw UnsupportedFileException("${src.name}: this file type is not supported yet.")
        val ctx = ExtractionContext(limits, this, options)
        val doc = try { handler.extract(src, ctx) } catch (e: UnsupportedFileException) { throw e } catch (e: ExtractionFailedException) { throw e }
            catch (e: ZipLimitException) { throw ExtractionFailedException("${src.name}: ${e.message}", e) }
            catch (e: Exception) { throw ExtractionFailedException("${src.name} could not be read (${e.javaClass.simpleName}: ${e.message?.take(100)}).", e) }
        return enforceCharLimit(doc)
    }

    private fun enforceCharLimit(doc: ExtractedDocument): ExtractedDocument {
        var budget = limits.maxChars; var cut = false
        val kept = ArrayList<DocSection>()
        for (s in doc.sections) {
            if (budget <= 0) { cut = true; break }
            if (s.text.length > budget) { kept += s.copy(text = s.text.take(budget)); budget = 0; cut = true } else { kept += s; budget -= s.text.length }
        }
        return if (!cut) doc else doc.copy(sections = kept, truncated = true, warnings = doc.warnings + "The text was cut after ${limits.maxChars} characters (size limit).")
    }

    companion object {
        fun defaultHandlers(): List<FileHandler> = listOf(
            PdfHandler(), DocxHandler(), XlsxHandler(), PptxHandler(), ZipArchiveHandler(), LegacyDocHandler(), ImageHandler(),
            CsvHandler(), JsonHandler(), HtmlHandler(), XmlHandler(), PlainTextHandler(), SniffedTextHandler()
        )
    }
}
