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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class SequentialLegacyNavigationTest {

    private fun screen(name: String) = object : Screen {
        override val route = name
        override val enterTransition = NavTransition.SlideInRight
        override val exitTransition = NavTransition.SlideOutLeft

        @Composable
        override fun Content(params: Params) {
            Text(name)
        }
    }

    private val home = screen("home")
    private val upsell = screen("upsell")

    private val sheet = object : Modal {
        override val route = "sheet"
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom

        @Composable
        override fun Content(params: Params) {
            Text("sheet")
        }
    }

    private fun newStore(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) = createStore {
        module(createNavigationModule {
            rootGraph {
                start(home)
                screens(home, upsell)
                modals(sheet)
            }
        })
        coroutineContext(StandardTestDispatcher(scheduler))
    }

    @Test
    fun dismissing_a_modal_then_navigating_awaited_leaves_both_applied() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = newStore(testScheduler)
            store.navigation { navigateTo("sheet") }
            advanceUntilIdle()

            store.dismissModal()
            store.navigate("upsell")
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("upsell", state.currentEntry.route, "the navigation must have landed")
            assertTrue(
                state.backStack.none { it.navigatable is Modal },
                "and the modal must be gone rather than one of the two winning"
            )
        }

    @Test
    fun the_same_pair_launched_separately_still_leaves_both_applied() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = newStore(testScheduler)
            store.navigation { navigateTo("sheet") }
            advanceUntilIdle()

            store.launch { store.dismissModal() }
            store.launch { store.navigate("upsell") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("upsell", state.currentEntry.route)
            assertTrue(state.backStack.none { it.navigatable is Modal })
        }

    @Test
    fun the_transactional_block_is_the_reference_result() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = newStore(testScheduler)
            store.navigation { navigateTo("sheet") }
            advanceUntilIdle()

            store.navigation {
                navigateBack()
                navigateTo("upsell")
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("upsell", state.currentEntry.route)
            assertTrue(state.backStack.none { it.navigatable is Modal })
        }

    @Test
    fun a_dismiss_applied_after_the_navigation_still_leaves_you_where_you_navigated() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = newStore(testScheduler)
            store.navigation { navigateTo("sheet") }
            advanceUntilIdle()

            store.navigate("upsell")
            store.dismissModal()
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals(
                "upsell",
                state.currentEntry.route,
                "a late dismiss must remove the modal, not undo the navigation written after it"
            )
            assertTrue(
                state.backStack.none { it.navigatable is Modal },
                "and the modal it was asked to dismiss must be gone"
            )
        }

    @Test
    fun dismissing_when_no_modal_is_present_changes_nothing() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = newStore(testScheduler)
            store.navigate("upsell")
            advanceUntilIdle()
            val before = store.selectState<NavigationState>().first().backStack.map { it.route }

            store.dismissModal()
            advanceUntilIdle()

            assertEquals(
                before,
                store.selectState<NavigationState>().first().backStack.map { it.route },
                "with nothing to dismiss the call is inert rather than a disguised back"
            )
        }

    @Test
    fun dismissing_a_current_modal_still_reveals_what_is_underneath() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = newStore(testScheduler)
            store.navigation { navigateTo("sheet") }
            advanceUntilIdle()

            store.dismissModal()
            advanceUntilIdle()

            assertEquals(
                "home",
                store.selectState<NavigationState>().first().currentEntry.route
            )
        }

    @Test
    fun either_order_reaches_the_same_place() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val forward = newStore(testScheduler).also {
                it.navigation { navigateTo("sheet") }
                advanceUntilIdle()
                it.dismissModal()
                it.navigate("upsell")
                advanceUntilIdle()
            }.selectState<NavigationState>().first()

            val inverted = newStore(testScheduler).also {
                it.navigation { navigateTo("sheet") }
                advanceUntilIdle()
                it.navigate("upsell")
                it.dismissModal()
                advanceUntilIdle()
            }.selectState<NavigationState>().first()

            assertEquals(
                forward.backStack.map { it.route },
                inverted.backStack.map { it.route },
                "the pair is order independent once dismiss names what it dismisses"
            )
        }

    @Test
    fun the_block_never_emits_a_state_where_only_one_of_the_two_has_happened() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = newStore(testScheduler)
            store.navigation { navigateTo("sheet") }
            advanceUntilIdle()

            val seen = mutableListOf<List<String>>()
            val collector = launch {
                store.selectState<NavigationState>().collect { seen.add(it.backStack.map { e -> e.route }) }
            }
            advanceUntilIdle()

            store.navigation {
                navigateTo("upsell")
                dismissModals()
            }
            advanceUntilIdle()
            collector.cancel()

            assertTrue(
                seen.none { stack -> "upsell" in stack && "sheet" in stack },
                "dismissModals stamps the navigate rather than adding a step, so the modal is " +
                    "gone in the same reduction that pushes the screen: $seen"
            )
            assertEquals(listOf("home", "upsell"), seen.last())
        }

    @Test
    fun two_separate_calls_do_emit_an_intermediate_state() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = newStore(testScheduler)
            store.navigation { navigateTo("sheet") }
            advanceUntilIdle()

            val seen = mutableListOf<List<String>>()
            val collector = launch {
                store.selectState<NavigationState>().collect { seen.add(it.backStack.map { e -> e.route }) }
            }
            advanceUntilIdle()

            store.dismissModal()
            advanceUntilIdle()
            store.navigate("upsell")
            advanceUntilIdle()
            collector.cancel()

            assertTrue(
                seen.any { it == listOf("home") },
                "each one-shot call commits on its own, so the screen under the modal is a state " +
                    "the UI can render before the next call arrives: $seen"
            )
            assertEquals(listOf("home", "upsell"), seen.last())
        }
}
