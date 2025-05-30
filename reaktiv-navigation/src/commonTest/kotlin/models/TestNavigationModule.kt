package models

import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationEntry
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.Screen
import io.github.syrou.reaktiv.navigation.TitleResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class TestNavigationModule : Module<NavigationState, NavigationAction> {
    val homeScreen = object : Screen {
        override val route = "home"
        override val titleResource: TitleResource = {
            "Home"
        }
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false

        @Composable
        override fun Content(params: Map<String, Any>) {
            Text("Home Screen")
        }
    }

    val profileScreen = object : Screen {
        override val route = "profile"
        override val titleResource: TitleResource = {
            "Profile"
        }
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = true

        @Composable
        override fun Content(params: Map<String, Any>) {
            Text("Profile Screen")
        }
    }

    val editScreen = object : Screen {
        override val route = "edit"
        override val titleResource: TitleResource = {
            "Edit"
        }
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = true

        @Composable
        override fun Content(params: Map<String, Any>) {
            Text("Edit Screen")
        }
    }

    override val initialState = NavigationState(
        currentEntry = NavigationEntry(homeScreen, emptyMap()),
        backStack = listOf(NavigationEntry(homeScreen, emptyMap())),
        availableScreens = mapOf("home" to homeScreen, "profile" to profileScreen)
    )

    override val reducer: (NavigationState, NavigationAction) -> NavigationState = { state, action ->
        when (action) {
            is NavigationAction.Navigate -> state.copy(
                currentEntry = NavigationEntry(
                    state.availableScreens[action.route]!!,
                    emptyMap()
                )
            )

            else -> state
        }
    }

    override val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic<NavigationAction> =
        { storeAccessor: StoreAccessor ->
            NavigationLogic(
                CoroutineScope(Dispatchers.Unconfined),
                initialState.availableScreens,
                storeAccessor
            )
        }
}