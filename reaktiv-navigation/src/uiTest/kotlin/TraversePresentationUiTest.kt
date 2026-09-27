import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.TraverseDirection
import io.github.syrou.reaktiv.navigation.TraversePresentation
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class TraversePresentationUiTest {

    private fun screen(route: String, label: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.SlideInRight
        override val exitTransition = NavTransition.SlideOutLeft

        @Composable
        override fun Content(params: Params) {
            Text(label)
        }
    }

    private val first = screen("first", "First Body")
    private val second = screen("second", "Second Body")

    private fun ComposeUiTest.mount(): Store {
        val store = createStore {
            module(
                createNavigationModule {
                    rootGraph {
                        start(first)
                        screens(first, second)
                    }
                }
            )
        }
        setContent { StoreProvider(store) { NavigationRender() } }
        waitUntilExactlyOneExists(hasText("First Body"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("second") } }
        waitUntilExactlyOneExists(hasText("Second Body"), timeoutMillis = UI_TEST_WAIT_MS)
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
            onNodeWithText("First Body").runCatching { assertDoesNotExist() }.isSuccess
        }
        return store
    }

    private fun ComposeUiTest.traverseBack(store: Store, presentation: TraversePresentation) {
        mainClock.autoAdvance = false
        store.launch {
            val firstEntry = store.selectState<NavigationState>().first().backStack.first()
            store.selectLogic<NavigationLogic>().commitTraverse(
                listOf(firstEntry), TraverseDirection.Back, presentation, null
            )
        }
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
            runBlocking { store.selectState<NavigationState>().first().backStack.size == 1 }
        }
        repeat(3) { mainClock.advanceTimeBy(16) }
    }

    @Test
    fun `a traverse the browser already animated shows the destination at rest`() = runComposeUiTest {
        val store = mount()

        traverseBack(store, TraversePresentation.AlreadyPresented)

        onNodeWithText("First Body").assertExists()
        onNodeWithText("Second Body").assertDoesNotExist()
        mainClock.autoAdvance = true
    }

    @Test
    fun `a traverse that animates keeps the leaving screen while it leaves`() = runComposeUiTest {
        val store = mount()

        traverseBack(store, TraversePresentation.Animate)

        onNodeWithText("First Body").assertExists()
        onNodeWithText("Second Body").assertExists()
        mainClock.autoAdvance = true
    }
}
