import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.param.getString
import io.github.syrou.reaktiv.navigation.transition.NavTransition

object SplashScreen : Screen {
    override val route = "splash"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false

    @Composable
    override fun Content(params: Params) {
        Text("Splash Screen")
    }
}

object HomeScreen : Screen {
    override val route = "home-overview"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false

    @Composable
    override fun Content(params: Params) {
        Text("Home Screen")
    }
}

object SettingsScreen : Screen {
    override val route = "settings"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false

    @Composable
    override fun Content(params: Params) {
        Text("Settings Screen")
    }
}


object NewsScreen : Screen {
    override val route = "news"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false

    @Composable
    override fun Content(params: Params) {
        Text("News Screen")
    }
}

object NewsOverviewScreen : Screen {
    override val route = "news-overview"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false

    @Composable
    override fun Content(params: Params) {
        Text("News Overview Screen")
    }
}

object NewsListScreen : Screen {
    override val route = "news-list"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false

    @Composable
    override fun Content(params: Params) {
        Text("News List Screen")
    }
}

object ProfileScreen : Screen {
    override val route = "profile"
    override val enterTransition = NavTransition.SlideInRight
    override val exitTransition = NavTransition.SlideOutLeft
    override val requiresAuth = true

    @Composable
    override fun Content(params: Params) {
        val userId = params.getString("userId") ?: "unknown"
        Text("Profile Screen - User: $userId")
    }
}

object WorkspaceOverviewScreen : Screen {
    override val route = "workspace-overview"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false

    @Composable
    override fun Content(params: Params) {
        Text("Home Screen")
    }
}

object ProjectOverviewScreen : Screen {
    override val route = "project-overview"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false

    @Composable
    override fun Content(params: Params) {
        Text("Project Screen")
    }
}

object ProjectTaskScreen : Screen {
    override val route = "project-tasks"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false

    @Composable
    override fun Content(params: Params) {
        Text("Task Screen")
    }
}

object StatsScreen : Screen {
    override val route = "stats/{type}"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false

    @Composable
    override fun Content(params: Params) {
        Text("Stats: ${params["type"]}")
    }
}

object MultiParamScreen : Screen {
    override val route = "company/{companyId}/user/{userId}/profile"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false

    @Composable
    override fun Content(params: Params) {
        Text("Company: ${params["companyId"]}, User: ${params["userId"]}")
    }
}
