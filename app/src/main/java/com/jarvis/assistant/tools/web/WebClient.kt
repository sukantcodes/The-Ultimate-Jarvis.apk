package com.jarvis.assistant.tools.web

import com.jarvis.assistant.provider.HttpRequestSpec
import com.jarvis.assistant.provider.HttpTransport
import com.jarvis.assistant.provider.TransportException
import com.jarvis.assistant.tools.files.XmlEvents
import com.jarvis.assistant.tools.files.XmlScan
import java.net.URI

data class WebPage(val url: String, val status: Int, val contentType: String, val title: String, val text: String, val links: List<Pair<String, String>>, val truncated: Boolean, val warnings: List<String>)

/**
 * Fetches pages through the shared transport. Redirects are followed by hand (the transport never follows them) so
 * each hop is checked by [UrlGuard]. No cookies or credentials are ever sent.
 */
class WebClient(private val transport: HttpTransport, private val guard: UrlGuard = UrlGuard(), private val maxRedirects: Int = 5, private val maxChars: Int = 200_000) {
    suspend fun fetch(url: String, timeoutMs: Long = 20_000): WebPage {
        var current = guard.check(url); var hops = 0
        while (true) {
            val resp = try {
                transport.execute(HttpRequestSpec("GET", current.toString(), mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android) JarvisAssistant/1.0", "Accept" to "text/html,application/xhtml+xml,text/plain,application/json;q=0.9,*/*;q=0.5"), null, timeoutMs))
            } catch (e: TransportException) { throw WebException(WebException.Kind.NETWORK, "The site could not be reached (${e.kind.name.lowercase()}).") }
            if (resp.code in 300..399) {
                val loc = resp.headers.entries.firstOrNull { it.key.equals("location", true) }?.value ?: throw WebException(WebException.Kind.HTTP, "The site redirected without saying where.")
                if (++hops > maxRedirects) throw WebException(WebException.Kind.HTTP, "Too many redirects.")
                current = guard.check(current.resolve(loc).toString()); continue
            }
            if (resp.code !in 200..299) throw WebException(WebException.Kind.HTTP, "The site answered with HTTP ${resp.code}.")
            val ctype = resp.headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            val body = resp.body
            val isHtml = ctype.contains("html") || (ctype.isEmpty() && body.trimStart().startsWith("<"))
            val isText = isHtml || ctype.startsWith("text/") || ctype.contains("json") || ctype.contains("xml") || ctype.isEmpty()
            if (!isText) throw WebException(WebException.Kind.UNSUPPORTED, "This address serves \"$ctype\", which can not be read as a web page.")
            val (title, text, links) = if (isHtml) HtmlText.extract(body, current) else Triple("", body, emptyList())
            val warnings = InjectionScanner.scan(text).toMutableList()
            val cut = text.length > maxChars
            if (cut) warnings += "The page text was cut after $maxChars characters."
            return WebPage(current.toString(), resp.code, ctype, title, text.take(maxChars), links, cut, warnings)
        }
    }
}

object HtmlText {
    fun extract(html: String, base: URI?): Triple<String, String, List<Pair<String, String>>> {
        val sb = StringBuilder(); val links = ArrayList<Pair<String, String>>(); var skip = 0; var title = ""; var inTitle = false; var href: String? = null; val linkText = StringBuilder()
        val block = setOf("p", "div", "section", "article", "li", "tr", "br", "ul", "ol", "table", "main", "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6")
        val hidden = setOf("script", "style", "noscript", "svg", "template", "iframe", "head")
        XmlScan.scan(html, object : XmlEvents {
            override fun start(name: String, attrs: String, selfClosing: Boolean) {
                val n = XmlScan.local(name).lowercase()
                when {
                    n == "title" -> inTitle = true
                    n in hidden && n != "head" -> if (!selfClosing) skip++
                    n == "nav" || n == "footer" || n == "aside" -> if (!selfClosing) skip++
                    n == "a" -> { href = XmlScan.attr(attrs, "href"); linkText.setLength(0) }
                    n == "li" -> sb.append("\n- ")
                    n.length == 2 && n[0] == 'h' && n[1] in '1'..'6' -> sb.append("\n\n").append("#".repeat(n[1] - '0')).append(' ')
                    n in block -> sb.append('\n')
                }
                if (XmlScan.attr(attrs, "hidden") != null || XmlScan.attr(attrs, "aria-hidden") == "true") { /* visible-text only is best effort */ }
            }
            override fun end(name: String) {
                val n = XmlScan.local(name).lowercase()
                when {
                    n == "title" -> inTitle = false
                    n in hidden && n != "head" || n == "nav" || n == "footer" || n == "aside" -> if (skip > 0) skip--
                    n == "a" -> { val h = href; if (h != null && skip == 0 && linkText.isNotBlank() && !h.startsWith("#") && !h.startsWith("javascript:") && !h.startsWith("mailto:")) { val abs = try { base?.resolve(h)?.toString() ?: h } catch (e: Exception) { h }; if (abs.startsWith("http")) links += linkText.toString().trim() to abs }; href = null }
                    n in block -> sb.append('\n')
                }
            }
            override fun text(text: String) {
                val t = XmlScan.decode(text).replace(Regex("\\s+"), " ")
                if (inTitle) { title += t; return }
                if (skip > 0 || t.isBlank()) return
                sb.append(t); if (href != null) linkText.append(t)
            }
        })
        val body = sb.toString().replace(Regex("[ \t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n").trim()
        return Triple(title.trim(), body, links.distinctBy { it.second }.take(100))
    }
}

data class SearchResult(val title: String, val url: String, val snippet: String)

interface SearchProvider { suspend fun search(query: String, limit: Int): List<SearchResult> }

/** Keyless search through DuckDuckGo's HTML page. Parsing is by structure, and an empty result is reported, never invented. */
class DuckDuckGoHtmlSearch(private val transport: HttpTransport) : SearchProvider {
    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        val url = "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val resp = try { transport.execute(HttpRequestSpec("GET", url, mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android) JarvisAssistant/1.0"), null, 20_000)) } catch (e: TransportException) { throw WebException(WebException.Kind.NETWORK, "Search could not be reached.") }
        if (!resp.isSuccessful) throw WebException(WebException.Kind.HTTP, "The search service answered with HTTP ${resp.code}.")
        return parse(resp.body).take(limit)
    }

    internal fun parse(html: String): List<SearchResult> {
        val out = ArrayList<SearchResult>()
        var cur: MutableMap<String, String>? = null; var capture: String? = null; val buf = StringBuilder()
        fun flush() { cur?.let { c -> val u = c["url"]; if (u != null && c["title"].orEmpty().isNotBlank()) out += SearchResult(c["title"]!!.trim(), u, c["snippet"].orEmpty().trim()) }; cur = null }
        XmlScan.scan(html, object : XmlEvents {
            override fun start(name: String, attrs: String, selfClosing: Boolean) {
                val cls = XmlScan.attr(attrs, "class").orEmpty()
                val n = XmlScan.local(name).lowercase()
                if (n == "a" && cls.contains("result__a")) { flush(); cur = HashMap(); cur!!["url"] = real(XmlScan.attr(attrs, "href").orEmpty()); capture = "title"; buf.setLength(0) }
                else if (cur != null && cls.contains("result__snippet")) { capture = "snippet"; buf.setLength(0) }
            }
            override fun end(name: String) { val c = capture; if (c != null) { cur?.set(c, XmlScan.decode(buf.toString()).replace(Regex("\\s+"), " ")); capture = null } }
            override fun text(text: String) { if (capture != null) buf.append(text) }
        })
        flush()
        return out.filter { it.url.startsWith("http") && !it.url.contains("duckduckgo.com/y.js") }
    }

    private fun real(href: String): String {
        val h = if (href.startsWith("//")) "https:$href" else href
        val m = Regex("[?&]uddg=([^&]+)").find(h) ?: return h
        return try { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") } catch (e: Exception) { h }
    }
}

/** Numbered sources used in an answer, so replies can cite [1], [2] and the UI can list them. */
class CitationStore {
    data class Citation(val number: Int, val title: String, val url: String)
    private val items = LinkedHashMap<String, Citation>()
    @Synchronized fun add(title: String, url: String): Int = items.getOrPut(url) { Citation(items.size + 1, title.ifBlank { url }, url) }.number
    @Synchronized fun all(): List<Citation> = items.values.toList()
    @Synchronized fun clear() = items.clear()
}
