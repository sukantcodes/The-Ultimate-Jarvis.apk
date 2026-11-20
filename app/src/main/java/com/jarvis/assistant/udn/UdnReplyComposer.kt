package com.jarvis.assistant.udn

import org.json.JSONObject

enum class UdnLang { EN, HI, HINGLISH }

/**
 * Builds the spoken/written reply from what a device ACTUALLY reported. A reply is only worded as success when the
 * command record is `succeeded`; every other terminal state is described as what it was. Pure Kotlin, unit-tested.
 */
object UdnReplyComposer {

    fun detectLanguage(text: String): UdnLang = when {
        text.any { it in 'ऀ'..'ॿ' } -> UdnLang.HI
        Regex("\\b(mere|meri|mera|kholo|khol|karo|kar do|batao|dikhao|hai|kya|band|chalao|doosra|dusra|sabhi|nahi|haan|kitni|par)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> UdnLang.HINGLISH
        else -> UdnLang.EN
    }

    private fun <T> pick(lang: UdnLang, en: T, hi: T, hing: T): T = when (lang) { UdnLang.EN -> en; UdnLang.HI -> hi; UdnLang.HINGLISH -> hing }

    fun notJoined(l: UdnLang) = pick(l, "This device hasn't joined your JARVIS network yet. Open Device Network to enroll it.",
        "यह डिवाइस अभी आपके JARVIS नेटवर्क में नहीं जुड़ा है। डिवाइस नेटवर्क खोलकर इसे जोड़ें।",
        "Yeh device abhi aapke JARVIS network mein nahi juda hai. Device Network kholkar ise enroll karein.")

    fun notAuthorized(l: UdnLang) = pick(l, "This device is still waiting for a parent device to approve it.",
        "यह डिवाइस अभी पैरेंट डिवाइस की मंज़ूरी का इंतज़ार कर रहा है।", "Yeh device abhi parent device ke approval ka wait kar raha hai.")

    fun parentOnly(l: UdnLang) = pick(l, "Only a parent device can control other devices.",
        "सिर्फ़ पैरेंट डिवाइस दूसरे डिवाइस को कंट्रोल कर सकता है।", "Sirf parent device hi doosre devices ko control kar sakta hai.")

    fun notFound(l: UdnLang, phrase: String) = pick(l, "I couldn't find a device matching \"$phrase\" on your network.",
        "मुझे आपके नेटवर्क पर \"$phrase\" नाम का कोई डिवाइस नहीं मिला।", "Mujhe aapke network par \"$phrase\" naam ka koi device nahi mila.")

    fun which(l: UdnLang, names: List<String>) = pick(l, "Which device do you mean: ${names.joinToString(", ", limit = 5)}?",
        "आपका मतलब कौन सा डिवाइस है: ${names.joinToString(", ")}?", "Aapka matlab kaun sa device hai: ${names.joinToString(", ")}?")

    fun confirm(l: UdnLang, action: String, names: List<String>) = pick(l, "Do you want me to $action on ${names.joinToString(" and ")}? Say yes to confirm.",
        "क्या मैं ${names.joinToString(" और ")} पर $action करूँ? पुष्टि के लिए हाँ बोलें।", "Kya main ${names.joinToString(" aur ")} par $action karun? Confirm karne ke liye haan bolo.")

    fun cancelled(l: UdnLang) = pick(l, "Okay, cancelled.", "ठीक है, रद्द कर दिया।", "Theek hai, cancel kar diya.")

    fun noDevices(l: UdnLang) = pick(l, "No devices are registered on your network yet.", "आपके नेटवर्क पर अभी कोई डिवाइस रजिस्टर नहीं है।", "Aapke network par abhi koi device registered nahi hai.")

    fun listDevices(l: UdnLang, devices: List<UdnDevice>, selfId: String?): String {
        val live = devices.filter { it.status != "revoked" }
        if (live.isEmpty()) return noDevices(l)
        val online = live.count { it.online }
        val parts = live.joinToString("; ") { d ->
            val role = if (d.isParent) "parent ${d.parentSlot}" else if (d.isAuthorized) "child" else "waiting for approval"
            val state = if (d.id == selfId) "this device" else if (d.online) "online" else "offline"
            "${d.name} ($role, $state)"
        }
        return pick(l, "You have ${live.size} device${if (live.size == 1) "" else "s"}, $online online: $parts.",
            "आपके ${live.size} डिवाइस हैं, ${online} ऑनलाइन: $parts।", "Aapke ${live.size} devices hain, $online online: $parts.")
    }

    fun ago(ms: Long?, now: Long = System.currentTimeMillis()): String {
        if (ms == null) return "never"
        val s = ((now - ms) / 1000).coerceAtLeast(0)
        return when {
            s < 90 -> "a moment ago"; s < 3600 -> "${s / 60} minutes ago"; s < 86_400 -> "${s / 3600} hours ago"; else -> "${s / 86_400} days ago"
        }
    }

    fun online(l: UdnLang, d: UdnDevice, now: Long = System.currentTimeMillis()): String = when {
        !d.isAuthorized -> pick(l, "${d.name} hasn't been authorized yet.", "${d.name} अभी अधिकृत नहीं है।", "${d.name} abhi authorized nahi hai.")
        d.online -> pick(l, "${d.name} is online.", "${d.name} ऑनलाइन है।", "${d.name} online hai.")
        else -> pick(l, "${d.name} is offline. It was last seen ${ago(d.lastSeenMs, now)}.", "${d.name} ऑफ़लाइन है। आखिरी बार ${ago(d.lastSeenMs, now)} दिखा था।", "${d.name} offline hai. Last seen ${ago(d.lastSeenMs, now)}.")
    }

    fun offline(l: UdnLang, d: UdnDevice, now: Long = System.currentTimeMillis()): String {
        val base = online(l, d, now)
        val battery = d.batteryPct?.let { " " + pick(l, "Its last reported battery was $it%.", "इसकी आखिरी बैटरी $it% थी।", "Iski last reported battery $it% thi.") } ?: ""
        return base + battery
    }

    fun unavailable(l: UdnLang, d: UdnDevice, reason: String?) =
        pick(l, "${d.name} can't do that: ${reason ?: "it isn't supported there"}.", "${d.name} यह नहीं कर सकता: ${reason ?: "यह वहाँ सपोर्टेड नहीं है"}।", "${d.name} yeh nahi kar sakta: ${reason ?: "yeh wahan supported nahi hai"}.")

    fun failure(l: UdnLang, d: UdnDevice, code: String?, message: String?): String {
        val why = UdnErrors.describe(code ?: "error", message)
        return pick(l, "That didn't work on ${d.name}: $why.", "${d.name} पर यह नहीं हो पाया: $why।", "${d.name} par yeh nahi ho paya: $why.")
    }

    /** Wording for a finished command. Only `succeeded` produces a success sentence. */
    fun outcome(l: UdnLang, d: UdnDevice, capability: String, rec: UdnCommandRecord): String {
        if (rec.status != "succeeded") return failure(l, d, rec.errorCode ?: rec.status, rec.errorMessage)
        val r = rec.result ?: JSONObject()
        val n = d.name
        return when (capability) {
            UdnCapabilities.DEVICE_BATTERY -> {
                val pct = r.optInt("percent", -1)
                if (pct < 0) failure(l, d, "battery_unavailable", null)
                else if (r.optBoolean("charging")) pick(l, "$n is at $pct% and charging.", "$n की बैटरी $pct% है और चार्ज हो रही है।", "$n ki battery $pct% hai aur charging ho rahi hai.")
                else pick(l, "$n is at $pct%.", "$n की बैटरी $pct% है।", "$n ki battery $pct% hai.")
            }
            UdnCapabilities.DEVICE_CONNECTIVITY -> {
                val type = when (r.optString("type")) { "wifi" -> "Wi-Fi"; "cellular" -> "mobile data"; "ethernet" -> "Ethernet"; else -> "an unknown network" }
                if (!r.optBoolean("connected")) pick(l, "$n isn't connected to any network.", "$n किसी नेटवर्क से जुड़ा नहीं है।", "$n kisi network se connected nahi hai.")
                else pick(l, "$n is connected over $type${if (r.optBoolean("internet_validated")) "" else ", but Android can't confirm internet access"}.",
                    "$n $type से जुड़ा है।", "$n $type se connected hai.")
            }
            UdnCapabilities.DEVICE_INFO -> pick(l,
                "$n is a ${r.optString("manufacturer")} ${r.optString("model")} running Android ${r.optString("android")}, JARVIS ${r.optString("app_version")}.",
                "$n एक ${r.optString("manufacturer")} ${r.optString("model")} है, Android ${r.optString("android")}।",
                "$n ek ${r.optString("manufacturer")} ${r.optString("model")} hai, Android ${r.optString("android")}.")
            UdnCapabilities.DEVICE_LOCK -> pick(l, "$n is locked; its screen is off.", "$n लॉक हो गया, स्क्रीन बंद है।", "$n lock ho gaya, screen band hai.")
            UdnCapabilities.MODULE_LIST -> {
                val arr = r.optJSONArray("modules")
                val labels = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it)?.optString("label") }
                pick(l, "$n has these JARVIS modules: ${labels.joinToString(", ")}. Tell me which one to open.",
                    "$n पर ये JARVIS मॉड्यूल हैं: ${labels.joinToString(", ")}। बताइए कौन सा खोलूँ।", "$n par ye JARVIS modules hain: ${labels.joinToString(", ")}. Batao kaun sa kholun.")
            }
            UdnCapabilities.MODULE_OPEN, UdnCapabilities.APP_LAUNCH, UdnCapabilities.CAMERA_OPEN -> {
                val what = r.optString("label").ifEmpty { r.optString("app") }.ifEmpty { "Camera Scan" }
                if (r.optBoolean("launched")) pick(l, "Opened $what on $n.", "$n पर $what खुल गया।", "$n par $what khul gaya.")
                else pick(l, "I couldn't open $what on $n directly because Android blocks background launches. I left a tap-to-open notification there.",
                    "Android बैकग्राउंड से खोलने नहीं देता, इसलिए $n पर $what सीधे नहीं खुला। वहाँ टैप करने वाला नोटिफिकेशन छोड़ दिया है।",
                    "Android background se open nahi karne deta, isliye $n par $what seedha nahi khula. Wahan tap-to-open notification chhod diya hai.")
            }
            UdnCapabilities.NOTIFY_SHOW -> pick(l, "Notification shown on $n.", "$n पर नोटिफिकेशन दिख गया।", "$n par notification dikh gaya.")
            UdnCapabilities.SETTINGS_VOLUME -> pick(l, "Volume on $n is now ${r.optInt("percent")}%.", "$n पर वॉल्यूम अब ${r.optInt("percent")}% है।", "$n par volume ab ${r.optInt("percent")}% hai.")
            UdnCapabilities.SETTINGS_BRIGHTNESS -> pick(l, "Brightness on $n is now ${r.optInt("percent")}%.", "$n पर ब्राइटनेस अब ${r.optInt("percent")}% है।", "$n par brightness ab ${r.optInt("percent")}% hai.")
            UdnCapabilities.SETTINGS_FLASHLIGHT -> pick(l, "Flashlight on $n is ${if (r.optBoolean("on")) "on" else "off"}.", "$n की टॉर्च ${if (r.optBoolean("on")) "चालू" else "बंद"} है।", "$n ki torch ${if (r.optBoolean("on")) "on" else "off"} hai.")
            UdnCapabilities.LISTENING_STOP -> pick(l, "Background listening is stopped on $n.", "$n पर बैकग्राउंड लिसनिंग बंद हो गई।", "$n par background listening band ho gayi.")
            UdnCapabilities.LOCATION_GET -> pick(l,
                "$n is at ${"%.5f".format(r.optDouble("lat"))}, ${"%.5f".format(r.optDouble("lng"))}, accurate to about ${r.optInt("accuracy_m")} meters, ${r.optLong("age_s")} seconds old.",
                "$n यहाँ है: ${"%.5f".format(r.optDouble("lat"))}, ${"%.5f".format(r.optDouble("lng"))}।", "$n yahan hai: ${"%.5f".format(r.optDouble("lat"))}, ${"%.5f".format(r.optDouble("lng"))}.")
            else -> pick(l, "Done on $n.", "$n पर हो गया।", "$n par ho gaya.")
        }
    }

    /** Result of a parent-to-device message. "Delivered" only when the device itself reported showing it. */
    fun messageResult(l: UdnLang, d: UdnDevice, status: String?, errorCode: String? = null): String = when (status) {
        "succeeded" -> pick(l, "Message delivered to ${d.name}.", "${d.name} को संदेश पहुँच गया।", "${d.name} ko message pahunch gaya.")
        "failed", "rejected", "cancelled" -> pick(l,
            "I saved the message, but ${d.name} couldn't show it: ${UdnErrors.describe(errorCode ?: "error", null)}.",
            "संदेश सेव हो गया, लेकिन ${d.name} उसे दिखा नहीं पाया।", "Message save ho gaya, lekin ${d.name} use dikha nahi paya.")
        else -> pick(l,
            "Message saved for ${d.name}. It hasn't confirmed it yet; it will arrive when the device is connected.",
            "${d.name} के लिए संदेश सेव हो गया। अभी पुष्टि नहीं हुई; डिवाइस जुड़ते ही पहुँच जाएगा।",
            "${d.name} ke liye message save ho gaya. Abhi confirm nahi hua; device connect hote hi pahunch jayega.")
    }

    /** Short action phrase used in confirmation questions. */
    fun actionPhrase(capability: String): String = when (capability) {
        UdnCapabilities.DEVICE_LOCK -> "lock the screen"
        UdnCapabilities.SETTINGS_VOLUME -> "change the volume"
        UdnCapabilities.SETTINGS_BRIGHTNESS -> "change the brightness"
        UdnCapabilities.SETTINGS_FLASHLIGHT -> "switch the flashlight"
        UdnCapabilities.LOCATION_GET -> "get the location"
        UdnCapabilities.CAMERA_OPEN -> "open the camera"
        UdnCapabilities.CHAT_ASK -> "ask JARVIS a question"
        UdnCapabilities.ROUTINE_RUN -> "run the routine"
        "disconnect" -> "remove it from your network"
        else -> UdnCapabilities.label(capability)
    }
}
