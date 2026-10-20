package app.cactusai.core

import app.cactusai.core.json.Json
import app.cactusai.core.json.JsonValue
import app.cactusai.core.tools.AssistantTools
import app.cactusai.core.tools.ToolGroup
import app.cactusai.core.tools.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    private val reg = AssistantTools.registry
    private val phone = setOf(ToolGroup.PHONE)
    private val both = setOf(ToolGroup.PHONE, ToolGroup.SCREEN)

    private fun check(tool: String, args: String?, groups: Set<ToolGroup> = both): ValidationResult =
        reg.validate(tool, args?.let { Json.parse(it) }, groups)

    private fun ok(tool: String, args: String?, groups: Set<ToolGroup> = both) =
        (check(tool, args, groups) as? ValidationResult.Ok ?: error("expected Ok for $tool $args, got ${check(tool, args, groups)}")).call

    private fun rejected(tool: String, args: String?, groups: Set<ToolGroup> = both): String =
        (check(tool, args, groups) as? ValidationResult.Rejected ?: error("expected rejection for $tool $args")).reason

    @Test
    fun needleToolsJsonIsValidAndMatchesTheEngineSchemaShape() {
        val arr = Json.parse(reg.toNeedleToolsJson(both)) as JsonValue.Arr
        assertEquals(reg.tools.size, arr.items.size)
        val timer = arr.items.map { it as JsonValue.Obj }.first { it.string("name") == "set_timer" }
        val params = timer.obj("parameters")!!
        assertEquals("object", params.string("type"))
        assertEquals("integer", params.obj("properties")!!.obj("minutes")!!.string("type"))
        assertEquals(1440L, params.obj("properties")!!.obj("minutes")!!.long("maximum"))
        assertEquals(listOf(JsonValue.Str("\\btimers?\\b")), timer.array("triggers"))
        val settings = arr.items.map { it as JsonValue.Obj }.first { it.string("name") == "open_settings" }
        assertTrue(settings.obj("parameters")!!.array("required")!!.contains(JsonValue.Str("page")))
    }

    @Test
    fun phoneOnlyToolsJsonHidesScreenTools() {
        val names = (Json.parse(reg.toNeedleToolsJson(phone)) as JsonValue.Arr).items.map { (it as JsonValue.Obj).string("name") }
        assertTrue("open_app" in names)
        assertTrue("scroll" !in names && "type_text" !in names)
    }

    @Test
    fun shorterDetailLevelsAreStrictlySmallerAndStayValid() {
        val full = reg.toNeedleToolsJson(both, app.cactusai.core.tools.ToolDetail.FULL)
        val compact = reg.toNeedleToolsJson(both, app.cactusai.core.tools.ToolDetail.COMPACT)
        val minimal = reg.toNeedleToolsJson(both, app.cactusai.core.tools.ToolDetail.MINIMAL)
        assertTrue(compact.length < full.length && minimal.length < compact.length)
        for (json in listOf(full, compact, minimal)) {
            val tools = (Json.parse(json) as JsonValue.Arr).items.map { it as JsonValue.Obj }
            assertEquals(17, tools.size)
            // The model must still see every name, closed set and number bound at every level.
            val search = tools.first { it.string("name") == "search_web" }
            assertTrue(search.obj("parameters")!!.obj("properties")!!.obj("site")!!.array("enum")!!.size == 5)
            val alarm = tools.first { it.string("name") == "set_alarm" }
            assertEquals(23L, alarm.obj("parameters")!!.obj("properties")!!.obj("hour")!!.long("maximum"))
        }
        assertTrue(!minimal.contains("The app name as the user said it."))
    }

    @Test
    fun theFullToolListStaysSmallEnoughToBeReasonable() {
        // Needle 3 reads the whole list as part of its prompt; keep it modest. (about 5.5 KB at the time of writing)
        assertTrue(reg.toNeedleToolsJson(both).length < 7_000)
        assertTrue(reg.toNeedleToolsJson(phone).length < 5_000)
    }

    @Test
    fun signatureChangesWithToolSetAndIsStable() {
        assertEquals(reg.signature(phone), reg.signature(phone))
        assertNotEquals(reg.signature(phone), reg.signature(both))
    }

    @Test
    fun acceptsTypicalCalls() {
        assertEquals("Spotify", ok("open_app", """{"app_name":"Spotify"}""").args.string("app_name"))
        assertEquals("youtube", ok("search_web", """{"query":"bambu lab p1s tips","site":"YouTube"}""").args.string("site"))
        assertEquals(15L, ok("set_timer", """{"minutes":15}""").args.long("minutes"))
        assertEquals(19, ok("set_alarm", """{"hour":19,"minute":30}""").args.int("hour"))
        assertEquals("bluetooth", ok("open_settings", """{"page":"bluetooth"}""").args.string("page"))
        ok("check_battery", "{}")
        ok("check_battery", null)
        ok("dial_number", """{"number":"555-123-4567"}""")
        ok("dial_number", "{}")
    }

    @Test
    fun floatingPointWholeNumbersAreAcceptedForIntegers() {
        assertEquals(15L, ok("set_timer", """{"minutes":15.0}""").args.long("minutes"))
    }

    @Test
    fun rejectsToolsOutsideTheAllowlist() {
        for (name in listOf("rm", "run_shell", "install_app", "exec", "OPEN_APP", "open_app ", "", "open-app")) {
            assertTrue(name, check(name, "{}") is ValidationResult.Rejected)
        }
    }

    @Test
    fun screenToolsAreRejectedWhenScreenControlIsOff() {
        assertTrue(rejected("scroll", """{"direction":"down"}""", phone).contains("turned off"))
        ok("scroll", """{"direction":"down"}""", both)
    }

    @Test
    fun rejectsUnknownAndInjectedArguments() {
        assertTrue(rejected("open_app", """{"app_name":"x","price":5}""").contains("no argument"))
        assertTrue(rejected("open_app", """{"app_name":"x","intent":"android.intent.action.DELETE"}""").contains("no argument"))
        assertTrue(rejected("check_battery", """{"shell":"id"}""").contains("no argument"))
    }

    @Test
    fun rejectsMissingRequiredAndWrongTypes() {
        assertTrue(rejected("open_app", "{}").contains("needs"))
        assertTrue(rejected("open_app", """{"app_name":null}""").contains("needs"))
        assertTrue(rejected("open_app", """{"app_name":5}""").contains("text"))
        assertTrue(rejected("set_alarm", """{"hour":"7"}""").contains("whole number"))
        assertTrue(rejected("set_alarm", """{"hour":7.5}""").contains("whole number"))
        assertTrue(rejected("open_app", "[]").contains("object"))
        assertTrue(rejected("open_app", "\"x\"").contains("object"))
    }

    @Test
    fun rejectsOutOfRangeNumbers() {
        assertTrue(rejected("set_alarm", """{"hour":24}""").contains("at most"))
        assertTrue(rejected("set_alarm", """{"hour":-1}""").contains("at least"))
        assertTrue(rejected("set_alarm", """{"hour":7,"minute":60}""").contains("at most"))
        assertTrue(rejected("tap_result", """{"position":0}""").contains("at least"))
        assertTrue(rejected("change_volume", """{"action":"set","level":101}""").contains("at most"))
    }

    @Test
    fun enumsAreClosedAndCaseNormalised() {
        assertTrue(rejected("open_settings", """{"page":"developer_options"}""").contains("one of"))
        assertTrue(rejected("search_web", """{"query":"x","site":"evil.example.com"}""").contains("one of"))
        assertEquals("wifi", ok("open_settings", """{"page":"WiFi"}""".replace("WiFi", "WIFI")).args.string("page"))
    }

    @Test
    fun rejectsEmptyOverlongAndHiddenCharacters() {
        assertTrue(rejected("open_app", """{"app_name":"   "}""").contains("empty"))
        assertTrue(rejected("open_app", """{"app_name":"${"a".repeat(61)}"}""").contains("longer"))
        assertTrue(rejected("send_text", """{"recipient":"Mom","message":"hi\nthere"}""").contains("not allowed"))
        assertTrue(rejected("open_app", "{\"app_name\":\"Spo\\u200Btify\"}").contains("not allowed"))
        assertTrue(rejected("open_app", "{\"app_name\":\"Spot\\u202Eify\"}").contains("not allowed"))
        assertTrue(rejected("open_app", "{\"app_name\":\"x\\u0000y\"}").contains("not allowed"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("Spotify", ok("open_app", """{"app_name":"  Spotify  "}""").args.string("app_name"))
    }

    @Test
    fun timerCrossChecks() {
        assertTrue(rejected("set_timer", "{}").contains("length of time"))
        assertTrue(rejected("set_timer", """{"minutes":0}""").contains("length of time"))
        assertTrue(rejected("set_timer", """{"hours":24,"minutes":1}""").contains("24 hours"))
        ok("set_timer", """{"hours":1,"minutes":30}""")
        ok("set_timer", """{"seconds":90}""")
    }

    @Test
    fun volumeCrossChecks() {
        assertTrue(rejected("change_volume", """{"action":"set"}""").contains("percentage"))
        assertTrue(rejected("change_volume", """{"action":"up","level":50}""").contains("only goes with set"))
        ok("change_volume", """{"action":"set","level":40}""")
        ok("change_volume", """{"action":"mute"}""")
    }

    @Test
    fun dialNumberRefusesServiceCodes() {
        for (bad in listOf("*#06#", "*21*5551234#", "#31#5551234", "555-CALL-NOW", "12", "+", "1234567890123456")) {
            assertTrue(bad, check("dial_number", """{"number":"$bad"}""") is ValidationResult.Rejected)
        }
    }

    @Test
    fun searchPhoneNeedsQueryUnlessListingApps() {
        ok("search_phone", """{"kind":"apps"}""")
        assertTrue(rejected("search_phone", """{"kind":"contacts"}""").contains("needs something"))
        ok("search_phone", """{"kind":"files","query":"resume"}""")
    }

    @Test
    fun everyToolHasAShortDescriptionAndValidSchema() {
        val arr = (Json.parse(reg.toNeedleToolsJson(both)) as JsonValue.Arr).items.map { it as JsonValue.Obj }
        for (t in arr) {
            val d = t.string("description")!!
            assertTrue(t.string("name"), d.length in 20..200)
        }
        assertEquals(17, arr.size)
    }
}
