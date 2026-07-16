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

### [BC-05] `Modal.dismissable` and `Modal.tapOutsideToDismiss` removed

**Type:** Breaking

**Grep:** `dismissable\|tapOutsideToDismiss`
**File glob:** `**/*.kt`

**Before:**
```kotlin
object MandatoryModal : Modal {
    override val dismissable = false
    override val tapOutsideToDismiss = false
}
```

**After:**
```kotlin
object MandatoryModal : Modal {
    override val tapOutsideClick = null
}
```

**Notes:** Both flags are replaced by a single `tapOutsideClick` lambda on `Modal`.
`navigateBack()` is now always allowed (except during a `LoadingModal`). To dismiss on
tap-outside, provide a lambda; to block it, set `null` (the default). See AD-12.

---

### [BC-06] `StoreAccessor.resumePendingNavigation()` removed

**Type:** Breaking

**Grep:** `resumePendingNavigation()`
**File glob:** `**/*.kt`

**Before:**
```kotlin
store.resumePendingNavigation()
```

**After:**
```kotlin
store.navigation {
    clearBackStack()
    resumePendingNavigation()
}
```

**Notes:** The standalone extension is removed. Use `resumePendingNavigation()` inside a
`navigation { }` block where order of operations (e.g. `clearBackStack()` before or after)
is explicit. See AD-13.

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

### [AD-10] Deep link alias patterns support path parameter extraction and full URL patterns

**Type:** Addition

**Grep:** `alias(`
**File glob:** `**/*.kt`

**Example:**
```kotlin
deepLinkAliases {
    alias(
        pattern = "{scheme}://{host}/invitations/team/confirm/{token}",
        targetRoute = "workspace/invite/{token}"
    ) { params ->
        Params.of("token" to (params["token"] as? String ?: ""))
    }
}

store.navigateDeepLink("https://staging.example.com/invitations/team/confirm/eyJhbGci...")
```

**Notes:** Previously, alias pattern matching used exact string equality — patterns containing
`{param}` placeholders would never match an incoming URL. Patterns are now compiled to regex
using the same `createRouteRegex` / `extractRouteParameterNames` utilities that power
`RouteResolver`'s parameterized route matching.

Path parameters captured from the pattern (e.g. `{scheme}`, `{host}`, `{token}`) are
extracted and merged with any query parameters before being passed to `paramsMapping`.
Query parameters take precedence when the same key appears in both.

Existing exact-string patterns (no placeholders) continue to work without changes.

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

### [AD-12] `Modal.tapOutsideClick` replaces `dismissable` + `tapOutsideToDismiss`

**Type:** Replaces-deprecated

**Grep:** `tapOutsideClick`
**File glob:** `**/*.kt`

**Replaces:** `Modal.dismissable` and `Modal.tapOutsideToDismiss` — see BC-05

**Example:**
```kotlin
// Dismiss on tap-outside
object InfoModal : Modal {
    override val tapOutsideClick: (suspend StoreAccessor.() -> Unit) = { navigateBack() }
}

// No tap-outside dismiss (default)
object MandatoryModal : Modal {
    // tapOutsideClick is null by default — nothing happens on outside tap
}

// Custom behaviour on tap-outside
object UnsavedChangesModal : Modal {
    override val tapOutsideClick: (suspend StoreAccessor.() -> Unit) = {
        navigation { navigateTo(DiscardWarningModal) }
    }
}
```

**Notes:** `navigateBack()` now always works for all modals (user back gesture, programmatic
code). The only exception is `LoadingModal` — back is blocked while async guard evaluation
is in progress to prevent state corruption. The tap-capturing layer is always present
regardless of `shouldDimBackground`, so `tapOutsideClick` fires even on non-dimmed modals.

---

### [AD-13] `resumePendingNavigation()` chainable inside `navigation { }` DSL

**Type:** Addition

**Grep:** `resumePendingNavigation`
**File glob:** `**/*.kt`

**Example:**
```kotlin
// Equivalent to old store.resumePendingNavigation()
store.navigation {
    clearBackStack()
    resumePendingNavigation()
}

// Resume but keep existing backstack as base
store.navigation {
    navigateTo("home")
    resumePendingNavigation()
}

// Post-login: dismiss modal then resume
store.navigation {
    navigateBack()
    clearBackStack()
    resumePendingNavigation()
}
```

**Notes:** `resumePendingNavigation()` is now a `NavigationOperation.ResumePending` step
that expands inline at execution time using the simulated backstack at that point in the
chain. It synthesizes the pending route's full path hierarchy on top of whatever the
simulated stack contains — order of preceding operations (e.g. `clearBackStack()`) directly
determines the final stack shape. No-op when `NavigationState.pendingNavigation` is null.

---

### [AD-14] All navigation DSL operations now preserved when navigateTo targets a dynamic graph

**Type:** Addition

**Grep:** `navigateTo.*workspace\|clearBackStack.*navigateTo\|navigateTo.*resumePending`
**File glob:** `**/*.kt`

**Example:**
```kotlin
store.navigation {
    clearBackStack()
    navigateTo(Route.Home) {
        param("ref", SubscriptionReferenceType.Signup.name)
    }
    resumePendingNavigation()
}
```

**Notes:** Previously, when a `navigation { }` block targeted a dynamic graph (one whose
`start` is an async lambda), only the resolved `navigateTo` step was executed — all
surrounding operations (`clearBackStack`, `navigateBack`, `popUpTo`,
`resumePendingNavigation`, secondary `navigateTo`) were silently dropped.

The root cause was that the dynamic-graph resolution path constructed a brand-new
`NavigationBuilder` containing only the resolved Navigate step instead of preserving the
original operation sequence. The fix rebuilds the full sequence around the resolved step:
operations before the primary Navigate are copied verbatim, the graph navigation is
replaced with its resolved screen, and all operations after are appended unchanged.

Any combination of DSL operations may now appear before or after a `navigateTo(dynamicGraph)`
call in the same block.

---

### [AD-15] `resumePendingNavigation()` no longer injects root graph entry into non-empty backStack

**Type:** Addition

**Grep:** `resumePendingNavigation`
**File glob:** `**/*.kt`

**Example:**
```kotlin
store.navigation {
    clearBackStack()
    navigateTo(Route.Workspace)
    resumePendingNavigation()
}
```

**Notes:** Previously, `resumePendingNavigation()` always added the root graph's resolved
start entry at the bottom of the synthesized backStack, even when the backStack already
contained entries. This caused the root start screen (e.g. a splash or loading screen) to
be re-injected after the user had intentionally navigated past it.

The root entry is now only synthesized when `simulatedBackStack` is empty at the point
`resumePendingNavigation()` executes. If the backStack already has entries — whether from a
preceding `navigateTo`, a `popUpTo` that kept some history, or entries from before the block
— those serve as the anchor and the root start is not added.

`navigateDeepLink` is unaffected: it always clears the backStack first, so synthesis starts
from empty and the root entry is still anchored at the bottom as documented in AD-05.

---

### [AD-11] Modals and screens registered directly inside intercept { } are now guarded

**Type:** Addition

**Grep:** `intercept.*modals\|modals.*intercept`
**File glob:** `**/*.kt`

**Example:**
```kotlin
createNavigationModule {
    rootGraph {
        start(startScreen)
        screens(startScreen, loginScreen)
        intercept(
            guard = { store ->
                if (store.selectState<AuthState>().value.isLoggedIn) GuardResult.Allow
                else GuardResult.PendAndRedirectTo(loginScreen)
            }
        ) {
            // Modals placed here (not inside a named graph) are now guarded
            modals(InvitationModal)
            graph("workspace") {
                start(homeScreen)
                screens(homeScreen)
            }
        }
    }
}
```

**Notes:** Previously, navigatables (modals, screens) registered directly inside an
`intercept { }` block — rather than inside a named nested `graph { }` block — were promoted
to the parent graph without retaining their intercept context. Navigation to those routes
would succeed without evaluating the guard. This is fixed via a new `navigatableIntercepts`
carrier on `NavigationGraph` that associates directly-nested navigatables with their guard
and is consumed during precomputation to register the correct `interceptedRoutes` entry.
No API change is required — existing `intercept { modals(...) }` usage now behaves correctly.

---

### [BC-07] Remove NavigationAction.RemoveLoadingModals

**Type:** Breaking

**Grep:** `RemoveLoadingModals`
**File glob:** `**/*.kt`

**Before:**
```kotlin
storeAccessor.dispatchAndAwait(NavigationAction.RemoveLoadingModals)
```

**After:**
```kotlin
storeAccessor.dispatchAndAwait(NavigationAction.SetEvaluating(false))
```

**Notes:** Loading modals are no longer pushed to the navigation backstack during guard/entry
evaluation. The evaluation overlay is now controlled by `NavigationState.isEvaluatingNavigation`.
Direct dispatch of `RemoveLoadingModals` is no longer needed. See AD-16 for the new API.

---

### [AD-16] NavigationState.isEvaluatingNavigation and NavigationAction.SetEvaluating

**Type:** Addition

**Grep:** `isEvaluatingNavigation`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val state by selectState<NavigationState>().collectAsState()
if (state.isEvaluatingNavigation) {
    // guard or entry-definition is being evaluated; loading overlay is visible
}
```

**Notes:** The evaluation overlay (loading modal shown during guard/entry-definition evaluation)
is now a pure boolean flag rather than a backstack entry. This eliminates the flash of the
previous screen when navigating through a guarded route. `NavigationRender` renders the
`LoadingModal` directly as a `zIndex(9001f)` overlay when `isEvaluatingNavigation` is `true`.
See BC-07 for the removed `RemoveLoadingModals` action.

---
### [BC-08] navigateBack() is a no-op while isEvaluatingNavigation is true

**Type:** Behavioural

**Grep:** `navigateBack`
**File glob:** `**/*.kt`

**Before:**
```kotlin
// navigateBack() during guard/entry evaluation dispatched Back and could
// corrupt the backstack that the in-flight evaluation was about to commit against
store.navigateBack()
```

**After:**
```kotlin
// Same call, now silently ignored while NavigationState.isEvaluatingNavigation
// is true. No code change needed unless you relied on back landing mid-evaluation.
store.navigateBack()
```

**Notes:** `navigateBack()` bypasses the navigation mutex by design. Previously only a
`LoadingModal` backstack entry blocked it, but since the evaluation overlay became a boolean
(see AD-16), that check no longer covered evaluation. The new gate closes the window where a
Back dispatched during async guard evaluation interleaved with the pending forward navigation.

---

### [BC-09] Toolchain modernised: Kotlin 2.4.10, Gradle 9.6.1, AGP 9.3.0, Compose Multiplatform 1.11.1

**Type:** Behavioural

**Grep:** `io.github.syrou:reaktiv`
**File glob:** `**/build.gradle.kts`

**Before:**
```kotlin
// Consumers on Kotlin 2.2.x / Compose Multiplatform 1.8.x
```

**After:**
```kotlin
// Consumers should upgrade to Kotlin 2.4+ and Compose Multiplatform 1.11+.
// Native/wasm klibs produced by Kotlin 2.4.10 are not consumable by older compilers.
```

**Notes:** Library artifacts are now built with Kotlin 2.4.10 and Compose Multiplatform 1.11.1.
JVM/Android consumers on slightly older Kotlin generally keep working (metadata n+1 rule), but
KMP native/wasm consumers must be on a compiler able to read 2.4 klibs. kotlinx dependency
floors: coroutines 1.11.0, serialization 1.11.0, kotlinx-datetime 0.8.0.

---

### [BC-10] macosX64 target removed from reaktiv-compose, reaktiv-navigation, reaktiv-devtools

**Type:** Breaking

**Grep:** `macosX64`
**File glob:** `**/build.gradle.kts`

**Before:**
```kotlin
kotlin {
    macosX64()
    sourceSets.commonMain.dependencies {
        implementation("io.github.syrou:reaktiv-navigation:<version>")
    }
}
```

**After:**
```kotlin
kotlin {
    // macosX64 no longer supported by Compose-dependent Reaktiv modules;
    // Apple Silicon (macosArm64) remains supported.
    macosArm64()
}
```

**Notes:** Compose Multiplatform 1.11 no longer publishes macosX64 artifacts, so the
Compose-dependent modules had to drop the target. reaktiv-core and reaktiv-introspection
still publish macosX64. The reaktiv-devtools server no longer ships an Intel-mac executable.

---
### [BC-11] NavigationAnimations.AnimatedEntry is now internal

**Type:** Breaking

**Grep:** `NavigationAnimations.AnimatedEntry`
**File glob:** `**/*.kt`

**Before:**
```kotlin
NavigationAnimations.AnimatedEntry(entry, type, decision, w, h) { content() }
```

**After:**
```kotlin
NavigationRender()
```

**Notes:** AnimatedEntry was accidental public surface: a low-level rendering detail consumed
only by the library's own layer renderers. Apps should render through NavigationRender. If you
were composing entries manually, open an issue describing the use case.

---
### [AD-17] Interactive iOS-style edge-swipe back gesture in NavigationRender

**Type:** Addition

**Grep:** `NavigationRender`
**File glob:** `**/*.kt`

**Example:**
```kotlin
StoreProvider(store) {
    NavigationRender()
}
```

**Notes:** NavigationRender now recognises a left-edge (RTL: right-edge) horizontal drag on
content screens and scrubs an interactive pop: the current screen follows the finger while the
previous backstack entry renders underneath with a parallax reveal. Release past 30% progress
or with a fast fling commits the back navigation (exactly one Back action); otherwise the
gesture cancels and the screen settles back with its state intact. The scrub transforms are
derived by reversing the push: popExitTransition/popEnterTransition win when set, otherwise
the enter/exit transitions are played backwards, and transitionless screens fall back to the
IOSSlideIn/IOSSlideOut pair. The gesture arms only when the stack can pop, no modal is on top,
no evaluation/bootstrap is in flight and the revealed entry would not restore a modal context.

Horizontally scrollable content coordinates with the back gesture through nested scrolling,
using the same rule as the vertical dismiss: while the content is scrolled to its start, a
backward drag's unconsumed leftover hands off to the back scrub from anywhere over the
content; content scrolled forward consumes the drag normally and never pops until it returns
to its start.

The preview works across layout-graph boundaries (e.g. popping from a sub graph with its own
chrome back to a parent-graph screen): shared layout chrome renders once and stays static,
while each screen scrubs wrapped in its own unique chrome, mirroring the strategy the timed
renderer uses. Caveat: for cross-hierarchy pairs the screens compose into dedicated preview
slots, so a cancelled gesture recomposes the top screen fresh; same-hierarchy pairs keep
composition state through arm, cancel and commit.

The gesture is platform-scoped with no configuration knob: active on Apple and desktop
targets. On Android it follows the system navigation mode, detected via
WindowInsets.systemGestures: under gesture navigation the OS owns the edges and the system
predictive back gesture (AD-21) provides the interactive pop, so the in-app recognizer stays
off; under 2- or 3-button navigation the edges are free and the in-app edge swipe activates,
giving button-navigation users an interactive pop they otherwise never get. Conflict
arbitration follows Compose's deepest-child-wins pointer model: screen content (pagers,
carousels, sliders) consumes drags before the navigation gesture, and the navigation gesture
consumes before app chrome such as drawers. At the stack root the gesture never arms, so a
ModalNavigationDrawer receives edge drags there untouched. Apps that want Material-style
drawer-everywhere priority on gesture platforms flip it with their existing drawer:

```kotlin
val navState by composeState<NavigationState>()
ModalNavigationDrawer(
    gesturesEnabled = drawerState.isOpen || !navState.canGoBack,
    ...
)
```

Per-screen opt-out remains `backGestureEnabled = false` (AD-18).

The gesture also respects the presentation axis: screens whose pop motion is vertical
(SlideUpBottom/SlideOutBottom/StackPush/StackPop via popExitTransition or enterTransition)
never arm the horizontal edge swipe; they dismiss with the vertical swipe instead (AD-19),
matching iOS where the interactive pop applies only to horizontal pushes and vertically
presented screens dismiss downward. Custom transitions classify as neutral (edge swipe arms
with the IOS fallback pair); override backGestureEnabled/swipeToDismiss for vertical Custom
transitions.

---

### [AD-18] Navigatable.backGestureEnabled

**Type:** Addition

**Grep:** `backGestureEnabled`
**File glob:** `**/*.kt`

**Example:**
```kotlin
object MapScreen : Screen {
    override val route = "map"
    override val backGestureEnabled = false

    @Composable
    override fun Content(params: Params) { MapView() }
}
```

**Notes:** Defaults to true. Set to false on screens whose content owns horizontal drags
(maps, carousels) or that must not be interactively popped (login). Disables only the
interactive scrub; Android system back still works through the commit-only path. See AD-17.

---

### [AD-19] Navigatable.swipeToDismiss

**Type:** Addition

**Grep:** `swipeToDismiss`
**File glob:** `**/*.kt`

**Example:**
```kotlin
object FilterSheet : Screen {
    override val route = "filters"
    override val enterTransition = NavTransition.SlideUpBottom
    override val exitTransition = NavTransition.SlideOutBottom
    override val swipeToDismiss = true

    @Composable
    override fun Content(params: Params) { FilterContent() }
}
```

**Notes:** Available on both Screens and Modals. Defaults follow iOS conventions: Modals are
swipe-dismissable by default (like UIKit sheets with isModalInPresentation = false), Screens
default to false (like full-screen pushes), and LoadingModal is never dismissable. Override
per navigatable to change. A downward drag scrubs the navigatable through its exit transition
(fallback SlideOutBottom) while the underlying screen animates forward beneath it, iOS
card-stack style: popEnterTransition wins when set, otherwise the underlying screen's own
exit transition plays backwards, otherwise it recedes back from 94% to full scale as the
sheet departs. The modal dimmer follows the drag. Commit dispatches through the dismiss
funnel (see AD-20).
Scrollable content inside a dismissable screen or modal coordinates with the gesture through
nested scrolling, matching iOS sheet behaviour and Material's ModalBottomSheet: while the
content is scrolled to the top, further downward drag hands off to the dismiss scrub; pulling
back up reduces the scrub to zero before scrolling resumes; mid-content drags scroll normally
and never trigger dismissal.
A downward drag starting in the top 32dp of the screen always dismisses, regardless of what
the content underneath does with drags: the vertical analogue of the horizontal edge-swipe
zone. This guarantees dismissability even when a component owns at-top downward drags in a
way nested scrolling cannot observe, such as `PullToRefreshBox`, without any per-screen
wiring. Taps and horizontal drags in the zone pass through untouched; content pulls below
the zone still refresh.
On platforms where the in-app edge swipe is active (Apple, desktop, Android button
navigation), the horizontal back pan also arms from anywhere on the screen when no child
claims the drag, matching modern iOS full-surface interactive pop: scrollables and other
drag consumers always win, the edge zone still steals over horizontal scrollables, and
mid-position horizontal scrollables hand off to the back scrub when they reach their start.

---

### [AD-20] Navigatable.onDismissRequest unified dismiss funnel

**Type:** Replaces-deprecated

**Grep:** `onDismissRequest`
**File glob:** `**/*.kt`

**Replaces:** `Modal.tapOutsideClick` (now deprecated, still functional)

**Example:**
```kotlin
object EditorSheet : Screen {
    override val route = "editor"
    override val swipeToDismiss = true
    override val onDismissRequest: (suspend StoreAccessor.() -> Unit) = {
        val state = selectState<EditorState>().first()
        if (!state.hasUnsavedChanges) navigateBack()
    }

    @Composable
    override fun Content(params: Params) { Editor() }
}
```

**Notes:** One optional handler invoked by every dismiss input: edge-swipe commit, swipe-down
commit, Android system back and (for modals) tap-outside. When null, gestures and system back
default to navigateBack() and tap-outside falls back to the deprecated tapOutsideClick (which
still defaults to doing nothing). If the handler declines (navigation state unchanged), the
scrubbed screen animates back into place.

---

### [AD-21] Automatic platform back handling and NavigationRender(handlePlatformBack)

**Type:** Addition

**Grep:** `handlePlatformBack`
**File glob:** `**/*.kt`

**Example:**
```kotlin
NavigationRender(handlePlatformBack = false)
```

**Notes:** On Android, NavigationRender now installs a PredictiveBackHandler that drives the
same interactive transition controller as the edge-swipe gesture: on Android 14+ the system
predictive-back gesture scrubs the pop preview, and on older devices or 3-button navigation
the flow completes commit-only with a normal animated pop. Remove app-level BackHandler blocks
that called navigateBack(), or pass handlePlatformBack = false to keep them. Adding the
defaulted parameter is source-compatible but binary-breaking (acceptable pre-1.0). Apple and
desktop targets are no-ops (the edge swipe is the mechanism there).

---

### [BC-12] ReaktivDebug mode and category helpers removed

**Type:** Breaking

**Grep:** `ReaktivDebug.(developmentMode|productionMode|enableOnly|compose|state|action|debug)`
**File glob:** `**/*.kt`

**Before:**
```kotlin
ReaktivDebug.developmentMode()
ReaktivDebug.enableOnly("NAV", "STATE")
ReaktivDebug.debug("CUSTOM", "message")
```

**After:**
```kotlin
ReaktivDebug.enable()
ReaktivDebug.general("message")
```

**Notes:** The category-filtering mechanism is gone; `enable()`/`disable()` is the only toggle
and no longer prints a confirmation line. Remaining loggers: `nav`, `store`, `general`,
`trace`, `warn`, `error`.

---

### [BC-13] Compose select() delegate removed

**Type:** Breaking

**Grep:** `select<`
**File glob:** `**/*.kt`

**Before:**
```kotlin
val count by select<TodoState, Int> { state -> state.items.size }
```

**After:**
```kotlin
val state by composeState<TodoState>()
val count = state.items.size
```

**Notes:** `StoreSelect.kt` was removed entirely. `composeState` is the single state
observation API for Compose.

---

### [BC-14] onActiveValueChange removed

**Type:** Breaking

**Grep:** `onActiveValueChange`
**File glob:** `**/*.kt`

**Before:**
```kotlin
onActiveValueChange<NavigationState, String>(
    selector = { it.currentEntry.path }
) { route -> analytics.trackScreenView(route) }
```

**After:**
```kotlin
val state by composeState<NavigationState>()
LaunchedEffect(state.currentEntry.path) {
    analytics.trackScreenView(state.currentEntry.path)
}
```

---

### [BC-15] Preview overloads composeState(initialValue)/selectState(initialValue) removed

**Type:** Breaking

**Grep:** `composeState\(initialValue|selectState\(initialValue|composeState\([^)]|selectState\([^)]`
**File glob:** `**/*.kt`

**Before:**
```kotlin
val state by composeState<CounterState>(initialValue = CounterState(count = 42))
```

**After:**
```kotlin
val state by composeState<CounterState>()
```

**Notes:** For previews, wrap the preview content in a `StoreProvider` with a store built
from the module's real initial state instead of passing a detached initial value.

---

### [BC-16] Deprecated entry()/startScreen()/startGraph() removed

**Type:** Deprecation-removal

**Grep:** `entry\(|startScreen\(|startGraph\(`
**File glob:** `**/*.kt`

**Before:**
```kotlin
graph("home") {
    startScreen(HomeScreen)
    graph("news") {
        startGraph("feed")
    }
    entry(SplashScreen)
    entry(route = { storeAccessor -> resolveStart(storeAccessor) })
}
```

**After:**
```kotlin
graph("home") {
    start(HomeScreen)
    graph("news") {
        start("feed")
    }
    start(SplashScreen)
    start(route = { storeAccessor -> resolveStart(storeAccessor) })
}
```

**Notes:** Completes the deprecation from BC-04; `start()` (AD-07) is the single entry-point
DSL. All overloads map one-to-one.

---

### [BC-17] Modal.tapOutsideClick removed

**Type:** Deprecation-removal

**Grep:** `tapOutsideClick`
**File glob:** `**/*.kt`

**Before:**
```kotlin
object MyModal : Modal {
    override val tapOutsideClick: (suspend StoreAccessor.() -> Unit) = { navigateBack() }
}
```

**After:**
```kotlin
object MyModal : Modal {
    override val onDismissRequest: (suspend StoreAccessor.() -> Unit) = { navigateBack() }
}
```

**Notes:** `onDismissRequest` (AD-20) unifies tap-outside, swipe-to-dismiss and system back
into one dismiss funnel. Tap-outside now only triggers `onDismissRequest`.

---

### [BC-18] DevToolsLogic.exportSessionJson/exportCrashSessionJson removed

**Type:** Breaking

**Grep:** `exportSessionJson|exportCrashSessionJson`
**File glob:** `**/*.kt`

**Before:**
```kotlin
val json = devToolsLogic.exportSessionJson()
val crashJson = devToolsLogic.exportCrashSessionJson(throwable)
```

**After:**
```kotlin
val json = introspectionLogic.exportSessionJson()
val crashJson = introspectionLogic.exportCrashSessionJson(throwable)
```

**Notes:** Session export belongs to introspection; the devtools copies were unused
duplicates of the `IntrospectionLogic` methods backed by the same shared `SessionCapture`.

---

### [AD-22] Shared core utilities: currentTimeMillis() and reaktivJson()

**Type:** Addition

**Grep:** `currentTimeMillis\(\)|reaktivJson\(`
**File glob:** `**/*.kt`

**Example:**
```kotlin
import io.github.syrou.reaktiv.core.util.currentTimeMillis
import io.github.syrou.reaktiv.core.util.reaktivJson

val timestamp = currentTimeMillis()
val json = reaktivJson(store.serializersModule)
val exportJson = reaktivJson(encodeDefaults = true)
```

**Notes:** `currentTimeMillis()` replaces scattered `Clock.System.now().toEpochMilliseconds()`
call sites and hides the `ExperimentalTime` opt-in. `reaktivJson()` is the single factory for
`Json` instances across all Reaktiv modules (`ignoreUnknownKeys = true` always; optional
`serializersModule`, `encodeDefaults`, `prettyPrint`).

---

### [BC-19] LogicTracer.pendingCallCount() removed; notifications are no-ops with zero observers

**Type:** Breaking | Behavioural

**Grep:** `pendingCallCount`
**File glob:** `**/*.kt`

**Before:**
```kotlin
assertEquals(0, LogicTracer.pendingCallCount())
```

**After:**
```kotlin
// No replacement needed: the tracer no longer tracks in-flight calls at all,
// so there is nothing to leak. Assert on observerCount() or captured events instead.
```

**Notes:** The tracer's observer registry is now thread-safe (copy-on-write) and all
notify methods bail out immediately when no observer is registered: `notifyMethodStart`
returns an empty call ID and allocates nothing. `LogicMethodCompleted`/`LogicMethodFailed`
gained a `timestampMs` field. The tracer no longer prints to stdout.

---

### [AD-23] LogicTracer.active fast-path flag

**Type:** Addition

**Grep:** `LogicTracer.active`
**File glob:** `**/*.kt`

**Example:**
```kotlin
if (LogicTracer.active) {
    expensiveDiagnostics()
}
```

**Notes:** True while at least one observer is registered. Compiler-injected tracing code
checks this before stringifying method parameters and results, so traced methods cost
almost nothing in production builds where no devtools/introspection observer is attached.
See BC-19.

---

### [BC-20] Tracing event types unified across core, introspection, and devtools

**Type:** Breaking

**Grep:** `CapturedLogicStart|CapturedLogicComplete|CapturedLogicFailed|toCaptured|fromCaptured|ActionStateEvent`
**File glob:** `**/*.kt`

**Before:**
```kotlin
val captured = event.toCaptured(clientId)
sessionCapture.captureLogicStarted(captured)
val message = DevToolsMessage.LogicMethodStarted.fromCaptured(captured)
```

**After:**
```kotlin
sessionCapture.captureLogicStarted(event)
val message = DevToolsMessage.LogicMethodStarted(clientId, event)
```

**Notes:** The core tracing events (`LogicMethodStart`/`LogicMethodCompleted`/`LogicMethodFailed`)
are now `@Serializable` and are the single canonical event shapes. Introspection's
`CapturedLogic*` mirror types, `EventConverters`, and the devtools UI `ActionStateEvent`
were deleted. `SessionData`/`SessionHistory` embed the core types; `SessionExport` format
version is now `"3.0"` (v2 exports do not import). DevTools wire messages wrap
`(clientId, event)`; `SessionHistorySync` carries a `SessionHistory`;
`StateSync.orchestrated` was removed. `IntrospectionLogicObserver` no longer takes a
clientId parameter.

---

### [BC-21] SessionCapture is asynchronous; export API consolidated

**Type:** Breaking | Behavioural

**Grep:** `exportSessionWithCrash|captureCrashFromLogicFailure|captureCrashFromThrowable|captureInitialState|getSessionHistory\(\)|exportSession\(\)`
**File glob:** `**/*.kt`

**Before:**
```kotlin
capture.captureCrashFromThrowable(throwable)
val json = capture.exportSessionWithCrash(crashInfo)
val history = capture.getSessionHistory()
```

**After:**
```kotlin
capture.reportCrash(throwable)
val json = capture.exportSession(crashInfo)
val history = capture.getSessionHistory()
```

**Notes:** Capture calls now enqueue records; a background worker performs JSON encoding
and batched storage writes off the dispatch path. `exportSession(crash)`,
`getSessionHistory()`, `clear()`, `stop()`, and `exportCrashSession(throwable)` are now
suspend and flush or drain pending records first; use `flush()` in tests before asserting
on side channels. `captureInitialState` takes the state map instead of pre-encoded JSON.
`captureCrashFromLogicFailure` (which dropped the stack trace) and
`captureCrashFromThrowable` were replaced by `reportCrash`; the traced-failure path now
preserves the full stack trace. `IntrospectionLogic` export methods and
`IntrospectionLogic.cleanup()`/`DevToolsLogic.cleanup()` are suspend accordingly.

---

### [BC-22] CrashModule removed; install CrashHandler directly

**Type:** Breaking

**Grep:** `CrashModule|CrashLogic|CrashState|CrashAction`
**File glob:** `**/*.kt`

**Before:**
```kotlin
val store = createStore {
    module(IntrospectionModule(config, sessionCapture, platformContext))
    module(CrashModule(platformContext, sessionCapture))
}
```

**After:**
```kotlin
val store = createStore {
    module(IntrospectionModule(config, sessionCapture, platformContext))
}
CrashHandler(platformContext, sessionCapture).install()
```

**Notes:** The module existed only to flip an `isInstalled` boolean nothing read.

---

### [BC-23] DevTools action streaming consumes the SessionCapture nexus

**Type:** Behavioural

**Grep:** `DevToolsMiddleware`
**File glob:** `**/*.kt`

**Before:**
```kotlin
// DevToolsMiddleware independently serialized state per action when PUBLISHER
```

**After:**
```kotlin
// The middleware collects SessionCapture.actions and forwards them; state is
// serialized exactly once by the capture worker. IntrospectionModule (or another
// starter of the shared SessionCapture) is required for action streaming.
```

**Notes:** `DevToolsMessage.CrashReport` now carries the canonical `CrashInfo` envelope
and is emitted by collecting `SessionCapture.crashes`; the hand-rolled crash export in
`DevToolsLogicObserver` was removed.

---

### [AD-24] SessionCapture crash/event nexus

**Type:** Addition

**Grep:** `reportCrash|capture\.actions|capture\.crashes|captureDispatchedAction`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val capture = SessionCapture()
capture.start("client", "MyApp", "Android")

capture.reportCrash(throwable)

scope.launch {
    capture.crashes.collect { crash -> uploadCrash(crash) }
}
scope.launch {
    capture.actions.collect { event -> forwardToTooling(event) }
}
```

**Notes:** `SessionCapture` is the single nexus for tooling signals: middleware enqueues
actions via `captureDispatchedAction(action, state)`, observers enqueue traced logic
events, and every crash source funnels through `reportCrash`, fanning out to storage,
`crashes` subscribers (devtools socket), and session exports. See BC-21/BC-23.

---

### [AD-25] Canonical serializable tracing events

**Type:** Addition

**Grep:** `LogicMethodStart\(|LogicMethodCompleted\(|LogicMethodFailed\(|toCrashInfo`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val crashInfo = failedEvent.toCrashInfo()
val sync = DevToolsMessage.SessionHistorySync(clientId, capture.getSessionHistory())
```

**Notes:** Core tracing events are `@Serializable`, carry `timestampMs`, and flow
unconverted from the compiler-injected call site through session storage, the devtools
wire protocol, and the WASM UI. `LogicMethodFailed.toCrashInfo()` builds the canonical
crash envelope with the full stack trace. See BC-20.

---

### [BC-24] NavigationAction.SetCurrentTitle and NavigationState.currentTitle removed

**Type:** Breaking

**Grep:** `SetCurrentTitle|currentTitle`
**File glob:** `**/*.kt`

**Before:**
```kotlin
Text(navigationState.currentTitle ?: "Home")
```

**After:**
```kotlin
Text(currentTitle() ?: "Home")
```

**Notes:** The dispatch round-trip that copied the resolved title into state is gone.
Titles are read directly from the navigatable bound to the current entry; see AD-26.
`currentActionResource()` no longer requires being under `NavigationRender` (the backing
CompositionLocal was removed), only under `StoreProvider`.

---

### [AD-26] NavigationEntry.navigatable direct access and title/action accessors

**Type:** Addition | Replaces-deprecated

**Grep:** `entry\.navigatable|currentNavigatable\(\)|currentTitle\(\)`
**File glob:** `**/*.kt`

**Replaces:** the `SetCurrentTitle` action + `NavigationState.currentTitle` round-trip (see BC-24)
and the `resolveNavigatable(entry)` lookups (see BC-25)

**Example:**
```kotlin
val title = navigationState.currentEntry.titleResource?.invoke()
val action = navigationState.currentEntry.actionResource
val navigatable = navigationState.currentEntry.navigatable

@Composable
fun TitleBar() {
    Text(currentTitle() ?: "Home")
    currentActionResource()?.invoke()
}
```

**Notes:** `NavigationEntry` holds a non-null direct reference to its `Navigatable`; titles
and action resources are read straight off the entry with no resolution step and no
null-handling. `currentNavigatable()`, `currentTitle()`, and `currentActionResource()` are
composables usable anywhere under `StoreProvider`. Screens and modals are never serialized;
see BC-25 for how entries persist.

---

### [BC-25] NavigationEntry is a runtime type; resolveNavigatable removed

**Type:** Breaking

**Grep:** `resolveNavigatable|NavigationEntry\(|NavigationEntry\.serializer`
**File glob:** `**/*.kt`

**Before:**
```kotlin
val navigatable = navModule.resolveNavigatable(entry) ?: return
val entry = NavigationEntry(path = path, params = params, stackPosition = 0)
```

**After:**
```kotlin
val navigatable = entry.navigatable
val entry = screen.toNavigationEntry(path = path, params = params)
```

**Notes:** `NavigationEntry` is no longer `@Serializable` itself and its constructor requires
the non-null `navigatable` as the first parameter (prefer `Navigatable.toNavigationEntry`).
`navigatableRoute` is now derived from the navigatable. Persistence uses
`NavigationEntrySerializer`, registered contextually by `NavigationModule` via
`CustomTypeRegistrar`: it stores `(path, params, stackPosition)` and rehydrates the
navigatable from the route registry on restore, falling back to `notFoundScreen` for paths
that no longer exist (a `SerializationException` is thrown when there is no fallback).
Serializing entries outside the store requires a `Json` built with the store's
`serializersModule`. `NavigationModule.resolveNavigatable` and
`StoreAccessor.resolveNavigatable` were removed.

---

### [BC-26] Screens beneath vertically dismissible screens stay composed

**Type:** Behavioural

**Grep:** `swipeToDismiss`
**File glob:** `**/*.kt`

**Before:**
```kotlin
// Navigating from HostScreen to a SlideUpBottom sheet disposed HostScreen:
// its DisposableEffect onDispose fired, LaunchedEffects were cancelled.
```

**After:**
```kotlin
// HostScreen stays composed beneath the sheet: effects keep running, state is
// preserved, and the dismiss gesture reveals it with zero composition cost.
```

**Notes:** Applies whenever the current content-layer entry arms the vertical swipe
dismiss (same predicate as the gesture). Matches modal semantics and iOS sheet
behaviour, where the presenting screen stays alive beneath the sheet. The premounted
hierarchy is hidden from accessibility/semantics while at rest and shielded from
pointer input, so it cannot be interacted with until revealed. Screens relying on
`DisposableEffect` disposal when a sheet opens must move that logic to navigation
callbacks instead. `currentTitle()`, `currentActionResource()` and
`currentNavigatable()` are now scoped: inside a rendered entry's subtree they resolve
to that entry, and during a committed dismiss/back gesture they resolve to the target
entry as soon as the finger lifts, so shared toolbars update immediately instead of
after the settle animation.

The content layer renders every entry through a single hosting slot keyed by
`stableKey`, so the screen beneath a sheet keeps one continuous composition from the
sheet's enter transition, through the premount, to the dismiss commit: its effects run
once when it first appears and are not re-triggered by the sheet's lifecycle, and its
state survives gesture cancels and commits. Push/pop navigation is unaffected:
returning to a popped-back screen still recomposes it and re-runs its effects.

---

### [BC-27] Navigations queue instead of being dropped

**Type:** Behavioural

**Grep:** `NavigationOutcome.Dropped`
**File glob:** `**/*.kt`

**Before:**
```kotlin
// navigate {} returned NavigationOutcome.Dropped when another navigation was in
// flight (e.g. during slow guard evaluation): the user's tap silently did nothing.
```

**After:**
```kotlin
// navigate {} suspends until the in-flight navigation completes, then executes.
// NavigationOutcome.Dropped is no longer returned.
```

**Notes:** Navigations are serialized in arrival order. Re-entrant navigations issued
from inside an in-flight navigation (a guard or entry lambda navigating) execute
inline as before. Code branching on `NavigationOutcome.Dropped` is now dead and can
be removed.

---

### [BC-28] Navigation stack math unified; popUpTo fallback resets the back stack

**Type:** Behavioural

**Grep:** `popUpTo\(.*fallback`
**File glob:** `**/*.kt`

**Before:**
```kotlin
// popUpTo with an unmatched route and a fallback dispatched a plain Navigate:
// the fallback destination was appended on top of the existing back stack.
```

**After:**
```kotlin
// The fallback now clears the back stack and navigates: the resulting stack is
// exactly [fallback destination], matching the documented intent.
```

**Notes:** The reducer and the navigation builder's execution simulation now share one
set of pure stack-transition functions, so their semantics can no longer diverge. Two
latent divergences were fixed in the process: the fallback behaviour above, and `back()`
inside a `navigation { }` block now models modal-context restoration identically to the
reducer, so multi-step blocks that go back over a modal compute subsequent steps against
the correct stack.

---

### [AD-27] Dismiss zone with default indicator

**Type:** Addition

**Grep:** `showsDismissIndicator`
**File glob:** `**/*.kt`

**Example:**
```kotlin
object FullBleedSheet : Screen {
    override val route = "full-bleed"
    override val enterTransition = NavTransition.SlideUpBottom
    override val exitTransition = NavTransition.SlideOutBottom

    override val showsDismissIndicator: Boolean = false
}
```

**Notes:** Vertically dismissible screens reserve a dismiss zone at the top of the
screen's own content area: a 28dp slot with a default grabber pill
(`testTag("reaktiv-dismiss-indicator")`) that takes real layout space and shifts the
screen content down. The slot renders below any graph layout chrome (toolbars from
`layout { }` graphs); on screens without layout chrome it sits at the absolute top,
padded by `WindowInsets.statusBars` so it never collides with system bars or the
Android notification shade. A downward drag starting in the slot always dismisses,
regardless of what the content does with drags (works over `PullToRefreshBox` and any
other consuming content, with no per-screen wiring); the zone is derived from the
pill's measured position, so it is exact in any hierarchy. Anything below the slot
belongs to the content. The slot lives inside the entry's subtree, so it follows the
sheet during gesture scrubs. Opt out per navigatable with
`showsDismissIndicator = false`; a 32dp invisible band below the status bar inset then
remains as the dismiss zone fallback.

---

