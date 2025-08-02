package eu.syrou.androidexample

import NavigationRender
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.syrou.androidexample.reaktiv.settings.SettingsModule
import eu.syrou.androidexample.ui.components.NotificationPermissionHandler
import eu.syrou.androidexample.ui.screen.SettingsScreen
import eu.syrou.androidexample.ui.theme.ReaktivTheme
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.extension.navigate
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent, "onNewIntent")
    }

    private fun handleDeepLink(intent: Intent, source: String) {
        println("KASTRULL - ON NEW INTENT FROM: $source, intent: $intent")
        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null) {
                val path = uri.path // This will give you "/navigation/user/edit/456"
                val segments = uri.pathSegments // This will give you a list: ["navigation", "user", "edit", "456"]
                println("KASTRULL - DEEP LINK PATH: $path")
                customApp.store.launch {
                    customApp.store.navigate(path?.replace("/navigation/", "") ?: "")
                }
            }
        }
    }

    var blocking: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //runBlocking { customApp.store.loadState() }
        handleDeepLink(intent, "onCreate")

        /*lifecycleScope.launch {
            val newsState = customApp.store.selectState<NewsModule.NewsState>()
            val videosState = customApp.store.selectState<VideosModule.VideosState>()
            val hasNews = newsState.first { it.news.isNotEmpty() }.news.isNotEmpty()
            val hasVideos = videosState.first { it.videos.isNotEmpty() }.videos.isNotEmpty()
            blocking = if (hasNews && hasVideos) false else true
        }*/
        setContent {
            ReaktivTheme {
                StoreProvider(store = customApp.store) {
                    NotificationPermissionHandler {
                        MainRender()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        runBlocking {

        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainRender() {
    val store = rememberStore()
    val settingsState by composeState<SettingsModule.SettingsState>()
    val drawerValue: DrawerValue = if (settingsState.drawerOpen) DrawerValue.Open else DrawerValue.Closed
    val drawerState = DrawerState(drawerValue) { drawerValue ->
        val isClosed = drawerValue == DrawerValue.Closed
        if (settingsState.drawerOpen && isClosed) {
            store.dispatch.invoke(SettingsModule.SettingsAction.SetDrawerOpen(false))
        } else {
            store.dispatch.invoke(SettingsModule.SettingsAction.SetDrawerOpen(true))
        }
        false
    }

    BackHandler(true) {
        store.launch {
            store.navigateBack()
        }
    }
    val items =
        listOf(
            "Settings" to Icons.Default.Settings,
            "Contact" to Icons.Default.Notifications
        )
    val selectedItem = remember { mutableStateOf(items[0]) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = true,
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
                                icon = { Icon(item.second, contentDescription = null) },
                                label = { Text(item.first) },
                                selected = item == selectedItem.value,
                                onClick = {
                                    store.dispatch.invoke(SettingsModule.SettingsAction.SetDrawerOpen(!settingsState.drawerOpen))
                                    selectedItem.value = item
                                    if (item.first == "Settings") {
                                        store.launch {
                                            store.navigate(SettingsScreen.route)
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
            NavigationRender(
                modifier = Modifier.fillMaxSize()
            ) { screen, params ->
                screen.Content(params)
            }
        }
    }
}