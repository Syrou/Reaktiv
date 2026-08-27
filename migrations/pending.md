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
// Three-level chain: startup -> auth -> premium.
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
// [splashScreen -> workspaceHome -> workspaceDetail]
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

**Replaces:** `Modal.tapOutsideClick` (removed, see BC-29)

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
default to navigateBack() and tap-outside does nothing. If the handler declines (navigation
state unchanged), the scrubbed screen animates back into place. The deprecated tapOutsideClick
it replaced has since been removed (see BC-29).

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

### [BC-29] Modal.tapOutsideClick removed

**Type:** Deprecation-removal

**Grep:** `tapOutsideClick`
**File glob:** `**/*.kt`

**Before:**
```kotlin
object FilterModal : Modal {
    override val route = "filters"
    override val tapOutsideClick: (suspend StoreAccessor.() -> Unit) = {
        navigateBack()
    }
}
```

**After:**
```kotlin
object FilterModal : Modal {
    override val route = "filters"
    override val onDismissRequest: (suspend StoreAccessor.() -> Unit) = {
        navigateBack()
    }
}
```

**Notes:** Pure rename for the tap-outside case: a null handler still does nothing on
outside tap, matching the old default. One semantic difference: onDismissRequest is
declared on Navigatable and is the unified dismiss funnel, so a custom handler now also
fires for edge-swipe commits, swipe-down commits and Android system back, not only
tap-outside. A handler that must react differently per input should inspect its own
state rather than assume the tap-outside origin. Replacement API documented in AD-20.

---
### [BC-30] Pop transition resolution unified across timed and gesture paths

**Type:** Behavioural

**Grep:** `popEnterTransition|popExitTransition`
**File glob:** `**/*.kt`

**Before:**
```kotlin
// Three sites resolved pop transitions with three different conventions, so a
// button back, a predictive back and an edge swipe over the same pair of
// screens could animate differently. Modal also defaulted the pop transitions
// to None (non-null), silently disabling declared enter/exit animations for
// overlay modals.
```

**After:**
```kotlin
// One shared resolver defines the semantics everywhere. A screen declares how
// the screen underneath it behaves:
// - popExitTransition belongs to the ARRIVING screen and drives how the screen
//   it covers exits on a forward push
// - popEnterTransition belongs to the POPPED screen and drives how the screen
//   it reveals enters on a pop
// - fallback: the pop plays the opposite transition in reverse, so the popped
//   screen reverses its enterTransition and the revealed screen reverses its
//   exitTransition, then the same-side transition mirrored
// - explicit NavTransition.None disables that animation on timed paths, while
//   gestures fall back to their kind default because a finger always needs
//   something to drag
object DetailScreen : Screen {
    override val route = "detail"
    override val enterTransition = NavTransition.SlideInRight
    // how the screen under detail exits when detail is pushed on top
    override val popExitTransition = NavTransition.SlideOutLeft
    // how the screen under detail enters when detail is popped off
    override val popEnterTransition = NavTransition.Fade
}
```

**Notes:** A screen declares how the screen underneath it behaves, in both
directions. Button back, system back, predictive back and edge-swipe gestures
resolve transitions through the same functions, so they can no longer diverge.
AnimationDecision gained enterReversed and exitReversed fields. Modal
popEnterTransition/popExitTransition defaults changed from None to null, so
overlay modals now animate with their declared enter/exit transitions. Forward
navigation consults the arriving screen's popExitTransition for the covered
screen's exit. Apps that placed popEnterTransition on the screen being returned
to must move it to the screen being popped, and popExitTransition moves from the
popped screen to the screen being pushed.

---
### [BC-31] invokeOnRemoval fires after the screen is visually gone

**Type:** Behavioural

**Grep:** `invokeOnRemoval`
**File glob:** `**/*.kt`

**Before:**
```kotlin
// Removal handlers ran the instant the backstack state changed, while the
// exit animation still had the removed screen composed on the UI side.
// Cleanup that tore down state the screen was rendering could glitch the
// exit animation.
```

**After:**
```kotlin
// Removal handlers (and the entry's lifecycle scope cancellation) are
// deferred by the entry's resolved pop exit duration, the same value the
// renderer animates with via the unified pop transition resolution (BC-30).
// Screens with no pop transition keep the previous immediate behaviour.
lifecycle.invokeOnRemoval { reason ->
    // now runs after the exit animation completes
}
```

**Notes:** RESET-reason handlers still run immediately during store reset, since
no animation plays there. Gesture-committed backs already have the screen
off-screen at dispatch, so handlers run slightly after the visual removal, never
before. Tests using virtual time pass unchanged; tests asserting a handler ran
synchronously after dispatch for a screen with a pop transition must advance the
scheduler past the transition duration.

---
### [BC-32] Navigation lifecycle is action-driven

**Type:** Behavioural

**Grep:** `onLifecycleCreated|invokeOnRemoval`
**File glob:** `**/*.kt`

**Before:**
```kotlin
// Lifecycle callbacks fired from an observer collecting the NavigationState
// flow: ANY backstack change, including externally applied state (DevTools
// listener sync, persistence restore), triggered onLifecycleCreated and
// invokeOnRemoval side effects.
```

**After:**
```kotlin
// Lifecycle is invoked by the navigation module's dispatch middleware, only
// when a NavigationAction is reduced. The logic diffs the reduced backstack
// against its own lifecycle bookkeeping, so the mechanism is idempotent and
// self-healing. Externally applied states (applyExternalStates) and
// Store.loadState() persistence restores no longer fire lifecycle hooks.
// To initialize lifecycles for a restored backstack explicitly:
storeAccessor.selectLogic<NavigationLogic>().adoptCurrentBackstack()
```

**Notes:** Side effects belong to the action pipeline in MVLI; state-flow
collectors should be render-pure. This makes session replication and DevTools
listener sync structurally incapable of triggering screen side effects. Store
reset semantics are unchanged (RESET-reason handlers still run via
beforeReset). See AD-28 for the adoption API.

---

### [AD-28] NavigationLogic.adoptCurrentBackstack

**Type:** Addition

**Grep:** `adoptCurrentBackstack`
**File glob:** `**/*.kt`

**Example:**
```kotlin
store.loadState()
store.selectLogic<NavigationLogic>().adoptCurrentBackstack()
```

**Notes:** Initializes lifecycles (onLifecycleCreated and removal handler
registration) for every entry in the current backstack that does not already
have one. Needed after restoring persisted navigation state, since restores
bypass the action pipeline and therefore no longer fire creation hooks
(see BC-32). Idempotent: entries with live lifecycles are untouched.

---
### [AD-29] Session quality: metadata, redaction, consent, full-session retention

**Type:** Addition

**Grep:** `ClientMetadata|StateRedactor|droppedRecords|suggestFileName`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val config = IntrospectionConfig(
    platform = "Android 15",
    autoStart = false,
    clientMetadata = ClientMetadata(appVersion = "1.4.2", osVersion = "15"),
    redactor = StateRedactor { moduleName, state -> state }
)
```

**Notes:** SessionExport is now format 3.1 (3.0 files still decode): ExportedClientInfo
carries ClientMetadata and SessionExport carries droppedRecords. SessionCapture
retention caps are optional and default unbounded;
the enqueue channel is unlimited with a 50k high-water valve that drops new records
and counts them, so droppedRecords is zero in every healthy session. The redactor
runs on the capture worker against both the initial snapshot and every per-action
delta. autoStart=false plus IntrospectionLogic.startCapture()/stopCapture() enable
consent-gated production capture. Export file names follow
reaktiv_{crash|session}_{client}_{appVersion}_{timestamp}.json via
SessionCapture.suggestFileName.

---

### [BC-33] IntrospectionModule replaced by createToolingModule

**Type:** Breaking

**Grep:** `IntrospectionModule|IntrospectionLogic|IntrospectionAction|IntrospectionState`
**File glob:** `**/*.kt`

**Before:**
```kotlin
val sessionCapture = SessionCapture()
val store = createStore {
    module(IntrospectionModule(introspectionConfig, sessionCapture, platformContext))
}
CrashHandler(platformContext, sessionCapture).install()
```

**After:**
```kotlin
val store = createStore {
    module(createToolingModule(introspectionConfig, platformContext))
}
```

**Notes:** ToolingModule owns its SessionCapture internally (reach it via
selectLogic<ToolingLogic>().getSessionCapture()) and installs the platform
CrashHandler automatically (opt out with installCrashHandler = false). Export
methods and startCapture/stopCapture live on ToolingLogic. See AD-30/AD-31.

---

### [BC-34] Device-side DevToolsModule replaced by DevToolsService

**Type:** Breaking

**Grep:** `DevToolsModule|DevToolsMiddleware|DevToolsAction|DevToolsState|DevToolsLogic`
**File glob:** `**/*.kt`

**Before:**
```kotlin
module(DevToolsModule(DevToolsConfig(introspectionConfig, serverUrl), scope, sessionCapture))
dispatch(DevToolsAction.Connect("ws://host:8080/ws"))
```

**After:**
```kotlin
module(createToolingModule(introspectionConfig, platformContext) {
    install(DevToolsService(DevToolsConfig(serverUrl = "ws://host:8080/ws", autoConnect = false)))
})
dispatch(DevToolsCommands.connect(role = ClientRole.PUBLISHER))
```

**Notes:** DevToolsConfig no longer wraps IntrospectionConfig (identity comes from
the tooling module) and gains autoConnect. Connection status surfaces in
ToolingState.services under "devtools" instead of DevToolsState. Runtime control is
typed: DevToolsCommands.connect/disconnect/reconnect/follow/unfollow build
ToolingAction.ServiceCommand carrying the DevToolsCommand enum. Programmatic use:
storeAccessor.toolingService("devtools") as DevToolsService.

---

### [AD-30] createToolingModule DSL

**Type:** Replaces-deprecated

**Grep:** `createToolingModule`
**File glob:** `**/*.kt`

**Replaces:** hand-wired IntrospectionModule + DevToolsModule sharing a SessionCapture

**Example:**
```kotlin
val store = createStore {
    module(createToolingModule(config, platformContext) {
        install(DevToolsService(DevToolsConfig(serverUrl = "ws://host:8080/ws")))
    })
}
```

**Notes:** One module owns the capture nexus, the capture middleware, service
lifecycle (started by ToolingLogic, stopped in beforeReset), and
ToolingState(isCapturing, services) for status UI. The nullable-module source-set
seam (fun toolingModule(context): Module<*, *>?) keeps production classpaths free
of tooling code.

---

### [AD-31] ToolingService contract with enum commands

**Type:** Addition

**Grep:** `ToolingService|ToolingCommand|ServiceCommand`
**File glob:** `**/*.kt`

**Example:**
```kotlin
enum class MyCommand : ToolingCommand { PING }

class MyService : ToolingService {
    override val name = "my-service"
    override suspend fun start(context: ToolingServiceContext) { context.setStatus(ServiceStatus(ServiceState.RUNNING)) }
    override suspend fun stop() {}
    override suspend fun onCommand(command: ToolingCommand, args: Map<String, String>) {}
}
```

**Notes:** Services may contribute middleware (outer, may block; the capture
middleware is innermost and always proceeds, so blocked actions are never captured
and projections never enter capture). Debug menus control services with plain
dispatch of ToolingAction.ServiceCommand(service, command: ToolingCommand, args);
commands are typed per-service enums implementing the ToolingCommand marker.

---
### [AD-32] Multi-device session replication (follower mode)

**Type:** Addition

**Grep:** `DevToolsCommands.follow|activeScrub|KeyframedReconstructor`
**File glob:** `**/*.kt`

**Example:**
```kotlin
dispatch(DevToolsCommands.follow())
dispatch(DevToolsCommands.unfollow())
```

**Notes:** A follower is a LISTENER-role device: local dispatch blocked (tooling
commands still pass), incoming StateSync projected via applyExternalStates, and
gesture scrubs mirrored through NavigationState.activeScrub (see AD-36). Exit
restores a clean state via store
reset. The wasm UI seeds newly attached ghost followers at the current timeline
position (bootstrapping flags patched via NavigationStatePatch), and its timeline
playback drives all subscribers. Publisher wire traffic conflates same-module
deltas in a 75ms window (the capture file keeps every record); ghost scrubbing uses
KeyframedReconstructor (keyframe every 500 actions) so seeking large sessions is
O(interval) instead of O(n). Large session histories sync in slices via
DevToolsMessage.SessionHistoryChunk (SessionHistory.chunked, 250 actions per chunk;
small sessions keep the single SessionHistorySync frame).

---

### [AD-34] StoreDSL.module accepts star-projected modules

**Type:** Addition

**Grep:** `Module<\*, \*>`
**File glob:** `**/*.kt`

**Example:**
```kotlin
fun toolingModule(context: Context): Module<*, *>? = ...

val store = createStore {
    toolingModule(context)?.let { module(it) }
}
```

**Notes:** Enables the variant source-set seam pattern where production source
sets return null and debug source sets return a real module: the erased overload
registers state serialization from the module's runtime state class, identical to
the reified path. See docs/tooling-attachment-android.md and
docs/tooling-attachment-ios.md.

---
### [AD-35] Field-level state deltas (export v3.2)

**Type:** Addition

**Grep:** `DeltaKind|mergeCapturedDeltas`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val action: CapturedAction = ...
if (action.deltaKind == DeltaKind.FIELDS) {
    merged = mergeFieldJson(shadowJson, action.stateDeltaJson)
}
```

**Notes:** CapturedAction.stateDeltaJson now carries only the changed top-level
fields (plus the type discriminator) when deltaKind is FIELDS; the first capture
per module, class changes, and keyframes stay FULL. Capture encodes states with
encodeDefaults=true so a field reverting to its default is still expressed.
StateReconstructor gains an action-aware applyDelta that merges FIELDS into the
module entry; followers keep a JSON shadow per module, merge deltas, and decode
the merged object before applyExternalStates. Conflation merges pending deltas
(never drops: a delta chain is stateful) via mergeCapturedDeltas. The server only
synthesizes legacy per-module StateSync for FULL events, so old listeners degrade
gracefully instead of corrupting. Export format is 3.2; 3.0/3.1 files decode with
deltaKind defaulting to FULL. This is the groundwork for moving gesture scrub
state into NavigationState.

---
### [AD-36] Gesture scrubs are navigation state

**Type:** Addition

**Grep:** `activeScrub|ScrubUpdate|ScrubEnd`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val scrubbing = selectState<NavigationState>().value.activeScrub != null
```

**Notes:** NavigationState.activeScrub (ScrubState: kind, topKey, revealedKey,
progress) mirrors live gesture scrubs into state, updated by
NavigationAction.ScrubUpdate/ScrubEnd. The leader's own rendering stays
controller-direct at full frequency; the controller dispatches throttled scrub
actions (16ms or 1 percent progress) so state is the transport truth. Any other
navigation action clears activeScrub, so a committed gesture's Back and the scrub
clearing arrive as one ordered state change, and followers drive their controller
from an activeScrub collector with handoffs armed at scrub begin: the
hold-until-projection machinery is gone because a single channel cannot
desynchronize. Scrub actions skip lifecycle sync and, being tiny FIELDS deltas
(AD-35), cost roughly forty bytes each on the wire and in session files. The
never-released interaction telemetry channel (InteractionTracer/Projector, the
Interaction wire message, capture interaction events) was removed in its favor.

---
### [AD-37] Unified crash representation with crash location

**Type:** Addition

**Grep:** `CrashOrigin|afterActionIndex|crashes`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val export: SessionExport = reaktivJson().decodeFromString(json)
export.crashes.forEach { crash ->
    println("${crash.origin} at ${crash.route} after action #${crash.afterActionIndex}")
    println("in ${crash.logicClass}.${crash.methodName}")
}
```

**Notes:** CrashInfo is now the single canonical crash representation and carries
where the crash happened: origin (LOGIC_METHOD, UNCAUGHT, MANUAL), logicClass and
methodName (correlated from the traced method start via callId), route (read from
the NavigationState shadow the capture worker already keeps for deltas), and
afterActionIndex (the exact timeline index, from the worker's processed-action
count). SessionCapture stores every reported crash in its own JSONL storage;
SessionExport gains crashes: List<CrashInfo> with crash kept as the last-crash
convenience. exportCrashSession marks UNCAUGHT; reportCrash(throwable) accepts an
origin. The wasm UI CrashEventInfo now wraps the full CrashInfo, the crash card
and detail panel show location and origin, and state-at-crash reconstruction uses
afterActionIndex exactly instead of guessing by timestamp. Export format stays
3.2; older files decode with MANUAL origin and no location.

---
### [AD-38] Guard observability through logic tracing

**Type:** Addition

**Grep:** `NavigationGuards`
**File glob:** `**/*.kt`

**Example:**
```kotlin
LogicTracer.addObserver(object : LogicObserver {
    override fun onMethodStart(event: LogicMethodStart) {
        if (event.logicClass == "NavigationGuards") {
            println("evaluating ${event.methodName} for ${event.params["target"]}")
        }
    }
    override fun onMethodCompleted(event: LogicMethodCompleted) {}
    override fun onMethodFailed(event: LogicMethodFailed) {}
})
```

**Notes:** Navigation guard evaluations (intercept outer chains, primary guards)
and dynamic entry selections are now reported through LogicTracer as synthetic
logic events with logicClass "NavigationGuards". Method names identify the
evaluation site: guard(zone), outerGuard[i](zone), entry(graph); params carry the
target route; the completion result carries the decision (Allow, Reject,
RedirectTo(route), PendAndRedirectTo(route)) or the resolved entry route; a
throwing guard reports a failure event. Because these ride the existing logic
trace stream, session capture, DevTools streaming, the wasm UI timeline and
crash correlation all show guard decisions with no protocol change. Zero cost
when no observer is registered. Guards skipped because the backstack is already
inside the intercept zone are not reported (nothing was evaluated).

---
### [AD-39] Performance lens over logic traces

**Type:** Addition

**Grep:** `aggregateLogicStats|MethodStats`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val stats = aggregateLogicStats(
    started = history.logicStarted,
    completed = history.logicCompleted,
    failed = history.logicFailed
)
stats.forEach { println("${it.methodIdentifier}: ${it.calls} calls, avg ${it.avgMs}ms, max ${it.maxMs}ms") }
```

**Notes:** reaktiv-devtools commonMain gains aggregateLogicStats folding a
session's logic trace events into per-method MethodStats (calls, finished,
failures, total/avg/max duration, inFlight), attributed via callId and sorted by
total time. The wasm UI right panel gains a State/Performance tab switch; the
Performance tab renders the aggregation live with relative total-time bars,
failure highlighting and a guard badge for NavigationGuards rows (AD-38), so
guard cost shows up beside logic method cost with no extra wiring.

---
### [AD-40] reaktiv-test artifact

**Type:** Addition

**Grep:** `reaktivTest|ReaktivTestScope`
**File glob:** `**/*Test*.kt`

**Example:**
```kotlin
@Test
fun `login updates auth state`() = reaktivTest(AuthModule) {
    dispatch(AuthAction.Login)
    assertTrue(currentState<AuthState>().isAuthenticated)
    assertNotDispatched<AuthAction.Logout>()
}
```

**Notes:** New io.github.syrou:reaktiv-test artifact (commonTest dependency).
reaktivTest(vararg modules, timeout, configure) creates the store on a
StandardTestDispatcher bound to the runTest scheduler, installs an
action-recording middleware and runs the body in ReaktivTestScope: dispatch
settles all follow-up effects before returning, settle drains the scheduler,
advanceTimeBy tests time-gated behavior (thresholds, debounces), currentState
and awaitState read module state, assertDispatched/assertNotDispatched assert
on the recorded action stream (including actions dispatched by logic and
middleware), and store gives direct access for extension APIs like
store.navigation. The configure block accepts the full StoreDSL for
middlewares and persistence.

---
### [AD-41] Composable state read tracking (recomposition blast radius)

**Type:** Addition

**Grep:** `StateReadTracker|StateRead`
**File glob:** `**/*.kt`

**Example:**
```kotlin
StateReadTracker.addObserver { read ->
    println("${read.composable} observes ${read.stateClass}")
}
val registry: Set<StateRead> = StateReadTracker.snapshot()
```

**Notes:** The reaktiv-tracing compiler plugin now instruments calls to the
compose selectState and composeState entry points inside @Composable functions,
reporting (state class, composable function) pairs to the new core
StateReadTracker. The tracker dedupes into a registry that accrues from app
start regardless of observers, and addObserver replays the seen set so
late-attaching tooling misses nothing. The tooling module captures reads into
the session (export v3.3, SessionData.stateReads; 3.x files decode with an
empty list), DevTools streams new pairs live (StateReadReport message, server
relay), and the wasm UI joins them with field-level deltas (AD-35): selecting
an action shows which composables recompose from that module's change.
Requires the tracing gradle plugin (reaktivTracing buildTypes gating applies,
so release builds carry zero instrumentation). Attribution is per composable
function on JVM/Android compilations; the read registry is class-level today,
field-level joins are a future refinement.

---
### [AD-42] DevTools connection loss recovery

**Type:** Addition | Behavioural

**Grep:** `autoReconnect`
**File glob:** `**/*.kt`

**Example:**
```kotlin
DevToolsConfig(
    serverUrl = "ws://192.168.1.100:8080/ws",
    autoReconnect = true
)
```

**Notes:** Platform connections now transition to DISCONNECTED when the server
closes the socket (previously the state stayed CONNECTED on a dead link).
DevToolsService monitors the active connection: on loss, a LISTENER drops its
role and the store resets to a clean local state (same semantics as unfollow),
then the service retries the last server URL with exponential backoff (1s
doubling to 30s cap) until connected or explicitly disconnected. A PUBLISHER
re-requests its role on reconnect; a former follower reconnects UNASSIGNED and
must follow again deliberately. autoReconnect (default true) disables the retry
loop when false; manual disconnect never triggers retries.

---
### [AD-43] Thread and dispatcher visibility with congestion warnings

**Type:** Addition | Breaking (LogicTracer.notifyMethodStart is now suspend)

**Grep:** `currentThreadName|aggregateThreadStats|notifyMethodStart`
**File glob:** `**/*.kt`

**Before:**
```kotlin
val callId = LogicTracer.notifyMethodStart(logicClass, methodName, params)
```

**After:**
```kotlin
suspend fun traced() {
    val callId = LogicTracer.notifyMethodStart(logicClass, methodName, params)
}
```

**Notes:** LogicMethodStart gains thread (captured via the new expect/actual
currentThreadName) and dispatcher (read from
coroutineContext[ContinuationInterceptor], which required notifyMethodStart to
become suspend; traced methods are always suspend so compiler-injected call
sites are unaffected, but any direct caller must now be in suspend context).
MethodStats gains threads, dispatchers and maxConcurrent (peak overlapping
calls computed by interval sweep); new aggregateThreadStats returns per-thread
calls, busy time and peak overlap; isMainThread and CONGESTION_PEAK_THRESHOLD
(3) are shared helpers. The Performance tab shows a red warning banner when any
logic method runs on the main thread, when a method peaks at 3 or more
concurrent calls (congestion), or when a thread runs that many overlapping
calls (contention); a thread summary lists calls, busy time and peak per
thread, and method cards carry on/via chips for threads and dispatchers.
Durations are attributed to the starting thread of each call; suspension-point
thread hops within a call are not tracked.

---
### [AD-44] Dispatch pipeline latency in the performance lens

**Type:** Addition

**Grep:** `StoreDispatch|aggregateDispatchStats`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val dispatch = aggregateDispatchStats(history.logicStarted, history.logicCompleted, history.logicFailed)
println("avg queue wait ${dispatch?.avgQueueWaitMs}ms, max depth ${dispatch?.maxQueueDepth}")
```

**Notes:** The store worker emits synthetic StoreDispatch trace events per
processed action when LogicTracer has observers: method name is the action
type, params carry queueWaitMs (enqueue to dequeue) and queueDepth, duration is
middleware plus reducer time, and the result records Processed or Blocked; a
throwing reducer or middleware reports a failure event with the exception. Zero
cost with no observer. aggregateDispatchStats folds these into processed count,
avg/max queue wait, max depth and total reducer time; the Performance tab shows
a Dispatch queue summary and raises the warning banner when queue wait peaks
past 100ms. StoreDispatch pairs are excluded from the event timeline (they
mirror action cards) and from the main-thread banner check. The Performance tab
also gains a tri-state warning filter: All shows everything, Warnings shows
only flagged methods, threads and dispatch congestion, Hide suppresses warning
styling and the banner entirely.

---
### [AD-45] State size watchdog

**Type:** Addition

**Grep:** `StateSizeTracker|ModuleSizeStats`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val tracker = StateSizeTracker()
tracker.feedInitial(session.initialStateJson)
session.actions.forEach { tracker.feed(it) }
tracker.snapshot().filter { it.isSuspicious }.forEach {
    println("${it.shortName} grew ${it.growthPercent}% to ${it.currentBytes} bytes")
}
```

**Notes:** StateSizeTracker (devtools commonMain) folds the existing delta
stream into per-module encoded-state sizes: field deltas merge into a JSON
shadow, full deltas replace it, and each update tracks current, max, session
growth percent and a consecutive-growth streak. A module is flagged suspicious
when it has grown for 10 straight updates and is up at least 50 percent this
session, the classic append-only leak signature. The Performance tab shows a
State size summary (current, max, growth per module) and the warning banner
calls out suspicious modules. Computed entirely UI-side from data the timeline
already has, so live sessions, ghosts and crash files all get it with no
capture or protocol change; the warning filter applies.

---
### [AD-46] Main-thread stall watchdog

**Type:** Addition

**Grep:** `StallWatchdog|installStallWatchdog|MainThreadWatchdog`
**File glob:** `**/*.kt`

**Example:**
```kotlin
IntrospectionConfig(
    platform = "Android",
    installStallWatchdog = true,
    stallThresholdMs = 300
)
```

**Notes:** The tooling module now runs a debug-only heartbeat on
Dispatchers.Main (100ms tick) with a monitor on Dispatchers.Default. When the
main thread stops beating past stallThresholdMs (default 300ms) and then
recovers, the watchdog reports a synthetic MainThreadWatchdog.stall trace event
whose duration is the freeze length, so UI freezes land on the same timeline as
actions, logic and crashes, in session files and live streams. The Performance
banner shows "UI frozen N time(s), worst Xms". Platforms without a usable Main
dispatcher (server natives, plain JVM tests) are detected with a synchronous
probe and the watchdog disables itself instead of failing into the store's
crash handling. Events are emitted at recovery time; a freeze the app never
recovers from is captured by the crash path instead.

---
### [AD-47] Navigation assertions for the test kit

**Type:** Addition

**Grep:** `assertCurrentRoute|assertBackStack|evaluateGuard`
**File glob:** `**/*Test*.kt`

**Example:**
```kotlin
@Test
fun `login flow lands on home`() = reaktivTest(AuthModule, navigationModule) {
    store.navigation { navigateTo("workspace/home") }
    assertCurrentRoute("home")
    assertBackStack("start", "home")
    assertEquals(GuardResult.Allow, evaluateGuard(requireAuth))
}
```

**Notes:** New io.github.syrou:reaktiv-test-navigation artifact (commonTest
dependency) with ReaktivTestScope extensions: assertCurrentRoute and
assertCurrentPath check the active entry, assertBackStack compares the full
stack in order, awaitRoute suspends until navigation lands, and evaluateGuard
runs a NavigationGuard directly against the test store for unit testing guard
logic without navigating. Packaged as a companion artifact rather than inside
reaktiv-test because reaktiv-navigation only targets the Compose platforms
(jvm, android, apple) while reaktiv-test covers every core target including
linux, mingw and wasm.

---
### [AD-48] Sensitive-key redaction on by default

**Type:** Addition | Behavioural

**Grep:** `sensitiveKeyRedactor|redactSensitiveKeys|DEFAULT_SENSITIVE_KEYS`
**File glob:** `**/*.kt`

**Example:**
```kotlin
IntrospectionConfig(
    platform = "Android",
    redactSensitiveKeys = true,
    redactor = sensitiveKeyRedactor(keys = DEFAULT_SENSITIVE_KEYS + "otp")
)
```

**Notes:** Captured state (the initial snapshot and every delta, so also crash
session files and the live stream) now masks values whose key looks sensitive
(password, token, secret, apiKey, authorization, credential, cvv, cardNumber,
ssn and similar) with "[REDACTED]" by default. Keys match case-insensitively and
ignore _ and - separators, so userPassword, api_key and access-token all match,
and a matched key masks its whole value including nested objects and arrays. It is on
by default via IntrospectionConfig.redactSensitiveKeys = true. A custom redactor
still runs, composed on top of the built-in (built-in first, then custom), so
setting redactor no longer silently disables password masking. Set
redactSensitiveKeys = false to turn the built-in off. sensitiveKeyRedactor()
builds a StateRedactor from a custom key set or mask for reuse. Behavioural: apps
that previously captured these fields verbatim will now see them masked, which is
what makes adding crash capture safe to ship in a debug build.

---
### [AD-49] Auto-generated crash diagnosis in the export

**Type:** Addition

**Grep:** `CrashDiagnosis|buildCrashDiagnosis|\.diagnosis`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val export = json.decodeFromString<SessionExport>(crashFileJson)
println(export.diagnosis?.text)
```

**Notes:** Exported sessions that contain a crash now carry a diagnosis field
(CrashDiagnosis) synthesized from data already captured: the failing logic method
with source file and line (correlated by callId), the route, the action index and
triggering action, the recent action sequence, the state field changes just before
the crash, and heuristic suspects (for example a field that became null right
before a null-related failure). CrashDiagnosis.text is a ready to read and copy
rendering. It is produced by buildCrashDiagnosis in SessionCapture at export time,
so it appears in crash files, manual exports and the live stream with no new
capture and no new privacy surface. SessionExportFormat.VERSION is bumped to 3.4
(additive, older files still parse). The DevTools crash detail view renders it as
a copyable Crash Diagnosis panel, populated on both ghost import and live crash
reports.

---
### [AD-50] reaktiv-devtools supports iOS

**Type:** Addition

**Grep:** `reaktiv-devtools`
**File glob:** `**/*.gradle.kts`

**Example:**
```kotlin
kotlin.sourceSets.getByName("iosMain") {
    dependencies {
        implementation("io.github.syrou:reaktiv-introspection:$reaktivVersion")
        implementation("io.github.syrou:reaktiv-devtools:$reaktivVersion")
    }
}
```

**Notes:** reaktiv-devtools now publishes iosArm64 and iosSimulatorArm64 artifacts, so
the debug-only tooling wiring in docs/tooling-attachment-ios.md resolves. The client
uses the Darwin engine on Apple targets. DevToolsConnection is no longer an
expect/actual class: the transport now lives in commonMain and only the ktor engine is
platform specific, so its public API is unchanged and no call sites need updating. The
DevTools server (DevToolsServer, ClientManager, LocalFileContent, and the server main
entry point) stays restricted to linuxX64, linuxArm64, macosArm64 and mingwX64 because
ktor-server-cio has no iOS artifacts. Connecting from iOS requires cleartext opt-in in
a debug Info.plist (NSAppTransportSecurity/NSAllowsLocalNetworking, plus
NSLocalNetworkUsageDescription for LAN addresses on a physical device), see the iOS
Info.plist requirements section in reaktiv-devtools/module.md.

---
### [AD-51] External control mode for replicated stores

**Type:** Addition

**Grep:** `beginExternalControl|markExternallyDriven|onExternalControlChanged|ExternalControlExempt`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val ops = storeAccessor.asInternalOperations()
ops?.beginExternalControl()
ops?.applyExternalStates(states)
ops?.endExternalControl()

class MyLogic(private val storeAccessor: StoreAccessor) : ModuleLogic() {
    override suspend fun onExternalControlChanged(externallyDriven: Boolean) {
        if (externallyDriven) cancelStartupWork()
    }
}

sealed class MyToolingAction : ModuleAction(MyModule::class), ExternalControlExempt
```

**Notes:** A store under external control has its state authored by a remote publisher
through applyExternalStates, and every dispatched action that is not
ExternalControlExempt is dropped and reported as DispatchResult.Blocked. Use this when
replicating one store into another so the two do not both author state.

Store.isExternallyDriven reports the current mode. beginExternalControl and
endExternalControl notify every ModuleLogic through onExternalControlChanged, entering
before the gate engages and leaving after it disengages, so a hook can still dispatch.
Neither may be called from inside action processing: the hook dispatches and the dispatch
loop is a single consumer, so doing so deadlocks.

markExternallyDriven is the synchronous variant for logic constructors, where no hooks
need notifying because no logic can have in-flight work yet. ToolingService gains
startsExternallyDriven for this: a service returning true makes ToolingLogic gate the
store before any module can begin start-up work. Treat it as one-shot, since logic is
rebuilt on every store reset and a standing true re-gates a store that just recovered.

A store reset always returns the store to local control. Related: BC-35.

---

### [BC-35] Followers no longer evaluate local navigation guards

**Type:** Behavioural

**Grep:** `defaultRole = ClientRole.LISTENER|DevToolsCommands.follow`
**File glob:** `**/*.kt`

**Before:**
```kotlin
// A LISTENER client ran its own bootstrap: entry selectors and intercept guards were
// evaluated locally before any publisher state arrived.
```

**After:**
```kotlin
// A LISTENER client evaluates no entry selectors and no guards. Its navigation state
// comes entirely from the publisher through state projection.
```

**Notes:** No source change is required. The behaviour changes in three ways.

Guards and entry selectors no longer run on a follower, so their side effects (session
initialisation, network calls) no longer happen there. Guards that were relied upon to
perform work rather than only to decide will not fire on a follower.

NavigationLogic.navigate returns NavigationOutcome.Dropped on a follower instead of
executing, so local navigation cannot fight the incoming projection.

A client configured with autoConnect and defaultRole = LISTENER gates its store during
construction, before start-up work begins, and then waits for a publisher indefinitely. It
renders the navigation loading placeholder until the first projection arrives and never
falls back to booting itself, because configuring the role declares the intent to follow. A
client that should boot normally and choose later must start UNASSIGNED and call follow when
it is ready. A slow or absent publisher is reported as a DEGRADED status and the wait
continues.

The wait ends by itself. A listener that connects before any publisher is linked to one as
soon as it registers, and the server then asks that publisher for a baseline, so no polling
or rescanning is involved on either side.

Cross-platform replication requires both ends to register the same navigatables.
NavigationState is polymorphic over Screen, Modal and NavigationGraph, so a follower whose
graph does not declare every type the publisher sends cannot decode the state sync. That
failure now surfaces as a DEGRADED tooling status reading "state sync rejected", rather
than leaving the follower on a loading screen with only a debug-level warning. Related:
AD-51.

---
### [AD-52] reaktiv-devtools JVM target and embeddable server

**Type:** Addition

**Grep:** `DevToolsServer.startEmbedded|RunningDevToolsServer`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val server = DevToolsServer.startEmbedded(port = 0)
try {
    val url = "ws://127.0.0.1:${server.port}/ws"
} finally {
    server.stop()
}
```

**Notes:** reaktiv-devtools now publishes a JVM variant alongside the existing android, iOS,
native and wasmJs ones. jvmMain shares desktopMain, so both the websocket client (ktor CIO)
and the DevTools server are available on the JVM. This makes it possible to embed the server
in a JVM or desktop host instead of only running the native binary, and it is what lets an
end-to-end test drive a real server, a real publisher and a real listener in one process
together with reaktiv-navigation, which has no native desktop target.

startEmbedded returns without blocking, unlike start, and reports the bound port so a caller
can pass port 0 and let the OS choose. RunningDevToolsServer.stop shuts the engine down.
DevToolsServer is an object, so a host that starts several servers over its lifetime should
call resetState between them to drop stale client and publisher bookkeeping.

---
### [AD-53] IntrospectionConfig.installLogicTracing

**Type:** Addition

**Grep:** `installLogicTracing`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val config = IntrospectionConfig(
    platform = "JVM",
    installLogicTracing = false
)
```

**Notes:** Defaults to true, which is the previous behaviour. Setting it false stops
ToolingLogic and DevToolsService registering their LogicTracer observers, so no logic
method events are captured or sent. Use it to cut telemetry volume when only state
replication or state capture is wanted.

It is also required when two stores run in the same process. LogicTracer is a global
object, so every store's observer sees every other store's dispatches and reports them
under its own client id. That crosstalk produces a message storm that can crowd out state
deltas on the wire. Running more than one instrumented store per process is not a supported
configuration, and this flag is the way to keep the extra instances quiet.

---
### [AD-54] Observers receive a full state baseline on attach

**Type:** Addition

**Grep:** `ListenerAttached`
**File glob:** `**/*.kt`

**Example:**
```kotlin
DevToolsMessage.ListenerAttached(
    listenerId = "devtools-ui",
    role = ClientRole.ORCHESTRATOR
)
```

**Notes:** ListenerAttached gains a role field defaulting to ClientRole.LISTENER, so existing
publishers keep their previous behaviour. The server now emits it for an attaching
ORCHESTRATOR as well as an attaching LISTENER, and the publisher answers according to the
role: a full StateSync for a listener, which replicates state, and a SessionHistorySync for
an orchestrator, which needs the captured initial state plus the action history.

This is what lets the devtools UI show the full application state rather than only the
modules appearing in deltas. The UI reconstructs through
StateReconstructor.reconstructAtIndex(initialStateJson, history, index), which produced a
partial picture whenever initialStateJson was still "{}". A UI attaching after the publisher
had already announced itself never received a baseline at all, because the history was sent
once at role assignment.

Two supporting behaviours: the publisher substitutes its current state when the capture has
not recorded an initial state yet, which happens when no non-tooling action has been
dispatched, and the UI adopts a full-tree StateSync as its baseline when it has none, which
covers publishers predating the role field.

---
### [AD-55] Per-module state projection with named failures

**Type:** Addition | Behavioural

**Grep:** `applyStateSync|cannot replicate`
**File glob:** `**/*.kt`

**Notes:** A follower now decodes an incoming full state tree one module at a time, applies
every module it can reconstruct, and reports the ones it cannot as a DEGRADED tooling status
naming the module and the underlying reason.

Previously the tree was decoded in a single call, so one undecodable module aborted every
other module with it and the follower ended up with no replicated state at all. The status
message is the actionable part: it carries the original error, which for navigation names the
exact route path that could not be resolved.

This matters most across applications. NavigationEntry does not serialise the screen class,
it serialises the route path and rehydrates by resolving that path against the follower's own
graph. So replicating navigation requires the follower to declare the same routes as the
publisher, including graph nesting such as "home/detail". Screens may be entirely different
classes on each platform, but a route the follower never declared cannot be reconstructed and
navigation will report as degraded while all other modules continue to replicate.

---
### [BC-36] Redaction preserves JSON shape

**Type:** Behavioural

**Grep:** `REDACTED_PLACEHOLDER|sensitiveKeyRedactor|redactSensitiveKeys`
**File glob:** `**/*.kt`

**Before:**
```kotlin
// Any value under a sensitive key became the mask string:
// {"hasHyperwalletToken": "[REDACTED]"}   // was Boolean
// {"confirmPassword": "[REDACTED]"}       // was an object
```

**After:**
```kotlin
// Strings are masked, numbers are zeroed only where the key itself is sensitive,
// and booleans are left alone:
// {"hasHyperwalletToken": true}
// {"confirmPassword": {"value": "[REDACTED]", "valid": true, "attempts": 3}}
// {"ssn": 0}
```

**Notes:** Strings are masked wherever they appear under a sensitive key. Numbers are zeroed
only where the key naming them is itself sensitive, which covers a secret held numerically
such as an SSN or card number stored as a Long while leaving an ordinary number that merely
sits inside a secret's object intact. Booleans are never masked, since a Boolean is a flag
about a secret rather than the secret, and reporting the opposite of the truth misleads anyone
reading a capture and makes a replicated follower behave differently from its publisher.
Objects and arrays are recursed into rather than replaced.

Masking only strings also makes redaction type-safe by construction. Substituting a value of a
different JSON type is invisible in a tree viewer but breaks anything decoding the capture back
into typed state, which is how one sensitive key used to make a whole module impossible to
replicate.

The polymorphic class discriminator, "type" by default and configurable through
sensitiveKeyRedactor, is preserved at any depth including inside a subtree being masked, since
replacing it leaves a type name no serializer can resolve.

Known gap: an enum serialises as a string and is indistinguishable from one in the JSON tree,
so an enum under a sensitive key is masked and will not decode. Rename the field or supply a
custom StateRedactor when that applies.

The old behaviour changed the JSON type of whatever it masked. That is invisible in a tree
viewer but fatal to anything decoding captured state back into typed state, so a single
sensitive key anywhere in a module made that module impossible to replicate or reconstruct.
Symptom was a follower that received deltas and applied none of them, reporting
"Expected valid boolean literal prefix, but had '[REDACTED]'".

Callers reading redacted output as text should note that a masked Boolean now reads "false"
rather than "[REDACTED]", so absence of the placeholder no longer proves a field was not
redacted.

Note that replication carries redacted values, so a follower holds the masked value rather
than the publisher's real one for sensitive keys. Set redactSensitiveKeys = false on both
ends when a faithful mirror matters more than masking.

---
### [AD-56] Publisher encodes state per module

**Type:** Addition | Behavioural

**Grep:** `cannot publish`
**File glob:** `**/*.kt`

**Notes:** A publisher now encodes its state tree one module at a time when answering an
attach, sends every module it can serialize, and names the ones it cannot in a DEGRADED
tooling status.

Previously the whole map was encoded in a single call, so one module that could not be
serialized threw and suppressed the entire baseline. The observer then received nothing and
reported "publisher sent no state", which pointed at the wrong end of the wire: the failure
was on the publisher, and only the publisher can fix it.

The usual cause is a sealed hierarchy whose subclasses are not serializable. Every direct
subclass of a serializable sealed class must itself be annotated, data objects included:

```kotlin
@Serializable
sealed class PaginationState {
    @Serializable data object Empty : PaginationState()
    @Serializable data object Idle : PaginationState()
}
```

Mirrors the receiving side, see AD-55.

---
### [AD-57] Waiting observers are linked when a publisher appears

**Type:** Addition

**Grep:** `attachWaitingObservers`
**File glob:** `**/*.kt`

**Notes:** The server links every observer that has no publisher to the current one after each
role assignment, and asks that publisher for a baseline for each observer it just linked. A
ghost publisher cannot answer, so for ghosts the request goes to its subscribers instead.

Running this after every assignment rather than only when a publisher registers makes the
linkage self-healing. Role assignments are handled concurrently, so a listener and a publisher
arriving together can interleave such that neither sees the other: the listener is assigned
while no publisher exists, and the publisher runs its auto-attach before the listener's role
has been recorded. Whichever assignment lands last now completes the linkage.

The baseline request must come after the role is assigned, since a publisher ignores an attach
notification until it knows it is the publisher. Related: AD-54.

---
### [BC-37] Queued navigations evaluate while the previous transition settles

**Type:** Behavioural

**Grep:** `navigation {`
**File glob:** `**/*.kt`

**Before:**
```kotlin
// A navigation issued during another navigation's enter/exit animation waited
// for the full animation before its guards and entry selectors even started.
store.navigation { navigateTo(Route.Home) }
```

**After:**
```kotlin
// Same call. Guard and entry evaluation for a queued navigation now runs
// during the previous transition. The state commit is still paced: it lands
// only after the previous transition has settled, so animations are never
// interrupted mid-flight.
store.navigation { navigateTo(Route.Home) }
```

**Notes:** The navigation mutex is released right after the state commit instead of being
held through the transition-length delay. A suspended `navigate()` call still returns only
after its own transition has settled, so awaiting callers observe the same timing as before.
One observable difference: the tail wait is now cancellable, so cancelling the calling
coroutine after the commit no longer blocks on the animation. Cache-related: see AD-58 for
skipping guard bodies entirely.

---
### [AD-58] cacheKey on intercept() and start(route = ...) skips re-evaluation

**Type:** Addition

**Grep:** `cacheKey =`
**File glob:** `**/*.kt`

**Example:**
```kotlin
createNavigationModule {
    rootGraph {
        start(
            route = { store ->
                store.selectLogic<ConfigLogic>().initConfig()
                if (userSession.hasValidSession()) NavigationPath(Route.HOME)
                else NavigationPath(Route.START)
            },
            loadingThreshold = 200.milliseconds,
            cacheKey = { _ -> userSession.sessionId }
        )
        intercept(
            guard = { _ ->
                if (userSession.blockingHasValidSession()) GuardResult.Allow
                else GuardResult.PendAndRedirectTo(Route.START)
            },
            cacheKey = { _ -> userSession.sessionId to firebaseVariables.configVersion }
        ) {
            graph(Route.HOME) { ... }
        }
    }
}
```

**Notes:** When `cacheKey` is provided, the guard or entry selector result is cached against
the key value. On the next evaluation the key selector runs first: if it equals the cached
key (structural equality), the cached result is returned and the guard or selector body,
its loading threshold machinery and the loading modal are all skipped. A changed key
re-runs the body and replaces the cached result. The key selector runs on every evaluation,
so keep it cheap and synchronous-ish (a state read or a property access, never a network
call). Omitting `cacheKey` keeps the previous always-evaluate behaviour.

The cache is keyed by the guard or selector function instance, so an outer `intercept`
guard shared across nested zones through guard chaining hits the same cache entry no matter
which zone triggers it. All results (`Allow`, `Reject`, `RedirectTo`, `PendAndRedirectTo`)
are cached, correctness of the key is the application's contract. The cache is cleared on
`store.reset()`. Same-zone navigations never reach the cache: they are short-circuited
before guard evaluation as documented in AD-09.

---
### [BC-38] Nested dynamic-entry graphs are addressable by full path, leading slashes normalized

**Type:** Behavioural

**Grep:** `navigateDeepLink|navigateTo("`
**File glob:** `**/*.kt`

**Before:**
```kotlin
// A graph with a dynamic start lambda nested inside another graph could only be
// reached by its bare graph id. The full path threw RouteNotFoundException, as
// did any deep link path with a leading slash.
store.navigateDeepLink("/home/insight")
store.navigation { navigateTo("home/insight") }
```

**After:**
```kotlin
// Same calls now evaluate the insight graph's start lambda and land on its
// resolved destination, with ancestor entries synthesized for deep links.
store.navigateDeepLink("/home/insight")
store.navigation { navigateTo("home/insight") }
```

**Notes:** Route strings passed to `navigateTo`, `navigateDeepLink`, alias targets, and
`GuardResult` redirects are now normalized (leading and trailing slashes stripped) and
canonicalized: a full nested path such as `home/insight` maps to its graph id before the
dynamic-entry, guard, and synthesis lookups run. Consequences:

- Deep links and deep link aliases can target a nested graph whose start is a dynamic
  lambda, by full path or bare id, with or without a leading slash.
- Intercept guards fire for full-path targets before the entry lambda is evaluated, so
  entry side effects never run for a navigation the guard rejects or pends.
- Backstack synthesis no longer silently skips nested dynamic-entry ancestor graphs.
- A pending navigation (`PendAndRedirectTo`) whose route is a dynamic-entry graph path is
  resolved through the entry lambda on `resumePendingNavigation()` instead of being
  silently dropped.
- Query parameters on non-alias deep links are now merged into the target params the same
  way the alias branch does.

New helpers `RouteResolver.canonicalGraphId(route)` and `RouteResolver.fullPathForGraph(graphId)`
back the canonicalization. No application code changes are required.

---
### [BC-39] Redaction is type aware and captured state always decodes

**Type:** Behavioural

**Grep:** `redactSensitiveKeys|sensitiveKeyRedactor|SessionCapture\(`
**File glob:** `**/*.kt`

**Before:**
```kotlin
// Every string leaf under a sensitive key became the mask string, including
// string-serialized types, so the capture stopped decoding:
// {"secretLevel": "[REDACTED]"}   // was an enum, decode threw
// {"tokenIssuedAt": "[REDACTED]"} // was an Instant, decode threw
```

**After:**
```kotlin
// Masking is guided by the module state's SerialDescriptor and every decode
// path restores masked constrained values to valid ones:
// {"secretLevel": "[REDACTED]"}   // still masked on the wire and in exports
// a follower or reconstruction decodes it as a fallback constant instead of throwing
```

**Notes:** Closes the known gap documented in BC-36. The built-in sensitive-key pass no longer
runs as a blind JsonElement transform inside a composed StateRedactor. SessionCapture now
walks the encoded JSON in parallel with the state class's SerialDescriptor, so it knows
whether a string leaf is a plain String, an enum, or a custom string format, and every decode
path (follower replication, per-action deltas, single-module sync) restores the `[REDACTED]`
sentinel to a valid value before typed decoding. Enum fallbacks resolve in order: a constant
pinned with `@RedactedAs`, a constant named REDACTED, UNKNOWN, or UNSPECIFIED, null when the
property is nullable, otherwise the first constant. Custom string formats resolve through
`@RedactedAs`, nullability, then `RedactionFallbacks.formatFallbacks` (Instant, LocalDate,
Duration, Uuid and friends map to epoch-style values).

SessionCapture gained a `redactSensitiveKeys: Boolean = true` constructor parameter and the
config's custom `redactor` now runs after the built-in pass instead of being composed with it.
Output of a custom StateRedactor is also repaired on decode, so a legacy redactor that masks
an enum no longer breaks replication.

The capture worker now verification-decodes its own redacted output (every FULL delta, every
initial-state module, and a 1-in-100 sample of FIELDS deltas). A failure or an unrestorable
masked leaf is reported once per cause as a synthetic logic event with logicClass
`RedactionWatchdog` plus a `ReaktivDebug.error`, so an undecodable capture is loud on the
publisher instead of surfacing as a degraded follower.

Chars under a sensitive key now mask to `*` instead of the placeholder, since the placeholder
does not decode as a Char. Booleans remain untouched, numbers are still zeroed only where the
key naming them is itself sensitive.

---
### [AD-59] Redacted and RedactedAs annotations with symmetric restore

**Type:** Addition

**Grep:** `@Redacted|@RedactedAs|restoreRedactedModuleElement|RedactionFallbacks`
**File glob:** `**/*.kt`

**Example:**
```kotlin
import io.github.syrou.reaktiv.core.serialization.Redacted
import io.github.syrou.reaktiv.core.serialization.RedactedAs

@Serializable
data class VaultState(
    @Redacted val internalCode: Clearance = Clearance.HIGH,
    @RedactedAs("UNKNOWN") val tier: Clearance = Clearance.HIGH,
    val password: String = ""
) : ModuleState

// Tooling-owned decode paths repair sentinels before typed decoding:
val decodable = restoreRedactedModuleElement(json, capturedModuleJson)
```

**Notes:** `@Redacted` (reaktiv-tracing-runtime, package `core.serialization`) marks a property as sensitive
regardless of its name, so redaction no longer depends on key-name heuristics alone.
`@RedactedAs("VALUE")` additionally pins the value the decode side restores, an enum constant
name for enums or a literal for custom string formats, and implies `@Redacted`. Both are
SerialInfo annotations read from the descriptor, so publisher and follower agree by sharing
the same compiled state classes. They ship in reaktiv-tracing-runtime, which is release-safe
and auto-added by the tracing Gradle plugin, keeping reaktiv-core free of them while release
state classes still compile and their generated serializers still resolve the annotation class
at runtime.

`restoreRedactedModuleElement(json, element)` (reaktiv-introspection) is the single decode
choke point: it resolves the module state's concrete descriptor from the polymorphic
discriminator and replaces `[REDACTED]` sentinels with valid values. DevToolsService uses it
on every follower decode path. Any new code that decodes captured or replicated module state
must go through it rather than a raw Json decode, that convention is what keeps the
"redaction never produces undecodable state" invariant durable. `RedactionFallbacks` exposes
the built-in per-serialName fallback registry and the recognized enum fallback constant names.
See BC-39 for the behavioural side.

---
### [BC-40] Store dispatch tracing moved behind an instrumentation seam

**Type:** Breaking | Behavioural

**Grep:** `setDispatchInstrumentation|DispatchInstrumentation`
**File glob:** `**/*.kt`

**Before:**
```kotlin
// Store emitted StoreDispatch spans directly through LogicTracer whenever
// any observer was registered, and core's dispatch path referenced LogicTracer.
```

**After:**
```kotlin
// Store holds only a nullable DispatchInstrumentation reference. The tooling
// module installs the LogicTracer-forwarding implementation:
// ToolingLogic calls store.setDispatchInstrumentation(DispatchTracingInstrumentation())
// when installLogicTracing is enabled, and clears it in beforeReset.
```

**Notes:** Core's `Store` no longer contains any tracing or measurement code.
`DispatchInstrumentation` (reaktiv-core) is a neutral SPI with callbacks for dispatch
start/completion/failure, external-control drops and transitions, and an optional
`DispatchStepDecorator` hook: Store hands each chain step (every middleware by name plus the
reducer) to the decorator for wrapping, and all timing, self-time math, thresholds, and span
emission live in the tooling-side decorator, not in Store. Without an installed instrumentation the dispatch path costs one
null check and emits nothing, so apps that never add the tooling dependency carry no dispatch
tracing. Consequence: StoreDispatch spans (queue wait, queue depth, AD-44) now appear only when
`createToolingModule` is installed. `DispatchTracingInstrumentation` (reaktiv-introspection)
reproduces the previous span shapes exactly, so downstream consumers (perf lens, capture) see
unchanged data.

---
### [AD-60] Dispatch phase self times and logic call trees

**Type:** Addition

**Grep:** `DispatchPhase|parentCallId`
**File glob:** `**/*.kt`

**Example:**
```kotlin
// The tooling decorator wraps every dispatch chain step and computes self
// times itself. With tooling installed, phases at or above 1ms become child spans:
// logicClass "DispatchPhase", methodName "reducer" or "<middleware>[index]",
// parented to the StoreDispatch span via LogicMethodStart.parentCallId.
```

**Notes:** `LogicMethodStart` gained `parentCallId: String? = null`. LogicTracer tracks a
per-coroutine-Job call stack, so any traced call started while another traced call is active in
the same coroutine records that caller as its parent. This links nested logic calls and parents
dispatch-phase spans under their dispatch, enabling flame-style rendering. Phase timings are
self times computed by the decorator's frame stack: a middleware's time excludes the chain
below it. Phase spans carry their true start timestamps, emit only at 4ms self time or more
(clock-tick noise stays silent), are hidden from the DevTools event stream like StoreDispatch
spans, and pipeline synthetics are excluded from per-thread contention stats so one nested
dispatch never reads as concurrent load. A slow reducer or middleware is
also surfaced as a Finding (see AD-63). Serialization is backward compatible, the field
defaults to null.

---
### [AD-61] Session markers

**Type:** Addition

**Grep:** `addMarker|SessionMarker|MarkerAdded`
**File glob:** `**/*.kt`

**Example:**
```kotlin
// On device, from a debug menu or report button:
store.selectLogic<ToolingLogic>().addMarker("saw the glitch", "list jumped to top")

// From the DevTools UI or any orchestrator, dropped remotely:
// DevToolsMessage.AddMarkerRequest(targetClientId, label, note)
```

**Notes:** `SessionMarker(id, label, note, timestampMs, afterActionIndex, route, source)` is
enriched by the capture worker with the current route and the action index at the moment of the
mark, exactly like crashes, so a marker pins a scrubbable position in the session. Markers
persist in capture storage, ride `SessionHistory` and `SessionExport` (format 3.5), stream live
as `DevToolsMessage.MarkerAdded`, and the server relays `AddMarkerRequest` to the publisher,
which records it with source "remote". `SessionCapture.addMarker(label, note, source)` is the
low-level API.

---
### [AD-62] Stall culprit sampling

**Type:** Addition

**Grep:** `hottestFrame|STACK_SAMPLE_LIMIT`
**File glob:** `**/*.kt`

**Example:**
```kotlin
// MainThreadWatchdog stall events now carry:
// params["stack"]        first stack captured at stall onset
// params["samples"]      number of stacks sampled during the stall
// params["hottestFrame"] most frequent top frame across samples
```

**Notes:** StallWatchdog samples the monitored thread's stack on every monitor tick while the
stall persists (capped at 50 samples) instead of once at onset, and reports the most frequent
top frame. On platforms without stack capture the findings layer falls back to correlating the
stall window against in-flight main-thread logic spans (AD-63). The constructor accepts an
injectable `stackCapturer` for tests.

---
### [AD-63] Findings, recomposition churn, and state growth field attribution

**Type:** Addition

**Grep:** `computeFindings|aggregateChurn|topGrowingField`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val findings = computeFindings(
    starts = logicStarts,
    completions = logicCompletions,
    sizes = stateSizeTracker.snapshot(),
    churn = aggregateChurn(actions, stateReads)
)
```

**Notes:** All in reaktiv-devtools commonMain, computed UI-side from already-captured data with
zero protocol change. `computeFindings` produces a ranked triage list: main-thread stalls with
an exact culprit (hottest sampled frame, else the longest overlapping main-thread logic span
with its file, line, and GitHub link), capture redaction issues, dispatch queue-wait warnings
naming the worst action, slow dispatch phases (reducer at 8ms is critical, middleware at 16ms
warns), suspicious state growth naming the fastest-growing field
(`ModuleSizeStats.topGrowingField`, tracked per top-level JSON field), and recomposition churn.
`aggregateChurn` scores each composable by the change volume of the states it reads, joining
the AD-41 state-read stream with captured action counts.

---
### [BC-41] Tracing runtime extracted from reaktiv-core

**Type:** Breaking

**Grep:** `io.github.syrou.reaktiv.core.tracing`
**File glob:** `**/*.kt, **/build.gradle.kts`

**Before:**
```kotlin
// LogicTracer, LogicMethodStart/Completed/Failed, LogicObserver, StateRead,
// StateReadTracker and Obfuscation shipped inside reaktiv-core.
```

**After:**
```kotlin
// The same classes, same package io.github.syrou.reaktiv.core.tracing, now ship
// in the reaktiv-tracing-runtime artifact. Apps applying the tracing Gradle
// plugin get it automatically. Direct users add:
// implementation("io.github.syrou:reaktiv-tracing-runtime:<version>")
```

**Notes:** reaktiv-core now contains no tracing machinery at all: no tracer, no event types,
no observers. The only tracing-adjacent surface left in core is the neutral
`DispatchInstrumentation` seam from BC-40, which has zero dependencies. The package name is
unchanged, so source code, the compiler plugin's injected calls, and the JSON wire and export
formats are all unaffected, this is purely a dependency-graph change. The tracing Gradle
plugin auto-adds the runtime alongside the annotations artifact, so apps using `reaktivTracing`
need no change. reaktiv-introspection and reaktiv-devtools expose the event types in their
public APIs and declare the runtime as an api dependency, so their consumers also need no
change. reaktiv-navigation carries an implementation dependency on the runtime for guard
tracing (AD-38), which keeps guard observability zero-config, the artifact is a small inert
event bus that no-ops without observers. A consumer that imported these types while depending
only on reaktiv-core must add the dependency shown above.

---
### [AD-64] Trace annotation for suspend functions outside ModuleLogic

**Type:** Addition

**Grep:** `@Trace`
**File glob:** `**/*.kt`

**Example:**
```kotlin
import io.github.syrou.reaktiv.tracing.annotations.Trace

class NewsRepository {
    @Trace
    suspend fun fetchTopStories(): List<Story> = api.load()
}
```

**Notes:** ModuleLogic suspend methods are instrumented automatically. `@Trace`
(reaktiv-tracing-annotations) opts any other suspend function into the same instrumentation:
repositories, data sources, use-case helpers, and top-level suspend functions (attributed to
their file name as the logic class). Events carry the same duration, params, file, line, and
GitHub link, and nest under the calling traced method through `parentCallId`, so a traced
repository call appears as a child of the logic span that invoked it. The function must be
suspend, the compiler warns and skips otherwise. `@NoTrace` is not needed alongside it, simply
omit `@Trace`. Instrumentation only exists in build types where the tracing plugin applies.

---
### [AD-65] Dispatch call-site provenance and dispatch-storm findings

**Type:** Addition

**Grep:** `dispatchedFrom|DispatchOriginTracker|dispatch-storm`
**File glob:** `**/*.kt`

**Example:**
```kotlin
// With the tracing plugin applied, every dispatch call site is recorded:
// StoreDispatch span params gain
//   "dispatchedFrom" -> "com.example.NewsLogic.refresh (NewsLogic.kt:42)"
// covering store.dispatch(...), dispatchAndAwait(...), and injected Dispatch lambdas.
```

**Notes:** A new compiler transformer wraps dispatch call sites (member `dispatchAndAwait` on
Store or StoreAccessor, and `invoke` on anything named `dispatch`, which covers the `Dispatch`
property and constructor-injected dispatch lambdas) to record the enclosing function, file, and
line into `DispatchOriginTracker` (reaktiv-tracing-runtime) just before the call. The tracker
pairs origins to action instances by identity with FIFO queues, no-ops when the tracer is
inactive, and is bounded at 256 entries. `DispatchTracingInstrumentation` consumes the origin
in `onDispatchStarted` and attaches it as the `dispatchedFrom` param on the StoreDispatch span,
so every action in the devtools stream answers "who dispatched this" with a clickable location.
`computeFindings` gained a dispatch-storm detector: 20 or more dispatches of the same action
type within one second produces a warning naming the recorded origin of the burst, which is how
feedback-loop bugs surface. Verified end to end by instrumented tests in reaktiv-introspection
(its own test compilation runs the plugin) on JVM, mingwX64, and wasmJs.

---
### [AD-66] Device log forwarding and positioned markers

**Type:** Addition

**Grep:** `ReaktivLogSink|LogBatch|addMarker`
**File glob:** `**/*.kt`

**Example:**
```kotlin
// Any ReaktivDebug output on a publisher now streams to the DevTools UI:
ReaktivDebug.general("sync finished with 42 items")
// arrives batched as DevToolsMessage.LogBatch and renders as log rows in the stream

// Markers can pin an explicit moment instead of "now":
capture.addMarker("saw it here", timestampMs = actionTime, afterActionIndex = 17)
```

**Notes:** `ReaktivLogSink` is a neutral fan-out seam on `ReaktivDebug` (reaktiv-core), the same
pattern as `DispatchInstrumentation`: copy-on-write sink list, zero work with no sinks, sinks
receive lines even when console printing is disabled. `DevToolsService` registers a sink that
feeds a 512-entry drop-oldest channel, flushed every 300ms in batches of up to 100 as
`DevToolsMessage.LogBatch` only while the client is a connected publisher, so memory overhead
is bounded on every platform. The UI retains the newest 3000 lines and renders them as
level-colored rows merged into the event stream behind a Logs toggle.
`SessionCapture.addMarker` and `DevToolsMessage.AddMarkerRequest` gained optional
`timestampMs` and `afterActionIndex`, letting the DevTools UI drop a marker at the currently
selected action rather than at receive time. Historical markers skip route enrichment, since
the current route would be wrong for a past moment.

---

### [AD-67] Ktor network inspection with timeline and cURL export

**Type:** Addition

**Grep:** `ReaktivNetworkInspection`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val client = HttpClient(engine) {
    install(ReaktivNetworkInspection) {
        maxBodyBytes = 64 * 1024
        redactedHeaders = setOf("Authorization", "Cookie", "Set-Cookie", "Proxy-Authorization")
        bodyRetentionCount = 50
    }
}
```

**Notes:** New module `reaktiv-network-ktor` provides a Ktor client plugin that captures
every request and response (method, url, headers, bodies, status, timing, errors) and emits
them through the new `NetworkTap` seam in reaktiv-introspection. The DevTools service
forwards captures from a publisher in batches (`DevToolsMessage.NetworkBatch`) using the
same buffered flush pipeline as device logs. In the WASM UI network requests appear as a
dedicated lane in the session timeline and as rows in the event stream, clicking one opens
the Network tab in the side panel with full headers, pretty printed bodies, copy as cURL
(`CurlFormatter.toCurl`), copy URL, and copy response. The originating client re-executes the
retained original request (with unredacted headers kept on device only) and tags the new
capture. Redaction applies before anything leaves the device. Bodies are
captured only for textual content types (json, xml, form, text, excluding event-stream),
truncated at `maxBodyBytes`, and skipped entirely above `hardBodyLimitBytes`. Capture is
inert until a listener attaches to `NetworkTap`, so an installed plugin without a connected
DevTools session does no work. Streaming reads through `prepare { }.execute { }` may be
read into memory by response body capture, set `captureBodies = false` or narrow
`shouldCaptureBody` for streaming-heavy clients.

---

### [BC-42] `@Sensitive` and `@PII` now actually redact traced parameters

**Type:** Behavioural

**Grep:** `@Sensitive|@PII`
**File glob:** `**/*.kt`

**Before:**
```kotlin
// The annotations were documented as redacting, but the compiler plugin never read them.
// A traced call recorded the real value:
//   params = { "password": "hunter2" }
suspend fun signIn(@Sensitive password: String) { }
```

**After:**
```kotlin
// Same source, but the plugin now emits Obfuscation.redact() / Obfuscation.maskPII(value):
//   params = { "password": "[REDACTED]" }
suspend fun signIn(@Sensitive password: String) { }
```

**Notes:** No source change is required, but traces, session exports and DevTools output will
start showing `[REDACTED]` for `@Sensitive` parameters and partially masked values for `@PII`
where they previously showed the value in full. Anything asserting on traced parameter strings
must be updated. A `@Sensitive` parameter is never evaluated at the call site, so a costly
`toString` on a secret no longer runs. If the redaction runtime cannot be resolved the parameter
is redacted rather than traced. See AD-68.

---

### [BC-43] `Obfuscation.redact` takes no argument

**Type:** Breaking

**Grep:** `Obfuscation.redact(`
**File glob:** `**/*.kt`

**Before:**
```kotlin
val masked = Obfuscation.redact(value)
```

**After:**
```kotlin
val masked = Obfuscation.redact()
```

**Notes:** The argument was ignored, and taking one forced the call site to evaluate the secret
it was about to discard. See BC-42.

---

### [BC-44] DevTools server client bookkeeping is suspending

**Type:** Breaking

**Grep:** `DevToolsServer.resetState\(\)|RunningDevToolsServer|\.broadcastClientList\(|clientManager\.isGhost\(`
**File glob:** `**/*.kt`

**Before:**
```kotlin
DevToolsServer.resetState()
val server = DevToolsServer.startEmbedded(port = 0)
val url = "ws://127.0.0.1:${server.port}/ws"
```

**After:**
```kotlin
// resetState and port are suspending; call them from a coroutine
DevToolsServer.resetState()
val server = DevToolsServer.startEmbedded(port = 0)
val url = "ws://127.0.0.1:${server.port()}/ws"
```

**Notes:** `RunningDevToolsServer.port` became `suspend fun port()` because the previous
`by lazy { runBlocking { ... } }` deadlocks when first read from a dispatcher that cannot spare a
thread. `ClientManager.reset()` became suspending so it takes the same lock as every other
mutation. `ClientManager.broadcastClientList()` and `ClientManager.isGhost()` are gone:
broadcasting is now internal to each mutating operation, and `isGhostDevice()` was an identical
duplicate of `isGhost()`. `ConnectedClient.info` is a `val`; replace the map entry with `copy()`
instead of mutating in place.

---

### [AD-68] `CopyOnWriteRegistry` for lock-free listener lists

**Type:** Addition

**Grep:** `CopyOnWriteRegistry`
**File glob:** `**/*.kt`

**Example:**
```kotlin
import io.github.syrou.reaktiv.core.util.CopyOnWriteRegistry

private val observers = CopyOnWriteRegistry<MyObserver>()

fun addObserver(observer: MyObserver): Boolean = observers.add(observer)
fun removeObserver(observer: MyObserver): Boolean = observers.remove(observer)

fun notify(event: MyEvent) {
    observers.forEachCatching({ ReaktivDebug.warn("observer threw: ${it.message}") }) {
        it.onEvent(event)
    }
}
```

**Notes:** Replaces the copy-on-write CAS loop that was hand-written in `ReaktivDebug`,
`LogicTracer`, `StateReadTracker` and the Store's crash listeners, along with the duplicated
notify-and-swallow loop. Registration and notification are safe from any thread, and a listener
added or removed during a notification pass does not disturb the pass in flight.

---

### [BC-45] Store dispatch pipeline is ordered and single-consumer

**Type:** Behavioural

**Grep:** `store.dispatch\(|dispatchAndAwait|addCrashListener|\.cleanup\(\)`
**File glob:** `**/*.kt`

**Before:**
```kotlin
// dispatch enqueued from a launched coroutine, so two calls could reach the reducer
// in either order, and high and low priority actions were consumed concurrently
store.dispatch(FirstAction)
store.dispatch(SecondAction)

// a test could read state immediately, because the enqueue hop gave collectors time to attach
val state = store.selectState<MyState>().value
```

**After:**
```kotlin
// dispatch enqueues inline: program order is preserved and one consumer applies every action
store.dispatch(FirstAction)
store.dispatch(SecondAction)

// nothing delays the reducer any more, so wait for the queue to drain before asserting
advanceUntilIdle()
val state = store.selectState<MyState>().value
```

**Notes:** Four behavioural changes, none of which need a source change outside tests.
Fire-and-forget `dispatch` now preserves program order. A single consumer drains both channels,
high priority first, so a `HighPriorityAction` and a normal action targeting the same module can
no longer interleave and lose an update. `Store.reset()` no longer clears registered
[CrashListener]s, so a listener installed at startup survives a reset. `Store.cleanup()` now
completes anything waiting in `dispatchAndAwait` with `DispatchResult.Error` instead of leaving
the caller suspended forever.

Tests that dispatched and then read state without settling the scheduler were relying on the old
enqueue hop; add `advanceUntilIdle()` (or await a `dispatchAndAwait`) before asserting. Note that
a `HighPriorityAction` used as a completion marker will overtake pending normal actions by design,
so use a normal-priority action when you need a FIFO marker.

---

### [BC-46] Session capture files are per-instance

**Type:** Behavioural

**Grep:** `SessionCapture\(|reaktiv-introspection`
**File glob:** `**/*.kt`

**Before:**
```kotlin
// every SessionCapture in the process wrote to the same files:
//   <tmp>/reaktiv-introspection/actions.jsonl
//   <tmp>/reaktiv-introspection/crashes.jsonl
```

**After:**
```kotlin
// each instance gets its own set, discriminated by clock, per-process random value and counter:
//   <tmp>/reaktiv-introspection/<id>-actions.jsonl
//   <tmp>/reaktiv-introspection/<id>-crashes.jsonl
```

**Notes:** Two captures in one process, or two processes sharing the temporary directory, used to
append to the same file and corrupt each other's session. Nothing outside `SessionCapture` refers
to these paths, so no source change is needed. One consequence: files now accumulate in the
temporary directory when a process exits without calling `SessionCapture.stop()`, where
previously a fixed set of files was reused and overwritten.

---

### [BC-47] `CrashEventCard` removed from the DevTools web UI

**Type:** Breaking

**Grep:** `CrashEventCard`
**File glob:** `**/*.kt`

**Before:**
```kotlin
CrashEventCard(crashEvent, selected, onClick)
```

**After:**
```kotlin
// Render the crash inline. The two label helpers it used are still available:
Text("Origin: ${crashOriginLabel(crashEvent.info.origin)}")
crashLocationLabel(crashEvent.info)?.let { Text(it) }
```

**Notes:** The composable had no call sites anywhere; the crash panel in `StateViewer` renders its
own layout and only used the two label helpers, which now live in `CrashLabels.kt`. Only affects
the wasmJs target of `reaktiv-devtools`, which is the embedded web app rather than a consumed
library surface.

---

### [BC-48] `NavigationAction.Back` carries the entry it expects to pop

**Type:** Breaking

**Grep:** `NavigationAction\.Back(?![\w(])|navigateBack\(`
**File glob:** `**/*.kt`

**Before:**
```kotlin
// object, so a reference was enough
store.dispatch(NavigationAction.Back)

// and the pop always applied to whatever was current at reduce time
```

**After:**
```kotlin
// data class with a defaulted parameter, so a reference becomes a call
store.dispatch(NavigationAction.Back())

// a caller that knows which entry it means to remove can say so, and the
// reducer drops the action if the stack has moved since
store.dispatch(NavigationAction.Back(expectedTopKey = entry.stableKey))
store.navigateBack(expectedTopKey = entry.stableKey)
```

**Notes:** Only value positions need the parentheses. Type positions are unchanged, so
`is NavigationAction.Back`, `mutableListOf<NavigationAction.Back>()` and
`filterIsInstance<NavigationAction.Back>()` all still compile as written.

`expectedTopKey` defaults to `null`, which pops whatever is current: hardware back, programmatic
back and `AtomicBatch` members keep their existing behaviour with no source change beyond the
parentheses.

Why it exists: an interactive gesture decides to go back when its settle animation ends, but the
pop happens later, in the reducer. Anything enqueued during the animation reduces first, so the
gesture could pop an entry the user never swiped. Checking the top before dispatching narrows that
window but cannot close it, because the check and the pop happen at different times. Naming the
expectation moves the decision into the reducer, which is the only place the state being popped is
the state actually in effect.

`NavigationLogic.navigateBack` and `StoreAccessor.navigateBack` gained the same defaulted
parameter. Wire format is unchanged: an object and a data class whose only field defaults to null
both serialize as `{}` with `encodeDefaults = false`. See AD-69.

---

### [AD-69] `completeInteractiveDismiss` replaces the two gesture completion functions

**Type:** Replaces-deprecated

**Grep:** `completeInteractiveDismiss|completeContentGesture|completeModalDismiss`
**File glob:** `**/*.kt`

**Replaces:** `completeContentGesture` and `completeModalDismiss`, which were near-identical copies.

**Example:**
```kotlin
// content back: an entry is revealed underneath
completeInteractiveDismiss(commit, progressVelocity, controller, store, top, revealed)

// modal dismiss: nothing is revealed, only the modal hands off
completeInteractiveDismiss(commit, progressVelocity, controller, store, entry, revealed = null)
```

**Notes:** Both are `internal`, so this affects no application code. They differed in three ways:
whether a revealed entry is marked and handed off, which handoff is armed, and how the pop was
issued. With BC-48 the third difference disappears, leaving `revealed` as the only variable, so
one function covers both. The unused `navModule` parameter is gone.

Companion to this, `ui/ScrubGesture.kt` now holds `ScrubAxis`, `trackScrub` and
`pumpInitialPassDrag`, shared by the four content recognisers in `BackGestureOverlay`.

---

### [AD-70] `ModuleShadow` folds captured deltas into per-module state

**Type:** Addition

**Grep:** `ModuleShadow`
**File glob:** `**/*.kt`

**Example:**
```kotlin
import io.github.syrou.reaktiv.introspection.protocol.ModuleShadow

val shadow = ModuleShadow(session.initialStateJson)
actions.forEach { shadow.apply(it) }
val fullStateJson = shadow.encode()
```

**Notes:** Replaces four hand-rolled copies of the same fold: `StateReconstructor`,
`KeyframedReconstructor`, `StateSizeTracker` and the follower shadow in `DevToolsService`. Three of
them round-tripped through a JSON string on every action, which is what made reconstructing a long
session quadratic; the shadow keeps the tree parsed and serializes only in `encode()`.

`apply` returns null when a `DeltaKind.FIELDS` delta arrives with no baseline, rather than treating
the partial object as the module's whole state. `StateSizeTracker` previously did treat it that
way, which under-reported the module's size and then compounded, since every later delta merged
onto that wrong base. This only differs on a malformed or truncated capture stream.

---

### [AD-71] `DevToolsMessage.FromClient` marks publisher-originated relays

**Type:** Addition

**Grep:** `DevToolsMessage.FromClient`
**File glob:** `**/*.kt`

**Example:**
```kotlin
when (message) {
    is DevToolsMessage.RoleAssignment -> { }
    is DevToolsMessage.FromClient -> clientManager.broadcastToListeners(message.clientId, message)
}
```

**Notes:** `LogicMethodStarted`, `LogicMethodCompleted`, `LogicMethodFailed`, `CrashReport`,
`StateReadReport`, `LogBatch`, `NetworkBatch`, `MarkerAdded`, `SessionHistorySync` and
`SessionHistoryChunk` implement it. All ten had their own branch in `DevToolsServer.handleMessage`
reducing to the same relay call. Purely additive: `clientId` was already declared on each of them
and is now an override.

---

### [AD-73] Gradle tasks for running the example app and the DevTools server

**Type:** Addition

**Grep:** `runDevToolsServer|runDebug`
**File glob:** `**/*.kts`

**Example:**
```bash
./gradlew :reaktiv-devtools:runDevToolsServer          # builds the WASM UI and serves it on 8080
./gradlew :reaktiv-devtools:runDevToolsServerHeadless  # websocket only, skips the WASM build

./gradlew :androidexample:runDebug        # assemble, install and launch
./gradlew :androidexample:reinstallDebug  # uninstall first, then the above
./gradlew :androidexample:stopDebug       # force-stop on device
```

**Notes:** `buildDevToolsServer` and `buildDevToolsServerFast` produced artifacts but nothing
served them, so getting a UI up meant running a native binary by hand with the distribution path
as an argument. `runDevToolsServer` runs the JVM target instead, so no native toolchain is needed
and the UI path is wired for you.

The Android tasks resolve `adb` from `ANDROID_HOME`, then `ANDROID_SDK_ROOT`, then `sdk.dir` in
`local.properties`, and fail with a clear message when none is set.

A new `reaktiv-network-ktor/module.md` documents the end to end wiring, and the stale setup
section in `reaktiv-devtools/module.md` was corrected: it still described `IntrospectionModule`
and `DevToolsModule`, which no longer exist.

---

### [AD-74] Chunked network body streaming

**Type:** Addition

**Grep:** `FetchNetworkBody|NetworkBodyChunk|bodyRetentionBytes|NetworkTap.bodySlice`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val client = HttpClient(engine) {
    install(ReaktivNetworkInspection) {
        maxBodyBytes = 64 * 1024
        bodyRetentionBytes = 8L * 1024 * 1024
    }
}
```

**Notes:** `maxBodyBytes` still bounds the body carried inline on every `NetworkRequestCapture`,
so batches stay small. The device now also retains the full request and response bytes, bounded
by `bodyRetentionCount` entries and the new `bodyRetentionBytes` total, and serves them a slice at a
time. When the DevTools UI opens a request whose body was truncated it streams the remainder in
64 KB chunks and renders the reassembled body, so a large JSON response reaches the tree viewer
instead of failing to parse.

New public API in `reaktiv-introspection`: `NetworkBodyPart`, `NetworkBodySlice`,
`NetworkBodyProvider`, `ByteArray.sliceOnCharBoundary`, and `NetworkTap.addBodyProvider` /
`removeBodyProvider` / `bodySlice`. New wire messages in `reaktiv-devtools`:
`DevToolsMessage.FetchNetworkBody` and `DevToolsMessage.NetworkBodyChunk`.

Slices are cut on UTF-8 character boundaries, so a multi byte character is never split across
two chunks. `sliceOnCharBoundary` reports `nextOffset` and the caller uses it as the next offset,
rather than assuming a fixed stride.

A body evicted from the device reports `available = false` and the UI keeps showing the inline
preview with a retry.

---

### [AD-75] The timeline flag button drops a marker at the pinned time

**Type:** Addition

**Grep:** `onDropMarker`
**File glob:** `**/*.kt`

**Notes:** The flag button in the session timeline called `addMarkerOnPublisher` directly with a
label and no timestamp, so the device stamped the marker with its own clock and the marker landed
at the latest available time rather than the time the user pinned. It also skipped the label and
note dialog that the `m` shortcut opens. Both paths now go through the same `dropMarker` entry
point, so the flag opens the dialog and the resulting marker carries the pinned timestamp and the
nearest action index. The button remains gated on a pinned time, which is what makes the marker
meaningful.

---

### [BC-49] `ReaktivDebug.isEnabled` is read-only

**Type:** Breaking

**Grep:** `ReaktivDebug\.isEnabled\s*=`
**File glob:** `**/*.kt`

**Before:**
```kotlin
ReaktivDebug.isEnabled = true
ReaktivDebug.isEnabled = false
```

**After:**
```kotlin
ReaktivDebug.enable()
ReaktivDebug.disable()
```

**Notes:** `isEnabled` is a process-global flag read from every thread on every log call, and it
was a plain non-volatile `var`, so a write on one thread was not guaranteed to be visible to
another. It is now backed by an `AtomicBoolean` and exposed as a `val`, with `enable()` and
`disable()` as the writers. Both existed already (see BC-12), so the migration is mechanical.

Reading `ReaktivDebug.isEnabled` is unchanged.

---

### [BC-50] Recompile against this release: appended constructor parameters

**Type:** Breaking

**Grep:** `ModuleSizeStats\(|SessionHistory\(|SessionData\(|StallWatchdog\(|SessionCapture\(`
**File glob:** `**/*.kt`

**Notes:** No source change is required for any of the types below. Every new parameter is
appended at the end of the parameter list and has a default, so existing calls compile as
written. They are listed because the generated constructor and `copy` descriptors changed, which
is a binary break: a consumer compiled against an older Reaktiv and run against this one gets
`NoSuchMethodError` until it is recompiled. Rebuild rather than patch.

| Type | Gained |
|---|---|
| `SessionHistory` | `markers: List<SessionMarker> = emptyList()` |
| `SessionData` | `markers: List<SessionMarker> = emptyList()` |
| `ModuleSizeStats` | `topGrowingField: String? = null`, `topGrowingFieldGrowthBytes: Int = 0` |
| `SessionCapture` | a trailing `Boolean` capture flag |
| `StallWatchdog` | a trailing culprit-sampling lambda |

In the same category, `clientId` on `CrashReport`, `LogicMethodStarted`, `LogicMethodCompleted`,
`LogicMethodFailed`, `SessionHistorySync`, `SessionHistoryChunk` and `StateReadReport` went from
`final` to an override of `DevToolsMessage.FromClient.clientId` (see AD-71). Reading it is
unchanged in source.

---

### [AD-76] Network filtering, endpoint stats, HAR export and timing phases

**Type:** Addition

**Grep:** `applyNetworkFilter|endpointStats|toHar\(|waitMs|downloadMs`
**File glob:** `**/*.kt`

**Notes:** `NetworkRequestCapture` gained `waitMs` (request start to response headers) and
`downloadMs` (headers to body read). Ktor exposes no DNS or connect timing, so those phases are
absent rather than guessed. Both are nullable and default to null, so an adapter that cannot
measure them simply omits them.

The Network tab gained a query, a failures-only toggle, per-method chips and newest/slowest/largest
sorting, an "By endpoint" aggregate view (calls, failures, median, slowest, bytes), and HAR 1.2
export for sharing a capture or opening it in browser devtools.

---

### [AD-78] DevTools UI state carries a data revision

**Type:** Addition

**Grep:** `dataRevision`
**File glob:** `**/*.kt`

**Notes:** Derived values in the DevTools UI were cached on `list.size`, which does not change when
a list is refilled with different content by a publisher switch or a re-sync, and does not change
at all once a capped list such as `networkEvents` reaches its 2000 entry cap. Findings, churn,
size stats and the timeline range could therefore describe the previous device.

The reducer now derives `dataRevision` by comparing the identity of every data-bearing field
after each action, so any content change bumps it and no per-action bookkeeping can be forgotten.
All 22 cache keys use it and dropped their `.size` terms.

---

### [AD-79] DevTools UI selection is a sealed type

**Type:** Addition

**Grep:** `Selection\.(None|Action|LogicCall|Crash|NetworkRequest)`
**File glob:** `**/*.kt`

**Notes:** The four mutually exclusive selection fields became one `Selection` value, so the
invariant is structural instead of being hand-maintained by a `selectingOnly` helper. Existing
read sites are unchanged: `selectedActionIndex`, `selectedLogicMethodCallId`,
`selectedNetworkRequestId` and `crashSelected` remain as extension properties over the sealed type.

The UI state and reducer moved from `wasmJsMain` to `commonMain` as `internal`, which adds no
public API and makes the reducer testable on the JVM for the first time.

---

### [BC-51] `ReaktivDebug.isEnabled` and oversized response bodies

**Type:** Behavioural

**Grep:** `hardBodyLimitBytes`
**File glob:** `**/*.kt`

**Notes:** A response whose text exceeds `hardBodyLimitBytes` is no longer copied into a byte array
before being trimmed for display. It is now reported with a bounded preview taken from the string,
`responseBodyTruncated = true`, and the real size, instead of costing a full second copy of the
body in memory. The captured preview is unchanged for bodies under the limit.

See BC-49 for the `ReaktivDebug.isEnabled` change recorded alongside this work.

---
### [BC-52] `reaktiv-navigation` no longer brings `reaktiv-tracing-runtime` transitively

**Type:** Breaking

**Grep:** `io.github.syrou.reaktiv.core.tracing`
**File glob:** `**/*.kt`

**Before:**
```kotlin
// build.gradle.kts — reaktiv-tracing-runtime arrived transitively via reaktiv-navigation
dependencies {
    implementation("io.github.syrou:reaktiv-navigation:$reaktivVersion")
}

// LogicTracer resolved without being declared
import io.github.syrou.reaktiv.core.tracing.LogicTracer
```

**After:**
```kotlin
dependencies {
    implementation("io.github.syrou:reaktiv-navigation:$reaktivVersion")
    implementation("io.github.syrou:reaktiv-tracing-runtime:$reaktivVersion")
}
```

**Notes:** `reaktiv-navigation` declared `implementation(project(":reaktiv-tracing-runtime"))` solely
to emit navigation guard and entry-selection spans. Because Kotlin Multiplatform places
`implementation` dependencies in `runtimeElements`, the tracing runtime landed on every consumer's
runtime classpath and was packaged into release artifacts with no way to exclude it. On Android,
R8 could not strip `LogicTracer`, `LogicObserver`, `LogicMethodEvents` or `CallRegistry`, because
navigation's guard tracing kept them reachable. On iOS there is no such pass at all, so the whole
module was linked into the framework.

Navigation now routes guard spans through the neutral `DispatchInstrumentation` seam in
`reaktiv-core`, and `reaktiv-introspection` supplies the forwarder that maps them onto `LogicTracer`.
Guard traces are unchanged when tooling is installed: the emitted `logicClass` is still
`"NavigationGuards"`, and the method names, `target` parameter, result strings and result types are
identical. With no tooling installed, nothing in the navigation module references tracing at all.

Only code that relied on resolving `reaktiv-tracing-runtime` transitively is affected. Declare it
directly, or depend on `reaktiv-introspection`, which exposes it as `api`. See AD-80 for the seam.

---

### [AD-80] Evaluation hooks on `DispatchInstrumentation`

**Type:** Addition

**Grep:** `onEvaluationStarted|activeDispatchInstrumentation`
**File glob:** `**/*.kt`

**Example:**
```kotlin
class MyInstrumentation : DispatchInstrumentation {

    override suspend fun onDispatchStarted(
        action: ModuleAction,
        queueWaitMs: Long,
        queueDepth: Long
    ): String = ""

    override fun onDispatchCompleted(token: String, applied: Boolean, durationMs: Long) = Unit
    override fun onDispatchFailed(token: String, error: Throwable, durationMs: Long) = Unit
    override suspend fun onDispatchDropped(action: ModuleAction) = Unit
    override suspend fun onExternalControlChanged(enabled: Boolean) = Unit

    override suspend fun onEvaluationStarted(
        scope: String,
        name: String,
        params: Map<String, String>
    ): String {
        println("$scope.$name started with $params")
        return "token-1"
    }

    override fun onEvaluationCompleted(
        token: String,
        result: String?,
        resultType: String,
        durationMs: Long
    ) {
        println("$token -> $result ($resultType) in ${durationMs}ms")
    }

    override fun onEvaluationFailed(token: String, error: Throwable, durationMs: Long) {
        println("$token failed: ${error.message}")
    }
}

store.setDispatchInstrumentation(MyInstrumentation())
```

**Notes:** `DispatchInstrumentation` covers work that happens inside the dispatch pipeline. The three
new methods cover suspending evaluations that happen outside it and still deserve a span, such as
navigation guards and dynamic entry selectors. All three have no-op defaults, so existing
implementers compile unchanged.

`onEvaluationStarted` returns a correlation token that is handed back to `onEvaluationCompleted` or
`onEvaluationFailed`. Returning an empty string is the convention for declining to trace a given
evaluation, and callers must tolerate it.

`Store.activeDispatchInstrumentation` is the matching read side. It returns the installed
instrumentation only when its `isActive` is true, so a caller can skip building span arguments
entirely when nothing is listening. This is how `reaktiv-navigation` keeps guard tracing at a single
null check when no tooling is attached.

Together these let a module emit spans without depending on any tracing artifact. See BC-52 for the
dependency this removed from `reaktiv-navigation`.

---
### [BC-53] `reaktivTracing.enabled` no longer defaults to true

**Type:** Breaking

**Grep:** `reaktivTracing`
**File glob:** `**/build.gradle.kts`

**Before:**
```kotlin
reaktivTracing {
    tracePrivateMethods.set(true)
}
```

**After:**
```kotlin
reaktivTracing {
    enableForTasksMatching("staging")
    enableForXcodeConfigurations("Debug")
    tracePrivateMethods.set(true)
}
```

**Notes:** `enabled` used to carry `convention(true)`, so applying the plugin without configuring it
instrumented every compilation, release and production builds included. The compiler plugin bakes
stringified parameters, source file paths, line numbers and the resolved GitHub URL into each traced
call site, and on Apple targets there is no shrinker to remove them, so an on-by-default codegen
plugin is the wrong polarity.

`enabled` now takes its convention from the declared activation criteria (see AD-81) and resolves to
false when none are declared, along with a configuration-time warning naming the project. Setting
`enabled` directly still works and still wins, so a build that already computes its own value is
unaffected.

`buildTypes` counts as a criterion, so a build that already declared
`buildTypes.set(setOf("debug"))` keeps instrumenting exactly the same compilations. Only a
`reaktivTracing { }` block that declares no criterion at all, or no block, changes behaviour.

`enabled.set(...)` keeps working exactly as it did and remains the escape hatch. An explicit value
overrides every criterion and bypasses the conflicting-task guard, so a build that cannot be
expressed declaratively, or that the criteria misjudge, can always take the decision over.

---

### [AD-81] Declarative activation for the tracing Gradle plugin

**Type:** Addition

**Grep:** `enableForTasksMatching|enableForXcodeConfigurations|conflictsWithTasksMatching`
**File glob:** `**/build.gradle.kts`

**Example:**
```kotlin
reaktivTracing {
    enableForTasksMatching("staging")
    conflictsWithTasksMatching("production")
    enableForXcodeConfigurations("Debug")
    tracePrivateMethods.set(true)
}
```

**Notes:** Instrumentation is a property of the build being run, not of the module holding the Logic
classes, so a library module that wants tracing had to hand-roll a provider chain over
`gradle.startParameter.taskNames` and the `CONFIGURATION` environment variable Xcode exports. Those
three helpers express the same thing declaratively and back `enabled`'s convention.

`enableForTasksMatching` activates when any requested task name contains one of the patterns, matched
case-insensitively. `enableForXcodeConfigurations` activates when Xcode's `CONFIGURATION` matches, so
a Debug build through a Run Script phase instruments the framework and an Archive does not. Either
signal activates on its own.

`conflictsWithTasksMatching` guards the case a compilation-scoped decision cannot express. A module
with a single compilation per target produces one artifact per invocation, so
`./gradlew testStagingUnitTest assembleProduction` would feed an instrumented build to the production
consumer. Declaring the conflicting pattern turns that into a configuration-time failure naming both
sides instead of a silently instrumented release artifact. It does not make the invocation work,
which requires per-consumer variant selection.

The underlying properties are `activatingTaskPatterns`, `conflictingTaskPatterns` and
`xcodeConfigurations`, all `SetProperty<String>` defaulting to empty. `buildTypes` keeps filtering
compilations as before and now also counts as an activation criterion, so a module that owns real
variants needs nothing beyond it. See BC-53 for the default change.

---
### [BC-54] `SessionFileExport.saveToDownloads` takes bytes, and exports are gzipped

**Type:** Breaking

**Grep:** `saveToDownloads`
**File glob:** `**/*.kt`

**Before:**
```kotlin
val json = capture.exportSession()
val path = SessionFileExport(platformContext).saveToDownloads(json, "session.json")
```

**After:**
```kotlin
val json = capture.exportSession()
val bytes = gzipCompress(json.encodeToByteArray())
val path = SessionFileExport(platformContext).saveToDownloads(bytes, "session.json.gz")
```

**Notes:** Session exports are now gzipped, so the writer takes bytes rather than a string.
`ToolingLogic.exportSessionToDownloads` and `exportCrashSessionToDownloads` compress for you and are
unchanged at the call site, as are the platform crash handlers. Only code calling `SessionFileExport`
directly needs updating.

`SessionCapture.suggestFileName` now returns a `.json.gz` name, and the Android writer reports
`application/gzip` for those. Import paths accept both, so files exported by earlier versions keep
loading, see AD-83.

---

### [AD-82] Network traffic is captured, exported and replayed

**Type:** Addition

**Grep:** `session.network|SessionHistory.*network|recordNetworkExchange`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val export = json.decodeFromString<SessionExport>(sessionJson)

export.session.network.forEach { exchange ->
    println("${exchange.method} ${exchange.url} -> ${exchange.responseStatus}")
    println(exchange.responseBody)
}
```

**Notes:** `SessionCapture` subscribes to `NetworkTap` while started and persists exchanges to its
own storage lane, so `SessionData.network` and `SessionHistory.network` both carry them. Format
version is now 3.6.

Network was previously visible only through the live `NetworkBatch` push, which meant it was absent
from exported sessions entirely and absent from the history a late-attaching orchestrator receives.
Both now show the traffic that transpired.

Bodies are materialised when the exchange is recorded rather than when the session is exported. The
event carried by `NetworkTap` holds only a bounded preview, and the full body lives in the emitting
plugin's retention window, which evicts as later requests arrive. Pulling at export time would race
that eviction and silently produce a thinner export than the same session showed live.

Imported sessions feed the DevTools Network tab through `AppendNetworkEvents`, the same action the
live path uses, so a ghost behaves like a live publisher.

---

### [AD-83] Gzip codec for session exports

**Type:** Addition

**Grep:** `gzipCompress|gzipDecompress|decodeSessionBytes|isGzip`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val bytes = gzipCompress(exportJson.encodeToByteArray())

val json = decodeSessionBytes(bytes)
```

**Notes:** Session JSON is dominated by repeated keys, urls, header names and JSON bodies, all of
which deflate compresses heavily, so exports are written and transferred gzipped. Implemented with
`java.util.zip` on JVM and Android, and `platform.zlib` through cinterop on every native target,
which ships with Kotlin/Native so no dependency is added.

`decodeSessionBytes` sniffs the gzip magic number and falls back to plain text, which is how exports
written before this change keep loading without a version check.

Both functions suspend, because the only gzip primitive a browser exposes is the asynchronous Web
Streams API. On wasmJs they throw: the DevTools UI inflates a session while it reads the file, on the
JavaScript side, so no byte array crosses the wasm boundary. `DecompressionStream` is required to
open a gzipped session in the browser, and the picker reports a clear error where it is missing.

---

### [AD-84] `ObservabilityOnly` messages reach orchestrators only

**Type:** Addition

**Grep:** `ObservabilityOnly|broadcastToObservers`
**File glob:** `**/*.kt`

**Example:**
```kotlin
public data class NetworkBatch(
    override val clientId: String,
    val events: List<NetworkRequestCapture>
) : DevToolsMessage(), ObservabilityOnly
```

**Notes:** `NetworkBatch` and `LogBatch` were relayed to every subscriber of a publisher, so each
attached listener received and deserialized batches it only discards. A listener replicates state,
it renders nothing.

`DevToolsMessage.ObservabilityOnly` marks a payload as UI-bound, and the server routes it through
`ClientManager.broadcastToObservers`, which filters the same subscription set to `ORCHESTRATOR`.
Marking the message rather than listing types in the server means a new observability message cannot
silently get replication routing.

---

### [BC-55] Ghost session payloads are pulled, not pushed

**Type:** Behavioural

**Grep:** `GhostSessionRestore|GhostSessionRequest`
**File glob:** `**/*.kt`

**Notes:** The server used to send `GhostSessionRestore`, carrying the whole session export, to every
client the moment it registered. At that point the client has no role yet, so a device connecting to
a server that already held an imported ghost was handed a payload it never reads. On Apple platforms
that exceeds `NSURLSessionWebSocketTask`'s default 1 MiB message limit and the connection stalls,
which presented as a listener stuck connecting whenever the ghost was imported before it connected.

Ghosts are already advertised through `ClientListUpdate`, so the payload is now requested. An
orchestrator that sees a ghost it does not hold sends `GhostSessionRequest`, and the server answers
that client alone. Combined with AD-89, which compresses the payload, the frame that crosses the
socket is both smaller and only sent to a client that asked for it.

---
### [AD-85] The capture lane serves bodies the emitting window has evicted

**Type:** Addition

**Grep:** `addBodyProvider|bodySlice`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val slice = NetworkTap.bodySlice(requestId, NetworkBodyPart.RESPONSE, offset = 0, maxBytes = 65_536)
```

**Notes:** `ReaktivNetworkInspection` keeps a rolling window of full bodies, 50 exchanges and 8 MB by
default, so a live UI could inspect recent traffic but anything older was gone for good. Scrolling
back past the window returned nothing.

`SessionCapture` now registers as a fallback `NetworkBodyProvider` while it is started. `NetworkTap`
tries providers in turn, so a hot body still comes from the plugin and an evicted one is served from
the capture lane, which keeps everything it recorded. The last resolved body is cached so paging
through a large response does not rescan the lane per slice.

The practical effect is that a live session and an imported one now answer the same questions, and
raising `bodyRetentionCount` is no longer the only way to keep older bodies reachable.

---

### [AD-86] Markers on imported sessions

**Type:** Addition

**Grep:** `ReplaceMarker|updateMarker|analyst`
**File glob:** `**/*.kt`

**Example:**
```kotlin
logic.addMarkerOnPublisher(
    publisherClientId = activeGhostId,
    label = "checkout hang starts here",
    note = "token refresh returns 401",
    timestampMs = pinnedTime
)

logic.updateMarker(markerId, label = "root cause", note = "stale refresh token")
```

**Notes:** Markers were requested from the publisher and echoed back as `MarkerAdded`, so a ghost,
which has no device to ask, could not be annotated at all. `addMarkerOnPublisher` now detects that
the selected publisher is the active ghost and creates the marker locally instead, tagged
`source = "analyst"` rather than `"device"`, so post-session annotation stays distinguishable from
what the device recorded while it ran.

`updateMarker` and the new `ReplaceMarker` action edit a marker in place, keeping its id and
position. Only analyst markers are editable: a device marker records what the session did and stays
as the device wrote it, so re-exporting cannot quietly rewrite history.

Two related data losses are fixed alongside. Importing a ghost dropped `session.markers` entirely,
and `exportSessionAsGhost` had no `network` parameter, so re-exporting an imported session lost its
network traffic. Both now round trip, which is what makes annotate-then-reshare work.

---
### [AD-87] `onConflict` resolves mixed invocations instead of failing them

**Type:** Addition

**Grep:** `onConflict|TracingConflictPolicy`
**File glob:** `**/build.gradle.kts`

**Example:**
```kotlin
reaktivTracing {
    enableForTasksMatching("staging")
    conflictsWithTasksMatching("production")
    enableForXcodeConfigurations("Debug")
}
```

**Notes:** `conflictsWithTasksMatching` used to throw when one invocation requested both a tracing
build and a conflicting one. That made `./gradlew assembleProduction assembleStaging` fail, which is
a normal CI command, so detection alone was not enough.

`onConflict` now decides the outcome, and defaults to `TracingConflictPolicy.DISABLE`: tracing is
switched off for that invocation, the production artifact stays clean, the staging artifact simply
carries no traces, and the build succeeds with a lifecycle line saying so. `FAIL` restores the old
behaviour for teams that would rather split the invocation than get an untraced staging build, and
`INSTRUMENT_ALL` opts into instrumenting both, which is only safe when the non-tracing artifact is
not shipped.

Building the tracing variant on its own is unaffected and still produces traces. The conflict is
reported once per project rather than once per compilation.

None of this makes a single compilation serve both forms at once, which needs per-consumer variant
selection through Gradle attributes. It makes the choice explicit and safe by default.

---

### [AD-88] `reaktiv-navigation` targets wasmJs

**Type:** Addition

**Grep:** `reaktiv-navigation`
**File glob:** `**/build.gradle.kts`

**Example:**
```kotlin
kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            implementation("io.github.syrou:reaktiv-navigation:$reaktivVersion")
        }
    }
}
```

**Notes:** `reaktiv-navigation` now builds for wasmJs alongside jvm, android, iOS and macOS, so a
Compose Multiplatform app can share its navigation graph with a web target. `reaktiv-core` and
`reaktiv-compose` already targeted wasmJs, so navigation was the only gap in that chain.

The public API is identical on wasmJs: the klib dump gained the target with no signature changes.

`PlatformBackHandler` is a no-op on wasmJs, as it is on jvm. Browsers deliver back through history
navigation rather than a callback the Compose tree can intercept, so back is driven by the app,
through UI affordances or the edge swipe gesture, which is enabled. Wiring the browser history API
to navigation is a separate piece of work and is not included.

Compose gesture tests moved from `commonTest` to a new `uiTest` source set that jvm and apple test
compilations depend on and wasmJs does not. Those tests drive touch input against a live Store, and
a headless browser is a poor host for both halves: the viewport differs from every other target, and
a Store whose logic runs on `Dispatchers.Default` deadlocks under `wasmJsBrowserTest`. All 374 jvm
tests still run, including the 13 UI suites that moved.

`reaktiv-test-navigation` does not target wasmJs, so navigation testing utilities are unavailable
there for now.

---

### [AD-89] Session payloads are compressed on the wire

**Type:** Addition

**Grep:** `encodeSessionPayload|decodeSessionPayload|isPlainSessionJson`
**File glob:** `**/*.kt`

**Example:**
```kotlin
connection.send(
    DevToolsMessage.GhostDeviceRegistration(
        sessionId = export.sessionId,
        sessionExportJson = encodeSessionPayload(jsonString)
    )
)

val export = json.decodeFromString<GhostSessionExport>(
    decodeSessionPayload(message.sessionExportJson)
)
```

**Notes:** `GhostDeviceRegistration` and `GhostSessionRestore` carry a whole session inside a JSON
string field, so they travelled uncompressed while the file paths were already gzipped. They now
carry base64 of the gzipped JSON. Base64 costs a third over the compressed bytes, which against a
typical gzip ratio still leaves the payload far smaller than the JSON it replaces.

`decodeSessionPayload` returns anything starting with `{` untouched, so a client on an older version
still interoperates in both directions and no protocol version gate is needed.

The wasmJs implementation keeps only strings crossing the wasm boundary: the host does the gzip and
the base64 with `CompressionStream`, `DecompressionStream` and `btoa`/`atob`, bridged into a suspend
function with `suspendCoroutine` rather than Promise interop. Marshalling a `ByteArray` into a
`Uint8Array` element by element would be slower and easier to get subtly wrong. JVM and Android use
`java.util.Base64`, native uses `kotlin.io.encoding.Base64`, both over the existing gzip codec from
AD-83.

The server relays the field opaquely and needed no change.

---

### [AD-90] DevTools UI exports are gzipped and carry network traffic

**Type:** Addition

**Grep:** `downloadSession|exportSessionAsGhost`
**File glob:** `**/*.kt`

**Notes:** A session exported from the DevTools UI was written as plain `.json` while device exports
were gzipped, so the two were not interchangeable in size or naming. The UI now writes `.json.gz`
through `CompressionStream`, falling back to plain JSON where that API is missing, and the import
picker sniffs the gzip magic number either way.

More importantly, the UI export silently dropped all network traffic. `exportSessionAsGhost` gained a
`network` parameter in AD-82 with an `emptyList()` default, and the call site was never updated, so
every UI-exported session contained zero network exchanges. It now passes `state.networkEvents`
filtered to the selected publisher, so another attached device's traffic cannot leak into the export.

HAR export is unchanged and stays plain, since `.har` is consumed by browser devtools.

---

### [BC-56] Stall spans cover when the stall happened

**Type:** Behavioural

**Grep:** `MainThreadWatchdog`
**File glob:** `**/*.kt`

**Notes:** `StallWatchdog` emitted its trace span without a start timestamp, so `LogicTracer` stamped
it with the current time. A stall can only be reported once the main thread recovers, so the span was
anchored to recovery and then given the stall duration, placing its end that far into the future. On
the timeline it rendered as a stall that began in the future and never finished.

The span is now anchored to the last heartbeat before the freeze, so it covers the interval the main
thread was actually blocked. Sessions exported before this fix keep their future-dated spans, since
the timestamps are baked into the export.

---

### [AD-91] Imported sessions resolve network bodies locally

**Type:** Addition

**Grep:** `NetworkBodyNotFetchable|capturedOnly`
**File glob:** `**/*.kt`

**Notes:** The network detail panel fetches a body from the publisher whenever the capture marks it
truncated. An imported session has no device behind it, so the request was never answered and the
panel sat on "Waiting for the device to send the body." indefinitely, never rendering the JSON tree.

`fetchNetworkBody` now short-circuits when a ghost is active, dispatching `NetworkBodyNotFetchable`
to close the load as complete and unavailable. The panel falls back to the captured preview, which
renders as a tree when it parses.

`NetworkBodyLoad.capturedOnly` distinguishes the two reasons a body cannot be fetched, so an imported
session reads "This session did not capture the full body" while a live device that dropped its body
keeps "no longer retained on the device".

---

### [AD-92] Session history carries network through chunking

**Type:** Addition

**Grep:** `networkPerChunk`
**File glob:** `**/*.kt`

**Notes:** `SessionHistory` gained a `network` field in AD-82, but `SessionHistory.chunked` builds
each chunk explicitly and did not pass it, so the field defaulted to empty and network was dropped
from every chunk. The orchestrator side did not read it either, so a UI attaching to a live publisher
never received the traffic that happened before it connected.

`chunked` now slices network alongside actions and logic events, with its own `networkPerChunk`
defaulting to 50 because exchanges carry full bodies and are much heavier per entry. The count also
feeds the chunk total, so a history that is only network still splits. `appendHistorySlice` applies
each slice through `AppendNetworkEvents`, the same action the live push and ghost import use.

---

### [AD-93] One byte budget for everything that batches onto the wire

**Type:** Addition

**Grep:** `WireBudget|approximateWireBytes`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val chunks = history.chunked()

var budget = WireBudget.MAX_PAYLOAD_BYTES
for (exchange in exchanges) {
    budget -= exchange.approximateWireBytes()
    if (budget <= 0) break
}
```

**Notes:** Every fan-out point that assembles a message from a variable number of records cut on
count: 250 actions, 1000 logic events, 50 network exchanges, 100 log lines. That was a reasonable
proxy while records were small and roughly uniform, and it stopped being one when network exchanges
began carrying full request and response bodies (AD-82). Fifty exchanges bounded by
`hardBodyLimitBytes` is a 200 MB frame in the worst case, on a path where only
`NetworkBodyChunk` was ever byte-aware.

`WireBudget.MAX_PAYLOAD_BYTES` is the shared ceiling, set at 1 MiB to stay inside the per-message
limits platform websocket clients impose. `approximateWireBytes` estimates an exchange from the
fields that vary by orders of magnitude, bodies, urls and headers, rather than serializing to
measure, which is accurate enough to decide where to cut.

`SessionHistory.chunked` now cuts network on whichever comes first, count or budget, and the
resulting group count feeds the chunk total so a history that is only network still splits correctly.
`forwardBatched` takes an optional `weigh` function, used by the live `NetworkBatch` path, so a burst
of large responses closes a batch early instead of riding in one frame. Light records still fill to
the count limit, so nothing gets slower in the common case.

A single record heavier than the whole budget still goes out on its own. Splitting it at this layer
would break the message it belongs to, and `FetchNetworkBody` already streams oversized bodies at
`WireBudget.BODY_CHUNK_BYTES`.

Ordering and completeness are unaffected: the same records go out in the same order, only the cut
points move.

---

### [BC-57] Graph layouts travel with their screen, and the dismiss affordance wraps them

**Type:** Behavioural

**Grep:** `decideLayoutSharing|DismissIndicatorSlot`
**File glob:** `**/*.kt`

**Notes:** Two defects in how a graph layout composed with its screens' transitions and gestures,
both visible on a screen that enters vertically over a screen that does not animate out.

`decideLayoutSharing` treated every layout of the arriving screen as shared chrome unless it was
lifting an animating exit. A vertical presentation is exactly the case where the outgoing screen
stays put, so `shouldAnimateExit` was false, no intersection with the previous layouts happened, and
a layout the previous screen never had was classified as already on screen. Shared layouts render
outside `animateNavTransition`, so the sheet appeared to slide up underneath chrome that had snapped
into place. A layout is now shared only when the screen being left actually had it.

`DismissIndicatorSlot` was nested inside `ApplyLayoutsHierarchy`, so the grab pill rendered below the
graph's own chrome and `indicatorCoordinates` measured the screen rather than the surface being
dismissed. It now wraps the layouts, so the affordance belongs to everything that arrived together.
The gesture recognizers themselves were always root level in `NavigationRender` and are unchanged.

The visible change is that a screen showing a dismiss indicator inside a graph layout now reserves
its indicator strip above the layout rather than inside it, shifting that layout's chrome down by the
strip height. Screens with no graph layout, or with no dismiss indicator, are unaffected.

`LayoutSharingTest` previously asserted the first defect as correct behaviour, expecting a layout the
exiting screen never had to be reported as shared. That expectation is corrected.

---

### [AD-94] `Graph` is declarable and can present itself

**Type:** Addition

**Grep:** `: Graph\b|graph\(([A-Z][A-Za-z0-9_]*)\)`
**File glob:** `**/*.kt`

**Example:**
```kotlin
object WizardGraph : Graph {
    override val route = "wizard"
    override val enterTransition = NavTransition.SlideUpBottom
}

createNavigationModule {
    rootGraph {
        start(HomeScreen)
        screens(HomeScreen)
        graph(WizardGraph) {
            start(WizardDetailsScreen)
            screens(WizardDetailsScreen, WizardPaymentScreen, WizardConfirmScreen)
            layout { content -> WizardLayout(content) }
        }
    }
}
```

**Notes:** A graph can now be a surface in its own right rather than only a grouping, declared the
same way `Screen` and `Modal` are. `graph(WizardGraph) { }` joins the existing `graph("wizard") { }`,
which is unchanged and still declares no presentation.

Alongside `route`, `Graph` carries `enterTransition`, `exitTransition`, `popEnterTransition`,
`popExitTransition`, `swipeToDismiss`, `showsDismissIndicator` and `onDismissRequest`. Every one has
a default, so implementing `Graph` with only a route is equivalent to the string form.

Transitions are nullable, and null means the graph has no opinion so the entering screen's own
transition is used. That is why existing graphs are unaffected. `NavTransition.None` is the
different statement that the graph arrives without animation.

A navigation animates the outermost boundary it crosses. Entering the graph animates the graph,
moving between screens already inside it animates the screen, which is what lets wizard steps slide
sideways inside a sheet that arrived from the bottom. The graph's declaration is reachable at
runtime through the new `NavigationGraph.declaration: Graph?`.

`swipeToDismiss` defaults to true only when the graph presents on a vertical axis, and
`showsDismissIndicator` follows it so a structural graph never offers a handle it cannot honour.
Committing the drag removes every entry belonging to the graph rather than popping one, so a wizard
dragged away from step three lands where it was opened from. See AD-95 and BC-58.

---

### [AD-95] `TransitionSpec` describes anything that arrives and leaves

**Type:** Addition

**Grep:** `TransitionSpec|presentsItself`
**File glob:** `**/*.kt`

**Example:**
```kotlin
fun describeArrival(spec: TransitionSpec): String =
    if (spec.presentsItself) "arrives as its own surface" else "defers to what it contains"
```

**Notes:** Both `Navigatable` and `Graph` now extend `TransitionSpec`, which holds the four
transition values. Transition resolution reads them from whichever node is the surface actually
moving, instead of each kind of node being handled separately.

`Navigatable` narrows `enterTransition` and `exitTransition` back to non-null, so `Screen` and
`Modal` implementations are unchanged and still must declare both. `Graph` leaves them nullable.

The `presentsItself` extension is the single question the renderer and the gesture predicates ask,
namely whether a node describes a surface of its own. Related to AD-94.

---

### [BC-58] Dismissing a presented graph collapses it, back still steps through it

**Type:** Behavioural

**Grep:** `swipeToDismiss|showsDismissIndicator|onDismissRequest`
**File glob:** `**/*.kt`

**Notes:** Only applies once a graph declares a presentation, so nothing changes for graphs declared
as `graph("id") { }` or for a `Graph` that overrides nothing but `route`.

Inside such a graph, dismissing and going back are now different intents. Only the drag unwinds the
whole graph. A system back press, and an edge swipe between two screens inside the graph, still pop
one entry each. Previously both gestures derived their own target at commit time and could disagree with what the user had been watching animate, so
a horizontal swipe between wizard steps could tear down the entire wizard.

The gesture axis is read from the surface that is actually leaving rather than always from the
current screen. On the first screen of a vertically presented graph, leaving means leaving the
graph, so the vertical drag arms and the horizontal swipe does not, even though that screen declares
horizontal transitions of its own. Between screens inside the graph no boundary is crossed, so the
screen decides and the horizontal swipe arms as before.

`UnifiedLayerRenderer` takes a fourth parameter, `evaluationOverlay: LoadingModal? = null`. Source
compatible because it is defaulted, binary incompatible, so recompile rather than swap the artifact.
Callers rendering their own layers can keep passing three arguments.

`MutableNavigationGraph` gained a `declaration` property in position six, shifting `componentN` for
the parameters after it. This affects destructuring only, which that builder type was never intended
for. Related to AD-94 and AD-95.

---

### [BC-59] System-layer navigations no longer wait for bootstrap

**Type:** Behavioural

**Grep:** `RenderLayer.SYSTEM`
**File glob:** `**/*.kt`

**Notes:** A navigatable on `RenderLayer.SYSTEM` is documented as sitting above everything, but it
could not reach the screen while the app was still deciding where to start. It waited for bootstrap
to complete, and bootstrap holds the navigation lock for the whole of its start-destination
evaluation, so an alert raised during a slow entry lambda or guard sat queued behind the very
loading screen it was meant to appear over.

System-layer navigations now skip both the bootstrap await and that lock. Every other navigation is
unchanged and still waits, so a modal or screen dispatched during startup continues to be applied
after the start destination resolves.

The practical effect is that a crash reporter, a forced-update prompt or a connectivity alert raised
during startup is now visible during startup. Bootstrap still completes and still reaches its start
destination, and the alert does not disturb where the app lands.

Anything already placed on the system layer inherits this. If a system-layer navigatable relied on
being deferred until the app had settled, move it off `RenderLayer.SYSTEM`.

---
### [BC-60] Clearing the backstack no longer takes system-layer entries with it

**Type:** Behavioural

**Grep:** `clearBackStack\(\)`
**File glob:** `**/*.kt`

**Notes:** `applyNavigate` already treated the system layer as a tail that content navigations are
inserted underneath, so an alert stays raised and stays current while the screen beneath it changes.
`applyClearBackstack` was the one operation that ignored that model and emptied the stack outright.

Clearing the backstack now keeps system-layer entries and drops everything else. The loading modal is
excluded, because it is navigation's own bootstrap placeholder rather than an app overlay, so it is
still cleared exactly as before.

The case this was found through: an alert raised while the app was still resolving its start
destination reached the screen (see BC-59) and was then destroyed the moment bootstrap finished,
because bootstrap clears the backstack before navigating to the start destination. The alert
disappeared on its own instead of waiting to be dismissed. It now outlives the loader it was raised
over, and dismissing it reveals the start destination underneath.

`currentEntry` after a clear is the topmost surviving system entry, or unchanged when none survive,
which is the same rule `applyNavigate` uses. Callers that clear the backstack while nothing is on the
system layer see no difference.

---
### [AD-96] Navigation supplies what a header needs

**Type:** Addition

**Grep:** `rememberNavigationChrome|previousTitle\(\)|showsNavigationChrome|LocalNavigationChromeInsets`
**File glob:** `**/*.kt`

**Example:**
```kotlin
object WizardGraph : Graph {
    override val route = "wizard"
    override val enterTransition = NavTransition.SlideUpBottom
}

@Composable
fun AppScaffold(content: @Composable () -> Unit) {
    val navigationState by composeState<NavigationState>()
    Scaffold(
        topBar = {
            if (navigationState.showsNavigationChrome) {
                val chrome = rememberNavigationChrome()
                TopAppBar(
                    title = { Text(chrome.title.orEmpty()) },
                    navigationIcon = {
                        chrome.onBack?.let { back ->
                            IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                        }
                    }
                )
            }
        }
    ) { padding ->
        CompositionLocalProvider(LocalNavigationChromeInsets provides padding) {
            Box(Modifier.padding(padding)) { content() }
        }
    }
}
```

**Notes:** Four additions that answer questions only navigation can answer, so an app does not have
to reach into the backstack to build a header.

`rememberNavigationChrome()` returns a `NavigationChromeState` carrying the current route, the
resolved title, the title of whatever a back navigation would reveal, and a back action that is null
at the root. Titles resolve in composition, so a `titleResource` built from `stringResource` stays
localised, and the title follows an in flight gesture because it reads the same perceived entry the
renderer does. The back action routes through the same path as the system back button and the edge
swipe, so a header cannot disagree with them about what leaving means inside a presented graph.

`previousTitle()` exposes the back label on its own for callers that do not want the whole bundle.
It skips overlay and system entries, because a back control names the content you would return to
rather than whatever is layered above it.

`Graph.showsNavigationChrome` declares whether destinations inside a graph want a header at all,
defaulting to `!presentsItself`. A graph that arrives as its own surface already carries its own
chrome, so a sheet or wizard opts out with no configuration, and a structural graph keeps the
header. `NavigationState.showsNavigationChrome` resolves that across the whole current hierarchy and
is false while a modal is current.

`LocalNavigationChromeInsets` is declared so a screen and the layout framing it can agree on how much
room the header takes without depending on each other, which matters when they live in different
modules. Whatever draws the header provides the value.

Reaktiv deliberately ships no header. These are facts, not a design system, so the same values can
drive a Material app bar, an iOS styled one, or a native platform bar.

---
### [BC-61] A screen on its way out keeps its own chrome

**Type:** Behavioural

**Grep:** `layout \{`
**File glob:** `**/*.kt`

**Notes:** The exiting slot is rendered for the whole of a transition, but the layouts belonging to
it were only applied when the exit was being lifted, which happens only when the layout set changes
and the exit animates. Everywhere else the outgoing screen rendered with no layouts at all while
still visibly on screen.

The visible defect: navigating between two sibling graphs that declare different layouts, where the
screen being left does not animate out. Its header vanished the instant the navigation started, its
height changed, and its content jumped up to fill the space for the length of the transition.

An exiting screen now keeps whatever chrome it does not share with the screen replacing it,
regardless of whether the exit animates. Lifting still decides where the exiting surface is ordered
against the arriving one, it just no longer decides whether that surface is whole. The revealed slot
used during a gesture already worked this way, so the two are now consistent.

Nothing changes when both screens resolve to the same layouts, which is the common case of moving
between screens inside one graph, because a shared layout renders once outside both slots and the
exiting slot correctly adds nothing.

`LayoutSharingDecision` gained `exitingUniqueRoutes`, which is where this is now decided.

---
### [BC-62] `dismissModal()` dismisses a modal instead of popping whatever is on top

**Type:** Behavioural

**Grep:** `dismissModal\(\)`
**File glob:** `**/*.kt`

**Before:**
```kotlin
public suspend fun StoreAccessor.dismissModal() {
    navigateBack()
}
```

**After:**
```kotlin
public suspend fun StoreAccessor.dismissModal() {
    selectLogic<NavigationLogic>().dismissModal()
}
```

**Notes:** `dismissModal()` was an alias for `navigateBack()` and had no notion of a modal, so it
popped whatever was on top of the stack at the moment it ran.

Each one-shot extension is its own transaction, so a dismiss written before a navigation can still be
applied after it. When that happened the old behaviour was destructive rather than inert. With no
modal present it popped a screen, so

```kotlin
store.dismissModal()
store.navigate("subscription-upsell")
```

could land on the previous screen instead of the upsell. With the modal still beneath a newly pushed
screen it popped that screen and left the modal on display. Either way one of the two calls appeared
not to have happened.

The call now names what it dismisses. It removes the topmost modal wherever that modal sits, does
nothing at all when none is present, and when the modal is beneath entries that arrived after the
dismiss was requested it removes the modal and keeps the current entry. The pair above reaches the
same stack in either order.

Callers that were using `dismissModal()` as a general back, including where no modal was involved,
now get no movement. Use `navigateBack()` for that, which is what it always meant.

`NavigationLogic.dismissModal()` is the new public method behind it.

**Caveat:** when more than one entry sits above the modal, only the current entry is preserved.
Anything between the modal and the current entry is dropped, since the dismiss is expressed as a
`PopUpTo` with the current entry re-added.

---
### [AD-97] Every navigation attempt leaves a record

**Type:** Addition

**Grep:** `ReaktivDebug.enable|addSink`
**File glob:** `**/*.kt`

**Example:**
```kotlin
ReaktivDebug.enable()
ReaktivDebug.addSink { level, category, message ->
    println("$category $message")
}
```

**Notes:** Guards and entry-selection lambdas were traced individually, but the navigation around
them was not, so an attempt that never reached the screen left nothing behind. A caller could not
tell a rejected, dropped or cancelled navigation from one that worked.

Each navigation is now traced as a whole and logged under the `NAV` category with how it ended:
`Success`, `Dropped`, `Rejected` or `Redirected(route)`. A navigation cancelled while it waited for
the one in front of it is logged as cancelled rather than vanishing, since the transaction records
the failure before rethrowing.

Navigating to the screen you are already on is skipped by design, and that skip is now logged too.
It previously returned `Success` with nothing applied and no record, which read as a navigation that
had worked. The return value is unchanged, so nothing matching on `NavigationOutcome` has to be
updated.

Tooling sees these under the `Navigation` trace scope, alongside the existing `NavigationGuards`
scope used by guard and entry evaluation.

---

### [AD-98] Failed response decoding is attributed to the request that caused it

**Type:** Addition

**Grep:** `decodeError`
**File glob:** `**/*.kt`

**Example:**
```kotlin
val client = HttpClient(engine) {
    install(ContentNegotiation) {
        json()
    }
    install(ReaktivNetworkInspection)
}

val user: User = client.get("https://api.example.com/user").body()
```

**Notes:** `ReaktivNetworkInspection` previously wrapped only the send phase, so an exchange that
returned 200 and then failed to deserialize was recorded as a success. Content negotiation converts
at `HttpResponsePipeline.Transform`, which runs when the caller asks for `body<T>()`, long after the
send phase has already emitted.

The plugin now tags each request with its capture id and wraps the response pipeline at
`HttpResponsePipeline.Receive`, so a conversion failure is caught, attributed to the exchange and
rethrown untouched. Installing the plugin is the only wiring an application needs, and the failure
is recorded even when the call site swallows the exception.

`NetworkRequestCapture` gained `decodeError: String?`, carrying the deepest cause of the failure
rather than the `JsonConvertException` wrapper, so the message names the offending field. It counts
towards `isFailure`, so the failures-only filter and the endpoint stats include it. The exchange is
re-emitted with the same `id`, which the DevTools UI merges in place rather than appending, keeping
the captured response body next to the exception that the body caused.

The failure is also logged through `ReaktivDebug` under a new `NETWORK` category at `ERROR` level,
which puts it in the DevTools log lane, and it is reported as a `network-decode` finding. Repeated
failures on one endpoint with the same message collapse into a single finding carrying the count.

A capture is only taken while something is listening on `NetworkTap`, so this costs nothing when
neither `DevToolsService` nor `SessionCapture` is running. Decoding performed by hand, such as
`Json.decodeFromString(response.bodyAsText())`, happens outside the client pipeline and is not
covered.

---

### [AD-99] `ReaktivDebug.error` accepts a category

**Type:** Addition

**Grep:** `ReaktivDebug.error(`
**File glob:** `**/*.kt`

**Example:**
```kotlin
ReaktivDebug.error("NETWORK", "Failed to decode GET /user", cause)
```

**Notes:** The existing two-argument `error(message, throwable)` is unchanged and still reports
under `GENERAL`. The three-argument overload lets a subsystem file its errors under its own
category, which tooling uses to group and filter log lines. See AD-98 for the first caller.

---
