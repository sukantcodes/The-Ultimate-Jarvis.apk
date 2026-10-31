package com.jarvis.assistant.automation.amazon

import java.net.URLEncoder

/**
 * Deep links into the Amazon Shopping app. These are ordinary https links that the installed Amazon app
 * opens (App Links); no Amazon API key or account secret is involved.
 *
 * URL parameters are only hints. Whatever Amazon shows is parsed from the screen and checked again against the
 * request (price, rating, Prime), so a parameter Amazon ignores never makes a wrong result look right.
 */
object AmazonLinks {

    enum class Market(val host: String, val appPackages: List<String>) {
        IN("www.amazon.in", listOf("in.amazon.mShop.android.shopping")),
        GLOBAL("www.amazon.com", listOf("com.amazon.mShop.android.shopping"))
    }

    /** Every Amazon Shopping package this module knows about, India first. */
    val allPackages: List<String> = Market.values().flatMap { it.appPackages }

    fun marketForPackage(pkg: String?): Market =
        Market.values().firstOrNull { pkg != null && pkg in it.appPackages } ?: Market.IN

    fun isAmazonShoppingPackage(pkg: String?): Boolean = pkg != null && pkg in allPackages

    fun searchUrl(request: AmazonSearchRequest, market: Market = Market.IN): String {
        val params = ArrayList<String>()
        params.add("k=" + URLEncoder.encode(request.query.trim(), "UTF-8"))
        when (request.sort) {
            SortPreference.PRICE_LOW -> params.add("s=price-asc-rank")
            SortPreference.PRICE_HIGH -> params.add("s=price-desc-rank")
            SortPreference.NEWEST -> params.add("s=date-desc-rank")
            // Rating is ranked on this side with a review-count-aware score; Amazon's own
            // "average review" sort pushes items with only a handful of ratings to the top.
            SortPreference.RATING, SortPreference.RELEVANCE -> Unit
        }
        request.minPrice?.let { params.add("low-price=" + it.toLong()) }
        request.maxPrice?.let { params.add("high-price=" + it.toLong()) }
        return "https://${market.host}/s?" + params.joinToString("&")
    }

    fun cartUrl(market: Market = Market.IN): String = "https://${market.host}/gp/cart/view.html"

    fun productUrl(asin: String, market: Market = Market.IN): String? =
        if (isValidAsin(asin)) "https://${market.host}/dp/${asin.uppercase()}" else null

    fun isValidAsin(value: String): Boolean = Regex("^[A-Za-z0-9]{10}$").matches(value.trim())

    /** Pulls an ASIN out of an Amazon product link, or null. */
    fun extractAsin(link: String): String? =
        Regex("""/(?:dp|gp/product|gp/aw/d)/([A-Za-z0-9]{10})""").find(link)?.groupValues?.get(1)?.uppercase()
}
