package eu.syrou.example.ui.scaffold

import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.Modifier
import eu.syrou.example.reaktiv.TestNavigationModule.TestNavigationAction
import eu.syrou.example.reaktiv.settings.SettingsModule
import eu.syrou.example.ui.screen.home.NotificationModal
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.ui.currentActionResource
import io.github.syrou.reaktiv.navigation.ui.currentTitle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeNavigationScaffold(content: @Composable () -> Unit) {
    val store = rememberStore()
    val navigationState by composeState<NavigationState>()
    val settingsState by composeState<SettingsModule.SettingsState>()
    val currentActionResource = currentActionResource()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(currentTitle() ?: "Home")
                },
                navigationIcon = {
                    IconButton(onClick = {
                        store.dispatch.invoke(SettingsModule.SettingsAction.SetDrawerOpen(!settingsState.drawerOpen))
                    }) {
                        Icon(PoegoIcons.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    currentActionResource?.invoke()
                    IconButton(onClick = {
                        store.launch {
                            store.dispatch(TestNavigationAction.TriggerMultipleNavigation)
                        }
                    }) {
                        Icon(PoegoIcons.News, contentDescription = "News")
                    }
                    IconButton(onClick = {
                        store.launch {
                            store.navigation {
                                navigateTo<NotificationModal> {
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
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        HOME_TABS.forEach { tab ->
            NavigationBarItem(
                selected = navigationState.isInGraph(tab.graph),
                onClick = {
                    store.launch {
                        store.navigation {
                            navigateTo("home/${tab.graph}")
                        }
                    }
                },
                icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) }
            )
        }
    }
}

private class HomeTab(val graph: String, val label: String, val icon: ImageVector)

private val HOME_TABS = listOf(
    HomeTab("news", "Home", Icons.Default.Home),
    HomeTab("workspace", "Workspace", Icons.Default.Home),
    HomeTab("leaderboard", "Leaderboard", Icons.Default.Star)
)
