package com.bastion.terminal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
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

private enum class Tab { HOME, ASK, MESH, LIBRARY }

@Composable
fun BastionApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var readerDocId by rememberSaveable { mutableLongStateOf(-1L) }
    var readerQuery by rememberSaveable { mutableStateOf("") }
    var readerZimPath by rememberSaveable { mutableStateOf<String?>(null) }
    var readerZimFile by rememberSaveable { mutableStateOf("") }

    // Open every ZIM in BASTION's reference folders (phone storage and SD card).
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { ZimLibrary.get(ctx).restore() }
    // Always-on service (if enabled): keeps the mesh inbox and auto-answer alive in the background.
    LaunchedEffect(Unit) { com.bastion.terminal.BastionService.sync(ctx) }

    // App PIN, if one is set. The wipe PIN is typed in the same box.
    var locked by androidx.compose.runtime.remember {
        mutableStateOf(com.bastion.terminal.security.AppLock.needsPin(ctx))
    }
    if (locked) {
        Surface(color = MaterialTheme.colorScheme.background) { LockScreen(onOpen = { locked = false }) }
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

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.HOME,
                    onClick = { tab = Tab.HOME },
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                )
                NavigationBarItem(
                    selected = tab == Tab.ASK,
                    onClick = { tab = Tab.ASK },
                    icon = { Icon(Icons.Filled.Search, contentDescription = "Ask") },
                    label = { Text("Ask") },
                )
                NavigationBarItem(
                    selected = tab == Tab.MESH,
                    onClick = { tab = Tab.MESH },
                    icon = { Icon(Icons.Filled.Email, contentDescription = "Mesh") },
                    label = { Text("Mesh") },
                )
                NavigationBarItem(
                    selected = tab == Tab.LIBRARY,
                    onClick = { tab = Tab.LIBRARY },
                    icon = { Icon(Icons.Filled.List, contentDescription = "Library") },
                    label = { Text("Library") },
                )
            }
        }
    ) { pad ->
        when (tab) {
            Tab.HOME -> HomeScreen(Modifier.padding(pad))
            Tab.ASK -> AskScreen(
                Modifier.padding(pad),
                onOpen = open,
                onOpenZim = { file, path, q ->
                    readerZimFile = file
                    readerZimPath = path
                    readerQuery = q
                },
            )
            Tab.MESH -> MeshScreen(
                Modifier.padding(pad),
                onAsk = { text ->
                    com.bastion.terminal.ai.AskSession.get(ctx).query = text
                    tab = Tab.ASK
                },
            )
            Tab.LIBRARY -> LibraryScreen(Modifier.padding(pad), onOpen = open)
        }
    }
}
