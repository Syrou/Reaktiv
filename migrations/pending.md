# Pending API Changes

This file accumulates breaking changes and significant additions introduced since
the last release. On release: rename this file to `v{old}-to-v{new}.md` and reset
it to this header only.

See `migrations/README.md` for the canonical entry format and field rules.

---

## Breaking Changes

<!-- Append BC-NN entries below, incrementing from the last BC ID in this section -->

### [BC-01] ModuleLogic generic type parameter removed

**Type:** Breaking

**Grep:** `ModuleLogic<`
**File glob:** `**/*.kt`

**Before:**
```kotlin
class CounterLogic(private val dispatch: Dispatch) : ModuleLogic<CounterAction>() {
    override suspend fun invoke(action: CounterAction) {
        when (action) {
            is CounterAction.Increment -> { ... }
        }
    }
}
```

**After:**
```kotlin
class CounterLogic(val storeAccessor: StoreAccessor) : ModuleLogic() {
    suspend fun increment() { ... }
}
```

**Notes:** `ModuleLogic` no longer takes a type parameter and no longer has an `invoke()`
method to override. The constructor parameter changes from a `Dispatch` lambda to a
`StoreAccessor`. Apply this change before BC-02.

---

### [BC-02] Logic invoke() handler replaced by public suspend methods

**Type:** Breaking

**Grep:** `override suspend fun invoke(`
**File glob:** `**/*.kt`

**Before:**
```kotlin
override suspend fun invoke(action: MyAction) {
    when (action) {
        is MyAction.DoWork -> performWork()
    }
}
```

**After:**
```kotlin
suspend fun doWork() {
    performWork()
}
```

**Notes:** Remove the entire `invoke()` override. Each handled action becomes its own
public `suspend fun` on the Logic class. Callers that previously dispatched an action to
trigger side effects must now call the method directly via `selectLogic<MyLogic>()` — see
AD-01. Apply after BC-01.

---

### [BC-03] Module.createLogic signature change — Dispatch replaced by StoreAccessor

**Type:** Breaking

**Grep:** `createLogic = { dispatch`
**File glob:** `**/*.kt`

**Before:**
```kotlin
override val createLogic = { dispatch: Dispatch -> MyLogic(dispatch) }
```

**After:**
```kotlin
override val createLogic = { storeAccessor: StoreAccessor -> MyLogic(storeAccessor) }
```

**Notes:** The `createLogic` lambda now receives a `StoreAccessor` instead of a `Dispatch`
function. The `StoreAccessor` exposes `.dispatch()`, `.selectState<T>()`, `.selectLogic<L>()`,
and `.launch { }` so all previous capabilities are still available.

---

### [BC-04] entry(screen), entry{lambda}, startGraph(), startScreen() deprecated in favour of start()

**Type:** Deprecation-removal

**Grep:** `entry(` `startGraph(` `startScreen(`
**File glob:** `**/*.kt`

**Before:**
```kotlin
rootGraph {
    entry(homeScreen)
    screens(homeScreen)
    graph("workspace") {
        startGraph("workspace/home")
    }
    graph("content") {
        entry(route = { store -> if (store.selectState<ContentState>().value.ready) homeScreen else loadingScreen })
        screens(homeScreen, loadingScreen)
    }
}
```

**After:**
```kotlin
rootGraph {
    start(homeScreen)
    screens(homeScreen)
    graph("workspace") {
        start("workspace/home")
    }
    graph("content") {
        start(route = { store -> if (store.selectState<ContentState>().value.ready) homeScreen else loadingScreen })
        screens(homeScreen, loadingScreen)
    }
}
```

**Notes:** All four methods now delegate to the unified `start()` overloads and carry
`@Deprecated` annotations with `ReplaceWith`. See AD-07 for the new API. The deprecated
methods will be removed in a future release.

---

## Additions

<!-- Append AD-NN entries below, incrementing from the last AD ID in this section -->

### [AD-01] Public suspend methods on Logic + selectLogic<>() call pattern

**Type:** Replaces-deprecated

**Grep:** `selectLogic<`
**File glob:** `**/*.kt`

**Replaces:** `override suspend fun invoke(action: T)` dispatching into Logic via action

**Example:**
```kotlin
// Define public methods on the Logic class
class OrderLogic(val storeAccessor: StoreAccessor) : ModuleLogic() {

    suspend fun submitOrder(orderId: String) {
        val result = api.submit(orderId)
        storeAccessor.dispatch(OrderAction.OrderSubmitted(result))
    }

    suspend fun cancelOrder(orderId: String) {
        storeAccessor.dispatch(OrderAction.Cancelled(orderId))
    }
}

// Call from another Logic class
class CheckoutLogic(val storeAccessor: StoreAccessor) : ModuleLogic() {
    suspend fun checkout(orderId: String) {
        val orderLogic = storeAccessor.selectLogic<OrderLogic>()
        orderLogic.submitOrder(orderId)
    }
}

// Call from application code — must be inside a coroutine scope
storeAccessor.launch {
    val orderLogic = storeAccessor.selectLogic<OrderLogic>()
    orderLogic.submitOrder("order-123")
}
```

**Notes:** `selectLogic<L>()` is a suspend function and must be called inside a coroutine
scope (e.g. `storeAccessor.launch { }`, another `suspend fun`, or a Compose
`LaunchedEffect`). The Logic instance is the canonical location for all business logic and
side effects — keep reducers as pure state transformations. See BC-01 and BC-02 for the
removal of the old `invoke()` pattern.

---

### [AD-02] StoreAccessor.getRegisteredModules() for Swift/Obj-C interop

**Type:** Addition

**Grep:** `getRegisteredModules`
**File glob:** `**/*.kt`

**Example:**
```kotlin
// Kotlin — prefer the reified overload instead
val navModule = storeAccessor.getModule<NavigationModule>()
```

```swift
// Swift — KClass cannot be constructed from Swift, use getRegisteredModules() instead
let navModule = store.getRegisteredModules()
    .first { $0 is NavigationModule } as? NavigationModule
```

**Notes:** The recommended primary approach for Swift interop is to expose module instances
as typed properties on your SDK class and pass them directly to `ReaktivState` /
`ReaktivLogic` property wrappers. `getRegisteredModules()` is a fallback for cases where a
direct reference is not available. From Kotlin, always prefer `getModule<M>()` or
`getModule(moduleClass: KClass<M>)`.

---

### [AD-03] Nested `intercept {}` blocks now chain guards (outer-first)

**Type:** Addition

**Grep:** `intercept(`
**File glob:** `**/*.kt`

**Example:**
```kotlin
// Three-level chain: startup → auth → premium.
// Guards run outermost-first. Navigation proceeds only when every guard returns Allow.
// The first non-Allow result stops evaluation; inner guards are never called.
createNavigationModule {
    rootGraph {
        entry(startScreen)
        screens(startScreen, loginScreen)
        intercept(
            guard = { store ->
                if (store.selectState<AppState>().value.startupReady) GuardResult.Allow
                else GuardResult.Reject
            }
        ) {
            intercept(
                guard = { store ->
                    if (store.selectState<AuthState>().value.isAuthenticated) GuardResult.Allow
                    else GuardResult.RedirectTo(loginScreen)
                }
            ) {
                // Free workspace — auth only
                graph("workspace") {
                    entry(homeScreen)
                    screens(homeScreen)
                }

                // Premium workspace — auth + premium check (independent chain)
                intercept(
                    guard = { store ->
                        if (store.selectState<AuthState>().value.hasPremium) GuardResult.Allow
                        else GuardResult.RedirectTo(upgradeScreen)
                    }
                ) {
                    graph("premium") {
                        entry(premiumHome)
                        screens(premiumHome)
                    }
                }
            }
        }
    }
}
```

**Notes:** Previously, nesting two `intercept {}` blocks caused the outer guard to silently
overwrite the inner one — only the outermost guard ever ran. Now guards are chained in
declaration order (outermost first) at any nesting depth. Each wrapped graph accumulates
only the guards that apply to it, so side-by-side `intercept {}` blocks at the same level
are fully independent. The change is backwards-compatible: single-level intercepts behave
identically to before.

The chain is built in two places:
- **`NavigationGraphBuilder.intercept()`** — uses `prependOuter` when stamping a guard onto
  a graph that already carries an inner guard, preserving the full accumulated chain.
- **`NavigationModule.collectGraphs()`** — uses `prependOuter` when propagating a parent
  graph's guard down to nested child graphs.

---

### [AD-04] currentActionResource() composable for screen-defined toolbar actions

**Type:** Addition

**Grep:** `currentActionResource`
**File glob:** `**/*.kt`

**Example:**
```kotlin
// On your Screen, define the action resource
object EditScreen : Screen {
    override val route = "edit"
    override val actionResource: ActionResource = {
        IconButton(onClick = { /* save */ }) {
            Icon(Icons.Default.Check, contentDescription = "Save")
        }
    }

    @Composable
    override fun Content(params: Params) { ... }
}

// In your scaffold or top bar, consume it
@Composable
fun AppTopBar() {
    val actionResource = currentActionResource()
    TopAppBar(
        actions = {
            actionResource?.invoke()
        }
    )
}
```

**Notes:** `currentActionResource()` must be called inside a composable that is a descendant
of `NavigationRender`. It returns the `actionResource` of the currently visible screen, or
`null` if the screen does not define one. Screens that define no `actionResource` leave the
toolbar actions area empty. The value updates automatically whenever navigation changes.

---

### [AD-05] Deep link backstack synthesis anchors root graph entry

**Type:** Addition

**Grep:** `navigateDeepLink`
**File glob:** `**/*.kt`

**Example:**
```kotlin
// Given a module with a root graph (static or dynamic entry) and nested graphs:
createNavigationModule {
    rootGraph {
        entry(splashScreen)
        screens(splashScreen)
        graph("workspace") {
            entry(workspaceHome)
            screens(workspaceHome, workspaceDetail)
        }
    }
}

// Deep linking to a nested screen now synthesizes the full backstack:
// [splashScreen → workspaceHome → workspaceDetail]
store.navigation { navigateDeepLink("workspace/detail") }
```

**Notes:** `navigateDeepLink` now always places the root graph's entry screen at the bottom
of the synthesized backstack before adding intermediate graph entries and the target
destination. This ensures the user can always navigate back to the start of the application.

Dynamic `entry { route = { ... } }` lambdas are evaluated during synthesis, so graphs with
async entry conditions (e.g. feature flags, auth checks) are supported. A root graph that
uses only a dynamic `entry` lambda (no static `startDestination`) requires a `loadingModal`
at the module level to provide the initial app state before synthesis runs.

---

### [AD-07] Unified start() DSL for graph entry configuration

**Type:** Replaces-deprecated

**Grep:** `start(`
**File glob:** `**/*.kt`

**Replaces:** `entry(screen)`, `entry { lambda }`, `startGraph()`, `startScreen()` — see BC-04

**Example:**
```kotlin
createNavigationModule {
    loadingModal(MyLoadingModal)
    rootGraph {
        // Static screen
        start(homeScreen)
        screens(homeScreen, loginScreen)

        graph("workspace") {
            // Static graph reference — forwards entry to the "dashboard" graph
            start("dashboard")
            graph("dashboard") {
                start(dashboardScreen)
                screens(dashboardScreen)
            }
        }

        graph("content") {
            // Dynamic — evaluated at navigation time; loadingModal shown if > 200ms
            start(route = { store ->
                val state = store.selectState<ContentState>().value
                if (state.ready) contentScreen else emptyScreen
            })
            screens(contentScreen, emptyScreen)
        }
    }
}
```

**Notes:** Three overloads replace the old API:
- `start(screen: Screen)` — static screen, replaces `entry(screen)` and `startScreen(screen)`
- `start(graphId: String)` — static graph reference, replaces `startGraph(graphId)`
- `start(route: suspend (StoreAccessor) -> NavigationNode, loadingThreshold: Duration = 200ms)` — dynamic, replaces `entry { }`

`start(graphId)` now correctly handles the case where the referenced graph itself uses a
dynamic `start { }` lambda. A `loadingModal` at the module level is required in that case
so there is a concrete screen to display while the entry condition is evaluated at startup.

---

### [AD-08] Dynamic graph entry lambdas run only on first entry, not on re-entry or synthesis

**Type:** Addition

**Grep:** `start(route =`
**File glob:** `**/*.kt`

**Example:**
```kotlin
graph(Route.Home) {
    start(route = { store ->
        // This lambda now runs ONCE — when the user first enters Home.
        // Navigating between screens inside Home, deep linking into Home,
        // or calling resumePendingNavigation() will NOT re-invoke it.
        val hasArtist = store.selectState<ArtistState>().first().currentArtist != null
        if (hasArtist) releasesScreen else artistOverviewScreen
    })
    screens(releasesScreen, artistOverviewScreen)
}
```

**Notes:** Previously, dynamic `start { route = { ... } }` lambdas on nested graphs were
re-invoked in three situations that should be no-ops:

- **Synthesis** (`resumePendingNavigation`, `navigateDeepLink`): if the graph was already in
  the backstack, the lambda ran again — causing loading screens to reappear and side effects
  (network calls, state waits) to repeat.
- **Re-navigation to the graph route**: calling `navigateTo("home")` while already inside
  the home graph re-invoked the lambda instead of being a no-op.

Both are now fixed. The lambda is invoked only when the backstack contains no entries
belonging to the target graph (first visit). Subsequent navigations within the graph, or
synthesis passes that encounter an already-visited graph, reuse the existing backstack entry.

---

### [AD-09] intercept guard is a gateway — evaluated once per zone entry, not per navigation

**Type:** Addition

**Grep:** `intercept(`
**File glob:** `**/*.kt`

**Example:**
```kotlin
intercept(guard = { store ->
    if (store.selectState<AuthState>().value.isLoggedIn) GuardResult.Allow
    else GuardResult.PendAndRedirectTo(Route.Login)
}) {
    graph(Route.Home) { ... }
    graph(Route.Settings) { ... }
}
```

**Notes:** The `intercept` guard now runs **once** when the user first enters the protected
zone (any graph or screen covered by the `intercept` block). Subsequent navigations between
screens and graphs within the same zone skip the guard entirely — treating it as a gateway
rather than a per-navigation validator.

The guard re-arms in two ways:
- The user navigates **out** of the zone (backstack no longer contains any zone entries),
  then navigates back in — guard runs again.
- `store.reset()` is called — all module states are cleared including the navigation
  backstack, so the zone is considered unvisited.

**Contract for state-change scenarios (e.g. logout):** If auth state changes while the user
is still physically inside the protected zone, the application must navigate the user out
of the zone to force the guard to re-evaluate on next entry:

```kotlin
// On logout: navigate out of the zone so the guard re-arms
store.dispatch(AuthAction.Logout)
store.navigation {
    clearBackStack()
    navigateTo(Route.Login)
}
// Next navigateTo(Route.Home) will run the guard again
```

---

### [AD-06] Entry chain resolution for dynamic entry lambdas returning NavigationPath

**Type:** Addition

**Grep:** `entry(route =`
**File glob:** `**/*.kt`

**Example:**
```kotlin
// A dynamic entry that delegates to another graph's entry
createNavigationModule {
    rootGraph {
        entry(homeScreen)
        screens(homeScreen)
        graph("workspace") {
            // Returns a NavigationPath — system follows the chain
            entry(route = { _ -> NavigationPath("projects") })
            screens(workspaceScreen)
            graph("projects") {
                entry(route = { _ -> projectHomeScreen })
                screens(projectHomeScreen, projectDetail)
            }
        }
    }
}

// Navigating to "workspace" resolves the full chain and lands on projectHomeScreen
store.navigation { navigateTo("workspace") }
```

**Notes:** Previously, navigating to a graph whose dynamic `entry` lambda returned a
`NavigationPath` pointing to another graph would stop at that path without evaluating the
target graph's own entry. The system now follows the chain — evaluating each graph's dynamic
entry in turn — until it reaches a concrete `Navigatable`. Cycle detection prevents infinite
loops if graphs accidentally reference each other.

---
