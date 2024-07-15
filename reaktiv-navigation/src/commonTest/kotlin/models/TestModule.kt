package models

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.navigation.NavTransition
import io.github.syrou.reaktiv.navigation.Screen
import kotlinx.serialization.Serializable

@Serializable
val homeScreen = object : Screen {
    override val route = "home"
    override val titleResourceId = 0
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false

    @Composable
    override fun Content(params: Map<String, Any>) {
    }
}

@Serializable
val profileScreen = object : Screen {
    override val route = "profile"
    override val titleResourceId = 0
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = true

    @Composable
    override fun Content(params: Map<String, Any>) {
    }
}