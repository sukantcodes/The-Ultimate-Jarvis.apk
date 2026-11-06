package com.jarvis.assistant.automation.blinkit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.jarvis.assistant.automation.agent.AgentTaskEngine
import com.jarvis.assistant.automation.amazon.AccessibilityNodeReader
import com.jarvis.assistant.automation.amazon.LedgerEntry
import com.jarvis.assistant.automation.amazon.OrderLedger
import com.jarvis.assistant.automation.amazon.OrderState
import com.jarvis.assistant.automation.rapido.LocationAccess
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Order ledger that survives the app being restarted, so a submitted order is never submitted again. */
class PrefsBlinkitLedger(context: Context) : OrderLedger {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_blinkit_orders", Context.MODE_PRIVATE)

    private fun parse(fingerprint: String, raw: String?): LedgerEntry? {
        val parts = raw?.split("|") ?: return null
        if (parts.size != 2) return null
        val state = try { OrderState.valueOf(parts[0]) } catch (_: Exception) { return null }
        val at = parts[1].toLongOrNull() ?: return null
        return LedgerEntry(fingerprint, state, at)
    }

    override fun get(fingerprint: String): LedgerEntry? = parse(fingerprint, prefs.getString(fingerprint, null))

    override fun put(entry: LedgerEntry) {
        val editor = prefs.edit()
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        prefs.all.forEach { (key, value) ->
            val at = (value as? String)?.split("|")?.getOrNull(1)?.toLongOrNull()
            if (at != null && at < cutoff) editor.remove(key)
        }
        editor.putString(entry.fingerprint, entry.state.name + "|" + entry.at)
        // commit(): the "attempted" mark must be on disk before the pay button is tapped.
        editor.commit()
    }

    override fun clear() { prefs.edit().clear().commit() }
}

/** The Blinkit tools: what the AI is told about them, and how a call is run. */
object BlinkitTools {
    private const val TAG = "BlinkitTools"

    val NAMES: Set<String> = setOf(
        "blinkit_search", "blinkit_compare", "blinkit_budget_plan", "blinkit_cart",
        "blinkit_address", "blinkit_prepare_checkout", "blinkit_payment_method", "blinkit_place_order", "blinkit_order_status"
    )

    /** One call may legitimately take a while (searching, planning); the agent has its own, shorter budgets. */
    const val TOOL_TIMEOUT_MS = 240_000L

    private val session = BlinkitSession()
    @Volatile private var gate: BlinkitGate? = null

    private fun gate(context: Context): BlinkitGate = gate ?: synchronized(this) {
        gate ?: BlinkitGate({ System.currentTimeMillis() }, PrefsBlinkitLedger(context)).also { gate = it }
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
            "blinkit_search",
            "Searches the Blinkit app and READS the products from the screen: name, brand, pack size, price, MRP, rating, offers, availability, plus a calculated unit price (per kg, litre or item). " +
                "Works for English and Hinglish, spelling mistakes and alternate names (doodh, aata, biscut). Pass the user's own words in 'request' " +
                "(e.g. '200 ke andar best value detergent' or 'something for breakfast'). Vague asks return several concrete suggestions. " +
                "Results are ranked locally; explicit fields override the sentence. Unit prices and value ranks are calculated by Jarvis and are labelled so; report only what the result says. " +
                "Unavailable items come with in-stock alternatives. This does NOT add anything to the cart.",
            JSONObject()
                .put("request", prop("STRING", "The user's own words for what they want."))
                .put("query", prop("STRING", "Optional: the product to search, to override the sentence."))
                .put("min_price", prop("NUMBER", "Optional: lowest price in rupees."))
                .put("max_price", prop("NUMBER", "Optional: highest price in rupees."))
                .put("quantity", prop("INTEGER", "Optional: how many the user wants."))
                .put("sort", prop("STRING", "Optional: value, price_low, unit_price, rating or relevance."))
                .put("brand", prop("STRING", "Optional: preferred brand."))
                .put("pack", prop("STRING", "Optional: pack size, e.g. '500 g' or '1 L'.")),
            listOf("request")
        ),
        tool(
            "blinkit_compare",
            "Compares products from the LAST blinkit_search by price, pack size, calculated unit price, rating and offers, and names the best value (not only the cheapest). " +
                "Pass the numbers shown in the search result. Omit them to compare the top results.",
            JSONObject().put("items", JSONObject().put("type", "ARRAY").put("description", "Optional result numbers to compare.").put("items", JSONObject().put("type", "INTEGER")))
        ),
        tool(
            "blinkit_budget_plan",
            "Finds the best combination of items within a total budget, e.g. 'milk, bread and eggs within 300 rupees'. Searches each item, picks the best value pack for each and reports total, shortfall and what to drop if it does not fit. " +
                "It plans only; use blinkit_cart action=add_plan to add the plan to the cart.",
            JSONObject()
                .put("request", prop("STRING", "The user's own words: the items and the total budget."))
                .put("reserve", prop("NUMBER", "Optional: rupees to keep aside for delivery or handling fees.")),
            listOf("request")
        ),
        tool(
            "blinkit_cart",
            "Cart actions in the Blinkit app, each verified by reading the cart afterwards. action: view (items, subtotal, fees, minimum-order gap, unavailable or substituted items), " +
                "add (a result number from the last search, or an item name; with quantity and optional variant/size), add_plan (add the last budget plan), remove, set_quantity, " +
                "coupons (list the offers visible in the cart) and apply_coupon (needs 'code'; the result says whether Blinkit accepted it). " +
                "Never reorders or adds anything twice after a timeout; it reads the cart first. It never checks out.",
            JSONObject()
                .put("action", prop("STRING", "view, add, add_plan, remove, set_quantity, coupons or apply_coupon."))
                .put("index", prop("INTEGER", "Result number from the last blinkit_search."))
                .put("item", prop("STRING", "Item name, when not using a result number."))
                .put("quantity", prop("INTEGER", "How many."))
                .put("variant", prop("STRING", "Optional: pack size or variant to pick."))
                .put("code", prop("STRING", "Coupon code, for apply_coupon.")),
            listOf("action")
        ),
        tool(
            "blinkit_address",
            "Reads or changes which address Blinkit delivers to. action: status (the current delivery address and ETA), location (is the phone's GPS usable; never returns coordinates; " +
                "with open_settings=true opens the right Android settings page, never switches location on by itself), list (saved addresses, changes nothing) and " +
                "select (switch to a saved address, ONLY when the user clearly asked for that address by name). The address is never changed silently and never shared.",
            JSONObject()
                .put("action", prop("STRING", "status, location, list or select."))
                .put("label", prop("STRING", "For select: the saved address label the user named, e.g. 'Home' or 'Office'."))
                .put("open_settings", prop("BOOLEAN", "For location: open the Android settings page that fixes the problem.")),
            listOf("action")
        ),
        tool(
            "blinkit_prepare_checkout",
            "Opens the cart's checkout in Blinkit, re-reads items, quantities, bill (item total, fees, discounts, total), delivery address and payment method, and stores a summary. It NEVER orders. " +
                "You must read the summary to the user and wait for them to clearly say to place it in a NEW message. Refuses on unavailable items, minimum-order gaps or an unserviceable address.",
            JSONObject()
        ),
        tool(
            "blinkit_payment_method",
            "Selects a payment method that is visibly offered on Blinkit's payment screen (UPI, Google Pay, PhonePe, Paytm, cash on delivery, card, etc.). It reads the screen before and after the tap, verifies the selected method, re-reads the payable summary and refreshes the confirmation gate. It NEVER places the order; read the updated summary and wait for a separate clear confirmation.",
            JSONObject().put("method", prop("STRING", "The payment method the user explicitly named, e.g. Google Pay, PhonePe, Paytm, UPI or Cash on Delivery.")),
            listOf("method")
        ),
        tool(
            "blinkit_place_order",
            "Places the prepared Blinkit order. It only works when the user's LATEST words, in a NEW message after they heard the summary from blinkit_prepare_checkout, clearly tell you to place the order, " +
                "the screen still matches the summary and the same order was not placed already. The check uses the user's real transcript, not any argument. Call it at most once per order. " +
                "PLACED means Blinkit's screen showed a confirmation. If a payment app opens, the user must finish the payment and PIN themselves. Anything else: do not say it was ordered and do not call it again.",
            JSONObject()
        ),
        tool(
            "blinkit_order_status",
            "Reads a live or past Blinkit order from the screen. action: status (stage, tracking, ETA, delay or substitution notices), history (recent orders), help (opens the order's help options: missing or damaged items, refund), " +
                "cancel (TWO steps: the first call explains the order state and taps nothing; the second only after the user, in a NEW message, clearly says to cancel). " +
                "Never claims delivered, cancelled or refunded unless the screen shows it.",
            JSONObject().put("action", prop("STRING", "status, history, help or cancel.")),
        )
    )

    // ---- Running a call -----------------------------------------------------------------------------

    private fun goalFor(name: String, args: JSONObject): String = when (name) {
        "blinkit_search" -> "Blinkit: search " + args.optString("request", args.optString("query", "")).take(70)
        "blinkit_compare" -> "Blinkit: compare products"
        "blinkit_budget_plan" -> "Blinkit: plan within budget"
        "blinkit_cart" -> "Blinkit: cart " + args.optString("action", "view")
        "blinkit_address" -> "Blinkit: address " + args.optString("action", "status")
        "blinkit_prepare_checkout" -> "Blinkit: prepare checkout"
        "blinkit_place_order" -> "Blinkit: place confirmed order"
        else -> "Blinkit: order " + args.optString("action", "status")
    }

    private fun doubleOrNull(args: JSONObject, key: String): Double? =
        if (args.has(key) && !args.isNull(key)) args.optDouble(key).takeIf { !it.isNaN() } else null

    private fun intOrNull(args: JSONObject, key: String): Int? =
        if (args.has(key) && !args.isNull(key)) args.optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null

    private fun text(args: JSONObject, key: String): String? = args.optString(key, "").takeIf { it.isNotBlank() }

    private fun ints(args: JSONObject, key: String): List<Int> {
        val array = args.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optInt(i, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } }
    }

    private fun directive(name: String, r: BlinkitResult): String? = when (r.status) {
        BlinkitStatus.PREPARED ->
            if (name == "blinkit_order_status")
                "Tell the user the order state in one or two sentences and ask if they want to cancel it. Do not call blinkit_order_status with action=cancel again until they answer in a new message."
            else
                "Read this order summary to the user in two or three short sentences (items, total with fees, delivery address, payment) and ask them to say clearly if you should place it. Do not call blinkit_place_order until they answer in a new message."
        BlinkitStatus.PLACED -> "Blinkit's screen showed an order confirmation. Say so once, briefly, with the ETA only if the result gives one."
        BlinkitStatus.AMBIGUOUS -> "Read the numbered options to the user and ask which one, then call again with that choice."
        BlinkitStatus.NEEDS_SIGN_IN, BlinkitStatus.NEEDS_USER, BlinkitStatus.NEEDS_ADDRESS, BlinkitStatus.NEEDS_LOCATION ->
            "The user must act on the phone (login, OTP, CAPTCHA, address or location). Tell them exactly that. Do not type, tap or try to get around it. Wait for them to say they are done."
        BlinkitStatus.NEEDS_PAYMENT_AUTH ->
            "A payment app or PIN screen is open. The payment and PIN are the user's alone. Tell them to finish it, then use blinkit_order_status to check. Do not call blinkit_place_order again."
        BlinkitStatus.REFUSED ->
            "Nothing was ordered or cancelled. Tell the user why in one sentence. Do not call the tool again until the user has heard the summary and confirmed in a new message."
        BlinkitStatus.NOT_VERIFIED ->
            "The result is unverified. Do not claim success and do not repeat the action; tell the user what could and could not be confirmed."
        BlinkitStatus.UNAVAILABLE, BlinkitStatus.NOT_SERVICEABLE, BlinkitStatus.STORE_CLOSED, BlinkitStatus.MIN_ORDER, BlinkitStatus.OVER_BUDGET ->
            "Tell the user plainly what blocks the order and offer the alternative the result names. Nothing was ordered."
        else -> if (!r.success) "The step failed. Tell the user the real reason briefly. You may retry once with a different approach if it makes sense; never pretend it worked." else null
    }

    private fun toJson(name: String, r: BlinkitResult): JSONObject = JSONObject().apply {
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
        directive(name, r)?.let { put("system_directive", it) }
    }

    /** Runs one Blinkit tool. Never throws: any failure becomes an honest result. */
    suspend fun execute(
        context: Context,
        name: String,
        args: JSONObject,
        userUtterance: String,
        onProgress: (String) -> Unit = {}
    ): JSONObject {
        val goal = goalFor(name, args)
        try {
            AgentTaskEngine.begin(goal, "blinkit")
        } catch (e: Exception) {
            Log.w(TAG, "Task overlay could not start: ${e.message}")
        }
        val result = try {
            val agent = BlinkitAgent(AndroidBlinkitDriver(context, onProgress), gate(context), session)
            run(context, agent, name, args, userUtterance)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$name failed: ${e.message}", e)
            BlinkitResult(false, BlinkitStatus.ERROR, "Blinkit automation hit an unexpected problem: ${e.message ?: "unknown"}. Nothing was ordered or cancelled.")
        }
        if (name == "blinkit_place_order" && result.success && result.verified && result.status == BlinkitStatus.PLACED) {
            BlinkitNotificationTracker.beginTracking(context, OrderStage.PLACED)
            Log.i(TAG, "Blinkit notification monitoring enabled after verified order placement.")
        } else if (name == "blinkit_order_status" && result.success && result.verified) {
            val stage = (result.extra["stage"] as? String)?.let { runCatching { OrderStage.valueOf(it) }.getOrNull() }
            if (stage != null && stage != OrderStage.DELIVERED && stage != OrderStage.CANCELLED && stage != OrderStage.UNKNOWN) {
                BlinkitNotificationTracker.beginTracking(context, stage)
            } else if (stage == OrderStage.DELIVERED || stage == OrderStage.CANCELLED) {
                BlinkitNotificationTracker.stopTracking(context)
            }
        }
        finishTask(result)
        return toJson(name, result)
    }

    private suspend fun run(context: Context, agent: BlinkitAgent, name: String, args: JSONObject, utterance: String): BlinkitResult = when (name) {
        "blinkit_search" -> {
            val said = text(args, "request") ?: text(args, "query") ?: ""
            val request = BlinkitQueryParser.merge(
                base = BlinkitQueryParser.parse(said),
                query = text(args, "query")?.takeIf { text(args, "request") != null },
                minPrice = doubleOrNull(args, "min_price"),
                maxPrice = doubleOrNull(args, "max_price"),
                quantity = intOrNull(args, "quantity"),
                sort = BlinkitQueryParser.sortFromText(args.optString("sort", "")),
                brand = text(args, "brand"),
                packHint = text(args, "pack")
            )
            agent.search(request)
        }
        "blinkit_compare" -> agent.compare(ints(args, "items"))
        "blinkit_budget_plan" -> agent.planBudget(
            BlinkitQueryParser.parseShoppingList(text(args, "request") ?: utterance),
            doubleOrNull(args, "reserve")?.coerceAtLeast(0.0) ?: 0.0
        )
        "blinkit_cart" -> agent.cartAction(
            action = args.optString("action", "view").trim().lowercase(),
            index = intOrNull(args, "index"),
            item = text(args, "item"),
            quantity = intOrNull(args, "quantity"),
            variant = text(args, "variant"),
            code = text(args, "code")
        )
        "blinkit_address" -> {
            val action = args.optString("action", "status").trim().lowercase()
            if (action == "location") {
                val r = agent.locationStatus()
                if (args.optBoolean("open_settings", false) && !r.success) openFixSettings(context, r.extra["access"]?.toString())
                r
            } else agent.address(action, text(args, "label"))
        }
        "blinkit_prepare_checkout" -> agent.prepareCheckout(utterance)
        "blinkit_payment_method" -> agent.selectPaymentMethod(text(args, "method") ?: "", utterance)
        "blinkit_place_order" -> agent.placeOrder(utterance)
        else -> agent.orderStatus(args.optString("action", "status").trim().lowercase(), utterance)
    }

    /** Opens the Android page that fixes a location problem. It only navigates; the user flips the switch. */
    private fun openFixSettings(context: Context, access: String?) {
        val intent = when (access) {
            LocationAccess.SERVICES_OFF.name -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            LocationAccess.PERMISSION_DENIED.name ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            else -> null
        } ?: return
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Settings page could not be opened: ${e.message}")
        }
    }

    private fun finishTask(result: BlinkitResult) {
        try {
            when {
                result.success && result.verified -> {
                    AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.VERIFIED_GOAL, result.message.take(160))
                    AgentTaskEngine.finish(AgentTaskEngine.State.COMPLETED)
                }
                result.status in setOf(BlinkitStatus.NEEDS_SIGN_IN, BlinkitStatus.NEEDS_USER, BlinkitStatus.NEEDS_ADDRESS, BlinkitStatus.NEEDS_LOCATION,
                    BlinkitStatus.NEEDS_PAYMENT_AUTH, BlinkitStatus.PREPARED, BlinkitStatus.AMBIGUOUS) ->
                    AgentTaskEngine.finish(AgentTaskEngine.State.BLOCKED, result.message.take(160))
                result.status == BlinkitStatus.CANCELLED -> Unit
                else -> AgentTaskEngine.finish(AgentTaskEngine.State.FAILED, result.message.take(160))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Task overlay could not finish: ${e.message}")
        }
    }

    // ---- Guards for the generic tools --------------------------------------------------------------

    /** Is a Blinkit page on screen, and which one? Used to block direct taps on pay/cancel and typing into security screens. */
    private fun guardContext(): Pair<Boolean, BlinkitScreen?> {
        val service = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance ?: return false to null
        val root = service.getActiveRootNode() ?: return false to null
        // Fast path: taps in unrelated apps never pay for a full screen read.
        if (!BlinkitLinks.isBlinkitPackage(root.packageName?.toString())) return false to null
        return true to BlinkitScreenReader.classify(AccessibilityNodeReader.read(root))
    }

    fun blockGenericClick(target: String, description: String, byPositionOrId: Boolean): String? {
        val (context, screen) = guardContext()
        return BlinkitGuards.genericClickBlock(context, screen, target, description, byPositionOrId)
    }

    fun blockGenericType(): String? {
        val (context, screen) = guardContext()
        return BlinkitGuards.genericTypeBlock(context, screen)
    }
}
