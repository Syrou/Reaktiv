import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Dispatch
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.navigation.NavTransition
import io.github.syrou.reaktiv.navigation.Screen

val homeScreen = object : Screen {
    override val route = "home"
    override val titleResourceId = 0
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false
    @Composable
    override fun Content(params: Map<String, Any>) {}
}
val profileScreen = object : Screen {
    override val route = "profile"
    override val titleResourceId = 0
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = true
    @Composable
    override fun Content(params: Map<String, Any>) {}
}

class TestModule : Module<TestModule.TestState, TestModule.TestAction> {
    data class TestState(val value: Int = 0) : ModuleState
    class TestAction : ModuleAction(TestModule::class)
    override val initialState = TestState()
    override val reducer: (TestState, TestAction) -> TestState = { state, _ -> state }
    override val logic = object : ModuleLogic<TestAction>() {
        override suspend fun invoke(action: ModuleAction, dispatch: Dispatch) {}
    }
}