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

    // Information tools. They only ever read; none of them starts an app, a call or a message.
    const val GET_TIME = "get_time"
    const val GET_DATE = "get_date"
    const val DEVICE_INFO = "device_info"
    const val CALCULATE = "calculate"
    const val SPORTS_INFO = "sports_info"
    const val WEATHER_INFO = "weather_info"
    const val ANSWER_QUESTION = "answer_question"

    val DEVICE_TOPICS = listOf("charging", "android_version", "storage", "model")
    val SPORTS_LEAGUES = listOf("nfl", "mlb", "nhl", "nba", "college_football", "college_basketball")
    val SPORTS_WHEN = listOf("today", "yesterday", "tomorrow", "next", "last")
    val WEATHER_WHEN = listOf("today", "tomorrow", "weekend")

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

    /**
     * Answers that need no phone action. Kept separate from [registry] so the phone-action list (and its tests) stay as
     * they were; [full] is what the app gives Needle. Descriptions are short on purpose: the whole list is read by the
     * model on every setup and has to fit its context window.
     */
    val information: List<ToolSpec> = listOf(
        ToolSpec(
            name = GET_TIME,
            description = "Tell the current time, here or in another place.",
            params = listOf(ParamSpec("place", ParamType.STRING, "A place such as California or Tokyo. Omit for here.", maxLength = 60)),
            group = ToolGroup.INFO,
            sensitivity = Sensitivity.SAFE,
            triggers = listOf("\\btime is it\\b", "\\bcurrent time\\b", "\\bwhat'?s the time\\b", "\\btime in [a-z]"),
        ),
        ToolSpec(
            name = GET_DATE,
            description = "Tell today's date, or the weekday, date and days away of a day such as tomorrow or Christmas.",
            params = listOf(ParamSpec("day", ParamType.STRING, "The day or holiday the user named. Omit for today.", maxLength = 60)),
            group = ToolGroup.INFO,
            sensitivity = Sensitivity.SAFE,
            triggers = listOf("\\bdays? (until|till|since)\\b", "\\btoday'?s date\\b"),
        ),
        ToolSpec(
            name = DEVICE_INFO,
            description = "Report a fact about this phone: charging, Android version, storage left or model.",
            params = listOf(ParamSpec("topic", ParamType.STRING, "Which fact.", required = true, enumValues = DEVICE_TOPICS)),
            group = ToolGroup.INFO,
            sensitivity = Sensitivity.SAFE,
            triggers = listOf("\\bcharging\\b", "\\bandroid version\\b", "\\b(storage|space) (left|free|remaining)\\b"),
        ),
        ToolSpec(
            name = CALCULATE,
            description = "Work out arithmetic such as 15 percent of 80 or 12 times 7.",
            params = listOf(ParamSpec("expression", ParamType.STRING, "The calculation as the user said it.", required = true, maxLength = 100)),
            group = ToolGroup.INFO,
            sensitivity = Sensitivity.SAFE,
            triggers = listOf("\\d\\s*(plus|minus|times|divided by|multiplied by|percent of|% of)\\s*\\d", "\\bsquare root\\b"),
        ),
        ToolSpec(
            name = SPORTS_INFO,
            description = "Look up a team's score, result or schedule in the NFL, MLB, NHL, NBA or college football or basketball.",
            params = listOf(
                ParamSpec("team", ParamType.STRING, "The team as the user said it, such as Tigers.", maxLength = 60),
                ParamSpec("league", ParamType.STRING, "The league, if the user named one.", enumValues = SPORTS_LEAGUES),
                ParamSpec("when", ParamType.STRING, "Which game. Omit for the current or latest one.", enumValues = SPORTS_WHEN),
            ),
            group = ToolGroup.ONLINE,
            sensitivity = Sensitivity.SAFE,
        ),
        ToolSpec(
            name = WEATHER_INFO,
            description = "Get the weather for a place from a web search.",
            params = listOf(
                ParamSpec("place", ParamType.STRING, "A city or place. Omit for here.", maxLength = 60),
                ParamSpec("when", ParamType.STRING, "Which day. Omit for today.", enumValues = WEATHER_WHEN),
            ),
            group = ToolGroup.ONLINE,
            sensitivity = Sensitivity.SAFE,
            triggers = listOf("\\bweather\\b", "\\bforecast\\b"),
        ),
        ToolSpec(
            name = ANSWER_QUESTION,
            description = "Answer a factual question such as who, what, when or how far by searching the web. Not for requests to search.",
            params = listOf(ParamSpec("question", ParamType.STRING, "The question, copied word for word.", required = true, maxLength = 300)),
            group = ToolGroup.ONLINE,
            sensitivity = Sensitivity.SAFE,
        ),
    )

    /** Everything the assistant can be asked: phone actions, screen control and information. */
    val full: ToolRegistry = ToolRegistry(registry.tools + information)
}
