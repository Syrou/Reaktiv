# Module reaktiv-core

The Core module is the foundation of Reaktiv, providing the essential building blocks for the
Model-View-Logic-Intent (MVLI) architecture in Kotlin Multiplatform projects.

## Setup

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.syrou:reaktiv-core:<version>")
}
```

---

## Core Concepts

### Store + Module

The [Store] is the central coordinator. Each feature domain is expressed as a [Module] that owns
its [ModuleState], a pure reducer, and a [ModuleLogic] factory.

```kotlin
// 1. Define state
data class CounterState(val count: Int = 0) : ModuleState

// 2. Define actions
sealed class CounterAction : ModuleAction() {
    data object Increment : CounterAction()
    data class Add(val amount: Int) : CounterAction()
}

// 3. Define logic (business logic + side effects)
class CounterLogic(val storeAccessor: StoreAccessor) : ModuleLogic() {
    suspend fun increment() {
        storeAccessor.dispatch(CounterAction.Increment)
    }

    suspend fun addDelayed(amount: Int) {
        delay(500)
        storeAccessor.dispatch(CounterAction.Add(amount))
    }
}

// 4. Define module (wires everything together)
object CounterModule : ModuleWithLogic<CounterState, CounterAction, CounterLogic> {
    override val initialState = CounterState()
    override val reducer = { state: CounterState, action: CounterAction ->
        when (action) {
            is CounterAction.Increment -> state.copy(count = state.count + 1)
            is CounterAction.Add -> state.copy(count = state.count + action.amount)
        }
    }
    override val createLogic = { storeAccessor: StoreAccessor -> CounterLogic(storeAccessor) }
}

// 5. Create the store
val store = createStore {
    module(CounterModule)
}
```

---

### Dispatching Actions and Reading State

```kotlin
// Dispatch a simple action
store.dispatch(CounterAction.Increment)

// Read the current state once
val state = store.getCurrentState<CounterState>()

// Observe state as a flow
store.selectState<CounterState>().collect { state ->
    println("Count is now: ${state.count}")
}
```

---

### Calling Logic from Outside Compose

Use [StoreAccessor.launch] to call suspend methods on a [ModuleLogic] instance from non-Compose
code (e.g. lifecycle callbacks, ViewModels, application-level events).

```kotlin
// From a ViewModel or lifecycle-aware component
store.launch {
    val logic = store.selectLogic<CounterLogic>()
    logic.addDelayed(10)
}

// Cross-module logic call (from inside another Logic class)
class CheckoutLogic(val storeAccessor: StoreAccessor) : ModuleLogic() {
    suspend fun checkout() {
        val orderLogic = storeAccessor.selectLogic<OrderLogic>()
        orderLogic.submitOrder("order-123")
    }
}
```

---

### Middleware

Middleware intercepts every dispatched action. Use it for logging, analytics, or side-effect
orchestration. The `updatedState` lambda returns the module state *after* the action is reduced.

```kotlin
val loggingMiddleware = Middleware { action, updatedState ->
    println("Action dispatched: $action")
    val newState = updatedState(action)
    println("New state: $newState")
}

val store = createStore {
    module(CounterModule)
    middleware(loggingMiddleware)
}
```

---

### State Persistence

Implement [PersistenceStrategy] to save and restore state across sessions. Pass it to
`createStore` via `persistWith`.

```kotlin
class DataStorePersistence(private val dataStore: DataStore<Preferences>) : PersistenceStrategy {
    override suspend fun save(key: String, value: String) {
        dataStore.edit { prefs -> prefs[stringPreferencesKey(key)] = value }
    }

    override suspend fun load(key: String): String? {
        return dataStore.data.first()[stringPreferencesKey(key)]
    }

    override suspend fun remove(key: String) {
        dataStore.edit { prefs -> prefs.remove(stringPreferencesKey(key)) }
    }
}

val store = createStore {
    module(CounterModule)
    persistWith(DataStorePersistence(dataStore))
}
```

---

### Custom Type Serializers

When a module's state contains polymorphic types or types requiring custom serializers, implement
[CustomTypeRegistrar] so the store can build a unified [SerializersModule].

```kotlin
@Serializable
sealed interface AppSettings
@Serializable data class BasicSettings(val theme: String) : AppSettings
@Serializable data class AdvancedSettings(val theme: String, val locale: String) : AppSettings

object SettingsModule : ModuleWithLogic<SettingsState, SettingsAction, SettingsLogic>,
        CustomTypeRegistrar {
    override val initialState = SettingsState(BasicSettings("dark"))
    override val reducer = { state: SettingsState, action: SettingsAction -> /* ... */ state }
    override val createLogic = { accessor: StoreAccessor -> SettingsLogic(accessor) }

    override fun registerAdditionalSerializers(builder: SerializersModuleBuilder) {
        builder.polymorphic(AppSettings::class) {
            subclass(BasicSettings::class)
            subclass(AdvancedSettings::class)
        }
    }
}
```

---

## Key Types

- [Store] — central coordinator; created via `createStore { }`
- [Module] / [ModuleWithLogic] — feature domain definition
- [ModuleState] — immutable state marker interface
- [ModuleAction] — action marker base class
- [ModuleLogic] — side-effect and business logic base class
- [StoreAccessor] — access point for `dispatch`, `selectState`, `selectLogic`, `launch`
- [Middleware] — action interception
- [PersistenceStrategy] — save/restore state across sessions
- [CustomTypeRegistrar] — register additional serializers for polymorphic state types
