import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class ModalNavigationContextTest {
    
    // Create test screens
    private val workspaceScreen = object : Screen {
        override val route = "workspace"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false

        @Composable
        override fun Content(params: Params) {
            Text("Workspace")
        }
    }

    private val videosListScreen = object : Screen {
        override val route = "videos"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false

        @Composable
        override fun Content(params: Params) {
            Text("Videos List")
        }
    }

    // Create test modal
    private val notificationModal = object : Modal {
        override val route = "notification"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false

        @Composable
        override fun Content(params: Params) {
            Text("Notification Modal")
        }
    }

    private fun createTestNavigationModule() = createNavigationModule {
        rootGraph {
            startScreen(workspaceScreen)
            screens(workspaceScreen, videosListScreen)
            modals(notificationModal)
        }
    }

    @Test
    fun `test modal context restoration - workspace to notification modal to videos to back should restore modal over workspace`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createTestNavigationModule())
                coroutineContext(testDispatcher)
            }

            // Step 1: Start on WorkspaceScreen
            advanceUntilIdle()
            var state = store.selectState<NavigationState>().first()
            assertEquals("workspace", state.currentEntry.navigatable.route)

            // Step 2: Open NotificationScreen modal from WorkspaceScreen
            store.navigation { navigateTo("notification") }
            advanceUntilIdle()
            state = store.selectState<NavigationState>().first()
            assertEquals("notification", state.currentEntry.navigatable.route)
            assertTrue(state.isCurrentModal)
            assertEquals("workspace", state.underlyingScreen?.navigatable?.route)

            // Step 3: Navigate from modal to VideosListScreen (modal should close)
            store.navigation { navigateTo("videos") }
            advanceUntilIdle()
            state = store.selectState<NavigationState>().first()
            assertEquals("videos", state.currentEntry.navigatable.route)
            assertFalse(state.isCurrentModal)

            // Step 4: Go back - should restore WorkspaceScreen with NotificationModal on top
            store.navigateBack()
            advanceUntilIdle()
            state = store.selectState<NavigationState>().first()
            
            // Validate the correct behavior: modal should be restored over original underlying screen
            assertEquals("notification", state.currentEntry.navigatable.route, "Should be back to the notification modal")
            assertTrue(state.isCurrentModal, "Should be showing a modal")
            assertNotNull(state.underlyingScreen, "Should have an underlying screen")
            assertEquals("workspace", state.underlyingScreen!!.navigatable.route, "Underlying screen should be workspace")
        }

    @Test
    fun `test dismissModals removes active modal`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createTestNavigationModule())
                coroutineContext(testDispatcher)
            }

            // Step 1: Start on WorkspaceScreen
            advanceUntilIdle()
            var state = store.selectState<NavigationState>().first()
            assertEquals("workspace", state.currentEntry.navigatable.route)
            assertTrue(state.activeModalContexts.isEmpty(), "Should have no active modal contexts initially")

            // Step 2: Open NotificationScreen modal from WorkspaceScreen
            store.navigation { navigateTo("notification") }
            advanceUntilIdle()
            state = store.selectState<NavigationState>().first()
            assertEquals("notification", state.currentEntry.navigatable.route)
            assertTrue(state.isCurrentModal, "Should be showing a modal")
            assertEquals("workspace", state.underlyingScreen?.navigatable?.route)
            assertTrue(state.activeModalContexts.isNotEmpty(), "Should have active modal contexts")

            // Step 3: Navigate to VideosListScreen with dismissModals() - modal should be completely dismissed
            store.navigation {
                navigateTo("videos")
                dismissModals()
            }
            advanceUntilIdle()
            state = store.selectState<NavigationState>().first()
            assertEquals("videos", state.currentEntry.navigatable.route, "Should be on videos screen")
            assertFalse(state.isCurrentModal, "Should not be showing a modal")
            assertTrue(state.activeModalContexts.isEmpty(), "Should have no active modal contexts after dismissal")

            // Step 4: Go back - should return to workspace WITHOUT restoring the modal
            store.navigateBack()
            advanceUntilIdle()
            state = store.selectState<NavigationState>().first()
            
            // Validate the correct behavior: modal should NOT be restored
            assertEquals("workspace", state.currentEntry.navigatable.route, "Should be back to workspace screen")
            assertFalse(state.isCurrentModal, "Should not be showing a modal")
            assertTrue(state.activeModalContexts.isEmpty(), "Should have no modal contexts - modal was permanently dismissed")
            
            // Verify backstack only contains screens, no modals
            val screensInBackStack = state.backStack.filter { it.isScreen }
            assertEquals(1, screensInBackStack.size, "Should only have workspace screen in backstack")
            assertEquals("workspace", screensInBackStack.first().navigatable.route)
        }
}