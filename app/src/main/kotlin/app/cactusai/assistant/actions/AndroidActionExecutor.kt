package app.cactusai.assistant.actions

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import app.cactusai.assistant.a11y.ScreenControl
import app.cactusai.assistant.a11y.ScreenResult
import app.cactusai.assistant.settings.AppSettings
import app.cactusai.assistant.system.NetworkMonitor
import app.cactusai.assistant.system.Permissions
import app.cactusai.core.match.AppRole
import app.cactusai.core.match.AppRoles
import app.cactusai.core.match.Candidate
import app.cactusai.core.match.MatchResult
import app.cactusai.core.match.NameMatcher
import app.cactusai.core.phone.PhoneNumbers
import app.cactusai.core.plan.Hint
import app.cactusai.core.steps.Choice
import app.cactusai.core.steps.ResultAction
import app.cactusai.core.steps.ResultItem
import app.cactusai.core.steps.StepExecutor
import app.cactusai.core.steps.StepOutcome
import app.cactusai.core.tools.AssistantTools
import app.cactusai.core.tools.ValidatedCall
import app.cactusai.core.web.SearchSite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs validated tool calls through ordinary Android APIs: PackageManager launch intents, framework
 * Settings intents, AlarmClock intents, ACTION_DIAL, ACTION_SENDTO, AudioManager and the Accessibility
 * service. It never places a call, never sends a message, never reads other apps' data and never runs
 * anything the model wrote: the model only chooses among the registered tools and their typed arguments.
 */
class AndroidActionExecutor(
    private val context: Context,
    private val apps: AppCatalog,
    private val contacts: ContactsLookup,
    private val device: DeviceActions,
    private val files: FileSearch,
    private val network: NetworkMonitor,
    private val settings: AppSettings,
) : StepExecutor {

    override suspend fun execute(call: ValidatedCall): StepOutcome = try {
        val a = call.args
        when (call.name) {
            AssistantTools.OPEN_APP -> openApp(a.string("app_name").orEmpty())
            AssistantTools.SEARCH_WEB -> searchWeb(a.string("query").orEmpty(), SearchSite.fromKey(a.string("site")))
            AssistantTools.OPEN_SETTINGS -> openSettings(a.string("page").orEmpty())
            AssistantTools.SET_TIMER -> setTimer((a.long("hours") ?: 0) * 3600 + (a.long("minutes") ?: 0) * 60 + (a.long("seconds") ?: 0))
            AssistantTools.SET_ALARM -> setAlarm(a.int("hour") ?: 0, a.int("minute") ?: 0, a.string("label"))
            AssistantTools.CALL_CONTACT -> callContact(a.string("name").orEmpty())
            AssistantTools.DIAL_NUMBER -> dialNumber(a.string("number"))
            AssistantTools.SEND_TEXT -> sendText(a.string("recipient").orEmpty(), a.string("message").orEmpty())
            AssistantTools.CHECK_BATTERY -> StepOutcome.Success(device.batteryStatus())
            AssistantTools.CHANGE_VOLUME -> StepOutcome.Success(device.changeVolume(a.string("action").orEmpty(), a.int("level")))
            AssistantTools.SEARCH_PHONE -> searchPhone(a.string("kind").orEmpty(), a.string("query"))
            AssistantTools.SCROLL -> screen { it.scroll(a.string("direction") == "down") }
            AssistantTools.NAVIGATE -> screen { it.navigate(a.string("destination").orEmpty()) }
            AssistantTools.TAP_TEXT -> screen { it.tapText(a.string("text").orEmpty()) }
            AssistantTools.TAP_RESULT -> screen { it.tapResult(a.int("position") ?: 1) }
            AssistantTools.TYPE_TEXT -> screen { it.typeText(a.string("text").orEmpty()) }
            AssistantTools.FIND_ON_SCREEN -> screen { it.findOnScreen(a.string("text").orEmpty()) }
            else -> StepOutcome.Failure("I do not know how to do ${call.name}.")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ActivityNotFoundException) {
        StepOutcome.Unavailable("No app on this phone can do that.")
    } catch (e: SecurityException) {
        StepOutcome.Failure("Android did not allow that action (${e.message}).")
    } catch (e: RuntimeException) {
        StepOutcome.Failure("That did not work: ${e.message ?: e::class.java.simpleName}")
    }

    /** Runs a tapped result row (an app, a number, a file). The row's action was built by this app, not by the model. */
    fun perform(action: ResultAction): String? = try {
        when (action) {
            is ResultAction.LaunchPackage -> {
                val intent = apps.launchIntent(action.packageName)
                if (intent == null) "That app can no longer be opened." else { context.startActivity(intent); null }
            }
            is ResultAction.DialNumber -> { start(dialIntent(action.number)); null }
            is ResultAction.OpenUri -> {
                val uri = Uri.parse(action.uri)
                val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(intent)
                null
            }
            ResultAction.None -> null
        }
    } catch (e: ActivityNotFoundException) {
        "No app on this phone can open that."
    } catch (e: SecurityException) {
        "Android did not allow that (${e.message})."
    }

    // ---- apps ----

    private suspend fun openApp(name: String): StepOutcome {
        AppRoles.roleFor(name)?.let { role -> if (launchRole(role)) return StepOutcome.Success("Opened ${roleLabel(role)}.") }

        val installed = withContext(Dispatchers.IO) { apps.launchable() }
        return when (val match = NameMatcher.find(name, installed.map { Candidate(it, it.label) })) {
            is MatchResult.Single -> launchPackage(match.match.candidate.item)
            is MatchResult.Ambiguous -> StepOutcome.NeedsChoice(
                "More than one app matches “$name”. Which one?",
                match.matches.map { Choice(it.candidate.item.packageName, it.candidate.item.label, it.candidate.item.packageName) },
            ) { choice ->
                installed.firstOrNull { it.packageName == choice.id }?.let { launchPackage(it) } ?: StepOutcome.Failure("That app is no longer installed.")
            }
            MatchResult.None -> {
                val site = SearchSite.fromSpokenName(name)
                when {
                    site == null -> StepOutcome.Unavailable("I could not find an app called “$name”. Say “show my installed apps” to see what is on this phone.")
                    !network.isOnline() -> StepOutcome.Unavailable("${site.label} is not installed and you are offline, so I cannot open its website.")
                    else -> {
                        start(Intent(Intent.ACTION_VIEW, Uri.parse(site.homeUrl)))
                        StepOutcome.Success("${site.label} is not installed, so I opened its website.")
                    }
                }
            }
        }
    }

    private fun launchPackage(app: InstalledApp): StepOutcome {
        val intent = apps.launchIntent(app.packageName) ?: return StepOutcome.Failure("${app.label} cannot be opened.")
        context.startActivity(intent)
        return StepOutcome.Success("Opened ${app.label}.")
    }

    private fun launchRole(role: AppRole): Boolean {
        val intent = when (role) {
            AppRole.CAMERA -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            AppRole.MESSAGING -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)
            AppRole.MAPS -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS)
            AppRole.CALCULATOR -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
            AppRole.BROWSER -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
            AppRole.CONTACTS -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS)
            AppRole.EMAIL -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL)
            AppRole.GALLERY -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
            AppRole.MUSIC -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
            AppRole.CALENDAR -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
            AppRole.CLOCK -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            AppRole.PHONE -> Intent(Intent.ACTION_DIAL)
            AppRole.SETTINGS -> Intent(Settings.ACTION_SETTINGS)
        }
        return try {
            start(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun roleLabel(role: AppRole): String = when (role) {
        AppRole.CAMERA -> "the camera"
        AppRole.MESSAGING -> "your messages"
        AppRole.MAPS -> "maps"
        AppRole.CLOCK -> "the clock"
        AppRole.CALCULATOR -> "the calculator"
        AppRole.BROWSER -> "the browser"
        AppRole.CONTACTS -> "your contacts"
        AppRole.EMAIL -> "your email"
        AppRole.GALLERY -> "your photos"
        AppRole.MUSIC -> "the music player"
        AppRole.CALENDAR -> "the calendar"
        AppRole.PHONE -> "the phone app"
        AppRole.SETTINGS -> "settings"
    }

    // ---- web and settings ----

    private fun searchWeb(query: String, site: SearchSite): StepOutcome {
        if (!network.isOnline()) {
            return StepOutcome.Unavailable("You are offline. Searching ${site.label} needs an internet connection; understanding your command still works without one.")
        }
        start(Intent(Intent.ACTION_VIEW, Uri.parse(site.searchUrl(query))))
        return StepOutcome.Success("Searching ${site.label} for “$query”.")
    }

    private fun openSettings(page: String): StepOutcome {
        return try {
            start(SettingsPages.intentFor(page))
            StepOutcome.Success("Opened ${SettingsPages.label(page)}.")
        } catch (_: ActivityNotFoundException) {
            start(SettingsPages.intentFor("main"))
            StepOutcome.Success("This phone has no separate ${SettingsPages.label(page)} screen, so I opened Android settings.")
        }
    }

    // ---- clock ----

    private fun setTimer(totalSeconds: Long): StepOutcome {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, totalSeconds.toInt())
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        return try {
            start(intent)
            StepOutcome.Success("Asked the Clock app for a ${describeDuration(totalSeconds)} timer. Check the Clock app to see it running.")
        } catch (_: ActivityNotFoundException) {
            StepOutcome.Unavailable("There is no Clock app on this phone that can set timers.")
        }
    }

    private fun setAlarm(hour: Int, minute: Int, label: String?): StepOutcome {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        if (label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        val shown = String.format(java.util.Locale.US, "%d:%02d %s", if (hour % 12 == 0) 12 else hour % 12, minute, if (hour < 12) "AM" else "PM")
        return try {
            start(intent)
            StepOutcome.Success("Asked the Clock app to create the $shown alarm. Confirm it there.")
        } catch (_: ActivityNotFoundException) {
            StepOutcome.Unavailable("There is no Clock app on this phone that can create alarms.")
        }
    }

    private fun describeDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = totalSeconds % 3600 / 60
        val s = totalSeconds % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it hour${if (it == 1L) "" else "s"}" },
            m.takeIf { it > 0 }?.let { "$it minute${if (it == 1L) "" else "s"}" },
            s.takeIf { it > 0 }?.let { "$it second${if (it == 1L) "" else "s"}" },
        ).joinToString(" ")
    }

    // ---- calls and texts: only ever a prefilled dialer or message screen ----

    private suspend fun callContact(name: String): StepOutcome =
        withContactNumber(name) { number, shown ->
            start(dialIntent(number))
            StepOutcome.Success("Opened the dialer for $shown ($number). Press the call button there to place the call; I did not call.")
        }

    private fun dialNumber(raw: String?): StepOutcome {
        if (raw == null) {
            start(Intent(Intent.ACTION_DIAL))
            return StepOutcome.Success("Opened the dialer.")
        }
        val number = PhoneNumbers.sanitize(raw) ?: return StepOutcome.Failure("That is not a phone number I can dial.")
        start(dialIntent(number))
        return StepOutcome.Success("Opened the dialer with $number. Press the call button there to place the call; I did not call.")
    }

    private suspend fun sendText(recipient: String, message: String): StepOutcome {
        fun compose(number: String, shown: String): StepOutcome {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).putExtra("sms_body", message)
            start(intent)
            return StepOutcome.Success("Opened your messaging app with the text for $shown. Press send there to send it; nothing was sent.")
        }
        val direct = PhoneNumbers.sanitize(recipient)
        if (direct != null) return compose(direct, direct)
        return withContactNumber(recipient) { number, shown -> compose(number, shown) }
    }

    private fun dialIntent(number: String): Intent {
        val clean = PhoneNumbers.sanitize(number) ?: number.filter { it in '0'..'9' || it == '+' }
        return Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", clean, null))
    }

    /** Finds a contact and one of their numbers, asking the user whenever it is not clear-cut. */
    private suspend fun withContactNumber(name: String, onNumber: suspend (number: String, shown: String) -> StepOutcome): StepOutcome {
        if (!Permissions.hasContacts(context)) {
            return StepOutcome.NeedsPermission("I need permission to read your contacts to find “$name”.", Manifest.permission.READ_CONTACTS)
        }
        val entries = withContext(Dispatchers.IO) { contacts.load() }
        val byContact = entries.groupBy { it.contactId }
        val candidates = byContact.values.map { Candidate(it, it.first().name) }

        suspend fun pickNumber(list: List<PhoneEntry>): StepOutcome {
            val shown = list.first().name
            val distinct = list.distinctBy { it.number.filter(Char::isDigit) }
            if (distinct.size == 1) return onNumber(distinct.first().number, shown)
            return StepOutcome.NeedsChoice(
                "$shown has more than one number. Which one?",
                distinct.map { Choice(it.number, it.typeLabel, it.number) },
            ) { choice -> onNumber(choice.id, shown) }
        }

        return when (val match = NameMatcher.find(name, candidates)) {
            MatchResult.None -> StepOutcome.Unavailable("I could not find “$name” in your contacts.")
            is MatchResult.Single -> pickNumber(match.match.candidate.item)
            is MatchResult.Ambiguous -> StepOutcome.NeedsChoice(
                "More than one contact matches “$name”. Which one?",
                match.matches.map { Choice(it.candidate.item.first().contactId.toString(), it.candidate.label, it.candidate.item.first().number) },
            ) { choice ->
                byContact[choice.id.toLongOrNull()]?.let { pickNumber(it) } ?: StepOutcome.Failure("That contact is no longer available.")
            }
        }
    }

    // ---- searching the phone ----

    private suspend fun searchPhone(kind: String, query: String?): StepOutcome = when (kind) {
        "apps" -> {
            val installed = withContext(Dispatchers.IO) { apps.launchable() }
            val shown = if (query == null) {
                installed
            } else {
                val q = NameMatcher.cleanQuery(query)
                installed.map { it to NameMatcher.score(q, it.label) }.filter { it.second >= NameMatcher.MIN_SCORE }.sortedByDescending { it.second }.map { it.first }
            }
            val rows = shown.map { ResultItem(it.label, it.packageName, ResultAction.LaunchPackage(it.packageName)) }
            StepOutcome.Success(
                when {
                    query == null -> "You have ${rows.size} apps. Tap one to open it."
                    rows.isEmpty() -> "No installed app matches “$query”."
                    else -> "Found ${rows.size} app${if (rows.size == 1) "" else "s"} matching “$query”. Tap one to open it."
                },
                rows,
            )
        }
        "contacts" -> if (!Permissions.hasContacts(context)) {
            StepOutcome.NeedsPermission("I need permission to read your contacts to search them.", Manifest.permission.READ_CONTACTS)
        } else {
            val q = NameMatcher.cleanQuery(query.orEmpty())
            val rows = withContext(Dispatchers.IO) { contacts.load() }
                .filter { NameMatcher.score(q, it.name) >= NameMatcher.MIN_SCORE }
                .take(10)
                .map { ResultItem(it.name, "${it.typeLabel}: ${it.number}", ResultAction.DialNumber(it.number)) }
            StepOutcome.Success(if (rows.isEmpty()) "No contact matches “$query”." else "Found ${rows.size} match${if (rows.size == 1) "" else "es"}. Tap a number to open the dialer.", rows)
        }
        "files" -> {
            val folders = settings.current.folderUris
            if (folders.isEmpty()) {
                StepOutcome.Unavailable("Android does not let apps search every file on the phone. Choose a folder under Settings → Files, then ask again.")
            } else {
                val hits = withContext(Dispatchers.IO) { files.search(folders, query.orEmpty()) }
                StepOutcome.Success(
                    if (hits.isEmpty()) "No file named like “$query” in the folders you shared." else "Found ${hits.size} file${if (hits.size == 1) "" else "s"} in the folders you shared.",
                    hits.map { ResultItem(it.name, null, ResultAction.OpenUri(it.uri.toString())) },
                )
            }
        }
        else -> StepOutcome.Failure("I cannot search for $kind.")
    }

    // ---- screen control (Accessibility service, enabled by the user in Android settings) ----

    private suspend fun screen(block: suspend (app.cactusai.assistant.a11y.ScreenController) -> ScreenResult): StepOutcome {
        val controller = ScreenControl.controller
            ?: return StepOutcome.Unavailable("Screen control is off. Turn on Cactus AI under Android's Accessibility settings to use it.", Hint.ENABLE_SCREEN_CONTROL)
        return mapScreen(block(controller))
    }

    private fun mapScreen(result: ScreenResult): StepOutcome = when (result) {
        is ScreenResult.Done -> StepOutcome.Success(result.message)
        is ScreenResult.NotFound -> StepOutcome.Failure(result.message)
        is ScreenResult.Blocked -> StepOutcome.Failure(result.message)
        is ScreenResult.Ambiguous -> StepOutcome.NeedsChoice(result.message, result.options.mapIndexed { i, label -> Choice(i.toString(), label) }) { choice ->
            mapScreen(result.pick(choice.id.toIntOrNull() ?: 0))
        }
        is ScreenResult.NeedsApproval -> StepOutcome.NeedsApproval(result.message) { mapScreen(result.proceed()) }
    }

    private fun start(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
