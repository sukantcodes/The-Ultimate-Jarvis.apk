package com.jarvis.assistant.automation.blinkit

import com.jarvis.assistant.automation.amazon.AmazonNode
import com.jarvis.assistant.automation.amazon.InMemoryOrderLedger
import com.jarvis.assistant.automation.rapido.LocationAccess
import com.jarvis.assistant.automation.rapido.LocationState

/** One product in the simulated Blinkit catalog. */
data class Item(
    val name: String,
    val pack: String,
    val price: Int,
    val mrp: Int = price,
    val rating: String? = null,
    val inStock: Boolean = true
)

/** Builders for fake Blinkit screens, in document order, the way the app's lists expose them. */
object BScreens {
    private var y = 0
    private fun next(step: Int = 120): Int { y += step; return y }
    fun reset() { y = 0 }

    fun node(text: String, clickable: Boolean = false, editable: Boolean = false, desc: String = "", top: Int = next()) =
        AmazonNode(text = text, description = desc, clickable = clickable, editable = editable, left = 0, top = top, right = 500, bottom = top + 100)

    fun rs(v: Int) = "₹$v"

    /** A product card: ETA, name, pack, price, MRP, rating, then ADD / stepper / Notify me. */
    fun card(item: Item, qtyInCart: Int): List<AmazonNode> {
        val out = ArrayList<AmazonNode>()
        out += node("8 MINS")
        out += node(item.name)
        out += node(item.pack)
        out += node(rs(item.price))
        if (item.mrp > item.price) out += node(rs(item.mrp))
        item.rating?.let { out += node(it) }
        when {
            !item.inStock -> out += node("Out of stock", clickable = false)
            qtyInCart > 0 -> {
                out += node("−", clickable = true, desc = "dec:${item.name}")
                out += node(qtyInCart.toString())
                out += node("+", clickable = true, desc = "inc:${item.name}")
            }
            else -> out += node("ADD", clickable = true, desc = "add:${item.name}")
        }
        return out
    }

    fun home(address: String = "HOME - Flat 12, MG Road, Hyderabad", cartItems: Int = 0): List<AmazonNode> {
        reset()
        val out = ArrayList<AmazonNode>()
        out += node("Delivery in 8 minutes")
        out += node(address, clickable = true)
        out += node("Search \"milk\"", clickable = true)
        out += node("Categories", clickable = true)
        out += node("Order again")
        if (cartItems > 0) out += node("View cart", clickable = true)
        return out
    }

    fun results(items: List<Item>, cart: Map<String, Int>, query: String = "milk"): List<AmazonNode> {
        reset()
        val out = ArrayList<AmazonNode>()
        out += node("Search \"$query\"", clickable = true, editable = true)
        out += node("Showing results for $query")
        out += node("Sort", clickable = true)
        out += node("Filters", clickable = true)
        items.forEach { out += card(it, cart[it.name] ?: 0) }
        if (cart.isNotEmpty()) out += node("View cart", clickable = true)
        return out
    }

    fun noResults(query: String): List<AmazonNode> {
        reset()
        return listOf(node("Search \"$query\"", clickable = true, editable = true), node("No results found for $query"), node("Try a different spelling"), node("Categories"))
    }

    fun cart(
        items: List<Item>, cart: Map<String, Int>, fees: List<Pair<String, String>> = listOf("Delivery charge" to "₹25", "Handling charge" to "₹2"),
        address: String = "Delivering to Home - Flat 12, MG Road, Hyderabad", note: String? = null, loggedIn: Boolean = true, recommended: List<Item> = emptyList(),
        discount: Int = 0, unavailable: Set<String> = emptySet()
    ): List<AmazonNode> {
        reset()
        val out = ArrayList<AmazonNode>()
        out += node("My Cart")
        out += node("Delivery in 9 minutes")
        out += node(address)
        val lines = items.filter { (cart[it.name] ?: 0) > 0 || it.name in unavailable }
        lines.forEach { out += card(it, cart[it.name] ?: 0).filterNot { n -> n.text == "8 MINS" } }
        val itemTotal = lines.filter { it.inStock }.sumOf { it.price * (cart[it.name] ?: 0) }
        out += node("Bill details")
        out += node("Item total"); out += node(rs(itemTotal))
        fees.forEach { (label, amount) -> out += node(label); out += node(amount) }
        if (discount > 0) { out += node("Coupon discount"); out += node("-₹$discount") }
        val feeSum = fees.sumOf { Regex("\\d+").find(it.second)?.value?.toInt() ?: 0 }
        out += node("Grand total"); out += node(rs(itemTotal + feeSum - discount))
        note?.let { out += node(it) }
        if (recommended.isNotEmpty()) { out += node("You might also like"); recommended.forEach { out += card(it, 0) } }
        out += if (loggedIn) node("Proceed", clickable = true) else node("Login to proceed", clickable = true)
        return out
    }

    fun picker(entries: List<Pair<String, String>>): List<AmazonNode> {
        reset()
        val out = ArrayList<AmazonNode>()
        out += node("Select delivery address")
        out += node("Use current location", clickable = true)
        out += node("Saved addresses")
        entries.forEach { (label, text) -> out += node(label, clickable = true); out += node(text, clickable = true) }
        out += node("Add new address", clickable = true)
        return out
    }

    fun confirmAddressPicker(address: String): List<AmazonNode> {
        reset()
        return listOf(node("Select delivery address"), node("Saved addresses"), node("Home"), node(address), node("Proceed to pay", clickable = true))
    }

    fun payment(address: String, total: Int, itemTotal: Int, fees: List<Pair<String, String>>, method: String = "UPI - Google Pay", options: List<String> = listOf(method)): List<AmazonNode> {
        reset()
        val out = ArrayList<AmazonNode>()
        out += node("Payment options")
        out += node("Delivering to Home - $address")
        out += node("Paying using " + method)
        options.filterNot { it == method }.forEach { out += node(it, clickable = true) }
        out += node("Bill details")
        out += node("Item total"); out += node(rs(itemTotal))
        fees.forEach { (l, a) -> out += node(l); out += node(a) }
        out += node("To pay"); out += node(rs(total))
        out += node("Pay ${rs(total)}", clickable = true)
        return out
    }

    fun tracking(stage: String = "Order placed", eta: String? = "Arriving in 9 mins", extra: List<String> = emptyList(), cancel: Boolean = false, help: Boolean = true): List<AmazonNode> {
        reset()
        val out = ArrayList<AmazonNode>()
        out += node(stage)
        eta?.let { out += node(it) }
        extra.forEach { out += node(it) }
        out += node("Order ID #4821")
        if (cancel) out += node("Cancel order", clickable = true)
        if (help) out += node("Need help?", clickable = true)
        return out
    }

    fun ordersList(): List<AmazonNode> {
        reset()
        return listOf(
            node("Your orders"),
            node("Delivered"), node("Amul Taaza Milk and 2 more"), node("₹182"), node("Reorder", clickable = true),
            node("Delivered"), node("Aashirvaad Atta 5 kg"), node("₹265"), node("Reorder", clickable = true)
        )
    }

    fun loading(): List<AmazonNode> { reset(); return listOf(node("")) }
    fun error(): List<AmazonNode> { reset(); return listOf(node("Something went wrong"), node("Please check and try again"), node("Try again", clickable = true), node("Blinkit")) }
    fun offline(): List<AmazonNode> { reset(); return listOf(node("No internet connection"), node("Check your connection"), node("Try again", clickable = true), node("Blinkit")) }
    fun login(): List<AmazonNode> { reset(); return listOf(node("Log in or sign up"), node("Enter mobile number", editable = true), node("Continue", clickable = true), node("Terms of service")) }
    fun otp(): List<AmazonNode> { reset(); return listOf(node("Enter OTP"), node("We sent a one time password to your phone"), node("OTP", editable = true), node("Verify", clickable = true)) }
    fun notServiceable(): List<AmazonNode> { reset(); return listOf(node("Sorry, we are not delivering to your location yet"), node("Coming soon to your location"), node("Change address", clickable = true)) }
    fun storeClosed(): List<AmazonNode> { reset(); return listOf(node("We are experiencing high demand"), node("Stores are closed right now, we'll be back soon"), node("OK", clickable = true)) }
    fun upiPin(): List<AmazonNode> { reset(); return listOf(node("Enter UPI PIN"), node("Pay ₹200 to Blinkit"), node("1"), node("2"), node("3")) }
}

/** A scripted phone: a current screen, taps that move between screens, and a virtual clock. */
class FakeBlinkit : BlinkitDriver {
    var screen: List<AmazonNode> = BScreens.home()
    var clock = 0L
    val log = ArrayList<String>()
    var installed = true
    var cancelFlag = false
    var launchWorks = true
    var typingWorks = true
    var unreadable = false
    var foreground: String? = "com.grofers.customerapp"
    var location = LocationState(LocationAccess.PRECISE, 20, "Indiranagar")
    var launchHandler: (String?) -> Unit = {}
    var clickHandler: (AmazonNode) -> Unit = {}
    var scrollHandler: (Boolean) -> Unit = {}
    var backHandler: () -> Unit = {}
    var typeHandler: (String) -> Unit = {}
    var submitHandler: () -> Boolean = { true }
    val oneShot = ArrayDeque<List<AmazonNode>>()
    var clicks = 0

    fun letTimePass(ms: Long) { clock += ms }

    override fun isInstalled() = installed
    override fun foregroundPackage(): String? = foreground
    override suspend fun snapshot(): List<AmazonNode>? { if (unreadable) return null; return oneShot.removeFirstOrNull() ?: screen }
    override suspend fun launch(url: String?): Boolean { log += "launch:${url ?: "home"}"; if (!launchWorks) return false; launchHandler(url); return true }
    override suspend fun click(node: AmazonNode): Boolean { clicks++; log += "click:${node.label}"; clickHandler(node); return true }
    override suspend fun typeInto(node: AmazonNode, text: String): Boolean { log += "type:$text"; if (typingWorks) typeHandler(text); return typingWorks }
    override suspend fun submitSearch(): Boolean { log += "submit"; return submitHandler() }
    override suspend fun scroll(down: Boolean): Boolean { log += if (down) "scroll:down" else "scroll:up"; scrollHandler(down); return true }
    override suspend fun back(): Boolean { log += "back"; backHandler(); return true }
    override suspend fun pause(ms: Long) { clock += ms }
    override fun now(): Long = clock
    override fun cancelled(): Boolean = cancelFlag
    override fun progress(message: String) { log += "progress:$message" }
    override suspend fun locationState(): LocationState = location
}

/**
 * A small simulated Blinkit: a catalog, a cart, saved addresses, a payment screen and order tracking. Taps are mapped
 * to what the real app would do, so the agent runs its real logic against it.
 */
class BlinkitWorld(val catalog: List<Item>) {
    val phone = FakeBlinkit()
    val cart = LinkedHashMap<String, Int>()
    var query = "milk"
    var address = "HOME - Flat 12, MG Road, Hyderabad"
    val saved = mutableListOf("Home" to "Flat 12, MG Road, Hyderabad", "Work" to "Tower B, HITEC City, Hyderabad")
    var fees = listOf("Delivery charge" to "₹25", "Handling charge" to "₹2")
    var paymentMethod = "UPI - Google Pay"
    var paymentOptions = listOf("UPI - Google Pay", "PhonePe", "Cash on Delivery")
    var requireAddressConfirm = false
    var loggedIn = true
    var paymentAppOpens = false
    var payClicks = 0
    var cancelClicks = 0
    var orderStage = "Order placed"
    var cancelAvailable = true
    var addWorks = true
    var incrementWorks = true
    var optionsFor: Set<String> = emptySet()
    var linkWorks = true
    var cartNote: String? = null
    var unavailableInCart: Set<String> = emptySet()
    val gate = BlinkitGate({ phone.now() }, InMemoryOrderLedger())
    val agent = BlinkitAgent(phone, gate)

    enum class At { HOME, RESULTS, CART, PICKER, PAYMENT, TRACKING, ORDERS, SEARCHBOX }
    var at = At.HOME

    fun results(): List<Item> {
        val tokens = query.lowercase().split(' ').filter { it.length >= 3 && !it.matches(Regex("\\d+.*")) }
        return catalog.filter { i -> tokens.any { t -> i.name.lowercase().contains(t.removeSuffix("s")) } }
    }

    fun itemTotal() = cart.entries.sumOf { (n, q) -> (catalog.first { it.name == n }.price) * q }
    fun feeSum() = fees.sumOf { Regex("\\d+").find(it.second)?.value?.toInt() ?: 0 }
    fun total() = itemTotal() + feeSum()
    private fun addressLine() = address.substringAfter("- ").ifBlank { address }

    fun render() {
        phone.screen = when (at) {
            At.HOME -> BScreens.home(address, cart.size)
            At.RESULTS -> BScreens.results(results(), cart, query)
            At.CART -> BScreens.cart(catalog, cart, fees, "Delivering to ${address.replace("HOME - ", "Home - ")}", note = cartNote, loggedIn = loggedIn, unavailable = unavailableInCart)
            At.SEARCHBOX -> { BScreens.reset(); listOf(BScreens.node("Search for products", clickable = true, editable = true), BScreens.node("Trending searches"), BScreens.node("Milk"), BScreens.node("Bread")) }
            At.PICKER -> if (requireAddressConfirm) BScreens.confirmAddressPicker(addressLine()) else BScreens.picker(saved)
            At.PAYMENT -> BScreens.payment(addressLine(), total(), itemTotal(), fees, paymentMethod, paymentOptions)
            At.TRACKING -> {
                val finished = orderStage.contains("delivered", true) || orderStage.contains("cancelled", true)
                BScreens.tracking(orderStage, if (finished) null else "Arriving in 9 mins", cancel = cancelAvailable && !finished)
            }
            At.ORDERS -> BScreens.ordersList()
        }
    }

    init {
        phone.launchHandler = { url ->
            when {
                url == null -> at = At.HOME
                url.contains("/s/?q=") && !linkWorks -> at = At.HOME
                url.contains("/s/?q=") -> { query = java.net.URLDecoder.decode(url.substringAfter("q="), "UTF-8"); at = At.RESULTS }
            }
            render()
        }
        phone.typeHandler = { text -> query = text }
        phone.submitHandler = { at = At.RESULTS; render(); true }
        phone.backHandler = { at = if (at == At.PICKER) At.HOME else At.RESULTS; render() }
        phone.clickHandler = { n ->
            val d = n.description
            val l = n.label
            when {
                d.startsWith("add:") -> if (addWorks) {
                    val name = d.removePrefix("add:")
                    if (!loggedIn) { phone.screen = BScreens.login() } else { cart[name] = 1; render() }
                }
                d.startsWith("inc:") -> if (incrementWorks) { val name = d.removePrefix("inc:"); cart[name] = (cart[name] ?: 0) + 1; render() }
                d.startsWith("dec:") -> { val name = d.removePrefix("dec:"); val q = (cart[name] ?: 0) - 1; if (q <= 0) cart.remove(name) else cart[name] = q; render() }
                l == "View cart" -> { at = At.CART; render() }
                l == "Proceed" -> { at = if (requireAddressConfirm) At.PICKER else At.PAYMENT; render() }
                l == "Proceed to pay" -> { at = At.PAYMENT; render() }
                at == At.PAYMENT && paymentOptions.contains(l) -> { paymentMethod = l; render() }
                l.startsWith("Pay ₹") -> {
                    payClicks++
                    if (paymentAppOpens) { phone.foreground = "com.google.android.apps.nbu.paisa.user"; phone.screen = BScreens.upiPin() }
                    else { at = At.TRACKING; render() }
                }
                l.startsWith("Delivering") || l.startsWith("HOME") || l.startsWith("Home -") -> { at = At.PICKER; render() }
                at == At.PICKER && saved.any { it.first == l || it.second == l } -> {
                    val pick = saved.first { it.first == l || it.second == l }
                    address = pick.first.uppercase() + " - " + pick.second
                    at = At.HOME; render()
                }
                l == "Cancel order" && at == At.TRACKING -> { cancelClicks++; orderStage = "Order cancelled"; cancelAvailable = false; render() }
                l == "Need help?" -> phone.log += "help-opened"
                l.startsWith("Your orders") || l == "Orders" -> { at = At.ORDERS; render() }
                l.startsWith("Search") && at == At.HOME -> { at = At.SEARCHBOX; render() }
                l.startsWith("Search") -> Unit
            }
        }
        render()
    }
}
