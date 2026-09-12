package eu.syrou.androidexample.reaktiv.subscription

import eu.syrou.androidexample.ui.screen.subscription.SubscriptionConfettiScreen
import eu.syrou.androidexample.ui.screen.subscription.SubscriptionGraph
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.extension.navigation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

@Serializable
enum class SubscriptionPlan(val label: String, val price: String, val blurb: String) {
    Monthly("Monthly", "€9 / month", "Cancel whenever you like"),
    Yearly("Yearly", "€90 / year", "Two months on the house")
}

@Serializable
enum class SubscriptionPhase {
    Idle,
    Purchasing,
    Celebrating
}

object SubscriptionModule :
    Module<SubscriptionModule.SubscriptionState, SubscriptionModule.SubscriptionAction> {

    @Serializable
    data class SubscriptionState(
        val selectedPlan: SubscriptionPlan? = null,
        val originPath: String? = null,
        val phase: SubscriptionPhase = SubscriptionPhase.Idle
    ) : ModuleState

    sealed class SubscriptionAction : ModuleAction(SubscriptionModule::class) {
        @Serializable
        data class Started(val originPath: String) : SubscriptionAction()

        @Serializable
        data class PlanSelected(val plan: SubscriptionPlan) : SubscriptionAction()

        @Serializable
        data object PurchaseStarted : SubscriptionAction()

        @Serializable
        data object PurchaseSucceeded : SubscriptionAction()

        @Serializable
        data object Finished : SubscriptionAction()
    }

    override val initialState = SubscriptionState()

    override val reducer: (SubscriptionState, SubscriptionAction) -> SubscriptionState = { state, action ->
        when (action) {
            is SubscriptionAction.Started -> SubscriptionState(originPath = action.originPath)
            is SubscriptionAction.PlanSelected -> state.copy(selectedPlan = action.plan)
            is SubscriptionAction.PurchaseStarted -> state.copy(phase = SubscriptionPhase.Purchasing)
            is SubscriptionAction.PurchaseSucceeded -> state.copy(phase = SubscriptionPhase.Celebrating)
            is SubscriptionAction.Finished -> state.copy(phase = SubscriptionPhase.Idle, originPath = null)
        }
    }

    override val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic =
        { storeAccessor -> SubscriptionLogic(storeAccessor) }
}

/**
 * Drives the fake checkout.
 *
 * The celebration is one side effect chain hanging off the buy action rather than a state observer
 * in the UI, which matters here because the screen that starts it is replaced halfway through:
 * running on the store's scope means the confetti and the return home still happen once the payment
 * screen is gone.
 */
class SubscriptionLogic(
    private val storeAccessor: StoreAccessor
) : ModuleLogic() {

    suspend fun begin() {
        val origin = storeAccessor.selectState<NavigationState>().first().currentEntry.path
        storeAccessor.dispatch(SubscriptionModule.SubscriptionAction.Started(origin))
        storeAccessor.navigation { navigateTo(SubscriptionGraph.route) }
    }

    suspend fun selectPlan(plan: SubscriptionPlan) {
        storeAccessor.dispatch(SubscriptionModule.SubscriptionAction.PlanSelected(plan))
    }

    suspend fun purchase() {
        storeAccessor.dispatch(SubscriptionModule.SubscriptionAction.PurchaseStarted)
        delay(PAYMENT_MILLIS)
        storeAccessor.dispatch(SubscriptionModule.SubscriptionAction.PurchaseSucceeded)
        val origin = storeAccessor.selectState<SubscriptionModule.SubscriptionState>().first().originPath

        // The celebration replaces the step it was bought from, and the steps behind that go with
        // it in the same block: leaving them on the stack means the return home has to unwind the
        // checkout underneath the celebration rather than lifting one screen off the origin.
        storeAccessor.navigation {
            navigateTo(SubscriptionConfettiScreen, replaceCurrent = true)
            if (origin != null) {
                popUpTo(origin)
            }
        }
        delay(CELEBRATION_MILLIS)
        finish()
    }

    suspend fun cancel() {
        returnToOrigin(consumeOrigin() ?: return)
    }

    suspend fun finish() {
        val state = storeAccessor.selectState<SubscriptionModule.SubscriptionState>().first()
        if (state.phase != SubscriptionPhase.Celebrating) return
        returnToOrigin(consumeOrigin() ?: return)
    }

    private suspend fun consumeOrigin(): String? {
        val origin = storeAccessor.selectState<SubscriptionModule.SubscriptionState>().first().originPath
        storeAccessor.dispatch(SubscriptionModule.SubscriptionAction.Finished)
        return origin
    }

    /**
     * The celebration outlives the screen that started it, and the timer can land after the user has
     * already left by hand, so nothing is popped unless the flow is still what is on screen.
     */
    private suspend fun returnToOrigin(origin: String) {
        val current = storeAccessor.selectState<NavigationState>().first().currentEntry
        val insideFlow = current.navigatable == SubscriptionConfettiScreen ||
            SubscriptionGraph.route in current.graphChain
        if (!insideFlow) return
        storeAccessor.navigation { popUpTo(origin) }
    }

    private companion object {
        const val PAYMENT_MILLIS = 1400L
        const val CELEBRATION_MILLIS = 3200L
    }
}
