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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import eu.syrou.androidexample.R
import eu.syrou.androidexample.reaktiv.twitchstreams.TwitchStreamsModule
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.alias.ActionResource
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.definition.ScreenGroup
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

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
        override val requiresAuth = false

        @Composable
        override fun Content(params: Params) {
            println("ViewUser - Params: $params")
            val id by remember { mutableStateOf(params["id"] as? String ?: params["userId"] as? String ?: "666") }
            val store = rememberStore()
            val scope = rememberCoroutineScope()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = colorResource(R.color.dark_background))
            ) {
                Text("User view based on param: $id")
                Button(onClick = {
                    scope.launch {
                        store.navigation {
                            navigateTo("user/$id/edit")
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
        override val requiresAuth = false

        @Composable
        override fun Content(params: Params) {
            println("EditUser - Params: $params")
            val id by remember { mutableStateOf(params["id"] as? String ?: params["userId"] as? String ?: "666") }
            val store = rememberStore()
            val scope = rememberCoroutineScope()
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
                    scope.launch {
                        store.navigation {
                            navigateTo("user/$id/delete")
                        }
                    }
                }) {
                    Text("Delete User")
                }
                Button(onClick = {
                    scope.launch { store.navigateBack() }
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
        override val requiresAuth = false

        @Composable
        override fun Content(params: Params) {
            val id by remember { mutableStateOf(params["id"] as? String ?: params["userId"] as? String ?: "666") }
            val store = rememberStore()
            val scope = rememberCoroutineScope()
            val thingState by composeState<TwitchStreamsModule.TwitchStreamsState>()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = colorResource(R.color.golden_brown))
            ) {
                Text(text = "Delete user: $id")
                Button(onClick = {
                    scope.launch {
                        store.navigation {
                            clearBackStack()
                            navigateTo("home")
                        }
                    }
                }) {
                    Text("Confirm Delete & Go Home")
                }
                Button(onClick = {
                    scope.launch { store.navigateBack() }
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
