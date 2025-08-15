package eu.syrou.androidexample.ui.scaffold

import PoegoIcons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import eu.syrou.androidexample.reaktiv.TestNavigationModule.TestNavigationAction
import eu.syrou.androidexample.reaktiv.settings.SettingsModule
import eu.syrou.androidexample.ui.screen.VideosListScreen
import eu.syrou.androidexample.ui.screen.home.NotificationScreen
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.extension.isInGraph
import io.github.syrou.reaktiv.navigation.extension.navigate
import io.github.syrou.reaktiv.navigation.extension.navigation
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeNavigationScaffold(content: @Composable () -> Unit) {
    val store = rememberStore()
    val navigationState by composeState<NavigationState>()
    val settingsState by composeState<SettingsModule.SettingsState>()
    val scope = rememberCoroutineScope()


    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(navigationState.currentEntry.screen.titleResource?.invoke() ?: "Home")
                },
                navigationIcon = {
                    IconButton(onClick = {
                        store.dispatch.invoke(SettingsModule.SettingsAction.SetDrawerOpen(!settingsState.drawerOpen))
                    }) {
                        Icon(PoegoIcons.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            store.dispatch(TestNavigationAction.TriggerMultipleNavigation)
                        }
                    }) {
                        Icon(PoegoIcons.News, contentDescription = "News")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            store.navigation {
                                presentModal<NotificationScreen> {
                                    put("TEST", listOf<String>("test1", "test2"))
                                }
                            }
                        }
                    }) {
                        Icon(PoegoIcons.Notifications, contentDescription = "Notifications")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            HomeBottomNavigation()
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding()
                )
        ) {
            content()
        }
    }
}

@Composable
fun HomeBottomNavigation() {
    val store = rememberStore()
    val navigationState by composeState<NavigationState>()
    val scope = rememberCoroutineScope()
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        NavigationBarItem(
            selected = navigationState.isInGraph("news"),
            onClick = {
                scope.launch {
                    store.navigate("home/news")
                }
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = "Home"
                )
            },
            label = { Text("Home") }
        )

        NavigationBarItem(
            selected = navigationState.isInGraph("workspace"),
            onClick = {
                scope.launch {
                    store.navigate("home/workspace")
                }
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = "workspace"
                )
            },
            label = { Text("Workspace") }
        )
        NavigationBarItem(
            selected = navigationState.isInGraph("leaderboard"),
            onClick = {
                scope.launch {
                    store.navigate("home/leaderboard")
                }
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.Star, // Or your leaderboard icon
                    contentDescription = "Leaderboard"
                )
            },
            label = { Text("Leaderboard") }
        )
    }
}