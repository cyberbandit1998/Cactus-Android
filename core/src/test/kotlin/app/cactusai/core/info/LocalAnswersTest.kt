package app.cactusai.core.info

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dates and times use a fixed clock, so these tests give the same answer on any day and in any time zone. */
class LocalAnswersTest {
    private val us = Locale.US

    // Tuesday 20 October 2026, 15:42 in Detroit (19:42 UTC)
    private val detroit = ZoneId.of("America/Detroit")
    private val clock = Clock.fixed(Instant.parse("2026-10-20T19:42:00Z"), detroit)
    private val today = LocalDate.of(2026, 10, 20)

    // ---- time ----

    @Test
    fun theLocalTimeRespectsTheTwelveOrTwentyFourHourSetting() {
        assertEquals("It's 3:42 PM.", TimeAnswer.answer(clock, us, use24Hour = false, place = null).text)
        assertEquals("It's 15:42.", TimeAnswer.answer(clock, us, use24Hour = true, place = null).text)
        assertEquals("It's 3:42 PM.", TimeAnswer.answer(clock, us, false, "  ").text)
    }

    @Test
    fun theTimeInAnotherPlaceUsesThatPlacesZoneAndSaysHowFarApart() {
        val a = TimeAnswer.answer(clock, us, false, "California")
        assertEquals("It's 12:42 PM in California (Pacific Time). That's 3 hours behind you.", a.text)
        assertEquals("It's 12:42 PM in California. That's 3 hours behind you.", a.spoken)
        assertEquals(AnswerOrigin.LOCAL, a.origin)
        assertTrue(a.sources.isEmpty())
    }

    @Test
    fun aDifferentDayAtTheOtherPlaceIsSaid() {
        val tokyo = TimeAnswer.answer(clock, us, false, "Tokyo").text
        assertTrue(tokyo, tokyo.startsWith("It's 4:42 AM tomorrow in Tokyo"))
        assertTrue(tokyo, tokyo.contains("13 hours ahead of you"))
        val auckland = TimeAnswer.answer(clock, us, true, "Auckland").text
        assertTrue(auckland, auckland.startsWith("It's 08:42 tomorrow in Auckland"))
    }

    @Test
    fun halfHourZonesAndTheSameZoneAreDescribedCorrectly() {
        assertTrue(TimeAnswer.answer(clock, us, false, "India").text.contains("9 hours 30 minutes ahead of you"))
        assertTrue(TimeAnswer.answer(clock, us, false, "Michigan").text.contains("same time as here"))
    }

    @Test
    fun daylightSavingChangesTheGap() {
        // 15 March 2026: the US is already on daylight time (since 8 March), Britain is not yet (until 29 March)
        val spring = Clock.fixed(Instant.parse("2026-03-15T17:00:00Z"), ZoneId.of("America/New_York"))
        assertTrue(TimeAnswer.answer(spring, us, false, "London").text.contains("4 hours ahead of you"))
        // 1 December 2026: both on standard time
        val winter = Clock.fixed(Instant.parse("2026-12-01T17:00:00Z"), ZoneId.of("America/New_York"))
        assertTrue(TimeAnswer.answer(winter, us, false, "London").text.contains("5 hours ahead of you"))
    }

    @Test
    fun anUnknownPlaceIsReportedNotGuessed() {
        val a = TimeAnswer.answer(clock, us, false, "Narnia")
        assertFalse(a.verified)
        assertTrue(a.text.contains("don't know which time zone"))
    }

    @Test
    fun placesResolveToTheRightZones() {
        fun zone(p: String) = ZoneResolver.resolve(p)?.zone?.id
        assertEquals("America/Los_Angeles", zone("California"))
        assertEquals("America/Los_Angeles", zone("the time in California"))
        assertEquals("America/Los_Angeles", zone("Pacific time"))
        assertEquals("America/Phoenix", zone("Arizona"))
        assertEquals("America/Chicago", zone("Texas"))
        assertEquals("America/New_York", zone("new york"))
        assertEquals("America/New_York", zone("Eastern"))
        assertEquals("Asia/Tokyo", zone("Tokyo"))
        assertEquals("Europe/London", zone("England"))
        assertEquals("Pacific/Honolulu", zone("Hawaii"))
        assertEquals("America/Chicago", zone("America/Chicago"))
        assertEquals("Europe/Berlin", zone("Berlin"))
        assertNull(ZoneResolver.resolve("Atlantis"))
        assertNull(ZoneResolver.resolve(""))
    }

    @Test
    fun placesThatSpanZonesAreSaidToDoSo() {
        assertNotNull(ZoneResolver.resolve("Texas")!!.note)
        assertNull(ZoneResolver.resolve("California")!!.note)
        assertTrue(TimeAnswer.answer(clock, us, false, "Texas").text.contains("spans more than one time zone"))
    }

    // ---- dates ----

    private fun date(day: String?, text: String = "what is the date") = DateInfo.answer(day, text, clock, us).text

    @Test
    fun todayTomorrowAndYesterday() {
        assertEquals("Today is Tuesday, October 20, 2026.", date(null))
        assertEquals("Today is Tuesday, October 20, 2026.", date("today"))
        assertEquals("Tomorrow is Wednesday, October 21, 2026.", date("tomorrow", "what day is tomorrow"))
        assertEquals("Yesterday was Monday, October 19, 2026.", date("yesterday"))
        assertEquals(LocalDate.of(2026, 10, 22), DateInfo.resolve("the day after tomorrow", today)!!.date)
    }

    @Test
    fun holidaysAreComputedForTheComingOccurrence() {
        assertEquals(LocalDate.of(2026, 12, 25), DateInfo.resolve("Christmas", today)!!.date)
        assertEquals(LocalDate.of(2026, 10, 31), DateInfo.resolve("Halloween", today)!!.date)
        assertEquals(LocalDate.of(2026, 11, 26), DateInfo.resolve("Thanksgiving", today)!!.date)
        assertEquals(LocalDate.of(2026, 11, 27), DateInfo.resolve("Black Friday", today)!!.date)
        assertEquals(LocalDate.of(2027, 1, 1), DateInfo.resolve("New Year's Day", today)!!.date)
        assertEquals(LocalDate.of(2027, 5, 31), DateInfo.resolve("Memorial Day", today)!!.date)
        assertEquals(LocalDate.of(2027, 9, 6), DateInfo.resolve("Labor Day", today)!!.date)
        assertEquals(LocalDate.of(2027, 5, 9), DateInfo.resolve("Mother's Day", today)!!.date)
        assertEquals(LocalDate.of(2027, 6, 20), DateInfo.resolve("Father's Day", today)!!.date)
        assertEquals(LocalDate.of(2027, 1, 18), DateInfo.resolve("MLK day", today)!!.date)
        assertEquals(LocalDate.of(2027, 7, 4), DateInfo.resolve("the Fourth of July", today)!!.date)
    }

    @Test
    fun easterMatchesTheKnownDates() {
        assertEquals(LocalDate.of(2024, 3, 31), DateInfo.easter(2024))
        assertEquals(LocalDate.of(2025, 4, 20), DateInfo.easter(2025))
        assertEquals(LocalDate.of(2026, 4, 5), DateInfo.easter(2026))
        assertEquals(LocalDate.of(2027, 3, 28), DateInfo.easter(2027))
        assertEquals(LocalDate.of(2038, 4, 25), DateInfo.easter(2038))
        assertEquals(LocalDate.of(2027, 3, 28), DateInfo.resolve("Easter", today)!!.date)
    }

    @Test
    fun aHolidayThatIsTodayCountsAsToday() {
        val onHalloween = LocalDate.of(2026, 10, 31)
        assertEquals(onHalloween, DateInfo.resolve("Halloween", onHalloween)!!.date)
        assertEquals(LocalDate.of(2027, 10, 31), DateInfo.resolve("Halloween", onHalloween.plusDays(1))!!.date)
    }

    @Test
    fun theWeekdayOfAHoliday() {
        assertEquals("Christmas is Friday, December 25, 2026, 66 days from today.", date("Christmas", "What day of the week is Christmas?"))
    }

    @Test
    fun countingDaysUntilAHoliday() {
        assertEquals("11 days until Halloween (Saturday, October 31, 2026).", date("Halloween", "How many days until Halloween?"))
        assertEquals("66 days until Christmas (Friday, December 25, 2026).", date("Christmas", "days till Christmas"))
        val oneDay = DateInfo.answer("tomorrow", "how many days until tomorrow", clock, us).text
        assertTrue(oneDay, oneDay.startsWith("1 day until"))
        assertEquals("Halloween is today (Saturday, October 31, 2026).", DateInfo.answer("Halloween", "how many days until halloween", Clock.fixed(Instant.parse("2026-10-31T16:00:00Z"), detroit), us).text)
    }

    @Test
    fun daysSinceLooksBackwards() {
        assertEquals("299 days since Christmas (Thursday, December 25, 2025).", date("Christmas", "How many days since Christmas?"))
    }

    @Test
    fun weekdaysAndRelativeDays() {
        assertEquals(LocalDate.of(2026, 10, 23), DateInfo.resolve("Friday", today)!!.date)
        assertEquals(LocalDate.of(2026, 10, 20), DateInfo.resolve("Tuesday", today)!!.date) // today counts
        assertEquals(LocalDate.of(2026, 10, 27), DateInfo.resolve("next Tuesday", today)!!.date)
        assertEquals(LocalDate.of(2026, 10, 13), DateInfo.resolve("last Tuesday", today)!!.date)
        assertEquals(LocalDate.of(2026, 10, 23), DateInfo.resolve("in 3 days", today)!!.date)
        assertEquals(LocalDate.of(2026, 11, 3), DateInfo.resolve("in two weeks", today)!!.date)
        assertEquals(LocalDate.of(2026, 10, 15), DateInfo.resolve("5 days ago", today)!!.date)
    }

    @Test
    fun spokenDatesAreUnderstood() {
        assertEquals(LocalDate.of(2026, 12, 25), DateInfo.resolve("December 25", today)!!.date)
        assertEquals(LocalDate.of(2026, 12, 25), DateInfo.resolve("dec 25th", today)!!.date)
        assertEquals(LocalDate.of(2026, 12, 25), DateInfo.resolve("25th of December", today)!!.date)
        assertEquals(LocalDate.of(2027, 3, 3), DateInfo.resolve("March 3", today)!!.date)
        assertEquals(LocalDate.of(2028, 3, 3), DateInfo.resolve("March 3 2028", today)!!.date)
        assertEquals(LocalDate.of(2026, 12, 25), DateInfo.resolve("12/25", today)!!.date)
        assertEquals(LocalDate.of(2027, 2, 14), DateInfo.resolve("Valentine's Day", today)!!.date)
        assertEquals(LocalDate.of(2028, 2, 29), DateInfo.resolve("February 29", today)!!.date) // the next leap day
    }

    @Test
    fun impossibleOrUnknownDaysAreRefusedNotGuessed() {
        assertNull(DateInfo.resolve("February 30", today))
        assertNull(DateInfo.resolve("13/45", today))
        assertNull(DateInfo.resolve("my birthday", today))
        assertNull(DateInfo.resolve("blursday", today))
        val a = DateInfo.answer("my birthday", "when is my birthday", clock, us)
        assertFalse(a.verified)
        assertTrue(a.text.contains("don't know which day"))
    }

    @Test
    fun theDateUsesThePhonesLocaleOrder() {
        val gb = DateInfo.answer("tomorrow", "tomorrow", clock, Locale.UK).text
        assertEquals("Tomorrow is Wednesday, 21 October 2026.", gb.replace("Wednesday 21", "Wednesday, 21"))
    }

    // ---- device ----

    private val phone = DeviceSnapshot(
        batteryPercent = 82, charging = true, chargingSource = "USB", androidRelease = "14", sdkInt = 34, securityPatch = "2026-09-05",
        storageFreeBytes = 52_300_000_000L, storageTotalBytes = 128_000_000_000L, manufacturer = "Google", model = "Pixel 7",
    )

    @Test
    fun batteryAndChargingAreAnsweredFromTheSnapshot() {
        assertEquals("Your battery is at 82% and charging over USB.", DeviceAnswers.battery(phone).text)
        assertEquals("Your battery is at 40% and not charging.", DeviceAnswers.battery(phone.copy(batteryPercent = 40, charging = false)).text)
        assertEquals("Yes, your phone is charging over USB. The battery is at 82%.", DeviceAnswers.describe("charging", phone, us).text)
        assertEquals("No, your phone is not charging. The battery is at 82%.", DeviceAnswers.describe("charging", phone.copy(charging = false), us).text)
        assertTrue(DeviceAnswers.describe("charging", phone.copy(chargingSource = "wireless"), us).text.contains("wirelessly"))
    }

    @Test
    fun androidVersionStorageAndModel() {
        assertEquals("Your phone runs Android 14 (API level 34). The security patch level is Sep 5, 2026.", DeviceAnswers.describe("android_version", phone, us).text)
        assertEquals("You have 52.3 GB of storage left out of 128 GB (40% free).", DeviceAnswers.describe("storage", phone, us).text)
        assertEquals("This is a Google Pixel 7.", DeviceAnswers.describe("model", phone, us).text)
        assertEquals("This is a Pixel 7.", DeviceAnswers.model(phone.copy(manufacturer = "Pixel")).text)
    }

    @Test
    fun whatTheValuesAreMissingIsSaidNotInvented() {
        val empty = DeviceSnapshot()
        for (topic in listOf("charging", "android_version", "storage", "model", "battery")) {
            val a = DeviceAnswers.describe(topic, empty, us)
            assertFalse(topic, a.verified)
            assertEquals(topic, "I can't read that on this phone.", a.text)
        }
        assertFalse(DeviceAnswers.describe("imei", phone, us).verified)
    }

    @Test
    fun storageSizesUseDecimalUnits() {
        assertEquals("1.5 TB", DeviceAnswers.size(1_500_000_000_000L).replace("1.50", "1.5"))
        assertEquals("500 MB", DeviceAnswers.size(500_000_000L))
        assertEquals("2 GB", DeviceAnswers.size(2_000_000_000L))
        assertEquals("12.3 GB", DeviceAnswers.size(12_340_000_000L))
    }

    // ---- calculator ----

    private fun calc(s: String): String = when (val r = Calculator.evaluate(s)) {
        is CalcResult.Value -> r.shown
        is CalcResult.Error -> "ERROR: ${r.message}"
    }

    @Test
    fun everydayArithmetic() {
        assertEquals("15 percent of 80 is 12.", calc("What's 15 percent of 80?"))
        assertEquals("12 times 7 is 84.", calc("12 times 7"))
        assertEquals("twelve times seven is 84.", calc("twelve times seven"))
        assertEquals("2 + 2 * 3 is 8.", calc("2 + 2 * 3"))
        assertEquals("(2 + 2) * 3 is 12.", calc("(2 + 2) * 3"))
        assertEquals("100 divided by 8 is 12.5.", calc("100 divided by 8"))
        assertEquals("0.1 + 0.2 is 0.3.", calc("0.1 + 0.2"))
        assertEquals("5 squared is 25.", calc("5 squared"))
        assertEquals("square root of 144 is 12.", calc("square root of 144"))
        assertEquals("2 to the power of 10 is 1,024.", calc("2 to the power of 10"))
        assertEquals("1,000 plus 2,500 is 3,500.", calc("1,000 plus 2,500"))
        assertEquals("20% of 50 is 10.", calc("20% of 50"))
        assertEquals("10 minus 15 is -5.", calc("10 minus 15"))
        assertEquals("7 x 6 is 42.", calc("7 x 6"))
        assertEquals("one hundred and five plus twenty five is 130.", calc("one hundred and five plus twenty five"))
    }

    @Test
    fun divisionByZeroAndNonsenseAreRefused() {
        assertTrue(calc("5 divided by 0").contains("divide by zero"))
        assertTrue(calc("square root of minus 4").startsWith("ERROR"))
        assertTrue(calc("hello world").startsWith("ERROR"))
        assertTrue(calc("").startsWith("ERROR"))
        assertTrue(calc("2 +").startsWith("ERROR"))
        assertTrue(calc("(2 + 3").startsWith("ERROR"))
    }

    @Test
    fun codeAndHugeInputsAreNotEvaluated() {
        assertTrue(calc("System.exit(0)").startsWith("ERROR"))
        assertTrue(calc("2; rm -rf /").startsWith("ERROR"))
        assertTrue(calc("9 ^ 9 ^ 9 ^ 9").startsWith("ERROR"))
        assertTrue(calc("1".repeat(200)).startsWith("ERROR"))
        assertTrue(calc("(".repeat(60) + "1" + ")".repeat(60)).startsWith("ERROR"))
    }

    @Test
    fun numberWordsBecomeDigits() {
        assertEquals("25", NumberWords.convert("twenty five"))
        assertEquals("105", NumberWords.convert("one hundred and five"))
        assertEquals("3.5", NumberWords.convert("three point five"))
        assertEquals("2000000", NumberWords.convert("two million"))
        assertEquals("1234", NumberWords.convert("one thousand two hundred thirty four"))
        assertEquals("what is 7 plus 8", NumberWords.convert("what is seven plus eight"))
    }

    @Test
    fun spokenFormSpellsOutSymbols() {
        assertEquals("12 times 7 is 84.", Calculator.spokenForm("12 * 7 is 84."))
        assertEquals("5 plus 3 is 8.", Calculator.spokenForm("5 + 3 is 8."))
        assertEquals("2 to the power of 10 is 1,024.", Calculator.spokenForm("2^10 is 1,024."))
    }
}
