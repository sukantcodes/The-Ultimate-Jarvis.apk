package com.jarvis.assistant.automation.amazon

import android.content.Context
import android.util.Log
import com.jarvis.assistant.automation.agent.AgentTaskEngine
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Order ledger that survives the app being restarted, so a submitted checkout is never submitted again. */
class PrefsOrderLedger(context: Context) : OrderLedger {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_amazon_orders", Context.MODE_PRIVATE)

    override fun get(fingerprint: String): LedgerEntry? {
        val raw = prefs.getString(fingerprint, null) ?: return null
        val parts = raw.split("|")
        if (parts.size != 2) return null
        val state = try { OrderState.valueOf(parts[0]) } catch (_: Exception) { return null }
        val at = parts[1].toLongOrNull() ?: return null
        return LedgerEntry(fingerprint, state, at)
    }

    override fun put(entry: LedgerEntry) {
        val editor = prefs.edit()
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        prefs.all.forEach { (key, value) ->
            val at = (value as? String)?.split("|")?.getOrNull(1)?.toLongOrNull()
            if (at != null && at < cutoff) editor.remove(key)
        }
        editor.putString(entry.fingerprint, entry.state.name + "|" + entry.at)
        // commit(): the "attempted" mark must be on disk before the order button is tapped.
        editor.commit()
    }

    override fun clear() { prefs.edit().clear().commit() }
}

/** The seven Amazon tools: what the AI is told about them, and how a call is run. */
object AmazonTools {
    private const val TAG = "AmazonTools"

    val NAMES: Set<String> = setOf(
        "amazon_search", "amazon_compare", "amazon_open_product", "amazon_add_to_cart",
        "amazon_cart", "amazon_prepare_checkout", "amazon_place_order"
    )

    /** One call may legitimately take a while (scrolling, retries); the agent has its own, shorter budgets. */
    const val TOOL_TIMEOUT_MS = 150_000L

    private val session = AmazonSession()
    @Volatile private var gate: PurchaseGate? = null

    private fun gate(context: Context): PurchaseGate = gate ?: synchronized(this) {
        gate ?: PurchaseGate({ System.currentTimeMillis() }, PrefsOrderLedger(context)).also { gate = it }
    }

    // ---- What the AI sees ---------------------------------------------------------------------------

    private fun prop(type: String, description: String) =
        JSONObject().put("type", type).put("description", description)

    private fun tool(name: String, description: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", props)
                if (required.isNotEmpty()) put("required", JSONArray(required))
            })
        }

    fun declarations(): List<JSONObject> = listOf(
        tool(
            "amazon_search",
            "Searches the Amazon Shopping app and READS the real results (price, rating, delivery, Prime) from the screen. Works for English and Hinglish. " +
                "Pass the user's own words in 'request' (e.g. 'iphone 15 black 60000 ke neeche'); budget, rating, Prime, sort, quantity and variants are understood from it. " +
                "Explicit fields override the sentence. Numbered results are remembered for amazon_compare, amazon_open_product and amazon_add_to_cart. " +
                "Report only what the result says; never invent prices, ratings or specs.",
            JSONObject()
                .put("request", prop("STRING", "The user's own words for what to find."))
                .put("query", prop("STRING", "Optional: product words only, if you want to override the sentence."))
                .put("min_price", prop("NUMBER", "Optional minimum price in rupees."))
                .put("max_price", prop("NUMBER", "Optional maximum price in rupees."))
                .put("min_rating", prop("NUMBER", "Optional minimum star rating, 1 to 5."))
                .put("prime_only", prop("BOOLEAN", "Only Prime items."))
                .put("sort", prop("STRING", "One of: relevance, price_low, price_high, rating, newest."))
                .put("quantity", prop("INTEGER", "How many the user wants (used by amazon_add_to_cart)."))
                .put("variants", JSONObject().put("type", "ARRAY").put("description", "Colour, size or storage choices such as 'Black', '128 GB', 'size 9'.").put("items", JSONObject().put("type", "STRING"))),
            listOf("request")
        ),
        tool(
            "amazon_compare",
            "Compares results of the last amazon_search side by side: price, MRP and discount, rating, review count, Prime, delivery charge and offers, and names the lowest price, best rated and best value. " +
                "With deep=true each product page is opened to read specifications and the seller (slower). Uses only values read from the screen.",
            JSONObject()
                .put("indices", JSONObject().put("type", "ARRAY").put("description", "Result numbers to compare (default: top 3).").put("items", JSONObject().put("type", "INTEGER")))
                .put("deep", prop("BOOLEAN", "Open each product page to read specifications."))
        ),
        tool(
            "amazon_open_product",
            "Opens one result of the last amazon_search by its number and confirms the page matches that product.",
            JSONObject().put("index", prop("INTEGER", "Result number, starting at 1.")),
            listOf("index")
        ),
        tool(
            "amazon_add_to_cart",
            "Adds a product to the Amazon cart and VERIFIES it by reading the cart. Give the result number from the last search, or omit it to use the product page that is open. " +
                "Selects colour/size/storage variants first and adds nothing if one cannot be confirmed. Sets the quantity in the cart. Never claim it was added unless success and verified are true. This does NOT buy anything.",
            JSONObject()
                .put("index", prop("INTEGER", "Result number from the last search."))
                .put("quantity", prop("INTEGER", "How many to add (default 1)."))
                .put("variants", JSONObject().put("type", "ARRAY").put("description", "Variants to select first.").put("items", JSONObject().put("type", "STRING")))
        ),
        tool(
            "amazon_cart",
            "Cart actions: action='view' lists the cart; 'remove' deletes one item (give words from its name); 'set_quantity' changes an item's quantity; " +
                "'check_order' reads the current screen to see whether Amazon is showing an order confirmation (use it after the user finished a payment check).",
            JSONObject()
                .put("action", prop("STRING", "view, remove, set_quantity or check_order."))
                .put("item", prop("STRING", "Words from the item's name (remove, set_quantity)."))
                .put("quantity", prop("INTEGER", "New quantity (set_quantity).")),
            listOf("action")
        ),
        tool(
            "amazon_prepare_checkout",
            "Opens the cart, goes to checkout and stops at Amazon's final review screen. It NEVER places the order. Returns the items, total, delivery, address and payment line. " +
                "You must read that summary to the user and wait for them to clearly say to place the order. Does not change the address or payment method.",
            JSONObject()
        ),
        tool(
            "amazon_place_order",
            "Places the prepared Amazon order. It only works when the user's LATEST words, in a NEW message after they heard the summary from amazon_prepare_checkout, clearly tell you to place the order, " +
                "and the screen still matches the summary. The check uses the user's real transcript, not any argument. Call it at most once per order. " +
                "If it returns anything but PLACED, do not say the order was placed and do not call it again; tell the user what it says.",
            JSONObject()
        )
    )

    // ---- Running a call -----------------------------------------------------------------------------

    private fun goalFor(name: String, args: JSONObject): String = when (name) {
        "amazon_search" -> "Amazon: search " + args.optString("request", args.optString("query", "")).take(80)
        "amazon_compare" -> "Amazon: compare products"
        "amazon_open_product" -> "Amazon: open result " + args.optInt("index", 0)
        "amazon_add_to_cart" -> "Amazon: add to cart"
        "amazon_cart" -> "Amazon: cart " + args.optString("action", "view")
        "amazon_prepare_checkout" -> "Amazon: prepare checkout"
        else -> "Amazon: place confirmed order"
    }

    private fun doubleOrNull(args: JSONObject, key: String): Double? =
        if (args.has(key) && !args.isNull(key)) args.optDouble(key).takeIf { !it.isNaN() } else null

    private fun intOrNull(args: JSONObject, key: String): Int? =
        if (args.has(key) && !args.isNull(key)) args.optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null

    private fun strings(args: JSONObject, key: String): List<String>? {
        val array = args.optJSONArray(key) ?: return null
        return (0 until array.length()).map { array.optString(it, "") }.filter { it.isNotBlank() }
    }

    private fun ints(args: JSONObject, key: String): List<Int> {
        val array = args.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optInt(i, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } }
    }

    private fun directive(r: AmazonResult): String? = when (r.status) {
        AmazonStatus.PREPARED ->
            "Read this order summary to the user in one or two short sentences (items, total, address, payment) and ask them to say clearly if you should place the order. Do not call amazon_place_order until they answer in a new message."
        AmazonStatus.PLACED -> "The order is verified as placed. Say so once, briefly."
        AmazonStatus.NEEDS_SIGN_IN, AmazonStatus.NEEDS_USER, AmazonStatus.NEEDS_PAYMENT_AUTH ->
            "The user must act on the phone (sign-in, OTP, CAPTCHA or payment check). Tell them exactly that. Do not type, tap or try to get around it. Wait for them to say they are done."
        AmazonStatus.REFUSED ->
            "The order was NOT placed. Tell the user why in one sentence. Do not call amazon_place_order again until the user has heard the summary and confirmed in a new message."
        AmazonStatus.NOT_VERIFIED ->
            "The result is unverified. Do not claim success and do not repeat the action; tell the user what could and could not be confirmed."
        else -> if (!r.success) "The step failed. Tell the user the real reason briefly. You may retry once with a different approach if it makes sense; never pretend it worked." else null
    }

    private fun toJson(r: AmazonResult): JSONObject = JSONObject().apply {
        put("success", r.success)
        put("status", r.status)
        put("message", r.message)
        put("verified", r.verified)
        r.extra.forEach { (k, v) ->
            when (v) {
                is String, is Number, is Boolean -> put(k, v)
                is Collection<*> -> put(k, JSONArray(v.map { it.toString() }))
                null -> Unit
                else -> put(k, v.toString())
            }
        }
        directive(r)?.let { put("system_directive", it) }
    }

    /** Runs one Amazon tool. Never throws: any failure becomes an honest result. */
    suspend fun execute(
        context: Context,
        name: String,
        args: JSONObject,
        userUtterance: String,
        onProgress: (String) -> Unit = {}
    ): JSONObject {
        val goal = goalFor(name, args)
        try {
            AgentTaskEngine.begin(goal, "amazon")
        } catch (e: Exception) {
            Log.w(TAG, "Task overlay could not start: ${e.message}")
        }
        val result = try {
            val agent = AmazonAgent(AndroidAmazonDriver(context, onProgress), gate(context), session)
            run(agent, name, args, userUtterance)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$name failed: ${e.message}", e)
            AmazonResult(false, AmazonStatus.ERROR, "Amazon automation hit an unexpected problem: ${e.message ?: "unknown"}. Nothing was ordered.")
        }
        finishTask(result)
        return toJson(result)
    }

    private suspend fun run(agent: AmazonAgent, name: String, args: JSONObject, utterance: String): AmazonResult = when (name) {
        "amazon_search" -> {
            val said = args.optString("request", "").ifBlank { args.optString("query", "") }
            val request = AmazonQueryParser.merge(
                base = AmazonQueryParser.parse(said),
                query = args.optString("query", "").takeIf { it.isNotBlank() && args.optString("request", "").isNotBlank() },
                minPrice = doubleOrNull(args, "min_price"),
                maxPrice = doubleOrNull(args, "max_price"),
                minRating = doubleOrNull(args, "min_rating"),
                primeOnly = if (args.has("prime_only")) args.optBoolean("prime_only") else null,
                sort = AmazonQueryParser.sortFromText(args.optString("sort", "")),
                quantity = intOrNull(args, "quantity"),
                variants = strings(args, "variants")
            )
            agent.search(request)
        }
        "amazon_compare" -> agent.compare(ints(args, "indices"), args.optBoolean("deep", false))
        "amazon_open_product" -> {
            val index = intOrNull(args, "index")
            if (index == null) AmazonResult(false, AmazonStatus.NOT_FOUND, "Which result number should I open?") else agent.openProduct(index)
        }
        "amazon_add_to_cart" -> {
            val request = session.lastRequest
            agent.addToCart(
                index = intOrNull(args, "index"),
                quantity = (intOrNull(args, "quantity") ?: request?.quantity ?: 1).coerceIn(1, 20),
                variants = strings(args, "variants") ?: request?.variants.orEmpty().filter { v ->
                    // colours/storage already in the search words were applied by the search; size choices still need picking
                    v.startsWith("size")
                }
            )
        }
        "amazon_cart" -> agent.cartAction(args.optString("action", "view").lowercase(Locale.ROOT).trim(), args.optString("item", "").ifBlank { null }, intOrNull(args, "quantity"))
        "amazon_prepare_checkout" -> agent.prepareCheckout(utterance)
        else -> agent.placeOrder(utterance)
    }

    private fun finishTask(result: AmazonResult) {
        try {
            when {
                result.success && result.verified -> {
                    AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.VERIFIED_GOAL, result.message.take(160))
                    AgentTaskEngine.finish(AgentTaskEngine.State.COMPLETED)
                }
                result.status in setOf(AmazonStatus.NEEDS_SIGN_IN, AmazonStatus.NEEDS_USER, AmazonStatus.NEEDS_PAYMENT_AUTH, AmazonStatus.PREPARED) ->
                    AgentTaskEngine.finish(AgentTaskEngine.State.BLOCKED, result.message.take(160))
                result.status == AmazonStatus.CANCELLED -> Unit
                else -> AgentTaskEngine.finish(AgentTaskEngine.State.FAILED, result.message.take(160))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Task overlay could not finish: ${e.message}")
        }
    }

    // ---- Guards for the generic tools --------------------------------------------------------------

    /** Is an Amazon page on screen, and which one? Used to block direct taps on purchase buttons and typing into security screens. */
    private fun guardContext(): Pair<Boolean, AmazonScreen?> {
        val service = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance ?: return false to null
        val root = service.getActiveRootNode() ?: return false to null
        // Fast path: taps in unrelated apps (Instagram, WhatsApp, ...) never pay for a full screen read.
        val foreground = root.packageName?.toString().orEmpty()
        val canBeAmazon = AmazonLinks.isAmazonShoppingPackage(foreground) ||
            Regex("chrome|browser|firefox|opera|brave|edge|duckduckgo|vivaldi", RegexOption.IGNORE_CASE).containsMatchIn(foreground)
        if (!canBeAmazon) return false to null
        val nodes = AccessibilityNodeReader.read(root)
        val screen = AmazonScreenReader.classify(nodes)
        val pkg = root.packageName?.toString()
        val context = AmazonLinks.isAmazonShoppingPackage(pkg) ||
            screen == AmazonScreen.ORDER_REVIEW ||
            nodes.any { it.haystack.contains("amazon.in") || it.haystack.contains("amazon.com") }
        return context to screen
    }

    fun blockGenericClick(target: String, description: String, byPositionOrId: Boolean): String? {
        val (context, screen) = guardContext()
        return AmazonGuards.genericClickBlock(context, screen, target, description, byPositionOrId)
    }

    fun blockGenericType(): String? {
        val (context, screen) = guardContext()
        return AmazonGuards.genericTypeBlock(context, screen)
    }
}
