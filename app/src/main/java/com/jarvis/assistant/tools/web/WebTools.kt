package com.jarvis.assistant.tools.web

import com.google.gson.JsonObject
import com.jarvis.assistant.tools.*
import com.jarvis.assistant.tools.builtin.int
import com.jarvis.assistant.tools.builtin.str

/** Web tools shared by every provider. Everything they return is marked as untrusted and carries citation numbers. */
class WebTools(private val client: WebClient, private val search: SearchProvider, val citations: CitationStore = CitationStore()) {
    fun all(): List<Tool> = listOf(WebSearch(), WebFetch(), WebCitations())

    private fun err(e: WebException) = ToolResult.Error(e.message ?: "Web request failed")

    inner class WebSearch : Tool {
        override val spec = ToolSpec("web_search", "Search the web. Returns titles, addresses and snippets with citation numbers. Use web_fetch to read a result.",
            listOf(ToolParam("query", required = true), ToolParam("limit", "integer", "Results to return (default 6, max 10).")), Risk.NETWORK, "web")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { return try {
            val q = args.str("query")?.trim().orEmpty(); if (q.isEmpty()) return ToolResult.Error("query is required")
            val res = search.search(q, (args.int("limit") ?: 6).coerceIn(1, 10))
            if (res.isEmpty()) ToolResult.Ok("No results were found for \"$q\".")
            else ToolResult.Ok(UntrustedContent.wrap("web search for \"$q\"", res.joinToString("\n\n") { "[${citations.add(it.title, it.url)}] ${it.title}\n${it.url}\n${it.snippet}" }))
        } catch (e: WebException) { err(e) } }
    }

    inner class WebFetch : Tool {
        override val spec = ToolSpec("web_fetch", "Read a web page as text (scripts, menus and footers removed). Pass a query-free address; the page may be long, so use max_chars.",
            listOf(ToolParam("url", required = true), ToolParam("max_chars", "integer", "Default 20000.")), Risk.NETWORK, "web")
        override fun describe(args: JsonObject) = "Open ${args.str("url")?.substringBefore('?')}"
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { return try {
            val p = client.fetch(args.str("url") ?: return ToolResult.Error("url is required"))
            val n = citations.add(p.title, p.url); val limit = (args.int("max_chars") ?: 20_000).coerceIn(500, 200_000)
            val shown = p.text.take(limit)
            val more = if (p.text.length > shown.length || p.truncated) "\n… (${p.text.length - shown.length} more characters not shown)" else ""
            ToolResult.Ok(UntrustedContent.wrap("[$n] ${p.title.ifBlank { p.url }} — ${p.url}", shown + more, p.warnings) +
                if (p.links.isNotEmpty()) "\n\nLinks on the page:\n" + p.links.take(15).joinToString("\n") { "- ${it.first.take(80)} → ${it.second}" } else "")
        } catch (e: WebException) { err(e) } }
    }

    inner class WebCitations : Tool {
        override val spec = ToolSpec("web_citations", "List the sources opened or found so far, with their numbers.", emptyList(), Risk.READ, "web")
        override suspend fun run(args: JsonObject, ctx: ToolContext) = ToolResult.Ok(citations.all().joinToString("\n") { "[${it.number}] ${it.title} — ${it.url}" }.ifBlank { "No sources yet." })
    }
}
