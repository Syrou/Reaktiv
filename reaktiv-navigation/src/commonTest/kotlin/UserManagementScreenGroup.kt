import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.syrou.reaktiv.navigation.NavTransition
import io.github.syrou.reaktiv.navigation.Screen
import io.github.syrou.reaktiv.navigation.ScreenGroup

object UserManagementScreens : ScreenGroup() {
    object ViewUser : Screen {
        override val route = "user/view/{id}"
        override val titleResourceId: Int = 0
        override val enterTransition = NavTransition.SLIDE
        override val exitTransition = NavTransition.SLIDE
        override val requiresAuth = false

        @Composable
        override fun Content(params: Map<String, Any>) {

        }
    }

    object EditUser : Screen {
        override val route = "user/edit/{id}"
        override val titleResourceId: Int = 0
        override val enterTransition = NavTransition.SLIDE
        override val exitTransition = NavTransition.SLIDE
        override val requiresAuth = false

        @Composable
        override fun Content(params: Map<String, Any>) {
            println("TESTOR - YAY: $params")
            val id by remember { mutableStateOf(params["id"] as? String ?: "666") }
            println("TEXT THINGY: $id")
            Text(
                modifier = Modifier.testTag("id"),
                text = id,
            )
        }
    }

    object DeleteUser : Screen {
        override val route = "user/delete/{id}"
        override val titleResourceId: Int = 0
        override val enterTransition = NavTransition.FADE
        override val exitTransition = NavTransition.FADE
        override val requiresAuth = false

        @Composable
        override fun Content(params: Map<String, Any>) {

        }
    }

    init {
        addScreens(
            ViewUser,
            EditUser,
            DeleteUser
        )
    }
}