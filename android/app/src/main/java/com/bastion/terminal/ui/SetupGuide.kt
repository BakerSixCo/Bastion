package com.bastion.terminal.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bastion.terminal.ai.AiState
import com.bastion.terminal.ai.LocalAi
import com.bastion.terminal.data.LibraryStore
import com.bastion.terminal.kiwix.ZimLibrary
import com.bastion.terminal.kiwix.ZimState
import com.bastion.terminal.mesh.MeshAutoResponder
import com.bastion.terminal.mesh.MeshBridge
import com.bastion.terminal.mesh.MeshInbox

/** Where each prerequisite stands, read live from the device. */
data class SetupStatus(
    val modelReady: Boolean,
    val zimCount: Int,
    val docCount: Int,
    val meshtasticInstalled: Boolean,
    val inboxOn: Boolean,
    val autoAnswerOn: Boolean,
    val atakInstalled: Boolean,
    val kiwixInstalled: Boolean,
    val mapsInstalled: Boolean,
    val deviceSecure: Boolean = false,
    val linkInstalled: Boolean = false,
) {
    /** The steps BASTION needs to be useful: AI model, something to answer from, mesh. */
    val coreDone: Int
        get() = listOf(modelReady, zimCount > 0 || docCount > 0, meshtasticInstalled, inboxOn).count { it }
    val coreTotal: Int get() = 4
}

private const val PKG_ATAK = "com.atakmap.app.civ"
private const val PKG_KIWIX = "org.kiwix.kiwixmobile"
private const val PKG_MAPS = "app.organicmaps"

private fun installed(ctx: Context, pkg: String) =
    runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

@Composable
fun rememberSetupStatus(): SetupStatus {
    val ctx = LocalContext.current
    val ai by remember { LocalAi.get(ctx) }.state.collectAsState()
    val zim by remember { ZimLibrary.get(ctx) }.state.collectAsState()
    val store = remember { LibraryStore.get(ctx) }
    val tick by store.changeCounter.collectAsState()
    var docs by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { docs = store.stats().first }
    // Installed apps and special access change outside BASTION: re-read on every return.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    return remember(ai, zim, docs, resumes) {
        SetupStatus(
            modelReady = ai is AiState.Ready || ai is AiState.Loaded || ai is AiState.Loading,
            zimCount = (zim as? ZimState.Ready)?.zims?.size ?: 0,
            docCount = docs,
            meshtasticInstalled = MeshBridge.isInstalled(ctx),
            inboxOn = MeshInbox.accessGranted(ctx),
            autoAnswerOn = MeshAutoResponder.isEnabled(ctx),
            atakInstalled = installed(ctx, PKG_ATAK),
            kiwixInstalled = installed(ctx, PKG_KIWIX),
            mapsInstalled = installed(ctx, PKG_MAPS),
            linkInstalled = com.bastion.terminal.link.LinkClient.get(ctx).isSetUp(),
            deviceSecure = (ctx.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isDeviceSecure == true,
        )
    }
}

/** Home card that opens the guide. */
@Composable
fun SetupCard(status: SetupStatus, onOpen: () -> Unit) {
    val done = status.coreDone == status.coreTotal
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                (if (done) "● " else "○ ") + "Setup guide",
                style = MaterialTheme.typography.titleMedium,
                color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (done) "The essentials are set up. Open the guide for the optional extras and how each piece works."
                else "${status.coreDone} of ${status.coreTotal} essentials ready. The guide walks through what to " +
                    "download and how to set each piece up.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onOpen) { Text(if (done) "Open guide" else "Continue setup") }
        }
    }
}

/**
 * Step-by-step setup with live status. BASTION itself cannot download
 * anything (it never connects to the internet), so every download happens in another
 * app or on a PC and this page says exactly what to get and where it goes.
 */
@Composable
fun SetupGuide(status: SetupStatus, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val zimFolder = remember { ZimLibrary.get(ctx).folder().absolutePath }
    var note by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onClose)

    fun open(intent: Intent, failure: String) {
        note = null
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            note = failure
        }
    }
    fun store(pkg: String, name: String) = open(
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")),
        "No app store on this phone. Install $name another way, then come back.",
    )
    fun launch(pkg: String, name: String) {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) open(i, "Could not open $name.") else note = "$name is not installed."
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onClose) { Text("← Back") }
        Text("Setup guide", style = MaterialTheme.typography.headlineMedium)
        Text(
            "BASTION never connects to the internet, so it can't download these for you. Get each file with this phone's " +
                "browser or on a computer while you have internet — after that everything works with no signal.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        Text("Essentials", style = MaterialTheme.typography.titleMedium)
        Step(
            n = 1, title = "AI model", done = status.modelReady,
            state = if (status.modelReady) "A model is installed." else "No model yet — Ask can only show matching passages.",
            steps = listOf(
                "Download “gemma-4-E2B-it.litertlm” (2.6 GB, free, Apache-2.0) from huggingface.co/litert-community/gemma-4-E2B-it-litert-lm.",
                "If you downloaded it on a computer, copy it to this phone over USB (the Download folder is fine).",
                "On Home, under Local AI, tap “Import model file” and pick it. The copy takes a minute or two.",
                "Needs about 3 GB of free storage and a phone with 8 GB of RAM or more.",
            ),
        )
        Step(
            n = 2, title = "Something to answer from", done = status.zimCount > 0 || status.docCount > 0,
            state = "${status.zimCount} offline reference file(s), ${status.docCount} of your own document(s).",
            steps = listOf(
                "Offline Wikipedia: download a .zim file from library.kiwix.org. “WikiMed” (medicine, about 1 GB) is a good first one; “Wikipedia top 1M, no pictures” is about 16 GB.",
                "On Home, under Offline reference, tap “Add ZIM” and pick the file — or copy big files over USB into $zimFolder and tap Rescan.",
                "Your own plans: Library tab → “New pack” → pick what it is (trip, hunting plan, bug-out plan, team plan, contact list) and fill in the form. Or import files (.txt, .md, .pdf with real text, .epub).",
                "Each pack has two switches: “Use in Ask” and “Answer over the mesh”. Leave the second one off for anything teammates on the radio shouldn't be able to ask about.",
            ),
        )
        Step(
            n = 3, title = "Meshtastic app and radio", done = status.meshtasticInstalled,
            state = if (status.meshtasticInstalled) "Meshtastic is installed. BASTION can't see whether a radio is connected — check in Meshtastic."
            else "Meshtastic is not installed.",
            steps = listOf(
                "Install the Meshtastic app.",
                "Turn on your Meshtastic radio. In Meshtastic → Connection, pair it over Bluetooth (the PIN is on the radio's screen, or 123456 on screenless radios).",
                "Set your region when Meshtastic asks (United States = US). A radio transmits nothing until its region is set.",
                "Send a test message in Meshtastic to confirm the radio works before relying on BASTION.",
            ),
            actions = if (status.meshtasticInstalled) listOf("Open Meshtastic" to { launch(MeshBridge.MESHTASTIC_PACKAGE, "Meshtastic") })
            else listOf("Get Meshtastic" to { store(MeshBridge.MESHTASTIC_PACKAGE, "Meshtastic") }),
        )
        Step(
            n = 4, title = "Mesh inbox", done = status.inboxOn,
            state = if (status.inboxOn) "On — received messages appear in the Mesh tab." else "Off — BASTION can send to the mesh but can't show what arrives.",
            steps = listOf(
                "Tap the button below and switch on “BASTION mesh inbox”. BASTION then reads the Meshtastic app's message notifications — only Meshtastic's.",
                "In Meshtastic, allow notifications when it asks. If they're off or muted, BASTION sees nothing.",
            ),
            actions = listOf("Notification access settings" to {
                open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), "This phone has no notification-access screen.")
            }),
        )

        Text("Privacy", style = MaterialTheme.typography.titleMedium)
        Step(
            n = 5, title = "Private, encrypted mesh channel", done = false, optional = true,
            state = "BASTION can't check this — it sees a channel's name, not its key.",
            steps = listOf(
                "Meshtastic's default channel (LongFast) is encrypted with a key everyone knows. Anyone in range can read it.",
                "In Meshtastic → Settings → Channels, add a channel, give it your own name, and let the app generate a new key (256-bit).",
                "Share it with your group by showing them the channel QR code in person. Anyone who gets that code can read the channel, so don't post it.",
                "Use that channel for anything sensitive. Direct messages between two radios are encrypted separately.",
                "Radio traffic can still be detected and located even when it can't be read.",
            ),
            actions = if (status.meshtasticInstalled) listOf("Open Meshtastic" to { launch(MeshBridge.MESHTASTIC_PACKAGE, "Meshtastic") }) else emptyList(),
        )
        Step(
            n = 6, title = "Screen lock (encrypts this phone's storage)", done = status.deviceSecure,
            state = if (status.deviceSecure) "A screen lock is set. Android encrypts BASTION's library and mesh inbox while the phone is locked."
            else "No screen lock. Anyone who picks up this phone can read your library and mesh messages.",
            steps = listOf(
                "Set a PIN or password (not just a swipe). Android's storage encryption depends on it.",
                "BASTION's data is excluded from cloud backups and phone-to-phone transfers, and BASTION never connects to the internet — its only network link is to the Meshtastic app on this phone.",
                "Offline reference (.zim) files are public content kept in shared storage; they are not secret.",
            ),
            actions = listOf("Security settings" to { open(Intent(Settings.ACTION_SECURITY_SETTINGS), "Could not open security settings.") }),
        )
        Step(
            n = 7, title = "Quiet the other apps", done = false, optional = true,
            state = "BASTION can't check this.",
            steps = listOf(
                "Meshtastic collects usage analytics unless you turn it off: Meshtastic → Settings → turn off analytics.",
                "To keep message text off the lock screen: Android Settings → Notifications → hide sensitive content. BASTION's inbox still works.",
                "ATAK, Kiwix and Organic Maps are separate apps with their own permissions and network access.",
            ),
        )

        Text("Optional", style = MaterialTheme.typography.titleMedium)
        Step(
            n = 8, title = "Auto-answer for others on the mesh", done = status.autoAnswerOn, optional = true,
            state = if (status.autoAnswerOn) "On." else "Off.",
            steps = listOf(
                "Needs steps 1, 2, 4 and a private channel (step 5). Mesh tab → switch on “Auto-answer questions”.",
                "Anyone on your channel can then send “?how do I treat a burn” and BASTION replies by itself with one short AI answer.",
                "It only answers on private channels unless you also switch on “Also answer on public channels”.",
            ),
        )
        Step(
            n = 9, title = "ATAK (tactical map)", done = status.atakInstalled, optional = true,
            state = if (status.atakInstalled) "ATAK-CIV is installed." else "ATAK-CIV is not installed.",
            steps = listOf(
                "Install ATAK-CIV, open it once, and go through its license and permission screens yourself.",
                "No plugin is needed (the old Meshtastic ATAK plugin no longer works — don't install it). In Meshtastic → Settings → Advanced → TAK Server, switch on “Enable Local TAK Server” and “Mesh to CoT Converter”.",
                "Set “TAK Mesh Channel” to your private channel, not the public default.",
                "Tap “Export TAK Data Package” and save the .zip. Import it in BASTION (Mesh → Units and TAK), then tap “Save package for ATAK” there — ATAK ignores Meshtastic's file as exported.",
                "In ATAK: Import → Choose App → pick the file BASTION saved. Mesh nodes then appear on the ATAK map and chat is bridged.",
                "Markers and other map items need radio firmware 2.8.0 or newer; older firmware bridges location and chat only.",
                "BASTION opens ATAK but doesn't exchange data with it yet.",
            ),
            actions = if (status.atakInstalled) listOf("Open ATAK" to { launch(PKG_ATAK, "ATAK") })
            else listOf("Get ATAK-CIV" to { store(PKG_ATAK, "ATAK-CIV") }),
        )
        Step(
            n = 10, title = "Offline maps", done = status.mapsInstalled, optional = true,
            state = if (status.mapsInstalled) "Organic Maps is installed." else "Organic Maps is not installed.",
            steps = listOf(
                "Install Organic Maps and download the regions you'll travel through while you have internet.",
            ),
            actions = if (status.mapsInstalled) listOf("Open Organic Maps" to { launch(PKG_MAPS, "Organic Maps") })
            else listOf("Get Organic Maps" to { store(PKG_MAPS, "Organic Maps") }),
        )
        Step(
            n = 11, title = "Kiwix reader", done = status.kiwixInstalled, optional = true,
            state = if (status.kiwixInstalled) "Kiwix is installed." else "Kiwix is not installed.",
            steps = listOf(
                "Only needed if you want to browse Wikipedia with pictures and links. BASTION searches its own copy of the .zim files (step 2); files downloaded inside Kiwix are not shared with BASTION.",
            ),
            actions = if (status.kiwixInstalled) listOf("Open Kiwix" to { launch(PKG_KIWIX, "Kiwix") })
            else listOf("Get Kiwix" to { store(PKG_KIWIX, "Kiwix") }),
        )
        Step(
            n = 12, title = "Units and TAK chat inside BASTION", done = status.linkInstalled, optional = true,
            state = if (status.linkInstalled) "A TAK data package is imported. The Mesh tab shows whether it is connected." else "Not set up.",
            steps = listOf(
                "In Meshtastic → Settings → Advanced → TAK Server: switch on the server and “Mesh to CoT Converter”, set the TAK channel to your private channel, then “Export TAK Data Package” and save the .zip.",
                "In BASTION → Mesh → Units and TAK → “Import TAK data package”, pick that .zip. Units with positions and TAK chat then appear there.",
                "This is BASTION's only network connection and it stays inside this phone. Keep the phone in airplane mode in the field anyway.",
            ),
        )
        Text(
            "Before you rely on it: switch on airplane mode and ask BASTION a question. If it answers with sources, " +
                "you're set for no-signal use.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Step(
    n: Int,
    title: String,
    done: Boolean,
    state: String,
    steps: List<String>,
    optional: Boolean = false,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                (if (done) "✓ " else if (optional) "– " else "○ ") + "$n. $title",
                style = MaterialTheme.typography.titleSmall,
                color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            steps.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            if (actions.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { (label, onClick) -> OutlinedButton(onClick = onClick) { Text(label) } }
            }
        }
    }
}
