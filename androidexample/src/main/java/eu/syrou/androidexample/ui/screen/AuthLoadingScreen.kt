package eu.syrou.androidexample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import eu.syrou.androidexample.R
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.serialization.Serializable
@Serializable
object AuthLoadingScreen : Screen {
    override val route = "auth-loading"
    override val enterTransition = NavTransition.Fade
    override val exitTransition = NavTransition.FadeOut
    override val requiresAuth = false

    @Composable
    override fun Content(params: Params) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colorResource(R.color.dark_background)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}
