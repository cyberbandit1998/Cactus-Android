package app.cactusai.core.tools

import app.cactusai.core.phone.PhoneNumbers
import app.cactusai.core.web.SearchSite

/**
 * The complete, fixed list of things the assistant can be asked to do.
 *
 * Design notes (from the Needle 3 tool-design guidance): one tool per action, plain names without a
 * shared prefix, closed sets as enums, "takes no arguments" stated in the description, and unambiguous
 * `triggers` only where the request wording cannot mean anything else.
 */
object AssistantTools {
    const val OPEN_APP = "open_app"
    const val SEARCH_WEB = "search_web"
    const val OPEN_SETTINGS = "open_settings"
    const val SET_TIMER = "set_timer"
    const val SET_ALARM = "set_alarm"
    const val CALL_CONTACT = "call_contact"
    const val DIAL_NUMBER = "dial_number"
    const val SEND_TEXT = "send_text"
    const val CHECK_BATTERY = "check_battery"
    const val CHANGE_VOLUME = "change_volume"
    const val SEARCH_PHONE = "search_phone"
    const val SCROLL = "scroll"
    const val NAVIGATE = "navigate"
    const val TAP_TEXT = "tap_text"
    const val TAP_RESULT = "tap_result"
    const val TYPE_TEXT = "type_text"
    const val FIND_ON_SCREEN = "find_on_screen"

    val SETTINGS_PAGES = listOf(
        "main", "wifi", "bluetooth", "display", "battery", "sound", "location", "apps", "storage",
        "airplane_mode", "nfc", "date_time", "accessibility",
    )

    val registry: ToolRegistry = ToolRegistry(
        listOf(
            ToolSpec(
                name = OPEN_APP,
                description = "Open or launch an installed app by name, such as Spotify, YouTube, Camera, Clock or Messages.",
                params = listOf(ParamSpec("app_name", ParamType.STRING, "The app name as the user said it.", required = true, maxLength = 60)),
                group = ToolGroup.PHONE,
                sensitivity = Sensitivity.SAFE,
            ),
            ToolSpec(
                name = SEARCH_WEB,
                description = "Search the web, or a site such as YouTube, for something. Searches Google when no site is named.",
                params = listOf(
                    ParamSpec("query", ParamType.STRING, "The words to search for.", required = true, maxLength = 200),
                    ParamSpec("site", ParamType.STRING, "Where to search. Omit for Google.", enumValues = SearchSite.entries.map { it.key }),
                ),
                group = ToolGroup.PHONE,
                sensitivity = Sensitivity.SAFE,
            ),
            ToolSpec(
                name = OPEN_SETTINGS,
                description = "Open an Android settings screen such as Wi-Fi, Bluetooth, display or battery settings.",
                params = listOf(ParamSpec("page", ParamType.STRING, "Which settings screen. Use main for plain settings.", required = true, enumValues = SETTINGS_PAGES)),
                group = ToolGroup.PHONE,
                sensitivity = Sensitivity.SAFE,
            ),
            ToolSpec(
                name = SET_TIMER,
                description = "Start a countdown timer. Give the hours, minutes and/or seconds the user said.",
                params = listOf(
                    ParamSpec("hours", ParamType.INTEGER, "Whole hours.", min = 0, max = 24),
                    ParamSpec("minutes", ParamType.INTEGER, "Whole minutes.", min = 0, max = 1440),
                    ParamSpec("seconds", ParamType.INTEGER, "Whole seconds.", min = 0, max = 86400),
                ),
                group = ToolGroup.PHONE,
                sensitivity = Sensitivity.SAFE,
                triggers = listOf("\\btimers?\\b"),
                crossCheck = { a ->
                    val total = (a.long("hours") ?: 0) * 3600 + (a.long("minutes") ?: 0) * 60 + (a.long("seconds") ?: 0)
                    when {
                        total <= 0 -> "a timer needs a length of time"
                        total > 86_400 -> "a timer can be at most 24 hours"
                        else -> null
                    }
                },
            ),
            ToolSpec(
                name = SET_ALARM,
                description = "Create an alarm at a clock time. The hour is 24-hour time, so 7 pm is hour 19.",
                params = listOf(
                    ParamSpec("hour", ParamType.INTEGER, "Hour of day, 0 to 23.", required = true, min = 0, max = 23),
                    ParamSpec("minute", ParamType.INTEGER, "Minute, 0 to 59.", min = 0, max = 59),
                    ParamSpec("label", ParamType.STRING, "Optional alarm name.", maxLength = 60),
                ),
                group = ToolGroup.PHONE,
                sensitivity = Sensitivity.SAFE,
                triggers = listOf("\\balarms?\\b"),
            ),
            ToolSpec(
                name = CALL_CONTACT,
                description = "Call a person from the user's contacts by name. Opens the dialer so the user presses call.",
                params = listOf(ParamSpec("name", ParamType.STRING, "The contact name as the user said it.", required = true, maxLength = 60)),
                group = ToolGroup.PHONE,
                sensitivity = Sensitivity.CONFIRM_BY_DEFAULT,
            ),
            ToolSpec(
                name = DIAL_NUMBER,
                description = "Open the phone dialer, with a phone number filled in when one is given. Does not place the call.",
                params = listOf(ParamSpec("number", ParamType.STRING, "The phone number as digits.", maxLength = 24)),
                group = ToolGroup.PHONE,
                sensitivity = Sensitivity.CONFIRM_BY_DEFAULT,
                triggers = listOf("\\bdial\\b"),
                crossCheck = { a ->
                    a.string("number")?.let { if (PhoneNumbers.sanitize(it) == null) "that is not a phone number I can dial" else null }
                },
            ),
            ToolSpec(
                name = SEND_TEXT,
                description = "Write a text message to a contact name or phone number. Opens the messaging app, never sends it.",
                params = listOf(
                    ParamSpec("recipient", ParamType.STRING, "A contact name or phone number.", required = true, maxLength = 60),
                    ParamSpec("message", ParamType.STRING, "The message text copied word for word.", required = true, maxLength = 500),
                ),
                group = ToolGroup.PHONE,
                sensitivity = Sensitivity.CONFIRM_BY_DEFAULT,
            ),
            ToolSpec(
                name = CHECK_BATTERY,
                description = "Report the phone's current battery percentage. Takes no arguments.",
                params = emptyList(),
                group = ToolGroup.PHONE,
                sensitivity = Sensitivity.SAFE,
                triggers = listOf("\\bbattery\\b"),
            ),
            ToolSpec(
                name = CHANGE_VOLUME,
                description = "Change the media volume: up, down, mute, unmute, or set to a percentage.",
                params = listOf(
                    ParamSpec("action", ParamType.STRING, "What to do with the volume.", required = true, enumValues = listOf("up", "down", "mute", "unmute", "set")),
                    ParamSpec("level", ParamType.INTEGER, "Percent from 0 to 100, only with action set.", min = 0, max = 100),
                ),
                group = ToolGroup.PHONE,
                sensitivity = Sensitivity.SAFE,
                triggers = listOf("\\b(volume|mute|unmute)\\b"),
                crossCheck = { a ->
                    when {
                        a.string("action") == "set" && a.long("level") == null -> "set volume needs a percentage"
                        a.string("action") != "set" && a.long("level") != null -> "a percentage only goes with set"
                        else -> null
                    }
                },
            ),
            ToolSpec(
                name = SEARCH_PHONE,
                description = "Find installed apps, contacts or files the user shared with this app. Omit the query to list all apps.",
                params = listOf(
                    ParamSpec("kind", ParamType.STRING, "What to look for.", required = true, enumValues = listOf("apps", "contacts", "files")),
                    ParamSpec("query", ParamType.STRING, "The name to look for.", maxLength = 80),
                ),
                group = ToolGroup.PHONE,
                sensitivity = Sensitivity.SAFE,
                crossCheck = { a ->
                    if (a.string("kind") != "apps" && a.string("query") == null) "searching ${a.string("kind")} needs something to look for" else null
                },
            ),
            ToolSpec(
                name = SCROLL,
                description = "Scroll the screen that is showing, up or down.",
                params = listOf(ParamSpec("direction", ParamType.STRING, "Which way to scroll.", required = true, enumValues = listOf("up", "down"))),
                group = ToolGroup.SCREEN,
                sensitivity = Sensitivity.SAFE,
            ),
            ToolSpec(
                name = NAVIGATE,
                description = "Press a system navigation button: back, home, recent apps or the notification shade.",
                params = listOf(ParamSpec("destination", ParamType.STRING, "Which button.", required = true, enumValues = listOf("back", "home", "recents", "notifications"))),
                group = ToolGroup.SCREEN,
                sensitivity = Sensitivity.SAFE,
            ),
            ToolSpec(
                name = TAP_TEXT,
                description = "Tap a visible button, link or item on the screen by its label, such as search.",
                params = listOf(ParamSpec("text", ParamType.STRING, "The label to tap, as the user said it.", required = true, maxLength = 60)),
                group = ToolGroup.SCREEN,
                sensitivity = Sensitivity.SAFE,
            ),
            ToolSpec(
                name = TAP_RESULT,
                description = "Tap the Nth item in the list or search results on screen, counting from 1 at the top.",
                params = listOf(ParamSpec("position", ParamType.INTEGER, "Which item, 1 for the first.", required = true, min = 1, max = 10)),
                group = ToolGroup.SCREEN,
                sensitivity = Sensitivity.SAFE,
            ),
            ToolSpec(
                name = TYPE_TEXT,
                description = "Type text into the text field that is focused on screen.",
                params = listOf(ParamSpec("text", ParamType.STRING, "The text to type, copied word for word.", required = true, maxLength = 300)),
                group = ToolGroup.SCREEN,
                sensitivity = Sensitivity.SAFE,
            ),
            ToolSpec(
                name = FIND_ON_SCREEN,
                description = "Check whether some text is visible on the screen that is showing.",
                params = listOf(ParamSpec("text", ParamType.STRING, "The text to look for.", required = true, maxLength = 60)),
                group = ToolGroup.SCREEN,
                sensitivity = Sensitivity.SAFE,
            ),
        ),
    )

    /** Facts passed to `needle_init`, never instructions (Needle 3 rule). */
    fun systemFacts(date: String, locale: String): String = "date: $date; locale: $locale; device: phone"
}
