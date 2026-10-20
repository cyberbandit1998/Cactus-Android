package app.cactusai.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.cactusai.assistant.actions.AndroidActionExecutor
import app.cactusai.assistant.actions.AppCatalog
import app.cactusai.assistant.actions.ContactsLookup
import app.cactusai.assistant.actions.DeviceActions
import app.cactusai.assistant.actions.FileSearch
import app.cactusai.assistant.system.NetworkMonitor
import app.cactusai.core.json.Json
import app.cactusai.core.plan.Hint
import app.cactusai.core.steps.StepOutcome
import app.cactusai.core.tools.AssistantTools
import app.cactusai.core.tools.ToolGroup
import app.cactusai.core.tools.ValidationResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Android side, with no AI involved: validated tool calls go through the real executor on a real
 * device or emulator. Each test sends the phone back to the home screen afterwards.
 */
@RunWith(AndroidJUnit4::class)
class AndroidActionsTest {
    private val context get() = TestSupport.context
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var executor: AndroidActionExecutor

    @Before
    fun setUp() {
        val c = TestSupport.container
        executor = AndroidActionExecutor(context, AppCatalog(context), ContactsLookup(context), DeviceActions(context), FileSearch(context), NetworkMonitor(context), c.settings)
    }

    @After
    fun home() {
        device.pressHome()
    }

    private fun call(tool: String, args: String) =
        (AssistantTools.registry.validate(tool, Json.parse(args), setOf(ToolGroup.PHONE, ToolGroup.SCREEN)) as ValidationResult.Ok).call

    private fun run(tool: String, args: String): StepOutcome = runBlocking { executor.execute(call(tool, args)) }

    @Test
    fun opensAndroidSettingsPages() {
        for (page in listOf("wifi", "bluetooth", "display", "battery", "main")) {
            val outcome = run("open_settings", """{"page":"$page"}""")
            assertTrue("$page: $outcome", outcome is StepOutcome.Success)
            device.pressHome()
        }
    }

    @Test
    fun opensAnInstalledAppByNameOrRole() {
        val byRole = run("open_app", """{"app_name":"settings"}""")
        assertTrue(byRole.toString(), byRole is StepOutcome.Success)
        device.pressHome()
        val installed = AppCatalog(context).launchable()
        assertTrue("this phone should have launchable apps", installed.isNotEmpty())
        val some = installed.first()
        val outcome = run("open_app", """{"app_name":"${some.label.replace("\"", "")}"}""")
        assertTrue("${some.label}: $outcome", outcome is StepOutcome.Success || outcome is StepOutcome.NeedsChoice)
    }

    @Test
    fun missingAppsAreReportedNotCrashed() {
        val outcome = run("open_app", """{"app_name":"Zzyzx Nonexistent Application"}""")
        assertTrue(outcome.toString(), outcome is StepOutcome.Unavailable)
    }

    @Test
    fun launchesABrowserSearchWhenOnline() {
        val outcome = run("search_web", """{"query":"3d printing ideas"}""")
        if (NetworkMonitor(context).isOnline()) {
            assertTrue(outcome.toString(), outcome is StepOutcome.Success)
        } else {
            assertTrue("offline searches must say so: $outcome", outcome is StepOutcome.Unavailable)
        }
    }

    @Test
    fun asksTheClockAppForTimersAndAlarms() {
        for (outcome in listOf(run("set_timer", """{"minutes":15}"""), run("set_alarm", """{"hour":7,"minute":30}"""))) {
            // An emulator image without a Clock app answers Unavailable; a phone answers Success.
            assertTrue(outcome.toString(), outcome is StepOutcome.Success || outcome is StepOutcome.Unavailable)
            device.pressHome()
        }
    }

    @Test
    fun dialerAndMessagesAreOnlyPrefilled() {
        val dial = run("dial_number", """{"number":"555-123-4567"}""")
        assertTrue(dial.toString(), dial is StepOutcome.Success)
        assertTrue((dial as StepOutcome.Success).message.contains("did not call"))
        device.pressHome()
        val text = run("send_text", """{"recipient":"555-123-4567","message":"I'm on my way"}""")
        assertTrue(text.toString(), text is StepOutcome.Success || text is StepOutcome.Unavailable)
        if (text is StepOutcome.Success) assertTrue(text.message.contains("nothing was sent"))
    }

    @Test
    fun batteryAndVolume() {
        val battery = run("check_battery", "{}")
        assertTrue((battery as StepOutcome.Success).message.contains("%"))
        val volume = run("change_volume", """{"action":"set","level":30}""")
        assertTrue(volume.toString(), volume is StepOutcome.Success || volume is StepOutcome.Failure)
    }

    @Test
    fun listsInstalledApps() {
        val outcome = run("search_phone", """{"kind":"apps"}""") as StepOutcome.Success
        assertTrue(outcome.results.isNotEmpty())
    }

    @Test
    fun screenToolsSayAccessibilityIsOffUnlessItIsOn() {
        val outcome = run("scroll", """{"direction":"down"}""")
        if (outcome is StepOutcome.Unavailable) {
            assertEquals(Hint.ENABLE_SCREEN_CONTROL, outcome.hint)
        } else {
            assertNotNull("the accessibility service is on, so scrolling answers", outcome)
        }
    }

    @Test
    fun unsupportedAndUnsafeCallsNeverReachTheExecutor() {
        for ((tool, args) in listOf("run_shell" to """{"cmd":"id"}""", "open_app" to """{"app_name":"x","intent":"android.intent.action.DELETE"}""", "dial_number" to """{"number":"*#06#"}""")) {
            val result = AssistantTools.registry.validate(tool, Json.parse(args), setOf(ToolGroup.PHONE))
            assertTrue("$tool should be rejected", result is ValidationResult.Rejected)
        }
    }
}
