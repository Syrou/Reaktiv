package models

import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Dispatch
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.navigation.NavTransition
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class TestNavigationModule : Module<NavigationState, NavigationAction> {
    val homeScreen = object : Screen {
        override val route = "home"
        override val titleResourceId = 0
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
        override val titleResourceId = 0
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = true

        @Composable
        override fun Content(params: Map<String, Any>) {
            Text("Profile Screen")
        }
    }

    override val initialState = NavigationState(
        currentScreen = homeScreen,
        backStack = listOf(Pair(homeScreen, emptyMap())),
        availableScreens = mapOf("home" to homeScreen, "profile" to profileScreen)
    )

    override val reducer: (NavigationState, NavigationAction) -> NavigationState = { state, action ->
        when (action) {
            is NavigationAction.Navigate -> state.copy(currentScreen = state.availableScreens[action.route]!!)
            else -> state
        }
    }

    override val createLogic: (dispatch: Dispatch) -> ModuleLogic<NavigationAction> = { dispatch: Dispatch ->
        NavigationLogic(
            CoroutineScope(Dispatchers.Unconfined),
            initialState.availableScreens,
            dispatch
        )
    }
}