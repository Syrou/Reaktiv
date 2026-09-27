package io.github.syrou.reaktiv.navigation.history

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.PrecomputedNavigationData
import io.github.syrou.reaktiv.navigation.TraverseDirection
import io.github.syrou.reaktiv.navigation.TraversePresentation
import io.github.syrou.reaktiv.navigation.definition.DismissSource
import io.github.syrou.reaktiv.navigation.model.PendingNavigation
import io.github.syrou.reaktiv.navigation.util.BackDecision
import io.github.syrou.reaktiv.navigation.util.backBlockedByWork
import io.github.syrou.reaktiv.navigation.util.isSystemOverlay
import io.github.syrou.reaktiv.navigation.util.decideBack
import io.github.syrou.reaktiv.navigation.util.impliesBackNavigation
import io.github.syrou.reaktiv.navigation.util.lastStackChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

@OptIn(ExperimentalAtomicApi::class)
internal class HistorySync private constructor(
    private val port: BrowserPort,
    private val logic: NavigationLogic,
    private val precomputedData: PrecomputedNavigationData,
    private val web: WebLocation,
    private val store: StoreAccessor,
    private val scope: CoroutineScope
) : BrowserListener {

    private enum class Kind { Push, Replace }

    private sealed class Origin {
        data object Edit : Origin()
        data class Traversal(val fromIdx: Int) : Origin()
    }

    private sealed class Refusal {
        data object Hold : Refusal()
        data class Run(val handler: suspend StoreAccessor.() -> Unit) : Refusal()
        data object DismissSystem : Refusal()
    }

    private class Target(val snapshot: LocationSnapshot, val digest: String, val url: String)

    private sealed class Event {
        class Committed(val action: NavigationAction, val state: NavigationState) : Event()
        class Landed(val uaTransition: Boolean) : Event()
        data object Insisted : Event()
        class InboundDone(val outcome: ExternalOutcome, val origin: Origin) : Event()
        data object Adopt : Event()
        data object CooldownEnded : Event()
        data object Retry : Event()
        class GoTimedOut(val expectedIdx: Int) : Event()
    }

    private val codec: LocationCodec get() = logic.locationCodec
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val passive = AtomicBoolean(false)
    private val landings = AtomicLong(0L)
    private val navigationState = AtomicReference<StateFlow<NavigationState>?>(null)

    private var current = HistoryEntryState(idx = 0, digest = null, trail = emptyList(), snapshot = null)
    private var pendingGo: Int? = null
    private var afterGo: Kind? = null
    private var awaitingBootstrap = false
    private var resolving = false
    private var inboundDirty = false
    private var insisted = false
    private var refusalStamp = -1L
    private var lastUaTransition = false
    private var cooldown: Job? = null
    private var trailing: Pair<HistoryEntryState, String>? = null
    private var retryPending = false
    private var persistedPending: PendingNavigation? = null

    var coldLocation: ExternalLocation? = null
        private set

    fun onCommitted(action: NavigationAction, state: NavigationState) {
        events.trySend(Event.Committed(action, state))
    }

    fun adopt() {
        events.trySend(Event.Adopt)
    }

    fun goPassive() {
        passive.store(true)
    }

    override fun onTraverseIntent(delta: Int, cancelable: Boolean): Boolean {
        if (passive.load() || delta >= 0) return false
        val state = navigationState.load()?.value ?: return false
        val refusal = refusalFor(state) ?: return false
        if (!cancelable) {
            events.trySend(Event.Insisted)
            return false
        }
        scope.launch { carryOut(refusal) }
        return true
    }

    override fun onLanded(uaTransition: Boolean) {
        landings.incrementAndFetch()
        events.trySend(Event.Landed(uaTransition))
    }

    private fun start(claim: ClaimResult) {
        val previous = (claim as? ClaimResult.Rebound)?.previous as? HistorySync
        val browserState = HistoryEntryState.decode(port.entryState(), codec)
        current = previous?.current ?: browserState ?: current
        pendingGo = previous?.pendingGo
        coldLocation = if (claim is ClaimResult.Bound) coldLocationFor(browserState) else null
        awaitingBootstrap = true
        persistedPending = port.sessionItem(PENDING_KEY)?.let(codec::decodePending)
        if (claim is ClaimResult.Bound) {
            persistedPending?.let { store.dispatch(NavigationAction.SetPendingNavigation(it)) }
        }

        scope.launch {
            navigationState.store(store.selectState<NavigationState>())
            for (event in events) handle(event)
        }
        scope.coroutineContext.job.invokeOnCompletion {
            if (!store.isActive) port.release(store, this)
        }
    }

    private fun coldLocationFor(browserState: HistoryEntryState?): ExternalLocation? {
        val url = port.url()
        val route = web.parse(url.pathname, url.search, url.hash)
        val snapshot = browserState?.snapshot
        if (snapshot != null) {
            return ExternalLocation.Snapshot(
                snapshot,
                ExternalLocation.Url(route.orEmpty(), url.href),
                TraverseDirection.Forward,
                TraversePresentation.AlreadyPresented
            )
        }
        if (route == null || isRoot(route)) return null
        return ExternalLocation.Url(route, url.href)
    }

    private fun isRoot(route: String): Boolean = route.substringBefore('?').isEmpty()

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Committed -> onCommit(event.action, event.state)
            is Event.Landed -> {
                lastUaTransition = event.uaTransition
                onLanding()
            }
            is Event.Insisted -> insisted = true
            is Event.InboundDone -> onInboundDone(event.outcome, event.origin)
            is Event.Adopt -> reconcile(Kind.Replace)
            is Event.CooldownEnded -> {
                cooldown = null
                trailing?.let { (state, url) ->
                    trailing = null
                    perform(Kind.Replace, state, url)
                    startCooldown()
                }
            }
            is Event.Retry -> {
                retryPending = false
                reconcileLatest()
            }
            is Event.GoTimedOut -> if (pendingGo == event.expectedIdx) {
                pendingGo = null
                current = HistoryEntryState.decode(port.entryState(), codec) ?: current
                val kind = afterGo ?: Kind.Replace
                afterGo = null
                reconcile(kind)
            }
        }
    }

    private fun onCommit(action: NavigationAction, state: NavigationState) {
        if (passive.load()) return
        persistPending(state.pendingNavigation)
        if (awaitingBootstrap) {
            if (state.isBootstrapping) return
            awaitingBootstrap = false
            if (pendingGo == null) reconcile(Kind.Replace) else afterGo = Kind.Replace
            return
        }
        if (resolving || state.isBootstrapping) return
        if (pendingGo != null) return
        val target = targetOf(state) ?: return
        if (target.digest == current.digest) return

        val changes = (action as? NavigationAction.AtomicBatch)?.actions ?: listOf(action)
        val change = changes.lastStackChange()
        if (change.impliesBackNavigation()) {
            val index = current.trail.lastIndexOf(target.digest)
            if (index >= 0) {
                go(-(current.trail.size - index))
                return
            }
        }
        val completesResume = change != null && changes.any { it is NavigationAction.ClearPendingNavigation }
        val keepsTop = current.snapshot?.top != null && current.snapshot?.top == target.snapshot.top
        val kind = if (change is NavigationAction.Replace || completesResume || keepsTop) Kind.Replace else Kind.Push
        write(target, kind)
    }

    private fun onLanding() {
        if (passive.load()) return
        val landed = HistoryEntryState.decode(port.entryState(), codec)
        val expected = pendingGo
        if (expected != null && landed?.idx == expected) {
            pendingGo = null
            current = landed
            val kind = afterGo
            afterGo = null
            when {
                awaitingBootstrap -> Unit
                kind != null -> reconcile(kind)
                else -> reconcileLatest()
            }
            return
        }
        pendingGo = null
        afterGo = null
        if (awaitingBootstrap) {
            if (landed != null) current = landed
            return
        }
        if (resolving) {
            inboundDirty = true
            return
        }

        val url = port.url()
        val route = web.parse(url.pathname, url.search, url.hash)
        if (landed == null) {
            current = HistoryEntryState(current.idx + 1, null, trailAfter(current), null)
            if (route == null || isRoot(route)) {
                reconcile(Kind.Replace)
            } else {
                startInbound(ExternalLocation.Url(route, url.href), Origin.Edit)
            }
            return
        }

        val delta = landed.idx - current.idx
        if (delta == 0 && landed.digest == current.digest) return
        if (delta < 0 && !insisted && !port.offersTraverseIntents) {
            val refusal = navigationState.load()?.value?.let(::refusalFor)
            if (refusal != null && port.activationCount != refusalStamp) {
                refusalStamp = port.activationCount
                expectLanding(current.idx)
                port.go(-delta)
                scope.launch { carryOut(refusal) }
                return
            }
        }
        insisted = false

        val fromIdx = current.idx
        current = landed
        val urlLocation = ExternalLocation.Url(route.orEmpty(), url.href)
        val snapshot = landed.snapshot
        val location = if (snapshot == null) {
            urlLocation
        } else {
            val ticket = landings.load()
            ExternalLocation.Snapshot(
                snapshot,
                urlLocation,
                if (delta < 0) TraverseDirection.Back else TraverseDirection.Forward,
                if (lastUaTransition) TraversePresentation.AlreadyPresented else TraversePresentation.Animate,
                isCurrent = { landings.load() == ticket }
            )
        }
        startInbound(location, Origin.Traversal(fromIdx))
    }

    private fun startInbound(location: ExternalLocation, origin: Origin) {
        resolving = true
        scope.launch {
            val outcome = try {
                logic.applyExternalLocation(location)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                ReaktivDebug.warn("HistorySync: the browser location could not be applied - ${failure.message}")
                ExternalOutcome.Unresolvable(failure.message.orEmpty())
            }
            events.send(Event.InboundDone(outcome, origin))
        }
    }

    private fun onInboundDone(outcome: ExternalOutcome, origin: Origin) {
        resolving = false
        if (inboundDirty) {
            inboundDirty = false
            onLanding()
            return
        }
        when (outcome) {
            is ExternalOutcome.Landed, is ExternalOutcome.LandedOnNotFound, is ExternalOutcome.Redirected ->
                reconcile(Kind.Replace)
            is ExternalOutcome.Rejected, is ExternalOutcome.Unresolvable -> {
                if (origin is Origin.Traversal && origin.fromIdx != current.idx) {
                    val delta = origin.fromIdx - current.idx
                    expectLanding(origin.fromIdx)
                    port.go(delta)
                } else {
                    reconcile(Kind.Replace)
                }
            }
            is ExternalOutcome.Stale -> reconcileLatest()
            is ExternalOutcome.Dropped -> Unit
        }
    }

    private fun persistPending(pending: PendingNavigation?) {
        if (pending == persistedPending) return
        persistedPending = pending
        port.storeSessionItem(PENDING_KEY, pending?.let(codec::encodePending))
    }

    private fun reconcile(kind: Kind) {
        val state = navigationState.load()?.value ?: return
        val target = targetOf(state) ?: return
        if (target.digest == current.digest) return
        write(target, kind)
    }

    private fun reconcileLatest() {
        if (awaitingBootstrap) return
        val state = navigationState.load()?.value ?: return
        val action = state.lastNavigationAction
        if (action == null) reconcile(Kind.Replace) else onCommit(action, state)
    }

    private fun targetOf(state: NavigationState): Target? {
        val top = state.backStack.lastOrNull(codec::isAddressable) ?: return null
        val snapshot = codec.snapshotOf(state.backStack)
        val url = if (top.navigatable == precomputedData.notFoundScreen) {
            port.url().href
        } else {
            web.format(top, top.navigatable.hiddenUrlParams)
        }
        return Target(snapshot, codec.digest(codec.encode(snapshot)), url)
    }

    private fun trailAfter(entry: HistoryEntryState): List<String> =
        (entry.trail + listOfNotNull(entry.digest)).takeLast(HistoryEntryState.TRAIL_LIMIT)

    private fun go(delta: Int) {
        flushTrailing()
        expectLanding(current.idx + delta)
        port.go(delta)
    }

    private fun expectLanding(idx: Int) {
        pendingGo = idx
        scope.launch {
            delay(GO_TIMEOUT_MS)
            events.send(Event.GoTimedOut(idx))
        }
    }

    private fun write(target: Target, kind: Kind) {
        when (kind) {
            Kind.Push -> {
                flushTrailing()
                perform(
                    Kind.Push,
                    HistoryEntryState(current.idx + 1, target.digest, trailAfter(current), target.snapshot),
                    target.url
                )
            }
            Kind.Replace -> {
                val state = HistoryEntryState(current.idx, target.digest, current.trail, target.snapshot)
                if (cooldown?.isActive == true) {
                    current = state
                    trailing = state to target.url
                } else {
                    perform(Kind.Replace, state, target.url)
                    startCooldown()
                }
            }
        }
    }

    private fun flushTrailing() {
        val (state, url) = trailing ?: return
        trailing = null
        perform(Kind.Replace, state, url)
    }

    private fun startCooldown() {
        cooldown = scope.launch {
            delay(REPLACE_INTERVAL_MS)
            events.send(Event.CooldownEnded)
        }
    }

    private fun perform(kind: Kind, state: HistoryEntryState, url: String) {
        when (portWrite(kind, state, url)) {
            WriteResult.Written -> current = state
            WriteResult.TooLarge -> {
                ReaktivDebug.warn(
                    "HistorySync: the navigation state is too large for the browser history entry, so " +
                        "this entry will restore from its URL only"
                )
                val lossy = state.lossy()
                if (portWrite(kind, lossy, url) == WriteResult.Written) current = lossy
            }
            WriteResult.Throttled -> {
                ReaktivDebug.warn("HistorySync: the browser throttled a history write, retrying shortly")
                scheduleRetry()
            }
            WriteResult.Failed -> ReaktivDebug.warn("HistorySync: the browser refused a history write")
        }
    }

    private fun portWrite(kind: Kind, state: HistoryEntryState, url: String): WriteResult = when (kind) {
        Kind.Push -> port.push(state.encode(codec), url)
        Kind.Replace -> port.replace(state.encode(codec), url)
    }

    private fun scheduleRetry() {
        if (retryPending) return
        retryPending = true
        scope.launch {
            delay(RETRY_MS)
            events.send(Event.Retry)
        }
    }

    private fun refusalFor(state: NavigationState): Refusal? {
        val top = state.currentEntry
        val isSystem = top.navigatable.isSystemOverlay
        if (!isSystem && backBlockedByWork(state)) {
            return Refusal.Hold
        }
        return when (
            val decision = decideBack(top, state.revealedEntry, DismissSource.Back, precomputedData.graphDefinitions)
        ) {
            is BackDecision.Pop -> if (isSystem) Refusal.DismissSystem else null
            is BackDecision.Refuse -> Refusal.Hold
            is BackDecision.Run -> Refusal.Run(decision.handler)
        }
    }

    private suspend fun carryOut(refusal: Refusal) {
        when (refusal) {
            is Refusal.Hold -> Unit
            is Refusal.Run -> refusal.handler(store)
            is Refusal.DismissSystem -> logic.navigateBack()
        }
    }

    companion object {
        private const val REPLACE_INTERVAL_MS = 350L
        private const val RETRY_MS = 1_000L
        private const val GO_TIMEOUT_MS = 5_000L
        private const val PENDING_KEY = "reaktiv.pendingNavigation"

        fun attach(
            setup: BrowserHistorySetup,
            logic: NavigationLogic,
            precomputedData: PrecomputedNavigationData,
            store: StoreAccessor,
            scope: CoroutineScope
        ): HistorySync? {
            val port = setup.port ?: return null
            val web = setup.webLocation ?: return null
            val sync = HistorySync(port, logic, precomputedData, web, store, scope)
            val claim = port.claim(store, sync)
            if (claim is ClaimResult.Denied) {
                check(setup.mode != BrowserHistoryMode.Always) {
                    "Browser history is set to Always, but another store already drives this page's " +
                        "browser history. Only one store per page can own it."
                }
                ReaktivDebug.warn(
                    "HistorySync: another store already drives this page's browser history, so this " +
                        "store runs without it"
                )
                return null
            }
            sync.start(claim)
            return sync
        }
    }
}
