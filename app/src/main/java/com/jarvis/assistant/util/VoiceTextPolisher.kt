package com.jarvis.assistant.util

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.provider.ChatClientFactory
import com.jarvis.assistant.provider.ChatMessage
import com.jarvis.assistant.provider.ChatRequest
import com.jarvis.assistant.provider.ChatResolution
import com.jarvis.assistant.provider.ChatRole
import com.jarvis.assistant.provider.ProviderRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Turns what the speech recognizer heard into clean written text: spelling, capital letters, commas, full stops and question marks,
 * and light grammar fixes, without changing what was meant. It tries the AI the person already uses (their custom provider, or
 * Gemini) with a short time limit, and always falls back to a fast built-in clean-up, so sending is never blocked or lost.
 */
object VoiceTextPolisher {

    private const val SYSTEM = "You clean up speech-to-text dictation before it is sent as a chat message. " +
        "Fix spelling and mis-heard words that are clearly wrong, add correct punctuation (commas, full stops, question marks, apostrophes) " +
        "and capital letters, and fix grammar only where it is plainly wrong or awkward. " +
        "Keep the speaker's meaning, wording, tone and language; never translate, never add or remove information, never answer or explain. " +
        "Keep names, numbers, code and technical terms as spoken. Reply with ONLY the corrected text, no quotes and no comments."

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    /** The polished text, ready to send. Takes at most about five seconds; never throws. */
    suspend fun polish(context: Context, raw: String): String {
        val cleaned = raw.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isBlank()) return ""
        val local = local(cleaned)
        // A short command ("open WhatsApp", "turn on wifi") is left as a command: capitalised, no extra punctuation, no round trip.
        if (cleaned.split(' ').size <= 4) return commandStyle(local)
        val ai = try {
            withTimeout(5200L) { askAi(context.applicationContext, cleaned) }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        return ai?.takeIf { sane(cleaned, it) } ?: local
    }

    private fun commandStyle(local: String): String = local.trimEnd('.', '!')

    /** Rejects answers that are empty, much longer or much shorter than what was said (a sign the model answered instead of cleaning). */
    private fun sane(original: String, result: String): Boolean {
        val r = result.trim()
        if (r.isBlank()) return false
        val ratio = r.length.toFloat() / original.length.coerceAtLeast(1)
        return ratio in 0.6f..1.8f
    }

    private suspend fun askAi(app: Context, text: String): String? = withContext(Dispatchers.IO) {
        // 1) the custom provider the person is using in the ChatBox
        try {
            val manager = ProviderRuntime.manager(app)
            val res = manager.resolveChat()
            if (res is ChatResolution.Ready) {
                val client = ChatClientFactory.create(res.provider.protocol, ProviderRuntime.transport())
                val out = client.complete(ChatRequest(res.provider, SYSTEM, listOf(ChatMessage(ChatRole.USER, text)), maxTokens = 600))
                val t = out.text.trim().trim('"')
                if (t.isNotBlank()) return@withContext t
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) { }
        // 2) Gemini with the built-in key
        val key = try { JarvisApp.instance.preferences.apiKey } catch (_: Throwable) { "" }
        if (key.isBlank()) return@withContext null
        geminiClean(key, text)
    }

    private fun geminiClean(key: String, text: String): String? {
        val body = JsonObject().apply {
            add("systemInstruction", JsonObject().apply {
                add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", SYSTEM) }) })
            })
            add("contents", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", text) }) })
                })
            })
            add("generationConfig", JsonObject().apply {
                addProperty("temperature", 0.2)
                addProperty("maxOutputTokens", 600)
                add("thinkingConfig", JsonObject().apply { addProperty("thinkingBudget", 0) })
            })
        }
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$key")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val root = JsonParser.parseString(resp.body?.string().orEmpty()).asJsonObject
            val parts = root.getAsJsonArray("candidates")?.get(0)?.asJsonObject
                ?.getAsJsonObject("content")?.getAsJsonArray("parts") ?: return null
            val sb = StringBuilder()
            for (p in parts) p.asJsonObject.get("text")?.let { sb.append(it.asString) }
            return sb.toString().trim().trim('"').ifBlank { null }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Built-in clean-up (instant, offline). Full English rules; other languages get capitals and a full stop only.
    // ------------------------------------------------------------------------------------------------------------

    private val contractions = mapOf(
        "im" to "I'm", "ive" to "I've", "dont" to "don't", "doesnt" to "doesn't", "didnt" to "didn't", "cant" to "can't",
        "wont" to "won't", "isnt" to "isn't", "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't",
        "couldnt" to "couldn't", "wouldnt" to "wouldn't", "shouldnt" to "shouldn't", "hasnt" to "hasn't", "havent" to "haven't",
        "hadnt" to "hadn't", "thats" to "that's", "whats" to "what's", "theres" to "there's", "youre" to "you're",
        "theyre" to "they're", "youve" to "you've", "weve" to "we've", "theyve" to "they've", "youll" to "you'll",
        "gonna" to "going to", "wanna" to "want to", "gotta" to "have to", "heres" to "here's", "whos" to "who's"
    )
    private val questionStarters = setOf("what", "why", "how", "when", "where", "who", "whom", "whose", "which")
    private val auxStarters = setOf("is", "are", "am", "was", "were", "do", "does", "did", "can", "could", "will", "would", "should", "shall", "may", "might", "have", "has", "had")
    private val subjects = setOf("you", "i", "we", "they", "he", "she", "it", "there", "this", "that", "anyone", "anybody", "everyone", "someone")
    private val openers = setOf("hello", "hi", "hey", "yes", "no", "okay", "ok", "well", "so", "also", "actually", "however", "anyway", "first", "then", "now", "alright", "sure", "right", "thanks", "thank you")

    fun local(input: String): String {
        var t = input.trim().replace(Regex("\\s+"), " ")
        if (t.isBlank()) return ""
        val english = Locale.getDefault().language.lowercase(Locale.ROOT) == "en"
        if (english) {
            // words
            val words = t.split(' ').toMutableList()
            for (i in words.indices) {
                val w = words[i]
                val core = w.trimEnd('.', ',', '?', '!')
                val tail = w.substring(core.length)
                val lower = core.lowercase(Locale.ROOT)
                words[i] = when {
                    lower == "i" -> "I$tail"
                    contractions.containsKey(lower) -> contractions.getValue(lower) + tail
                    else -> w
                }
            }
            t = words.joinToString(" ")
            // a comma after a leading opener ("well I think ..." -> "Well, I think ...")
            val firstTwo = t.split(' ').take(2).joinToString(" ").lowercase(Locale.ROOT).trimEnd(',', '.')
            val firstOne = t.split(' ').first().lowercase(Locale.ROOT).trimEnd(',', '.')
            val opener = when {
                openers.contains(firstTwo) -> firstTwo
                openers.contains(firstOne) -> firstOne
                else -> null
            }
            if (opener != null) {
                val n = opener.split(' ').size
                val parts = t.split(' ')
                if (parts.size > n && !parts[n - 1].endsWith(",") && !parts[n - 1].endsWith(".") && !parts[n - 1].endsWith("?")) {
                    t = (parts.take(n).joinToString(" ") + "," + " " + parts.drop(n).joinToString(" "))
                }
            }
            // a comma before a mid-sentence "but"
            t = t.replace(Regex("(\\b\\w+(?: \\w+){2,}) but\\b")) { m -> m.groupValues[1] + ", but" }
        }
        // capital after each sentence end
        val sb = StringBuilder(t.length)
        var cap = true
        for (ch in t) {
            if (cap && ch.isLetter()) { sb.append(ch.uppercaseChar()); cap = false } else sb.append(ch)
            if (ch == '.' || ch == '?' || ch == '!') cap = true
        }
        t = sb.toString()
        // closing punctuation
        if (!t.last().let { it == '.' || it == '?' || it == '!' || it == '…' || it == '।' }) {
            val first = t.split(' ').first().lowercase(Locale.ROOT).trimEnd(',')
            val second = t.split(' ').getOrNull(1)?.lowercase(Locale.ROOT)?.trimEnd(',').orEmpty()
            val question = english && (questionStarters.contains(first) || (auxStarters.contains(first) && subjects.contains(second)))
            t += if (question) "?" else "."
        }
        return t
    }
}
