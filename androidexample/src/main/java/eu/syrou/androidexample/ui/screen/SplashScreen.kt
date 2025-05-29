package eu.syrou.androidexample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import eu.syrou.androidexample.R
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable
object SplashScreen : Screen {
    override val route: String = "splash"
    override val enterTransition: NavTransition = NavTransition.None
    override val exitTransition: NavTransition = NavTransition.None
    override val requiresAuth: Boolean = false

    @Composable
    override fun Content(
        params: Map<String, Any>
    ) {
        val store = rememberStore()
        LaunchedEffect(Unit) {
            delay(3000)
            store.navigation {
                navigateTo("home")
                clearBackStack()
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colorResource(R.color.dark_background))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {}
                    ),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}