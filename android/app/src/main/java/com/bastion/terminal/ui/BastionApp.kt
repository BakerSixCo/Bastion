package com.bastion.terminal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

private enum class Tab { HOME, ASK, LIBRARY }

@Composable
fun BastionApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var readerDocId by rememberSaveable { mutableLongStateOf(-1L) }
    var readerQuery by rememberSaveable { mutableStateOf("") }

    if (readerDocId >= 0) {
        BackHandler { readerDocId = -1L }
        ReaderScreen(
            docId = readerDocId,
            query = readerQuery,
            onClose = { readerDocId = -1L },
        )
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
            Tab.ASK -> AskScreen(Modifier.padding(pad), onOpen = open)
            Tab.LIBRARY -> LibraryScreen(Modifier.padding(pad), onOpen = open)
        }
    }
}
