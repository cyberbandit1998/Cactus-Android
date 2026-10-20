package app.cactusai.core.sports

import app.cactusai.core.match.TextNorm
import java.time.LocalDate

enum class League(val key: String, val display: String, val sport: String) {
    NFL("nfl", "NFL", "football"),
    MLB("mlb", "MLB", "baseball"),
    NHL("nhl", "NHL", "hockey"),
    NBA("nba", "NBA", "basketball"),
    COLLEGE_FOOTBALL("college_football", "college football", "football"),
    COLLEGE_BASKETBALL("college_basketball", "college basketball", "basketball");

    val isCollege: Boolean get() = this == COLLEGE_FOOTBALL || this == COLLEGE_BASKETBALL

    /** Roughly when the league plays, including the playoffs. Used to pick between two teams that share a name, and to say when a season is over. */
    fun inSeason(date: LocalDate): Boolean {
        val md = date.monthValue * 100 + date.dayOfMonth
        return when (this) {
            NFL -> md >= 901 || md <= 215
            MLB -> md in 320..1105
            NHL -> md >= 1001 || md <= 630
            NBA -> md >= 1015 || md <= 630
            COLLEGE_FOOTBALL -> md >= 825 || md <= 120
            COLLEGE_BASKETBALL -> md >= 1101 || md <= 410
        }
    }

    companion object {
        fun fromKey(key: String?): League? = entries.firstOrNull { it.key == key?.lowercase() }
    }
}

/** A team. For professional teams [region] is the city; for colleges it is the school's usual name ("Michigan State"). */
data class Team(val league: League, val region: String, val nickname: String, val aliases: List<String> = emptyList()) {
    val fullName: String get() = if (region.isBlank()) nickname else "$region $nickname"
    val id: String get() = "${league.key}:${TextNorm.normalize(fullName).replace(' ', '-')}"

    /** The school or franchise, so the football and basketball teams of one school can be recognised as the same place. */
    val identity: String get() = TextNorm.normalize(fullName)
}

/**
 * The teams the assistant knows by name: every NFL, MLB, NHL and NBA team and a sample of well-known college programs (as
 * of late 2026; teams move and rename, and the college list is not complete). A team that is not here is not an error: the
 * words the user said are searched for as they are, and the answer says the team was not recognised.
 */
object TeamDirectory {
    private fun pro(league: League, vararg entries: String): List<Team> = entries.map { e ->
        val parts = e.split("|")
        Team(league, parts[0], parts[1], parts.drop(2))
    }

    private val nfl = pro(
        League.NFL,
        "Arizona|Cardinals", "Atlanta|Falcons", "Baltimore|Ravens", "Buffalo|Bills", "Carolina|Panthers", "Chicago|Bears", "Cincinnati|Bengals",
        "Cleveland|Browns", "Dallas|Cowboys", "Denver|Broncos", "Detroit|Lions", "Green Bay|Packers|pack", "Houston|Texans", "Indianapolis|Colts",
        "Jacksonville|Jaguars|jags", "Kansas City|Chiefs", "Las Vegas|Raiders", "Los Angeles|Chargers|la chargers", "Los Angeles|Rams|la rams",
        "Miami|Dolphins|fins", "Minnesota|Vikings|vikes", "New England|Patriots|pats", "New Orleans|Saints", "New York|Giants|ny giants", "New York|Jets|ny jets",
        "Philadelphia|Eagles", "Pittsburgh|Steelers", "San Francisco|49ers|niners|forty niners", "Seattle|Seahawks", "Tampa Bay|Buccaneers|bucs",
        "Tennessee|Titans", "Washington|Commanders",
    )
    private val mlb = pro(
        League.MLB,
        "Arizona|Diamondbacks|dbacks|d backs", "Atlanta|Braves", "Baltimore|Orioles|o s", "Boston|Red Sox", "Chicago|Cubs", "Chicago|White Sox", "Cincinnati|Reds",
        "Cleveland|Guardians", "Colorado|Rockies", "Detroit|Tigers", "Houston|Astros", "Kansas City|Royals", "Los Angeles|Angels|la angels", "Los Angeles|Dodgers|la dodgers",
        "Miami|Marlins", "Milwaukee|Brewers", "Minnesota|Twins", "New York|Mets|ny mets", "New York|Yankees|ny yankees", "|Athletics|oakland athletics|sacramento athletics|a s",
        "Philadelphia|Phillies", "Pittsburgh|Pirates", "San Diego|Padres", "San Francisco|Giants|sf giants", "Seattle|Mariners", "St. Louis|Cardinals|st louis cardinals|saint louis cardinals",
        "Tampa Bay|Rays", "Texas|Rangers", "Toronto|Blue Jays|jays", "Washington|Nationals|nats",
    )
    private val nhl = pro(
        League.NHL,
        "Anaheim|Ducks", "Boston|Bruins", "Buffalo|Sabres", "Calgary|Flames", "Carolina|Hurricanes|canes", "Chicago|Blackhawks|hawks", "Colorado|Avalanche|avs",
        "Columbus|Blue Jackets|jackets", "Dallas|Stars", "Detroit|Red Wings|wings", "Edmonton|Oilers", "Florida|Panthers", "Los Angeles|Kings|la kings",
        "Minnesota|Wild", "Montreal|Canadiens|habs", "Nashville|Predators|preds", "New Jersey|Devils", "New York|Islanders|isles", "New York|Rangers|ny rangers",
        "Ottawa|Senators|sens", "Philadelphia|Flyers", "Pittsburgh|Penguins|pens", "San Jose|Sharks", "Seattle|Kraken", "St. Louis|Blues|st louis blues",
        "Tampa Bay|Lightning|bolts", "Toronto|Maple Leafs|leafs", "Utah|Mammoth", "Vancouver|Canucks", "Vegas|Golden Knights|knights", "Washington|Capitals|caps", "Winnipeg|Jets",
    )
    private val nba = pro(
        League.NBA,
        "Atlanta|Hawks", "Boston|Celtics", "Brooklyn|Nets", "Charlotte|Hornets", "Chicago|Bulls", "Cleveland|Cavaliers|cavs", "Dallas|Mavericks|mavs", "Denver|Nuggets",
        "Detroit|Pistons", "Golden State|Warriors|dubs", "Houston|Rockets", "Indiana|Pacers", "Los Angeles|Clippers|la clippers", "Los Angeles|Lakers|la lakers", "Memphis|Grizzlies",
        "Miami|Heat", "Milwaukee|Bucks", "Minnesota|Timberwolves|wolves", "New Orleans|Pelicans|pels", "New York|Knicks", "Oklahoma City|Thunder|okc",
        "Orlando|Magic", "Philadelphia|76ers|sixers", "Phoenix|Suns", "Portland|Trail Blazers|blazers", "Sacramento|Kings", "San Antonio|Spurs", "Toronto|Raptors",
        "Utah|Jazz", "Washington|Wizards",
    )

    /** School|Nickname|aliases. Each school gets a football and a basketball team. */
    private val schools = listOf(
        "Michigan|Wolverines|umich|u of m|university of michigan", "Michigan State|Spartans|msu|sparty", "Ohio State|Buckeyes|osu", "Alabama|Crimson Tide|bama", "Georgia|Bulldogs|uga",
        "Texas|Longhorns", "Oklahoma|Sooners", "Notre Dame|Fighting Irish|irish", "Penn State|Nittany Lions", "USC|Trojans|southern cal", "UCLA|Bruins", "Oregon|Ducks",
        "Washington|Huskies", "LSU|Tigers|louisiana state", "Florida|Gators", "Florida State|Seminoles|fsu|noles", "Clemson|Tigers", "Auburn|Tigers", "Tennessee|Volunteers|vols",
        "Texas A&M|Aggies", "Wisconsin|Badgers", "Iowa|Hawkeyes", "Nebraska|Cornhuskers|huskers", "Miami|Hurricanes|the u", "Duke|Blue Devils", "North Carolina|Tar Heels|unc",
        "Kentucky|Wildcats", "Kansas|Jayhawks", "Purdue|Boilermakers", "Illinois|Fighting Illini|illini", "Indiana|Hoosiers", "Minnesota|Golden Gophers|gophers", "Northwestern|Wildcats",
        "Maryland|Terrapins|terps", "Rutgers|Scarlet Knights", "Arizona|Wildcats", "Arizona State|Sun Devils|asu", "Colorado|Buffaloes|buffs", "Utah|Utes", "Oklahoma State|Cowboys",
        "Baylor|Bears", "TCU|Horned Frogs", "Ole Miss|Rebels|mississippi", "Mississippi State|Bulldogs", "Arkansas|Razorbacks|hogs", "Missouri|Tigers|mizzou", "South Carolina|Gamecocks",
        "Virginia Tech|Hokies", "Virginia|Cavaliers|hoos", "Louisville|Cardinals", "West Virginia|Mountaineers", "Boise State|Broncos", "Oregon State|Beavers", "Stanford|Cardinal",
        "California|Golden Bears|cal", "Syracuse|Orange", "Boston College|Eagles", "Houston|Cougars", "Cincinnati|Bearcats", "Memphis|Tigers", "Central Michigan|Chippewas",
        "Western Michigan|Broncos", "Eastern Michigan|Eagles", "Toledo|Rockets", "Bowling Green|Falcons", "Ohio|Bobcats", "Northern Illinois|Huskies", "Army|Black Knights",
        "Navy|Midshipmen", "Air Force|Falcons", "Vanderbilt|Commodores|vandy", "Wake Forest|Demon Deacons", "NC State|Wolfpack", "Georgia Tech|Yellow Jackets", "Texas Tech|Red Raiders",
        "Kansas State|Wildcats|k state", "Iowa State|Cyclones", "UConn|Huskies|connecticut", "Gonzaga|Bulldogs", "Villanova|Wildcats", "Marquette|Golden Eagles",
        "Creighton|Bluejays", "Xavier|Musketeers", "Butler|Bulldogs", "Dayton|Flyers",
    )

    private val college: List<Team> = schools.flatMap { e ->
        val p = e.split("|")
        listOf(League.COLLEGE_FOOTBALL, League.COLLEGE_BASKETBALL).map { Team(it, p[0], p[1], p.drop(2)) }
    }

    val all: List<Team> = nfl + mlb + nhl + nba + college

    private val index: Map<String, List<Team>> = buildMap<String, MutableList<Team>> {
        fun add(name: String, team: Team) {
            val key = TextNorm.normalize(name)
            if (key.isNotEmpty()) getOrPut(key) { mutableListOf() }.let { if (team !in it) it += team }
        }
        for (t in all) {
            add(t.fullName, t)
            add(t.nickname, t)
            t.aliases.forEach { add(it, t) }
            // "Michigan Wolverines football" style names are handled by the resolver's noise removal.
        }
    }

    /** Teams whose city or school alone was said ("Detroit", "Michigan"). */
    private val regionIndex: Map<String, List<Team>> = all.filter { it.region.isNotBlank() }.groupBy { TextNorm.normalize(it.region) }

    fun byName(normalized: String): List<Team> = index[normalized].orEmpty()

    fun byRegion(normalized: String): List<Team> = regionIndex[normalized].orEmpty()

    /** Every spelling that identifies [team], longest first, for finding it inside a sentence. */
    fun namesOf(team: Team): List<String> =
        (listOf(team.fullName, team.nickname) + team.aliases).map { TextNorm.normalize(it) }.filter { it.length >= 3 }.distinct().sortedByDescending { it.length }

    /**
     * The ids of the teams named in the user's favourites list (free text such as "Detroit Tigers" or "Michigan"). A shared
     * nickname counts for the professional team; a school counts for both its football and basketball teams; unknown entries
     * and a bare pro city ("Detroit") are ignored because they name several teams.
     */
    fun favoriteIds(favorites: List<String>): Set<String> = favorites.flatMap { f ->
        val norm = TextNorm.normalize(f.replace("'", ""))
        val named = byName(norm)
        when {
            named.size == 1 -> named
            named.isNotEmpty() -> named.filter { !it.league.isCollege }.ifEmpty { named }
            else -> byRegion(norm).filter { it.league.isCollege }
        }
    }.map { it.id }.toSet()
}

sealed interface TeamResolution {
    /** [assumed] says why one of several teams was picked, so the answer can tell the user. */
    data class Found(val team: Team, val assumed: String? = null) : TeamResolution
    data class Ambiguous(val options: List<Team>) : TeamResolution
    data class Unknown(val said: String) : TeamResolution
}

object TeamResolver {
    private val noise = setOf(
        "the", "a", "an", "game", "games", "score", "scores", "play", "plays", "playing", "match", "team", "teams", "mens", "men", "s", "of", "for", "about", "tonight",
        "today", "tomorrow", "yesterday", "next", "last", "latest", "current", "whats", "what", "is", "did", "do", "does", "when", "win", "won", "lose", "lost",
    )
    private val sportWords = mapOf(
        "football" to setOf(League.NFL, League.COLLEGE_FOOTBALL), "baseball" to setOf(League.MLB), "hockey" to setOf(League.NHL),
        "basketball" to setOf(League.NBA, League.COLLEGE_BASKETBALL), "hoops" to setOf(League.NBA, League.COLLEGE_BASKETBALL),
    )
    private val collegeWords = setOf("college", "ncaa", "university", "campus")

    /**
     * Works out which team the user means. [said] is the team as Needle copied it, [context] the whole spoken request (for words
     * such as "football"), [leagueHint] Needle's league guess, [favorites] the user's favourite teams.
     */
    fun resolve(said: String?, context: String, leagueHint: League?, favorites: List<String>, today: LocalDate): TeamResolution {
        val sayNorm = TextNorm.normalize((said ?: "").replace("'", ""))
        val contextTokens = TextNorm.tokens(context.replace("'", "")).toSet()
        val sportHint: Set<League>? = sportWords.entries.firstOrNull { it.key in contextTokens || it.key in TextNorm.tokens(sayNorm) }?.value
        val wantsCollege = contextTokens.any { it in collegeWords }

        val stripped = TextNorm.tokens(sayNorm).filter { it !in noise && it !in sportWords.keys && it !in collegeWords }.joinToString(" ")
        val candidates = lookup(sayNorm).ifEmpty { lookup(stripped) }.ifEmpty { containedIn(sayNorm.ifEmpty { TextNorm.normalize(context) }) }
        if (candidates.isEmpty()) return TeamResolution.Unknown((said ?: "").trim())

        var pool = candidates
        // Needle's league guess narrows the pool only when it leaves something.
        leagueHint?.let { h -> pool.filter { it.league == h }.takeIf { it.isNotEmpty() }?.let { pool = it } }
        sportHint?.let { s -> pool.filter { it.league in s }.takeIf { it.isNotEmpty() }?.let { pool = it } }
        if (wantsCollege) pool.filter { it.league.isCollege }.takeIf { it.isNotEmpty() }?.let { pool = it }
        if (distinct(pool).size == 1) return TeamResolution.Found(pool.first(), null)

        // The user's own favourites settle a shared name ("Tigers" -> Detroit Tigers).
        val fav = TeamDirectory.favoriteIds(favorites)
        pool.filter { it.id in fav }.takeIf { it.isNotEmpty() }?.let { favoured ->
            if (distinct(favoured).size == 1) return TeamResolution.Found(favoured.first(), "your favourite teams include the ${favoured.first().fullName}")
            pool = favoured
        }

        // One school with a football and a basketball team: whichever is in season, if only one is.
        if (pool.map { it.identity }.distinct().size == 1) {
            val inSeason = pool.filter { it.league.inSeason(today) }
            if (inSeason.size == 1) return TeamResolution.Found(inSeason.first(), "${inSeason.first().league.sport} is in season")
            return TeamResolution.Ambiguous(pool)
        }

        // A name used by exactly one professional team and some colleges ("Tigers", "Cowboys") is the professional team.
        if (!wantsCollege) {
            val pros = pool.filter { !it.league.isCollege }
            if (pros.size == 1) return TeamResolution.Found(pros.first(), "no league was named, so I took the ${pros.first().fullName}")
            val proInSeason = pros.filter { it.league.inSeason(today) }
            if (pros.isNotEmpty() && proInSeason.size == 1) {
                return TeamResolution.Found(proInSeason.first(), "${proInSeason.first().league.sport} is in season")
            }
        }
        return TeamResolution.Ambiguous(pool)
    }

    private fun distinct(teams: List<Team>) = teams.map { it.id }.distinct()

    private fun lookup(norm: String): List<Team> {
        if (norm.isEmpty()) return emptyList()
        TeamDirectory.byName(norm).takeIf { it.isNotEmpty() }?.let { return it }
        return TeamDirectory.byRegion(norm)
    }

    /** "the detroit tigers game tonight" -> the team whose longest name appears inside. */
    private fun containedIn(norm: String): List<Team> {
        if (norm.isEmpty()) return emptyList()
        val padded = " $norm "
        var best: List<Team> = emptyList()
        var bestLen = 0
        for (team in TeamDirectory.all) {
            for (name in TeamDirectory.namesOf(team)) {
                if (name.length > bestLen && padded.contains(" $name ")) {
                    best = TeamDirectory.byName(name).ifEmpty { listOf(team) }
                    bestLen = name.length
                }
            }
        }
        return best
    }
}
