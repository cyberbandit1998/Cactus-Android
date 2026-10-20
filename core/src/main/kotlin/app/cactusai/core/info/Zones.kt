package app.cactusai.core.info

import app.cactusai.core.match.TextNorm
import java.time.DateTimeException
import java.time.ZoneId

/** A place the user named and the time zone it is in. [note] is set when the place spans several zones. */
data class ZoneResolution(val zone: ZoneId, val label: String, val note: String? = null)

/**
 * Turns "California", "Tokyo", "Eastern time" or "America/Chicago" into an IANA zone, using only the phone's own zone
 * database and the small table below. No network. A place that is not in the table is looked up among the IANA city
 * names; anything that cannot be resolved is reported as unknown rather than guessed.
 */
object ZoneResolver {
    private const val PT = "America/Los_Angeles"
    private const val MT = "America/Denver"
    private const val CT = "America/Chicago"
    private const val ET = "America/New_York"

    private fun multi(vararg names: String) = names.toSet()

    /** Places that sit in more than one zone; the main one is used and the answer says so. */
    private val splitPlaces = multi(
        "texas", "florida", "tennessee", "kentucky", "indiana", "michigan", "idaho", "oregon", "kansas", "nebraska",
        "north dakota", "south dakota", "alaska", "canada", "australia", "russia", "brazil", "mexico", "united states", "usa", "america",
    )

    private val table: Map<String, Pair<String, String>> = buildMap {
        fun add(zone: String, label: String, vararg places: String) = places.forEach { put(it, zone to label) }
        add(PT, "California", "california", "los angeles", "san francisco", "san diego", "san jose", "sacramento", "la")
        add(PT, "Washington State", "washington state", "seattle", "spokane")
        add(PT, "Oregon", "oregon", "portland")
        add(PT, "Nevada", "nevada", "las vegas", "reno")
        add(PT, "Pacific Time", "pacific", "pacific time", "pt", "pst", "pdt", "west coast")
        add(MT, "Colorado", "colorado", "denver")
        add(MT, "Utah", "utah", "salt lake city")
        add("America/Phoenix", "Arizona", "arizona", "phoenix", "tucson")
        add(MT, "New Mexico", "new mexico", "albuquerque")
        add(MT, "Montana", "montana")
        add(MT, "Wyoming", "wyoming")
        add(MT, "Idaho", "idaho", "boise")
        add(MT, "Mountain Time", "mountain", "mountain time", "mt", "mst", "mdt")
        add(CT, "Texas", "texas", "houston", "dallas", "austin", "san antonio")
        add(CT, "Illinois", "illinois", "chicago")
        add(CT, "Minnesota", "minnesota", "minneapolis")
        add(CT, "Wisconsin", "wisconsin", "milwaukee")
        add(CT, "Missouri", "missouri", "st louis", "saint louis", "kansas city")
        add(CT, "Louisiana", "louisiana", "new orleans")
        add(CT, "Tennessee", "tennessee", "nashville", "memphis")
        add(CT, "Alabama", "alabama")
        add(CT, "Oklahoma", "oklahoma", "oklahoma city")
        add(CT, "Kansas", "kansas")
        add(CT, "Nebraska", "nebraska", "omaha")
        add(CT, "Iowa", "iowa")
        add(CT, "Arkansas", "arkansas")
        add(CT, "Mississippi", "mississippi")
        add(CT, "Central Time", "central", "central time", "ct", "cst", "cdt")
        add(ET, "New York", "new york", "new york city", "nyc", "new york state")
        add(ET, "Florida", "florida", "miami", "orlando", "tampa")
        add(ET, "Georgia", "georgia", "atlanta")
        add(ET, "Michigan", "michigan", "detroit", "ann arbor", "lansing", "grand rapids")
        add(ET, "Ohio", "ohio", "cleveland", "columbus", "cincinnati")
        add(ET, "Pennsylvania", "pennsylvania", "philadelphia", "pittsburgh")
        add(ET, "Virginia", "virginia")
        add(ET, "North Carolina", "north carolina", "charlotte", "raleigh")
        add(ET, "South Carolina", "south carolina")
        add(ET, "Massachusetts", "massachusetts", "boston")
        add(ET, "Maryland", "maryland", "baltimore")
        add(ET, "New Jersey", "new jersey")
        add(ET, "Connecticut", "connecticut")
        add(ET, "Maine", "maine")
        add(ET, "Vermont", "vermont")
        add(ET, "New Hampshire", "new hampshire")
        add(ET, "Delaware", "delaware")
        add(ET, "West Virginia", "west virginia")
        add(ET, "Indiana", "indiana", "indianapolis")
        add(ET, "Kentucky", "kentucky", "louisville")
        add(ET, "Washington, D.C.", "washington dc", "washington d c", "dc")
        add(ET, "Eastern Time", "eastern", "eastern time", "et", "est", "edt", "east coast")
        add("America/Anchorage", "Alaska", "alaska", "anchorage")
        add("Pacific/Honolulu", "Hawaii", "hawaii", "honolulu")
        add("America/Toronto", "Toronto", "toronto", "ottawa", "montreal", "ontario", "quebec")
        add("America/Vancouver", "Vancouver", "vancouver", "british columbia")
        add("America/Edmonton", "Alberta", "edmonton", "calgary", "alberta")
        add("America/Toronto", "Canada", "canada")
        add("America/Mexico_City", "Mexico City", "mexico city", "mexico")
        add("America/Sao_Paulo", "São Paulo", "sao paulo", "brazil", "rio de janeiro", "rio")
        add("America/Argentina/Buenos_Aires", "Buenos Aires", "buenos aires", "argentina")
        add("Europe/London", "London", "london", "uk", "united kingdom", "england", "britain", "great britain", "scotland", "wales")
        add("Europe/Dublin", "Dublin", "dublin", "ireland")
        add("Europe/Paris", "Paris", "paris", "france")
        add("Europe/Berlin", "Berlin", "berlin", "germany", "munich")
        add("Europe/Madrid", "Madrid", "madrid", "spain", "barcelona")
        add("Europe/Rome", "Rome", "rome", "italy", "milan")
        add("Europe/Amsterdam", "Amsterdam", "amsterdam", "netherlands", "holland")
        add("Europe/Lisbon", "Lisbon", "lisbon", "portugal")
        add("Europe/Athens", "Athens", "athens", "greece")
        add("Europe/Stockholm", "Stockholm", "stockholm", "sweden")
        add("Europe/Moscow", "Moscow", "moscow", "russia")
        add("Europe/Istanbul", "Istanbul", "istanbul", "turkey")
        add("Africa/Cairo", "Cairo", "cairo", "egypt")
        add("Africa/Johannesburg", "Johannesburg", "johannesburg", "south africa", "cape town")
        add("Africa/Nairobi", "Nairobi", "nairobi", "kenya")
        add("Africa/Lagos", "Lagos", "lagos", "nigeria")
        add("Asia/Dubai", "Dubai", "dubai", "uae", "united arab emirates", "abu dhabi")
        add("Asia/Kolkata", "India", "india", "mumbai", "delhi", "new delhi", "bangalore", "kolkata")
        add("Asia/Shanghai", "China", "china", "beijing", "shanghai")
        add("Asia/Hong_Kong", "Hong Kong", "hong kong")
        add("Asia/Singapore", "Singapore", "singapore")
        add("Asia/Tokyo", "Tokyo", "tokyo", "japan", "osaka")
        add("Asia/Seoul", "Seoul", "seoul", "korea", "south korea")
        add("Asia/Bangkok", "Bangkok", "bangkok", "thailand")
        add("Asia/Manila", "Manila", "manila", "philippines")
        add("Australia/Sydney", "Sydney", "sydney", "australia", "melbourne", "canberra")
        add("Australia/Perth", "Perth", "perth")
        add("Pacific/Auckland", "Auckland", "auckland", "new zealand", "wellington")
        add("UTC", "UTC", "utc", "gmt", "zulu", "coordinated universal time", "greenwich mean time")
    }

    /** `places` may carry filler such as "the time in", "california time", "in california". */
    fun resolve(rawPlace: String): ZoneResolution? {
        var norm = TextNorm.normalize(rawPlace)
        for (filler in listOf("the time in ", "time in ", "in the ", "in ", "the ")) {
            if (norm.startsWith(filler)) norm = norm.removePrefix(filler)
        }
        norm = norm.removeSuffix(" time zone").removeSuffix(" timezone").trim()
        if (norm.isEmpty()) return null

        table[norm]?.let { (zone, label) -> return fromTable(norm, zone, label) }
        // "California time" -> california (but "pacific time" is already in the table above)
        if (norm.endsWith(" time")) table[norm.removeSuffix(" time")]?.let { (zone, label) -> return fromTable(norm.removeSuffix(" time"), zone, label) }

        // An IANA id typed or spoken as written, such as America/Chicago.
        val asId = rawPlace.trim().replace(' ', '_')
        if (asId.contains('/')) {
            try {
                return ZoneResolution(ZoneId.of(asId), asId.substringAfterLast('/').replace('_', ' '))
            } catch (_: DateTimeException) {
            }
        }
        // A city that only the IANA database knows: "Berlin" -> Europe/Berlin, when exactly one zone has that name.
        val wanted = norm.replace(' ', '_')
        val matches = ZoneId.getAvailableZoneIds().filter { id -> id.contains('/') && id.substringAfterLast('/').lowercase() == wanted && !id.startsWith("Etc/") }
        if (matches.size == 1) {
            val id = matches.single()
            return ZoneResolution(ZoneId.of(id), id.substringAfterLast('/').replace('_', ' '))
        }
        return null
    }

    private fun fromTable(key: String, zone: String, label: String): ZoneResolution {
        val note = if (key in splitPlaces) "$label spans more than one time zone; this is its main one." else null
        return ZoneResolution(ZoneId.of(zone), label, note)
    }
}
