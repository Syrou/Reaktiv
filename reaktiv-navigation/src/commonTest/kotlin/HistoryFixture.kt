import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Dismissal
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.history.HistoryEntryState
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

internal fun historyScreen(route: String, dismissal: Dismissal = Dismissal.Default): Screen = object : Screen {
    override val route = route
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val dismissal = dismissal

    @Composable
    override fun Content(params: Params) {
        Text(route)
    }
}

internal fun historyModal(route: String, layer: RenderLayer = RenderLayer.GLOBAL_OVERLAY): Modal = object : Modal {
    override val route = route
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val renderLayer = layer

    @Composable
    override fun Content(params: Params) {
        Text(route)
    }
}

internal fun TestScope.fakeBrowser(
    url: String = FakeBrowser.ORIGIN + "/",
    offersTraverseIntents: Boolean = false
): FakeBrowser = FakeBrowser(StandardTestDispatcher(testScheduler), url, offersTraverseIntents)

internal val FakeBrowser.urls: List<String> get() = entries.map { "#" + it.url.substringAfter('#', "") }

internal val FakeBrowser.currentHash: String get() = "#" + currentUrl.substringAfter('#', "")

internal suspend fun Store.locations(): List<String> =
    selectState<NavigationState>().first().backStack.map { it.location }

internal suspend fun Store.historyEntry(browser: FakeBrowser, index: Int = browser.index): HistoryEntryState? =
    HistoryEntryState.decode(browser.entries[index].state, selectLogic<NavigationLogic>().locationCodec)
