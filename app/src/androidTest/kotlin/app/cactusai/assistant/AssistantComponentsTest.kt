package app.cactusai.assistant

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.cactusai.assistant.a11y.AccessibilityStatus
import app.cactusai.assistant.a11y.CactusAccessibilityService
import app.cactusai.assistant.assistant.CactusRecognitionService
import app.cactusai.assistant.assistant.CactusVoiceInteractionService
import app.cactusai.assistant.assistant.CactusVoiceInteractionSessionService
import app.cactusai.assistant.assistant.Phase
import app.cactusai.assistant.assistant.PermissionNeed
import app.cactusai.assistant.system.Permissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Assistant, accessibility and lifecycle checks that need an Android runtime but not the AI.
 * What these cannot cover (the real assistant gesture, enabling accessibility, revoking permissions
 * while running) is in docs/DEVICE_TEST_CHECKLIST.md.
 */
@RunWith(AndroidJUnit4::class)
class AssistantComponentsTest {
    private val context get() = TestSupport.context
    private val pm get() = context.packageManager
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun voiceInteractionServiceIsRegisteredWithTheRequiredPermission() {
        val info = pm.getServiceInfo(ComponentName(context, CactusVoiceInteractionService::class.java), PackageManager.GET_META_DATA)
        assertEquals("android.permission.BIND_VOICE_INTERACTION", info.permission)
        assertNotNull(info.metaData?.get("android.voice_interaction"))
        val sessionInfo = pm.getServiceInfo(ComponentName(context, CactusVoiceInteractionSessionService::class.java), 0)
        assertEquals("android.permission.BIND_VOICE_INTERACTION", sessionInfo.permission)
        assertNotNull(pm.getServiceInfo(ComponentName(context, CactusRecognitionService::class.java), 0))
    }

    @Test
    fun theSystemCanFindUsAsAVoiceInteractionService() {
        val found = pm.queryIntentServices(Intent("android.service.voice.VoiceInteractionService"), 0)
        assertTrue("Cactus AI should be listed as an assistant candidate", found.any { it.serviceInfo.packageName == context.packageName })
    }

    @Test
    fun accessibilityServiceIsDeclaredAndProtectedByTheSystemPermission() {
        val info = pm.getServiceInfo(ComponentName(context, CactusAccessibilityService::class.java), 0)
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", info.permission)
    }

    @Test
    fun accessibilityIsOffUntilTheUserTurnsItOn() {
        assumeFalse("the tester already switched the service on", AccessibilityStatus.isEnabledInSettings(context))
        assertTrue(!AccessibilityStatus.isConnected)
    }

    @Test
    fun theAppRequestsOnlyThePermissionsItNeeds() {
        val requested = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        val expected = setOf(
            Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS, "com.android.alarm.permission.SET_ALARM",
        )
        assertTrue("unexpected permissions: ${requested - expected}", requested.all { it in expected || it.startsWith("app.cactusai.assistant.DYNAMIC_RECEIVER") })
        for (never in listOf(Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, "android.permission.QUERY_ALL_PACKAGES", "android.permission.READ_EXTERNAL_STORAGE")) {
            assertTrue("$never must not be requested", never !in requested)
        }
    }

    @Test
    fun withoutMicrophonePermissionListeningAsksInsteadOfRecording() {
        assumeFalse("microphone already allowed on this phone", Permissions.hasMicrophone(context))
        val controller = TestSupport.container.controller
        controller.clear()
        controller.startListening()
        assertEquals(PermissionNeed.MICROPHONE, controller.state.value.permission)
        assertTrue(controller.state.value.phase != Phase.LISTENING)
    }

    @Test
    fun deniedPermissionsAreExplainedNotSilent() {
        val controller = TestSupport.container.controller
        controller.permissionDenied(PermissionNeed.MICROPHONE)
        assertTrue(controller.state.value.response.contains("type commands"))
        controller.permissionDenied(PermissionNeed.CONTACTS)
        assertTrue(controller.state.value.response.contains("dial a number"))
        controller.clear()
    }

    @Test
    fun theAppSurvivesBackgroundingAndRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.text("Cactus AI")), 8_000))
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED) // as if another app took the screen
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.recreate() // as if the phone was rotated
            assertTrue(device.wait(Until.hasObject(By.text("Cactus AI")), 8_000))
        }
    }

    @Test
    fun theFiveTabsAreReachable() {
        ActivityScenario.launch(MainActivity::class.java).use {
            for (tab in listOf("Command", "History", "Models", "Settings", "Home")) {
                val button = device.wait(Until.findObject(By.text(tab)), 8_000)
                assertNotNull("tab $tab", button)
                button.click()
            }
        }
    }
}
