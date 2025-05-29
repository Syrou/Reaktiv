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
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.alias.ActionResource
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.definition.ScreenGroup
import io.github.syrou.reaktiv.navigation.extension.clearBackStack
import io.github.syrou.reaktiv.navigation.extension.navigate
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

object UserManagementScreens : ScreenGroup(ViewUser, EditUser, DeleteUser) {

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
            val id by remember { mutableStateOf(params["id"] as? String ?: "666") }
            val store = rememberStore()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = colorResource(R.color.dark_background))
            ) {
                Text("User view based on param: $id")
                Button(onClick = {
                    store.launch {
                        store.navigate("user/$id/edit") {
                            forwardParams()
                        }
                    }
                }) {
                    Text("Next")
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
            val id by remember { mutableStateOf(params["id"] as? String ?: "666") }
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
                        store.navigate("user/$id/delete") {
                            forwardParams()
                        }
                    }
                }) {
                    Text("Next")
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
            val id by remember { mutableStateOf(params["id"] as? String ?: "666") }
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
                        store.clearBackStack {
                            navigate("home")
                        }
                    }
                }) {
                    Text("Clear")
                }
                thingState.twitchStreamers.forEach {
                    Text(it.user_name)
                }
            }
        }
    }
}