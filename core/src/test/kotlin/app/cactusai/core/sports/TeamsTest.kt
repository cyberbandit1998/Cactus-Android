package app.cactusai.core.sports

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamsTest {
    private val october = LocalDate.of(2026, 10, 20)
    private val july = LocalDate.of(2026, 7, 15)
    private val december = LocalDate.of(2026, 12, 10)

    private fun resolve(
        said: String?,
        context: String = said.orEmpty(),
        league: League? = null,
        favorites: List<String> = emptyList(),
        today: LocalDate = october,
    ) = TeamResolver.resolve(said, context, league, favorites, today)

    private fun name(r: TeamResolution): String = (r as TeamResolution.Found).team.fullName + "/" + r.team.league.key

    @Test
    fun theDirectoryHasEveryProfessionalTeam() {
        assertEquals(32, TeamDirectory.all.count { it.league == League.NFL })
        assertEquals(30, TeamDirectory.all.count { it.league == League.MLB })
        assertEquals(32, TeamDirectory.all.count { it.league == League.NHL })
        assertEquals(30, TeamDirectory.all.count { it.league == League.NBA })
        assertTrue(TeamDirectory.all.count { it.league == League.COLLEGE_FOOTBALL } >= 80)
        assertEquals(TeamDirectory.all.count { it.league == League.COLLEGE_FOOTBALL }, TeamDirectory.all.count { it.league == League.COLLEGE_BASKETBALL })
        assertEquals("every id is unique", TeamDirectory.all.size, TeamDirectory.all.map { it.id }.distinct().size)
    }

    @Test
    fun theExampleTeamsResolve() {
        assertEquals("Detroit Tigers/mlb", name(resolve("Detroit Tigers")))
        assertEquals("Detroit Lions/nfl", name(resolve("Lions")))
        assertEquals("Detroit Red Wings/nhl", name(resolve("the Red Wings")))
        assertEquals("Detroit Pistons/nba", name(resolve("Pistons", "what's the score of the Pistons game")))
        assertEquals("Michigan Wolverines/college_football", name(resolve("Michigan football", "what time is the Michigan football game")))
        assertEquals("Michigan Wolverines/college_basketball", name(resolve("Michigan", "Michigan basketball score", today = december, league = League.COLLEGE_BASKETBALL)))
    }

    @Test
    fun aSharedNicknameIsTheProfessionalTeamAndSaysSo() {
        val r = resolve("Tigers") as TeamResolution.Found
        assertEquals("Detroit Tigers", r.team.fullName)
        assertNotNull(r.assumed)
        assertEquals("Dallas Cowboys", (resolve("Cowboys") as TeamResolution.Found).team.fullName)
    }

    @Test
    fun favouriteTeamsSettleAnAmbiguousName() {
        val r = resolve("Tigers", favorites = listOf("Detroit Tigers", "Detroit Lions"))
        assertEquals("Detroit Tigers", (r as TeamResolution.Found).team.fullName)
        assertTrue(r.assumed!!.contains("favourite"))
        // "Giants" is two professional teams; the favourite picks one even in October when both are playing
        assertTrue(resolve("Giants") is TeamResolution.Ambiguous)
        assertEquals("San Francisco Giants", (resolve("Giants", favorites = listOf("San Francisco Giants")) as TeamResolution.Found).team.fullName)
        assertEquals("New York Giants", (resolve("Giants", favorites = listOf("NY Giants")) as TeamResolution.Found).team.fullName)
    }

    @Test
    fun theSeasonDecidesBetweenTwoProfessionalTeamsWhenOnlyOneIsPlaying() {
        val r = resolve("Giants", today = july) as TeamResolution.Found
        assertEquals("San Francisco Giants", r.team.fullName)
        assertEquals("baseball is in season", r.assumed)
        val kings = resolve("Kings", today = december)
        assertTrue("both hockey and basketball are on in December", kings is TeamResolution.Ambiguous)
    }

    @Test
    fun aCityWithSeveralTeamsIsAskedAbout() {
        val r = resolve("Detroit") as TeamResolution.Ambiguous
        assertEquals(setOf("Detroit Lions", "Detroit Tigers", "Detroit Red Wings", "Detroit Pistons"), r.options.map { it.fullName }.toSet())
        // naming the sport settles it
        assertEquals("Detroit Pistons", (resolve("Detroit", "Detroit basketball score") as TeamResolution.Found).team.fullName)
        assertEquals("Detroit Red Wings", (resolve("Detroit", "Detroit hockey") as TeamResolution.Found).team.fullName)
    }

    @Test
    fun aSchoolWithTwoSportsFollowsTheSeasonThenAsks() {
        val fall = resolve("Michigan") as TeamResolution.Found
        assertEquals(League.COLLEGE_FOOTBALL, fall.team.league)
        val spring = resolve("Michigan", today = LocalDate.of(2027, 3, 1)) as TeamResolution.Found
        assertEquals(League.COLLEGE_BASKETBALL, spring.team.league)
        assertTrue("football and basketball overlap in December", resolve("Michigan", today = december) is TeamResolution.Ambiguous)
        assertEquals("Michigan State Spartans", (resolve("Michigan State") as TeamResolution.Found).team.fullName)
    }

    @Test
    fun aLeagueHintNarrowsOnlyWhenItCanAndIsOtherwiseIgnored() {
        assertEquals("Detroit Tigers/mlb", name(resolve("Tigers", league = League.MLB)))
        // there is no NFL team called the Tigers, so a wrong league guess is ignored
        assertEquals("Detroit Tigers/mlb", name(resolve("Tigers", league = League.NFL)))
        assertEquals("New York Jets/nfl", name(resolve("Jets", league = League.NFL)))
        assertEquals("Winnipeg Jets/nhl", name(resolve("Jets", league = League.NHL)))
    }

    @Test
    fun spokenVariantsAndNoiseWordsAreUnderstood() {
        assertEquals("Detroit Tigers/mlb", name(resolve("the detroit tigers game", "what's the Detroit Tigers score")))
        assertEquals("San Francisco 49ers/nfl", name(resolve("Niners")))
        assertEquals("Philadelphia 76ers/nba", name(resolve("Sixers")))
        assertEquals("St. Louis Blues/nhl", name(resolve("St. Louis Blues")))
        assertEquals("Detroit Tigers/mlb", name(resolve("", "when do the Detroit Tigers play next")))
    }

    @Test
    fun anUnknownTeamIsReportedNotGuessed() {
        val r = resolve("Flying Pigs")
        assertTrue(r is TeamResolution.Unknown)
        assertEquals("Flying Pigs", (r as TeamResolution.Unknown).said)
        assertTrue(resolve(null, "what is the weather") is TeamResolution.Unknown)
    }

    @Test
    fun seasonsAreAboutRight() {
        assertTrue(League.NFL.inSeason(LocalDate.of(2026, 10, 20)))
        assertTrue(League.NFL.inSeason(LocalDate.of(2027, 1, 20)))
        assertTrue(!League.NFL.inSeason(LocalDate.of(2026, 6, 1)))
        assertTrue(League.MLB.inSeason(LocalDate.of(2026, 7, 4)))
        assertTrue(!League.MLB.inSeason(LocalDate.of(2026, 12, 1)))
        assertTrue(League.NHL.inSeason(LocalDate.of(2027, 2, 1)))
        assertTrue(League.COLLEGE_BASKETBALL.inSeason(LocalDate.of(2027, 3, 20)))
        assertTrue(!League.COLLEGE_BASKETBALL.inSeason(LocalDate.of(2026, 10, 20)))
    }

    @Test
    fun favouriteNamesAreMatchedToTeamIds() {
        val ids = TeamDirectory.favoriteIds(listOf("Detroit Tigers", "Lions", "Michigan", "Detroit", "Nonsense FC"))
        assertTrue("mlb:detroit-tigers" in ids)
        assertTrue("nfl:detroit-lions" in ids)
        assertTrue("college_football:michigan-wolverines" in ids && "college_basketball:michigan-wolverines" in ids)
        assertEquals("a bare city and an unknown team add nothing", 4, ids.size)
    }
}
