package eu.syrou.androidexample.ui.screen.home.news

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.serialization.Serializable

@Serializable
object NewsScreen : Screen {
    override val route: String = "overview"
    override val titleResource: TitleResource = {
        "Home"
    }
    override val enterTransition: NavTransition = NavTransition.None
    override val exitTransition: NavTransition = NavTransition.None
    override val requiresAuth: Boolean = false


    @Composable
    override fun Content(
        params: Params
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                Text(
                    "Home Screen",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
            item { NewsSection() }
            item { VideosSection() }
            item { LiveStreamsSection() }
        }
    }
}