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
