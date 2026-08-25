import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.dismissModal
import io.github.syrou.reaktiv.navigation.extension.navigate
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyNavigationOrderingJvmTest {

    private fun screen(name: String) = object : Screen {
        override val route = name
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(name)
        }
    }

    private val home = screen("home")
    private val upsell = screen("upsell")

    private val sheet = object : Modal {
        override val route = "sheet"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text("sheet")
        }
    }

    private fun newStore() = createStore {
        module(createNavigationModule {
            rootGraph {
                start(home)
                screens(home, upsell)
                modals(sheet)
            }
        })
    }

    @Test
    fun awaited_in_sequence_the_pair_holds_on_a_real_dispatcher() = runBlocking {
        repeat(40) {
            val store = newStore()
            store.navigation { navigateTo("sheet") }
            store.dismissModal()
            store.navigate("upsell")

            val state = store.selectState<NavigationState>().first()
            assertEquals("upsell", state.currentEntry.route, "iteration $it")
            assertTrue(state.backStack.none { e -> e.navigatable is Modal }, "iteration $it")
        }
    }

    @Test
    fun the_pair_survives_being_launched_as_two_coroutines() = runBlocking {
        repeat(60) {
            val store = newStore()
            store.navigation { navigateTo("sheet") }

            val a = store.launch(Dispatchers.Default) { store.dismissModal() }
            val b = store.launch(Dispatchers.Default) { store.navigate("upsell") }
            listOf(a, b).joinAll()

            val state = store.selectState<NavigationState>().first()
            assertEquals("upsell", state.currentEntry.route, "iteration $it")
            assertTrue(
                state.backStack.none { e -> e.navigatable is Modal },
                "iteration $it left a modal behind"
            )
        }
    }

    @Test
    fun dismissing_with_no_modal_present_leaves_the_stack_alone() = runBlocking {
        val store = newStore()
        store.navigate("upsell")
        val before = store.selectState<NavigationState>().first().backStack.map { it.route }

        store.dismissModal()

        assertEquals(
            before,
            store.selectState<NavigationState>().first().backStack.map { it.route },
            "a dismiss with nothing to dismiss must not double as a back"
        )
    }
}
