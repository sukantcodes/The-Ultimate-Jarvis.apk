package com.jarvis.assistant.automation.maps

import android.content.Context
import com.google.gson.JsonObject
import com.jarvis.assistant.provider.HttpRequestSpec
import com.jarvis.assistant.provider.HttpTransport
import com.jarvis.assistant.provider.TransportException
import com.jarvis.assistant.tools.Risk
import com.jarvis.assistant.tools.Tool
import com.jarvis.assistant.tools.ToolContext
import com.jarvis.assistant.tools.ToolParam
import com.jarvis.assistant.tools.ToolResult
import com.jarvis.assistant.tools.ToolSpec
import com.jarvis.assistant.tools.builtin.bool
import com.jarvis.assistant.tools.builtin.int
import com.jarvis.assistant.tools.builtin.str
import kotlinx.coroutines.delay

private fun JsonObject.list(name: String): List<String> {
    val e = get(name) ?: return emptyList()
    return when {
        e.isJsonArray -> e.asJsonArray.mapNotNull { x -> x.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() } }
        e.isJsonPrimitive -> e.asString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }
}

private fun JsonObject.number(name: String): Double? = get(name)?.takeIf { it.isJsonPrimitive }?.runCatching { asDouble }?.getOrNull()

/**
 * Google Maps skill. Finding places and reading their details uses OpenStreetMap data (free, no key); starting and
 * changing navigation hands links to Google Maps itself, which needs no permission. Only reading the Maps screen and
 * Google Maps' own saved lists need the Accessibility service, and each of those says so when it is off.
 */
object MapsTools {
    const val GROUP = "maps"
    private const val UA = "TheUltimateJarvis/1.0 (Android assistant)"
    private val OVERPASS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")
    private const val NOMINATIM = "https://nominatim.openstreetmap.org/search"

    class Deps(val app: Context, val transport: HttpTransport, val book: PlaceBook, val session: MapsSession, val driver: AndroidMapsDriver)

    private val session = MapsSession()

    /** One place book and one trip record for the whole app, however often the tool list is rebuilt. */
    fun deps(app: Context, transport: HttpTransport): Deps {
        val prefs = app.applicationContext.getSharedPreferences("jarvis_maps_places", Context.MODE_PRIVATE)
        val book = PlaceBook(PlaceBook.fromJson(prefs.getString("places", null))) { list ->
            prefs.edit().putString("places", PlaceBook.toJson(list)).apply()
        }
        return Deps(app.applicationContext, transport, book, session, AndroidMapsDriver(app))
    }

    fun all(d: Deps): List<Tool> {
        MapsShortcuts.publish(d.app, d.book.all())
        return listOf(FindNearby(d), Navigate(d), ChangeRoute(d), NavStatus(d), NavControl(d), Places(d), OpenSaved(d), PinShortcut(d), LocationStatus(d))
    }

    // ---------------------------------------------------------------- shared helpers

    private fun resolveText(d: Deps, text: String): MapsTarget {
        val t = text.trim()
        d.book.find(t)?.let { return MapsTarget(it.name, it.destination()) }
        return MapsTarget(t, t)
    }

    /** Sends the trip to Google Maps, records it, and says honestly whether guidance is visibly running. */
    private suspend fun launchTrip(d: Deps, dest: MapsTarget, stops: List<MapsTarget>, mode: MapsLinks.Mode, avoid: Set<MapsLinks.Avoid>): ToolResult {
        val uri = (if (stops.isEmpty()) MapsLinks.navigationUri(dest.query, mode, avoid) else null)
            ?: MapsLinks.directionsUrl(null, dest.query, stops.map { it.query }, mode, avoid, navigate = mode != MapsLinks.Mode.TRANSIT)
        if (!MapsLauncher.open(d.app, uri)) return ToolResult.Error("Google Maps (or another map app) could not be opened. Install Google Maps, then try again.")
        if (mode == MapsLinks.Mode.TRANSIT) {
            return ToolResult.Ok("Showing public-transport routes to ${dest.label} in Google Maps. Maps does not start guidance for transit by itself; pick a route there.")
        }
        d.session.start(dest, stops, mode, avoid)
        val summary = d.session.summary()
        if (!d.driver.accessibilityOn()) return ToolResult.Ok("Sent to Google Maps: $summary Guidance should begin in a moment. (Accessibility is off, so I can not confirm it from the screen.)")
        repeat(5) {
            delay(1_500)
            d.driver.texts()?.let { MapsScreen.parse(it) }?.takeIf { it.navigating }?.let { return ToolResult.Ok("Navigation started. $summary ${it.describe()}") }
        }
        return ToolResult.Ok("Sent to Google Maps: $summary I could not confirm from the screen that guidance began; if Maps shows a route preview, tap Start.")
    }

    private suspend fun getText(d: Deps, url: String, headers: Map<String, String> = mapOf("User-Agent" to UA, "Accept" to "application/json")): String? = try {
        val r = d.transport.execute(HttpRequestSpec("GET", url, headers, null, 30_000L))
        if (r.isSuccessful) r.body else null
    } catch (_: TransportException) { null }

    private suspend fun geocode(d: Deps, area: String): MapsPlace? {
        val body = getText(d, "$NOMINATIM?q=${MapsLinks.enc(area)}&format=jsonv2&limit=1&extratags=0") ?: return null
        return MapsGeo.parseNominatim(body, null, null).firstOrNull()
    }

    // ---------------------------------------------------------------- finding places

    class FindNearby(private val d: Deps) : Tool {
        override val spec = ToolSpec(
            "maps_find_nearby",
            "Find the nearest places of a kind or brand (\"dominos\", \"petrol pump\", \"ATM\", \"pharmacy\") around the phone's location, or around any area or saved place named in near. Returns the nearest few with distance, address, hours and phone when known, and remembers them so maps_navigate can start navigation to number 1, 2 or 3. Distances are straight-line.",
            listOf(
                ToolParam("query", required = true, description = "What to find. The user's own words are fine, e.g. \"dominos store\"."),
                ToolParam("near", description = "Search around this area or saved place instead of the phone's location, e.g. \"Koramangala\" or \"office\"."),
                ToolParam("radius_km", "integer", "Only look this far (default: widens from 3 km to 30 km until something is found)."),
                ToolParam("limit", "integer", "How many to return, 1 to 5 (default 3)."),
                ToolParam("open_in_maps", "boolean", "Also open this search in Google Maps so the user sees the map.")
            ), Risk.NETWORK, GROUP
        )

        override fun describe(args: JsonObject) = "Find ${args.str("query").orEmpty()} nearby"

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val query = MapsGeo.cleanQuery(args.str("query").orEmpty())
            if (query.filter { it.isLetterOrDigit() }.length < 3 && MapsGeo.categoryTags(query) == null) return ToolResult.Error("Tell me what to look for, for example a shop name or \"petrol pump\".")

            val near = args.str("near")?.trim().orEmpty()
            val centre: Triple<Double, Double, String> = if (MapsLinks.isCurrentLocation(near)) {
                when (val fix = MapsLocation.read(d.app)) {
                    is MapsLocation.Result.Fix -> Triple(fix.lat, fix.lon, "your location")
                    is MapsLocation.Result.Unavailable -> return ToolResult.Error(fix.reason)
                }
            } else {
                val saved = d.book.find(near)?.takeIf { it.lat != null && it.lon != null }
                if (saved != null) Triple(saved.lat!!, saved.lon!!, saved.name)
                else geocode(d, near)?.let { Triple(it.lat, it.lon, near) } ?: return ToolResult.Error("I could not find an area called \"$near\".")
            }
            val (lat, lon, around) = centre
            val limit = (args.int("limit") ?: 3).coerceIn(1, 5)
            val radii = args.number("radius_km")?.let { listOf((it * 1000).toInt().coerceIn(500, 50_000)) } ?: listOf(3_000, 10_000, 30_000)

            var found: List<MapsPlace> = emptyList()
            var usedRadius = radii.last()
            var reached = false
            for (r in radii) {
                val q = MapsLinks.enc(MapsGeo.overpassQuery(query, lat, lon, r))
                val body = OVERPASS.firstNotNullOfOrNull { base -> getText(d, "$base?data=$q") }
                if (body == null) continue
                reached = true
                found = MapsGeo.rank(MapsGeo.parseOverpass(body, lat, lon), limit)
                if (found.isNotEmpty()) { usedRadius = r; break }
            }
            if (found.isEmpty()) {
                val box = 0.3
                val url = "$NOMINATIM?q=${MapsLinks.enc(query)}&format=jsonv2&limit=10&extratags=1&bounded=1&viewbox=${lon - box},${lat + box},${lon + box},${lat - box}"
                getText(d, url)?.let { reached = true; found = MapsGeo.rank(MapsGeo.parseNominatim(it, lat, lon), limit) }
            }
            if (args.bool("open_in_maps") == true || (found.isEmpty() && reached)) MapsLauncher.open(d.app, MapsLinks.searchUri(query, lat, lon))
            if (found.isEmpty()) {
                return if (!reached) ToolResult.Error("The map data service could not be reached. Check the internet connection and try again.")
                else ToolResult.Ok("No \"$query\" found within ${usedRadius / 1000} km of $around in the map data (it can miss new shops). I opened the search in Google Maps so you can look there.")
            }
            d.session.remember(found)
            return ToolResult.Ok(buildString {
                appendLine("Nearest \"$query\" to $around:")
                found.forEachIndexed { i, p ->
                    append("${i + 1}. ${p.name}, ${MapsGeo.distanceText(p.distanceM)} away (straight line). ${p.address}.")
                    p.hours?.let { append(" Hours: $it.") }
                    p.phone?.let { append(" Phone: $it.") }
                    appendLine()
                }
                append("Say \"navigate\" (or maps_navigate with candidate 1) to start guidance; Google Maps will then show the real road distance and time. Source: OpenStreetMap.")
            })
        }
    }

    // ---------------------------------------------------------------- navigation

    class Navigate(private val d: Deps) : Tool {
        override val spec = ToolSpec(
            "maps_navigate",
            "Start navigation in Google Maps at once. The destination can be a place name, an address, a saved place (home, office), or candidate N from the last maps_find_nearby. Without from, guidance starts from where the phone is. With from, Maps can only show the route preview, because it can not guide from somewhere else.",
            listOf(
                ToolParam("destination", description = "Where to go: name, address or saved place."),
                ToolParam("candidate", "integer", "Number from the last maps_find_nearby result."),
                ToolParam("from", description = "Start point if it is not the phone's location."),
                ToolParam("stops", "array", "Places to pass through, in order."),
                ToolParam("mode", description = "driving, walking, bicycling, two_wheeler or transit (default driving).", enumValues = listOf("driving", "walking", "bicycling", "two_wheeler", "transit")),
                ToolParam("avoid", "array", "Any of: tolls, highways, ferries.")
            ), Risk.WRITE, GROUP
        )

        override fun describe(args: JsonObject) = "Navigate to ${args.str("destination") ?: args.int("candidate")?.let { "place #$it" }.orEmpty()}"

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val dest: MapsTarget = args.int("candidate")?.let { n ->
                val c = d.session.candidates.getOrNull(n - 1) ?: return ToolResult.Error("There is no result number $n. Run maps_find_nearby first.")
                MapsTarget(c.name, MapsLinks.coords(c.lat, c.lon))
            } ?: args.str("destination")?.takeIf { it.isNotBlank() }?.let { resolveText(d, it) }
            ?: return ToolResult.Error("Tell me where to go: a place, an address, a saved place, or candidate number.")
            val mode = MapsLinks.mode(args.str("mode")) ?: return ToolResult.Error("Mode must be driving, walking, bicycling, two_wheeler or transit.")
            val (avoid, unknown) = MapsLinks.avoid(args.list("avoid"))
            if (unknown.isNotEmpty()) return ToolResult.Error("I can avoid tolls, highways or ferries, not: ${unknown.joinToString()}.")
            val stops = args.list("stops").map { resolveText(d, it) }
            val from = args.str("from")?.trim()

            if (!MapsLinks.isCurrentLocation(from)) {
                val origin = d.book.find(from.orEmpty())?.destination() ?: from
                val url = MapsLinks.directionsUrl(origin, dest.query, stops.map { it.query }, mode, avoid, navigate = false)
                if (!MapsLauncher.open(d.app, url)) return ToolResult.Error("Google Maps could not be opened.")
                return ToolResult.Ok("Showing the route from $from to ${dest.label} in Google Maps. Maps can only guide from where you are, so tap Start there once you are at $from.")
            }
            return launchTrip(d, dest, stops, mode, avoid)
        }
    }

    class ChangeRoute(private val d: Deps) : Tool {
        override val spec = ToolSpec(
            "maps_change_route",
            "Change the trip that is running: go somewhere else, add or remove a stop, switch travel mode, or avoid tolls, highways or ferries. Google Maps restarts guidance on the new route. Works on the trip Jarvis started.",
            listOf(
                ToolParam("destination", description = "New final destination."),
                ToolParam("add_stop", description = "A place to pass through next."),
                ToolParam("remove_stop", description = "Name of a stop to drop."),
                ToolParam("mode", description = "driving, walking, bicycling or two_wheeler.", enumValues = listOf("driving", "walking", "bicycling", "two_wheeler")),
                ToolParam("avoid", "array", "Replaces what is avoided: any of tolls, highways, ferries."),
                ToolParam("clear_avoid", "boolean", "true stops avoiding anything.")
            ), Risk.WRITE, GROUP
        )

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val s = d.session
            val current = s.destination ?: return ToolResult.Error("No trip started by Jarvis is running. Use maps_navigate with a destination first.")
            val dest = args.str("destination")?.takeIf { it.isNotBlank() }?.let { resolveText(d, it) } ?: current
            val mode = if (args.has("mode")) MapsLinks.mode(args.str("mode")) ?: return ToolResult.Error("Unknown travel mode.") else s.mode
            var avoid = s.avoid
            if (args.bool("clear_avoid") == true) avoid = emptySet()
            if (args.has("avoid")) {
                val (a, unknown) = MapsLinks.avoid(args.list("avoid"))
                if (unknown.isNotEmpty()) return ToolResult.Error("I can avoid tolls, highways or ferries, not: ${unknown.joinToString()}.")
                avoid = a
            }
            var stops = s.stops
            args.str("remove_stop")?.takeIf { it.isNotBlank() }?.let { r ->
                val t = r.trim().lowercase()
                val hit = stops.firstOrNull { it.label.lowercase().contains(t) || it.query.lowercase().contains(t) } ?: return ToolResult.Error("The trip has no stop matching \"$r\".")
                stops = stops - hit
            }
            args.str("add_stop")?.takeIf { it.isNotBlank() }?.let { stops = stops + resolveText(d, it) }
            if (dest == current && mode == s.mode && avoid == s.avoid && stops == s.stops) return ToolResult.Ok("Nothing to change: the trip already is ${s.summary()}")
            return launchTrip(d, dest, stops, mode, avoid)
        }
    }

    class NavStatus(private val d: Deps) : Tool {
        override val spec = ToolSpec(
            "maps_nav_status",
            "Say how the trip is going: time left, distance, arrival time and the next instruction, read from the Google Maps screen (needs the Accessibility service), plus the trip Jarvis started.",
            emptyList(), Risk.READ, GROUP
        )

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val trip = d.session.summary()
            if (!d.driver.accessibilityOn()) return ToolResult.Ok("$trip I can not read live progress because the Accessibility service is off.")
            val texts = d.driver.texts() ?: return ToolResult.Ok("$trip Google Maps is not on screen, so I can not read live progress. Say \"show navigation\" to bring it up.")
            return ToolResult.Ok("$trip ${MapsScreen.parse(texts).describe()}")
        }
    }

    class NavControl(private val d: Deps) : Tool {
        override val spec = ToolSpec(
            "maps_nav_control",
            "Press a control in Google Maps while navigating: stop (exit navigation), overview (whole route), or recenter (back to the live view). Needs the Accessibility service.",
            listOf(ToolParam("action", required = true, enumValues = listOf("stop", "overview", "recenter", "show"), description = "What to press. show only brings Google Maps to the front.")),
            Risk.WRITE, GROUP
        )

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val action = args.str("action").orEmpty().trim().lowercase()
            if (action == "show") return if (MapsLauncher.bringToFront(d.app)) ToolResult.Ok("Google Maps is in front.") else ToolResult.Error("Google Maps could not be opened.")
            val labels = when (action) {
                "stop" -> listOf("Exit navigation", "Exit", "End")
                "overview" -> listOf("Overview")
                "recenter" -> listOf("Re-centre", "Re-center", "Recenter", "Resume")
                else -> return ToolResult.Error("action must be stop, overview, recenter or show.")
            }
            if (!d.driver.accessibilityOn()) return ToolResult.Error("Pressing buttons in Google Maps needs the Accessibility service, which is off.")
            if (d.driver.texts() == null) { MapsLauncher.bringToFront(d.app); delay(1_500) }
            val tapped = MapsSavedFlow(d.driver).tapFirst(labels) ?: return ToolResult.Error("I could not find a ${labels.first()} button on the Google Maps screen. Is navigation running?")
            if (action != "stop") return ToolResult.Ok("Pressed $tapped.")
            delay(1_500)
            val still = d.driver.texts()?.let { MapsScreen.parse(it) }?.navigating == true
            return if (still) ToolResult.Ok("Pressed $tapped, but Google Maps still shows navigation; it may be asking to confirm.")
            else { d.session.end(); ToolResult.Ok("Navigation ended.") }
        }
    }

    // ---------------------------------------------------------------- saved places

    class Places(private val d: Deps) : Tool {
        override val spec = ToolSpec(
            "maps_places",
            "Jarvis's own list of named places (home, office, a friend's house). list shows them; save adds or replaces one, from an address, from the phone's current location, or from candidate N of the last search; remove deletes one. Saved places work with maps_navigate and appear as shortcuts when the Jarvis icon is long-pressed.",
            listOf(
                ToolParam("action", required = true, enumValues = listOf("list", "save", "remove"), description = "list, save or remove."),
                ToolParam("name", description = "The name to use, e.g. home."),
                ToolParam("address", description = "Address or place text to save."),
                ToolParam("use_current_location", "boolean", "Save where the phone is right now."),
                ToolParam("candidate", "integer", "Save result number N of the last maps_find_nearby.")
            ), Risk.WRITE, GROUP
        )

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val name = args.str("name")?.trim().orEmpty()
            when (args.str("action")?.trim()?.lowercase()) {
                "list" -> {
                    val all = d.book.all()
                    return ToolResult.Ok(if (all.isEmpty()) "No places saved yet." else all.joinToString("\n") { "- ${it.name}: ${it.address ?: if (it.lat != null) "saved position" else "(name only)"}" })
                }
                "remove" -> {
                    if (name.isEmpty()) return ToolResult.Error("Which place should I remove?")
                    val ok = d.book.remove(name)
                    if (ok) MapsShortcuts.publish(d.app, d.book.all())
                    return if (ok) ToolResult.Ok("Removed $name.") else ToolResult.Error("No saved place matches \"$name\".")
                }
                "save" -> {
                    if (name.isEmpty()) return ToolResult.Error("What should I call this place?")
                    val place: SavedPlace = when {
                        args.int("candidate") != null -> {
                            val n = args.int("candidate")!!
                            val c = d.session.candidates.getOrNull(n - 1) ?: return ToolResult.Error("There is no result number $n.")
                            SavedPlace(name, c.address, c.lat, c.lon)
                        }
                        args.bool("use_current_location") == true -> when (val fix = MapsLocation.read(d.app)) {
                            is MapsLocation.Result.Fix -> SavedPlace(name, args.str("address")?.trim()?.takeIf { it.isNotEmpty() }, fix.lat, fix.lon)
                            is MapsLocation.Result.Unavailable -> return ToolResult.Error(fix.reason)
                        }
                        !args.str("address").isNullOrBlank() -> SavedPlace(name, args.str("address")!!.trim())
                        else -> return ToolResult.Error("Give an address, use_current_location, or a candidate number.")
                    }
                    d.book.save(place)
                    MapsShortcuts.publish(d.app, d.book.all())
                    return ToolResult.Ok("Saved $name. Say \"navigate to $name\" any time.")
                }
                else -> return ToolResult.Error("action must be list, save or remove.")
            }
        }
    }

    class OpenSaved(private val d: Deps) : Tool {
        override val spec = ToolSpec(
            "maps_open_saved",
            "Start navigation to a place in Google Maps' own Starred places, Favorites (liked), Want to go or any custom list, by driving the Google Maps screen. Needs the Accessibility service. Slower and less certain than maps_navigate with a saved place, and it says plainly where it got stuck.",
            listOf(
                ToolParam("place", required = true, description = "Name of the place as it appears in the list."),
                ToolParam("list", description = "starred (default), favorites or liked, want to go, or a custom list name.")
            ), Risk.WRITE, GROUP
        )

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val place = args.str("place")?.trim().orEmpty()
            if (place.isEmpty()) return ToolResult.Error("Which saved place?")
            if (!d.driver.accessibilityOn()) return ToolResult.Error("Opening Google Maps' saved lists needs the Accessibility service, which is off. I can still navigate to a place saved with maps_places.")
            return when (val r = MapsSavedFlow(d.driver).navigateToSaved(args.str("list"), place)) {
                is MapsFlowResult.Started -> {
                    d.session.start(MapsTarget(place, place), emptyList(), MapsLinks.Mode.DRIVING, emptySet())
                    ToolResult.Ok("Navigating to $place from your ${MapsSavedFlow.listLabel(args.str("list"))}. ${r.snapshot.describe()}")
                }
                is MapsFlowResult.Done -> ToolResult.Ok(r.note)
                is MapsFlowResult.Stuck -> ToolResult.Error("I stopped before starting: could not ${r.step} in Google Maps. " + (if (r.seen.isNotBlank()) "The screen showed: ${r.seen}. " else "") + "Navigation was not started.")
                MapsFlowResult.ScreenUnreadable -> ToolResult.Error("I could not read the Google Maps screen (is Maps in front?). Navigation was not started.")
                MapsFlowResult.Cancelled -> ToolResult.Error("Cancelled.")
            }
        }
    }

    class PinShortcut(private val d: Deps) : Tool {
        override val spec = ToolSpec(
            "maps_pin_shortcut",
            "Put a one-tap \"navigate to <place>\" icon on the phone's home screen for a saved place. Android asks the user to confirm.",
            listOf(ToolParam("name", required = true, description = "A place saved with maps_places.")), Risk.WRITE, GROUP
        )

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val p = d.book.find(args.str("name").orEmpty()) ?: return ToolResult.Error("No saved place matches that. Save it first with maps_places.")
            return if (MapsShortcuts.pin(d.app, p)) ToolResult.Ok("Android is asking you to confirm adding a \"Go: ${p.name}\" icon to the home screen.")
            else ToolResult.Error("This launcher does not allow adding home-screen shortcuts. Long-pressing the Jarvis icon still shows your saved places.")
        }
    }

    class LocationStatus(private val d: Deps) : Tool {
        override val spec = ToolSpec("maps_location_status", "Check whether the phone's location is usable for finding nearby places, and how accurate it is.", emptyList(), Risk.READ, GROUP)

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = when (val r = MapsLocation.read(d.app)) {
            is MapsLocation.Result.Fix -> ToolResult.Ok("Location works (accurate to about ${r.accuracyM} m).")
            is MapsLocation.Result.Unavailable -> ToolResult.Error(r.reason)
        }
    }
}
