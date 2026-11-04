package com.jarvis.assistant.automation.amazon

/** Builders for fake Amazon screens and a scripted fake phone, shared by the Amazon tests. */
object Screens {
    private var y = 0
    private fun next(step: Int = 120): Int { y += step; return y }

    fun node(text: String, clickable: Boolean = false, editable: Boolean = false, top: Int = next(), desc: String = "", viewId: String = "") =
        AmazonNode(text = text, description = desc, viewId = viewId, clickable = clickable, editable = editable,
            left = 0, top = top, right = 1000, bottom = top + 100)

    fun reset() { y = 0 }

    fun searchResults(vararg cards: Triple<String, String, String>): List<AmazonNode> {
        reset()
        val out = ArrayList<AmazonNode>()
        out += node("Search Amazon.in", clickable = true, editable = true, viewId = "com.amazon/search_edit")
        out += node("Sort by", clickable = true)
        out += node("Filters", clickable = true)
        out += node("results", clickable = false)
        // each card: title node, rating node, price node
        for ((title, rating, price) in cards) {
            out += node(title, clickable = true, top = next(150))
            out += node(rating)
            out += node(price)
        }
        return out
    }

    fun product(title: String, price: String, withAdd: Boolean = true, unavailable: Boolean = false, selected: List<String> = emptyList(), options: List<String> = emptyList()): List<AmazonNode> {
        reset()
        val out = ArrayList<AmazonNode>()
        out += node(title)
        out += node("4.3 out of 5 stars 1,204 ratings")
        out += node(price)
        out += node("FREE delivery Tomorrow")
        options.forEach { o -> out += node(o, clickable = true).copy(selected = o in selected) }
        if (unavailable) out += node("Currently unavailable")
        if (withAdd) out += node("Add to Cart", clickable = true)
        if (withAdd) out += node("Buy Now", clickable = true)
        out += node("About this item")
        out += node("8GB RAM and 256GB storage for fast work")
        out += node("Long battery life of up to 20 hours")
        out += node("Customer reviews")
        out += node("Sold by RetailNet")
        return out
    }

    fun cart(vararg rows: Triple<String, String, Int>, subtotal: String? = null): List<AmazonNode> {
        reset()
        val out = ArrayList<AmazonNode>()
        out += node("Shopping Cart")
        if (rows.isEmpty()) { out += node("Your Amazon Cart is empty"); out += node("Continue shopping", clickable = true); out += node("Sign in to your account", clickable = true); return out.filterNot { it.label.startsWith("Sign in") } }
        subtotal?.let { out += node("Subtotal $it") }
        out += node("Proceed to Buy (${rows.size} items)", clickable = true)
        for ((title, price, qty) in rows) {
            out += node(title, clickable = true, top = next(220))
            out += node(price)
            out += node("Qty: $qty", clickable = true)
            out += node("Delete", clickable = true)
        }
        return out
    }

    fun review(title: String, price: String, total: String, address: String = "Deliver to Sukant, Hyderabad 500001", pay: String = "Pay on Delivery"): List<AmazonNode> {
        reset()
        return listOf(
            node("Review your order"),
            node(address),
            node(title),
            node(price),
            node("Qty: 1"),
            node("Items: $price"),
            node("Delivery: FREE"),
            node("Order Total: $total"),
            node("Payment method: $pay"),
            node("Arriving tomorrow"),
            node("Place your order", clickable = true)
        )
    }

    fun placed(): List<AmazonNode> { reset(); return listOf(node("Order placed, thank you!"), node("Your order has been placed"), node("Arriving tomorrow"), node("View order", clickable = true)) }
    fun loading(): List<AmazonNode> { reset(); return listOf(node("")) }
    fun error(): List<AmazonNode> { reset(); return listOf(node("Something went wrong"), node("Please try again later"), node("Try again", clickable = true), node("Amazon")) }
    fun offline(): List<AmazonNode> { reset(); return listOf(node("No internet connection"), node("Check your connection"), node("Try again", clickable = true), node("Amazon")) }
    fun signIn(): List<AmazonNode> { reset(); return listOf(node("Sign in or create account"), node("Enter mobile number or email", editable = true), node("Continue", clickable = true), node("Conditions of Use")) }
    fun otp(): List<AmazonNode> { reset(); return listOf(node("Enter OTP"), node("We sent a one time password to your phone"), node("OTP", editable = true), node("Submit", clickable = true)) }
    fun home(): List<AmazonNode> { reset(); return listOf(node("Search Amazon.in", clickable = true, editable = true, viewId = "search_edit"), node("Hello, Sukant"), node("Today's deals", clickable = true), node("Categories", clickable = true)) }
}

/** A scripted phone: a current screen, taps that move between screens, and a virtual clock. */
class FakePhone : AmazonDriver {
    var screen: List<AmazonNode> = Screens.home()
    var clock = 0L
    val log = ArrayList<String>()
    var installed = true
    var cancelFlag = false
    var launchWorks = true
    var typingWorks = true
    var unreadable = false
    var launchHandler: (String) -> Unit = {}
    var clickHandler: (AmazonNode) -> Unit = {}
    var scrollHandler: (Boolean) -> Unit = {}
    var backHandler: () -> Unit = {}
    var submitHandler: () -> Boolean = { true }
    /** When set, the Nth snapshot call (1-based) returns the given screen once. */
    val oneShot = ArrayDeque<List<AmazonNode>>()
    var clicks = 0
    /** Test helper: lets virtual time pass, as when the user takes a moment to answer. */
    fun letTimePass(ms: Long) { clock += ms }

    override fun isAmazonInstalled() = installed
    override fun foregroundPackage(): String? = "in.amazon.mShop.android.shopping"
    override suspend fun snapshot(): List<AmazonNode>? { if (unreadable) return null; return oneShot.removeFirstOrNull() ?: screen }
    override suspend fun launch(url: String): Boolean { log += "launch:$url"; if (!launchWorks) return false; launchHandler(url); return true }
    override suspend fun click(node: AmazonNode): Boolean { clicks++; log += "click:${node.label}"; clickHandler(node); return true }
    override suspend fun typeInto(node: AmazonNode, text: String): Boolean { log += "type:$text"; return typingWorks }
    override suspend fun submitSearch(): Boolean { log += "submit"; return submitHandler() }
    override suspend fun scroll(down: Boolean): Boolean { log += if (down) "scroll:down" else "scroll:up"; scrollHandler(down); return true }
    override suspend fun back(): Boolean { log += "back"; backHandler(); return true }
    override suspend fun pause(ms: Long) { clock += ms }
    override fun now(): Long = clock
    override fun cancelled(): Boolean = cancelFlag
    override fun progress(message: String) { log += "progress:$message" }
}
