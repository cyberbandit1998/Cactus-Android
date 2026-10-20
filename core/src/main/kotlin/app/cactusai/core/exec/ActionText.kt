package app.cactusai.core.exec

import app.cactusai.core.tools.AssistantTools
import app.cactusai.core.tools.ValidatedCall
import app.cactusai.core.web.SearchSite

/** Short present-tense wording for what a step is doing, for the live status line and the cancel window. */
object ActionText {
    fun describe(call: ValidatedCall): String {
        val a = call.args
        return when (call.name) {
            AssistantTools.OPEN_APP -> "Opening ${a.string("app_name").orEmpty()}"
            AssistantTools.SEARCH_WEB -> "Searching ${SearchSite.fromKey(a.string("site")).label} for “${a.string("query").orEmpty()}”"
            AssistantTools.OPEN_SETTINGS -> when (val page = a.string("page")) {
                null, "main" -> "Opening settings"
                else -> "Opening ${pageName(page)} settings"
            }
            AssistantTools.SET_TIMER -> "Setting a timer for ${duration(a.int("hours") ?: 0, a.int("minutes") ?: 0, a.int("seconds") ?: 0)}"
            AssistantTools.SET_ALARM -> "Setting an alarm for ${clock(a.int("hour") ?: 0, a.int("minute") ?: 0)}"
            AssistantTools.CALL_CONTACT -> "Opening the dialer for ${a.string("name").orEmpty()}"
            AssistantTools.DIAL_NUMBER -> "Opening the dialer"
            AssistantTools.SEND_TEXT -> "Writing a text to ${a.string("recipient").orEmpty()}"
            AssistantTools.CHECK_BATTERY -> "Checking the battery"
            AssistantTools.CHANGE_VOLUME -> when (a.string("action")) {
                "up" -> "Turning the volume up"
                "down" -> "Turning the volume down"
                "mute" -> "Muting the volume"
                "unmute" -> "Unmuting the volume"
                else -> "Setting the volume to ${a.int("level") ?: 0}%"
            }
            AssistantTools.SEARCH_PHONE -> "Looking through your ${a.string("kind").orEmpty()}"
            AssistantTools.SCROLL -> "Scrolling ${a.string("direction").orEmpty()}"
            AssistantTools.NAVIGATE -> when (a.string("destination")) {
                "back" -> "Going back"
                "home" -> "Going to the home screen"
                "recents" -> "Opening recent apps"
                else -> "Opening the notification shade"
            }
            AssistantTools.TAP_TEXT -> "Tapping “${a.string("text").orEmpty()}”"
            AssistantTools.TAP_RESULT -> "Tapping result ${a.int("position") ?: 1}"
            AssistantTools.TYPE_TEXT -> "Typing “${a.string("text").orEmpty().take(40)}”"
            AssistantTools.FIND_ON_SCREEN -> "Looking for “${a.string("text").orEmpty()}” on the screen"
            AssistantTools.GET_TIME -> a.string("place")?.let { "Checking the time in $it" } ?: "Checking the time"
            AssistantTools.GET_DATE -> "Checking the date"
            AssistantTools.DEVICE_INFO -> "Checking ${topicName(a.string("topic"))}"
            AssistantTools.CALCULATE -> "Working it out"
            AssistantTools.SPORTS_INFO -> a.string("team")?.let { "Looking up the $it game online" } ?: "Looking up the game online"
            AssistantTools.WEATHER_INFO -> "Checking the weather online"
            AssistantTools.ANSWER_QUESTION -> "Searching the web for an answer"
            else -> call.name
        }
    }

    /** "Opening Spotify" for one step; "Opening YouTube, then searching YouTube for “x”" for a sequence. */
    fun describeAll(steps: List<ValidatedCall>): String =
        steps.mapIndexed { i, step -> describe(step).let { if (i == 0) it else it.replaceFirstChar { c -> c.lowercase() } } }.joinToString(", then ")

    private fun pageName(page: String): String = when (page) {
        "wifi" -> "Wi-Fi"
        "bluetooth" -> "Bluetooth"
        "nfc" -> "NFC"
        "airplane_mode" -> "airplane mode"
        "date_time" -> "date and time"
        else -> page.replace('_', ' ')
    }

    private fun topicName(topic: String?): String = when (topic) {
        "charging" -> "whether the phone is charging"
        "android_version" -> "the Android version"
        "storage" -> "the storage"
        "model" -> "the phone model"
        else -> "the phone"
    }

    private fun duration(h: Int, m: Int, s: Int): String {
        val parts = buildList {
            if (h > 0) add("$h hour${if (h == 1) "" else "s"}")
            if (m > 0) add("$m minute${if (m == 1) "" else "s"}")
            if (s > 0) add("$s second${if (s == 1) "" else "s"}")
        }
        return parts.joinToString(" ").ifEmpty { "0 seconds" }
    }

    private fun clock(hour: Int, minute: Int): String {
        val h12 = when (val h = hour % 12) { 0 -> 12 else -> h }
        return "$h12:${minute.toString().padStart(2, '0')} ${if (hour < 12) "AM" else "PM"}"
    }
}
