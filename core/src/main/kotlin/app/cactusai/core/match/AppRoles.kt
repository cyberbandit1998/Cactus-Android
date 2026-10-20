package app.cactusai.core.match

/**
 * Generic things people ask for by purpose ("my camera", "my messages") rather than by app name.
 * The Android layer maps each role to the system's own default for it, so a phone with two camera
 * apps opens the user's default instead of asking which one.
 */
enum class AppRole { CAMERA, MESSAGING, MAPS, CLOCK, CALCULATOR, BROWSER, CONTACTS, EMAIL, GALLERY, MUSIC, CALENDAR, PHONE, SETTINGS }

object AppRoles {
    private val names: Map<String, AppRole> = buildMap {
        fun add(role: AppRole, vararg words: String) = words.forEach { put(it, role) }
        add(AppRole.CAMERA, "camera", "phone camera", "cam")
        add(AppRole.MESSAGING, "messages", "messaging", "texts", "text messages", "sms", "text")
        add(AppRole.MAPS, "maps", "map", "navigation")
        add(AppRole.CLOCK, "clock", "alarm clock", "alarms", "timer", "stopwatch")
        add(AppRole.CALCULATOR, "calculator", "calc")
        add(AppRole.BROWSER, "browser", "web browser", "internet")
        add(AppRole.CONTACTS, "contacts", "address book", "phone book")
        add(AppRole.EMAIL, "email", "e mail", "mail")
        add(AppRole.GALLERY, "gallery", "photo gallery", "pictures", "photos")
        add(AppRole.MUSIC, "music", "music player")
        add(AppRole.CALENDAR, "calendar")
        add(AppRole.PHONE, "phone", "dialer", "phone app")
        add(AppRole.SETTINGS, "settings", "phone settings", "system settings")
    }

    fun roleFor(rawQuery: String): AppRole? = names[NameMatcher.cleanQuery(rawQuery)]
}
