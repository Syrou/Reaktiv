package eu.syrou.example.ui.screen

import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import eu.syrou.example.reaktiv.twitchstreams.TwitchStreamsModule
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.alias.ActionResource
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.definition.ScreenGroup
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import eu.syrou.example.ui.theme.DarkBackground
import eu.syrou.example.ui.theme.GoldenBrown

object UserManagementScreens : ScreenGroup(ViewUser, EditUser, DeleteUser) {

    @Serializable
    object ViewUser : Screen {
        override val route = "user/{id}"
        override val titleResource: TitleResource = { "User" }
        override val actionResource: ActionResource = { Text("asdf") }
        override val enterTransition = NavTransition.SlideInRight
        override val exitTransition = NavTransition.SlideOutLeft
        override val popEnterTransition: NavTransition = NavTransition.None
        override val popExitTransition: NavTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            val id = params["id"] as? String ?: params["userId"] as? String ?: "666"
            val store = rememberStore()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = DarkBackground)
            ) {
                Text("User view based on param: $id")
                Button(onClick = {
                    store.launch {
                        store.navigation {
                            navigateTo(EditUser, "id" to id)
                        }
                    }
                }) {
                    Text("Edit User")
                }
            }
        }
    }

    @Serializable
    object EditUser : Screen {
        override val route = "user/{id}/edit"
        override val titleResource: TitleResource = { "User edit" }
        override val enterTransition = NavTransition.SlideInRight
        override val exitTransition = NavTransition.SlideOutLeft
        override val popEnterTransition: NavTransition = NavTransition.None
        override val popExitTransition: NavTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            val id = params["id"] as? String ?: params["userId"] as? String ?: "666"
            val store = rememberStore()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = DarkBackground)
            ) {
                Text(
                    modifier = Modifier.testTag("id"),
                    text = "TEXT EDIT: $id",
                )
                Button(onClick = {
                    store.launch {
                        store.navigation {
                            navigateTo(DeleteUser, "id" to id)
                        }
                    }
                }) {
                    Text("Delete User")
                }
                Button(onClick = {
                    store.launch { store.navigation { navigateBack() } }
                }) {
                    Text("Back")
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

        @Composable
        override fun Content(params: Params) {
            val id = params["id"] as? String ?: params["userId"] as? String ?: "666"
            val store = rememberStore()
            val thingState by composeState<TwitchStreamsModule.TwitchStreamsState>()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = GoldenBrown)
            ) {
                Text(text = "Delete user: $id")
                Button(onClick = {
                    store.launch {
                        store.navigation {
                            clearBackStack()
                            navigateTo("home")
                        }
                    }
                }) {
                    Text("Confirm Delete & Go Home")
                }
                Button(onClick = {
                    store.launch { store.navigation { navigateBack() } }
                }) {
                    Text("Cancel")
                }
                thingState.twitchStreamers.forEach {
                    Text(it.user_name)
                }
            }
        }
    }
}
