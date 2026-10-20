package app.cactusai.core.info

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Facts about the phone, read by the app from official Android APIs and handed over as plain values. Null means "not available". */
data class DeviceSnapshot(
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    /** "AC", "USB", "wireless" or "dock" when charging. */
    val chargingSource: String? = null,
    val androidRelease: String? = null,
    val sdkInt: Int? = null,
    /** `Build.VERSION.SECURITY_PATCH`, as yyyy-MM-dd. */
    val securityPatch: String? = null,
    val storageFreeBytes: Long? = null,
    val storageTotalBytes: Long? = null,
    val manufacturer: String? = null,
    val model: String? = null,
)

/** Wording for device questions. No permissions are involved: every value comes from a public, permission-free Android API. */
object DeviceAnswers {
    private const val UNAVAILABLE = "I can't read that on this phone."

    fun describe(topic: String?, s: DeviceSnapshot, locale: Locale = Locale.getDefault()): InfoAnswer = when (topic) {
        "charging" -> charging(s)
        "android_version" -> androidVersion(s, locale)
        "storage" -> storage(s)
        "model" -> model(s)
        "battery" -> battery(s)
        else -> InfoAnswer("I don't know that device fact.", verified = false)
    }

    fun battery(s: DeviceSnapshot): InfoAnswer {
        val pct = s.batteryPercent ?: return InfoAnswer(UNAVAILABLE, verified = false)
        val state = when (s.charging) {
            true -> " and charging${source(s)}"
            false -> " and not charging"
            null -> ""
        }
        return InfoAnswer("Your battery is at $pct%$state.")
    }

    fun charging(s: DeviceSnapshot): InfoAnswer {
        val c = s.charging ?: return InfoAnswer(UNAVAILABLE, verified = false)
        val pct = s.batteryPercent?.let { " The battery is at $it%." }.orEmpty()
        return InfoAnswer(if (c) "Yes, your phone is charging${source(s)}.$pct" else "No, your phone is not charging.$pct")
    }

    private fun source(s: DeviceSnapshot): String = when (s.chargingSource) {
        "AC" -> " from a wall charger"
        "USB" -> " over USB"
        "wireless" -> " wirelessly"
        "dock" -> " in a dock"
        else -> ""
    }

    fun androidVersion(s: DeviceSnapshot, locale: Locale = Locale.getDefault()): InfoAnswer {
        val release = s.androidRelease ?: return InfoAnswer(UNAVAILABLE, verified = false)
        val api = s.sdkInt?.let { " (API level $it)" }.orEmpty()
        val patch = s.securityPatch?.let { raw ->
            try {
                val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(LocalDate.parse(raw))
                " The security patch level is $date."
            } catch (_: java.time.format.DateTimeParseException) {
                ""
            }
        }.orEmpty()
        return InfoAnswer("Your phone runs Android $release$api.$patch")
    }

    fun storage(s: DeviceSnapshot): InfoAnswer {
        val free = s.storageFreeBytes ?: return InfoAnswer(UNAVAILABLE, verified = false)
        val total = s.storageTotalBytes
        val left = "You have ${size(free)} of storage left"
        return if (total != null && total > 0) {
            val pct = (free * 100.0 / total).toInt()
            InfoAnswer("$left out of ${size(total)} ($pct% free).")
        } else {
            InfoAnswer("$left.")
        }
    }

    fun model(s: DeviceSnapshot): InfoAnswer {
        val model = s.model?.takeIf { it.isNotBlank() } ?: return InfoAnswer(UNAVAILABLE, verified = false)
        val maker = s.manufacturer?.takeIf { it.isNotBlank() }
        val name = if (maker == null || model.startsWith(maker, ignoreCase = true)) model else "${maker.replaceFirstChar { it.uppercase() }} $model"
        return InfoAnswer("This is a $name.")
    }

    /** Decimal units, as Android's own storage settings show them. */
    fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000_000L -> "${trim(bytes / 1e12, 2)} TB"
        bytes >= 1_000_000_000L -> "${trim(bytes / 1e9, 1)} GB"
        bytes >= 1_000_000L -> "${trim(bytes / 1e6, 0)} MB"
        else -> "${bytes / 1000} KB"
    }

    private fun trim(value: Double, decimals: Int): String {
        val text = String.format(Locale.ROOT, "%.${decimals}f", value)
        return if (decimals > 0) text.trimEnd('0').trimEnd('.') else text
    }
}
