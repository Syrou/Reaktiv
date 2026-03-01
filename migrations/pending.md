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
