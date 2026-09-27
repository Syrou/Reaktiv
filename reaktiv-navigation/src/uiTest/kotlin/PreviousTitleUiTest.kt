import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.previousTitle
import kotlinx.coroutines.launch
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class PreviousTitleUiTest {

    private fun titled(route: String, title: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val titleResource: TitleResource = { title }

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private val alert = object : Modal {
        override val route = "alert"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val renderLayer = RenderLayer.SYSTEM

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    @Test
    fun previousTitleNamesTheEntryBackReveals() = runComposeUiTest {
        val home = titled("home", "Home")
        val detail = titled("detail", "Detail")
        val store = createStore {
            module(createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home, detail)
                    modals(alert)
                }
            })
        }
        setContent {
            StoreProvider(store) {
                Text("back:" + (previousTitle() ?: "none"))
            }
        }
        waitUntilExactlyOneExists(hasText("back:none"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigation { navigateTo("detail") } }
        waitUntilExactlyOneExists(hasText("back:Home"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigation { navigateTo(alert) } }
        waitUntilExactlyOneExists(hasText("back:Detail"), timeoutMillis = UI_TEST_WAIT_MS)
    }
}
