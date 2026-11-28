package com.bastion.terminal.ui

import androidx.activity.compose.BackHandler
import android.content.res.Configuration
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.bastion.terminal.kiwix.ZimLibrary

private enum class Tab { HOME, ASK, MAP, MESH, LIBRARY }

@Composable
fun BastionApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var readerDocId by rememberSaveable { mutableLongStateOf(-1L) }
    var readerQuery by rememberSaveable { mutableStateOf("") }
    var readerZimPath by rememberSaveable { mutableStateOf<String?>(null) }
    var readerZimFile by rememberSaveable { mutableStateOf("") }
    var emergency by rememberSaveable { mutableStateOf(false) }

    // Open every ZIM in BASTION's reference folders (phone storage and SD card).
    val ctx = LocalContext.current
    // Turn-by-turn keeps the screen on whichever tab is open.
    val routeSession = androidx.compose.runtime.remember { RouteSession.get(ctx) }
    val rootView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(routeSession.navigating) {
        rootView.keepScreenOn = routeSession.navigating
        onDispose { }
    }
    // Then add the kit's documents (the docs folder, and PDFs inside document bundles) to the library.
    LaunchedEffect(Unit) {
        ZimLibrary.get(ctx).restore()
        com.bastion.terminal.data.LibraryStore.get(ctx).importKit(ZimLibrary.get(ctx))
    }
    // Always-on service (if enabled): keeps the mesh inbox and auto-answer alive in the background.
    LaunchedEffect(Unit) { com.bastion.terminal.BastionService.sync(ctx) }
    // Make sure the mesh inbox reader is actually connected (Android can leave it unbound).
    LaunchedEffect(Unit) { com.bastion.terminal.mesh.MeshInbox.reconnect(ctx) }

    // App PIN, if one is set. The wipe PIN is typed in the same box.
    var locked by androidx.compose.runtime.remember {
        mutableStateOf(com.bastion.terminal.security.AppLock.needsPin(ctx))
    }
    if (locked) {
        Surface(color = MaterialTheme.colorScheme.background) { LockScreen(onOpen = { locked = false }) }
        return
    }

    if (emergency) {
        // Clear of the status and gesture bars (Android 15 draws apps edge to edge), so the TEST banner and
        // the bottom buttons are never under them.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            androidx.compose.foundation.layout.Box(Modifier.safeDrawingPadding()) { EmergencyScreen(onClose = { emergency = false }) }
        }
        return
    }

    readerZimPath?.let { path ->
        BackHandler { readerZimPath = null }
        Surface(color = MaterialTheme.colorScheme.background) {
            ZimReaderScreen(zimFile = readerZimFile, path = path, query = readerQuery, onClose = { readerZimPath = null })
        }
        return
    }

    if (readerDocId >= 0) {
        BackHandler { readerDocId = -1L }
        // The reader sits outside the Scaffold, so it needs its own Surface to
        // supply the theme's content color; without one, text falls back to
        // black on the dark window background (found on emulator 2026-11-21).
        Surface(color = MaterialTheme.colorScheme.background) {
            ReaderScreen(
                docId = readerDocId,
                query = readerQuery,
                onClose = { readerDocId = -1L },
            )
        }
        return
    }

    val open: (Long, String) -> Unit = { id, q ->
        readerDocId = id
        readerQuery = q
    }

    val items = listOf(
        Triple(Tab.HOME, "Home", Icons.Filled.Home),
        Triple(Tab.ASK, "Ask", Icons.Filled.Search),
        Triple(Tab.MAP, "Map", Icons.Filled.Place),
        Triple(Tab.MESH, "Mesh", Icons.Filled.Email),
        Triple(Tab.LIBRARY, "Library", Icons.Filled.List),
    )
    // Sideways there is little height to spare, so the tabs move to the left edge.
    val sideways = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        bottomBar = {
            if (!sideways) NavigationBar {
                items.forEach { (t, name, icon) ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(icon, contentDescription = name) },
                        label = { Text(name) },
                    )
                }
            }
        }
    ) { pad ->
        Row(Modifier.padding(pad)) {
            if (sideways) NavigationRail {
                items.forEach { (t, name, icon) ->
                    NavigationRailItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(icon, contentDescription = name) },
                        label = { Text(name) },
                    )
                }
            }
            val page = Modifier.weight(1f).fillMaxHeight()
            when (tab) {
                Tab.HOME -> HomeScreen(page, onEmergency = { emergency = true })
                Tab.ASK -> AskScreen(
                    page,
                    onOpen = open,
                    onOpenZim = { file, path, q ->
                        readerZimFile = file
                        readerZimPath = path
                        readerQuery = q
                    },
                )
                Tab.MAP -> MapScreen(page)
                Tab.MESH -> MeshScreen(
                    page,
                    onAsk = { text ->
                        com.bastion.terminal.ai.AskSession.get(ctx).query = text
                        tab = Tab.ASK
                    },
                )
                Tab.LIBRARY -> LibraryScreen(page, onOpen = open)
            }
        }
    }
}
