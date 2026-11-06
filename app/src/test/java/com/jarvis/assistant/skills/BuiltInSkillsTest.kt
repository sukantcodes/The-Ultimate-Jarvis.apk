package com.jarvis.assistant.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Guards every skill that ships inside the app: each file must parse, keep its description under the cap
 * (so nothing is cut off in the prompt), contain no stray escape characters, and mention only tools Jarvis really has.
 */
class BuiltInSkillsTest {

    private val skillsDir: File = listOf(
        File("src/main/assets/skills"),
        File("app/src/main/assets/skills")
    ).firstOrNull { it.isDirectory } ?: error("skills folder not found from ${File(".").absolutePath}")

    private val files = skillsDir.listFiles { f -> f.name.endsWith(".md") }!!.sortedBy { it.name }

    /** Every tool the live AI session can call. Update this list when a tool is added or removed. */
    private val realTools = setOf(
        "builtin_chrome_search", "call_contact", "call_control", "clean_jarvis_cache", "click_ui_element",
        "device_status", "go_to_background", "inspect_screen", "lock_device", "media_playback_control", "open_app",
        "open_settings_page", "open_website", "press_key", "recall_activity", "run_macro", "scroll_screen",
        "search_and_play_youtube", "search_in_chrome", "search_youtube", "open_youtube_shorts", "send_whatsapp_message", "set_brightness",
        "set_flashlight", "set_volume", "show_power_menu", "social_app_action", "stop_macro", "task_completed",
        "type_text", "unlock_device", "use_skill", "whatsapp_call", "whatsapp_status_action", "youtube_accessibility_action",
        "amazon_search", "amazon_compare", "amazon_open_product", "amazon_add_to_cart", "amazon_cart",
        "amazon_prepare_checkout", "amazon_place_order",
        "blinkit_search", "blinkit_compare", "blinkit_budget_plan", "blinkit_cart", "blinkit_address",
        "blinkit_prepare_checkout", "blinkit_payment_method", "blinkit_place_order", "blinkit_order_status",
        "rapido_location_status", "rapido_find_rides", "rapido_prepare_booking", "rapido_book", "rapido_status",
        "rapido_cancel_ride",
        "clock_set_alarm", "clock_list_alarms", "clock_next_alarm", "clock_change_alarm", "clock_set_alarm_enabled",
        "clock_delete_alarm", "clock_ring_control", "clock_open_settings"
    )

    /** Words that look like tool calls but are argument names written inside a call. */
    private val argumentNames = setOf(
        "contact_name", "recipient_name", "app_number", "call_type", "app_name", "target_name", "element_id",
        // action values written in backticks, not tool names
        "skip_ad", "open_channel", "click_first", "speaker_on", "speaker_off", "record_start", "record_stop"
    )

    @Test
    fun thereAreBuiltInSkills() {
        assertTrue("expected the shipped skills", files.size >= 20)
    }

    @Test
    fun everySkillParsesAndMatchesItsFileName() {
        for (file in files) {
            val parsed = SkillParser.parse(file.readText())
            assertNotNull("${file.name} does not parse", parsed)
            assertEquals("${file.name}: name must equal the file name", file.name.removeSuffix(".md"), parsed!!.name)
        }
    }

    @Test
    fun descriptionsAreNotCutOff() {
        for (file in files) {
            val raw = Regex("(?m)^description:\\s*(.*)$").find(file.readText())?.groupValues?.get(1)?.trim()?.trim('"', '\'')
            assertNotNull("${file.name}: no description", raw)
            assertTrue("${file.name}: description is ${raw!!.length} characters, the limit is ${SkillParser.MAX_DESCRIPTION}", raw.length <= SkillParser.MAX_DESCRIPTION)
        }
    }

    @Test
    fun playbooksFitTheBodyLimitAndHaveNoStrayEscapes() {
        for (file in files) {
            val text = file.readText()
            val body = SkillParser.parse(text)!!.body
            assertTrue("${file.name}: body is longer than ${SkillParser.MAX_BODY}", text.substringAfter("\n---\n", "").trim().length <= SkillParser.MAX_BODY)
            assertTrue("${file.name}: contains an escaped backtick", !body.contains("\\`"))
            assertTrue("${file.name}: contains a tab", !body.contains('\t'))
        }
    }

    @Test
    fun onlyRealToolsAreMentioned() {
        val problems = ArrayList<String>()
        for (file in files) {
            val text = file.readText()
            val found = Regex("`([a-z][a-z0-9]*(?:_[a-z0-9]+)+)(?:\\(|`)").findAll(text).map { it.groupValues[1] }.toSet() +
                Regex("\\b((?:amazon|blinkit|rapido|clock)_[a-z_]+)").findAll(text).map { it.groupValues[1] }.toSet()
            for (name in found) {
                if (name !in realTools && name !in argumentNames) problems.add("${file.name}: unknown tool '$name'")
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }
}
