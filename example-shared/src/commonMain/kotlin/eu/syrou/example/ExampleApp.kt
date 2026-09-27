package eu.syrou.example

import androidx.compose.ui.graphics.vector.ImageVector
import eu.syrou.example.reaktiv.settings.SettingsModule.DrawerItem
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.syrou.example.reaktiv.crashtest.CrashTestLogic
import eu.syrou.example.reaktiv.settings.SettingsModule
import eu.syrou.example.reaktiv.subscription.SubscriptionLogic
import eu.syrou.example.ui.screen.SettingsScreen
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.ui.NavigationBackgroundProvider
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExampleApp(platform: ExamplePlatform) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            .build()
    }
    CompositionLocalProvider(LocalExamplePlatform provides platform) {
        ExampleDrawer(platform)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExampleDrawer(platform: ExamplePlatform) {
    val store = rememberStore()
    val settingsState by composeState<SettingsModule.SettingsState>()
    val drawerState = rememberDrawerState(
        initialValue = if (settingsState.drawerOpen) DrawerValue.Open else DrawerValue.Closed
    )
    val latestDrawerOpen by rememberUpdatedState(settingsState.drawerOpen)

    LaunchedEffect(settingsState.drawerOpen) {
        val target = if (settingsState.drawerOpen) DrawerValue.Open else DrawerValue.Closed
        if (drawerState.targetValue != target) {
            if (settingsState.drawerOpen) drawerState.open() else drawerState.close()
        }
    }

    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.currentValue }
            .drop(1)
            .collect { value ->
                val open = value == DrawerValue.Open
                if (open != latestDrawerOpen) {
                    store.dispatch.invoke(SettingsModule.SettingsAction.SetDrawerOpen(open))
                }
            }
    }

    val items = DrawerItem.entries.filter { it != DrawerItem.DEVTOOLS || platform.devTools != null }

    val navigationState by composeState<NavigationState>()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen || !navigationState.canGoBack,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier
                        .requiredWidth(300.dp)
                        .fillMaxHeight()
                        .padding(top = 65.dp)
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Spacer(Modifier.height(12.dp))
                        items.forEach { item ->
                            NavigationDrawerItem(
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { Text(item.label) },
                                selected = item == settingsState.drawerSelection,
                                onClick = {
                                    store.dispatch.invoke(SettingsModule.SettingsAction.SetDrawerOpen(!settingsState.drawerOpen))
                                    store.dispatch.invoke(SettingsModule.SettingsAction.SelectDrawerItem(item))
                                    when (item) {
                                        DrawerItem.SETTINGS -> {
                                            store.launch {
                                                store.navigation {
                                                    navigateTo(SettingsScreen.route)
                                                }
                                            }
                                        }

                                        DrawerItem.REAKTIV_PLUS -> {
                                            store.launch {
                                                store.selectLogic<SubscriptionLogic>().begin()
                                            }
                                        }

                                        DrawerItem.DEVTOOLS -> {
                                            val devTools = platform.devTools ?: return@NavigationDrawerItem
                                            store.launch {
                                                store.navigation {
                                                    navigateTo(devTools)
                                                }
                                            }
                                        }

                                        DrawerItem.CRASH_TEST -> {
                                            store.launch {
                                                val crashLogic = store.selectLogic<CrashTestLogic>()
                                                crashLogic.triggerCrashWithTracedOperations()
                                            }
                                        }

                                        DrawerItem.RESET_STORE -> {
                                            store.launch {
                                                store.reset()
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                            )
                        }
                    }
                }
            }
        ) {
            NavigationBackgroundProvider(
                backgroundColor = MaterialTheme.colorScheme.background,
                dismissIndicatorBackground = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f),
                dismissIndicatorColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            ) {
                NavigationRender(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                )
            }
        }
    }
}

private val DrawerItem.label: String
    get() = when (this) {
        DrawerItem.SETTINGS -> "Settings"
        DrawerItem.REAKTIV_PLUS -> "Reaktiv Plus"
        DrawerItem.DEVTOOLS -> "DevTools"
        DrawerItem.CRASH_TEST -> "Crash Test"
        DrawerItem.RESET_STORE -> "Reset Store"
    }

private val DrawerItem.icon: ImageVector
    get() = when (this) {
        DrawerItem.SETTINGS -> Icons.Default.Settings
        DrawerItem.REAKTIV_PLUS -> Icons.Default.Star
        DrawerItem.DEVTOOLS -> Icons.Default.Build
        DrawerItem.CRASH_TEST -> Icons.Default.Warning
        DrawerItem.RESET_STORE -> Icons.Default.Refresh
    }
