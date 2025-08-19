package eu.syrou.androidexample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import eu.syrou.androidexample.R
import eu.syrou.androidexample.reaktiv.twitchstreams.TwitchStreamsModule
import eu.syrou.androidexample.ui.screen.home.NotificationModal
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.alias.ActionResource
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.GuidedFlow
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.definition.ScreenGroup
import io.github.syrou.reaktiv.navigation.extension.guidedFlow
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

object UserManagementScreens : ScreenGroup(ViewUser, EditUser, DeleteUser) {

    /**
     * Start a user management guided flow with the given user ID
     * The flow definition is configured at module creation time in CustomApplication.kt
     */
    suspend fun startUserManagementFlow(store: Store, userId: String) {
        store.dispatch(
            NavigationAction.StartGuidedFlow(
                GuidedFlow("user-management"),
                mapOf("userId" to userId)
            )
        )
    }

    /**
     * Convenience function to complete the user management flow with notification
     * This demonstrates runtime modification of the flow completion and navigation using the new DSL
     */
    suspend fun completeFlowWithNotification(store: Store) {
        // Use the new guidedFlow DSL for atomic operations
        store.guidedFlow("user-management") {
            updateOnComplete { storeAccessor ->
                val twitchState = storeAccessor.selectState<TwitchStreamsModule.TwitchStreamsState>().first()

                storeAccessor.navigation {
                    clearBackStack()
                    if (twitchState.twitchStreamers.isEmpty()) {
                        navigateTo("home")
                    } else {
                        navigateTo("home")
                        navigateTo<VideosListScreen>()
                    }
                    navigateTo<NotificationModal>()
                }
            }
            nextStep()
        }
    }

    @Serializable
    object ViewUser : Screen {
        override val route = "user/{id}"
        override val titleResource: TitleResource = {
            "User"
        }
        override val actionResource: ActionResource = {
            Text("asdf")
        }
        override val enterTransition = NavTransition.SlideInRight
        override val exitTransition = NavTransition.SlideOutLeft
        override val popEnterTransition: NavTransition = NavTransition.Hold
        override val popExitTransition: NavTransition = NavTransition.Hold
        override val requiresAuth = false

        @Composable
        override fun Content(
            params: Map<String, Any>
        ) {
            println("ViewUser - Params: $params")
            val id by remember { mutableStateOf(params["id"] as? String ?: params["userId"] as? String ?: "666") }
            val store = rememberStore()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = colorResource(R.color.dark_background))
            ) {
                Text("User view based on param: $id")
                Button(onClick = {
                    store.launch {
                        store.guidedFlow("user-management") {
                            nextStep(mapOf("userId" to "667"))
                        }
                    }
                }) {
                    Text("Next Step in Flow")
                }
            }
        }
    }

    @Serializable
    object EditUser : Screen {
        override val route = "user/{id}/edit"
        override val titleResource: TitleResource = {
            "User edit"
        }
        override val enterTransition = NavTransition.SlideInRight
        override val exitTransition = NavTransition.SlideOutLeft
        override val popEnterTransition: NavTransition = NavTransition.Hold
        override val popExitTransition: NavTransition = NavTransition.Hold
        override val requiresAuth = false

        @Composable
        override fun Content(
            params: Map<String, Any>
        ) {
            println("EditUser - Params: $params")
            val id by remember { mutableStateOf(params["id"] as? String ?: params["userId"] as? String ?: "666") }
            val store = rememberStore()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = colorResource(R.color.dark_background))
            ) {
                Text(
                    modifier = Modifier.testTag("id"),
                    text = "TEXT EDIT: $id",
                )
                Button(onClick = {
                    store.launch {
                        store.guidedFlow("user-management") {
                            nextStep()
                        }
                    }
                }) {
                    Text("Next Step in Flow")
                }
                Button(onClick = {
                    store.launch {
                        store.guidedFlow("user-management") {
                            previousStep()
                        }
                    }
                }) {
                    Text("Previous Step")
                }
            }
        }
    }

    @Serializable
    object DeleteUser : Screen {
        override val route = "user/{id}/delete"
        override val titleResource: TitleResource? = null
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom
        override val popEnterTransition: NavTransition = NavTransition.SlideUpBottom
        override val popExitTransition: NavTransition = NavTransition.SlideOutBottom
        override val requiresAuth = false

        @Composable
        override fun Content(
            params: Map<String, Any>
        ) {
            val id by remember { mutableStateOf(params["id"] as? String ?: params["userId"] as? String ?: "666") }
            val store = rememberStore()
            val thingState by composeState<TwitchStreamsModule.TwitchStreamsState>()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = colorResource(R.color.golden_brown))
            ) {
                Text(
                    text = "Delete user: $id",
                )
                Button(onClick = {
                    store.launch {
                        UserManagementScreens.completeFlowWithNotification(store)
                    }
                }) {
                    Text("Complete Flow")
                }
                Button(onClick = {
                    store.launch {
                        store.guidedFlow("user-management") {
                            previousStep()
                        }
                    }
                }) {
                    Text("Previous Step")
                }
                thingState.twitchStreamers.forEach {
                    Text(it.user_name)
                }
            }
        }
    }
}