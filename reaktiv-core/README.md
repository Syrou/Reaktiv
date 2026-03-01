# Reaktiv Core Module

The Core module is the foundation of the Reaktiv library, providing the essential components for implementing the Model-View-Logic-Intent (MVLI) architecture in Kotlin Multiplatform projects.

## Features

- **State Management**: Efficient and predictable state handling with immutable states
- **Action Dispatching**: Type-safe action creation and dispatching with priority support
- **Module System**: Organize your application logic into reusable modules with typed Logic layer
- **Middleware Support**: Extend functionality with custom middleware for cross-cutting concerns
- **State Persistence**: Save and restore application state with customizable strategies
- **Custom Serialization**: Register custom serializers for polymorphic types
- **Coroutine Integration**: Built with Kotlin Coroutines for smooth asynchronous operations

## Key Components

### Store

The `Store` class is the central piece of the Reaktiv architecture. It manages the state, handles actions, and coordinates between different modules.

```kotlin
val store = createStore {
    module(CounterModule)
    module(UserModule)
    module(navigationModule)
    middlewares(loggingMiddleware, analyticsMiddleware)
    coroutineContext(Dispatchers.Default)
    persistenceManager(PlatformPersistenceStrategy())
}
```

### ModuleWithLogic

`ModuleWithLogic` is the recommended interface for defining modules with type-safe logic access:

```kotlin
object CounterModule : ModuleWithLogic<CounterState, CounterAction, CounterLogic> {
    override val initialState = CounterState(0)

    override val reducer: (CounterState, CounterAction) -> CounterState = { state, action ->
        when (action) {
            is CounterAction.Increment -> state.copy(count = state.count + 1)
            is CounterAction.Decrement -> state.copy(count = state.count - 1)
            is CounterAction.SetCount -> state.copy(count = action.value)
        }
    }

    override val createLogic: (StoreAccessor) -> CounterLogic = { storeAccessor ->
        CounterLogic(storeAccessor)
    }
}
```

### ModuleState

States represent the data of your application at a given point in time. They must be immutable and serializable:

```kotlin
@Serializable
data class CounterState(
    val count: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null
) : ModuleState
```

### ModuleAction

Actions are events that describe changes in your application. They are dispatched to the store to trigger state updates:

```kotlin
sealed class CounterAction : ModuleAction(CounterModule::class) {
    data object Increment : CounterAction()
    data object Decrement : CounterAction()
    data class SetCount(val value: Int) : CounterAction()
    data class SetLoading(val loading: Boolean) : CounterAction()
    data class SetError(val message: String?) : CounterAction()
}
```

### ModuleLogic

Logic classes handle side effects and complex operations. Use public suspend methods instead of the deprecated `invoke()` pattern:

```kotlin
class CounterLogic(private val storeAccessor: StoreAccessor) : ModuleLogic<CounterAction>() {

    private val api = CounterApi()

    suspend fun incrementAsync() {
        storeAccessor.dispatch(CounterAction.SetLoading(true))
        delay(1000)
        storeAccessor.dispatch(CounterAction.Increment)
        storeAccessor.dispatch(CounterAction.SetLoading(false))
    }

    suspend fun fetchCount() {
        storeAccessor.dispatch(CounterAction.SetLoading(true))
        try {
            val count = api.getCount()
            storeAccessor.dispatch(CounterAction.SetCount(count))
        } catch (e: Exception) {
            storeAccessor.dispatch(CounterAction.SetError(e.message))
        } finally {
            storeAccessor.dispatch(CounterAction.SetLoading(false))
        }
    }

    suspend fun syncWithOtherModule() {
        val userState = storeAccessor.selectState<UserState>().first()
        if (userState.isLoggedIn) {
            fetchCount()
        }
    }
}
```

### StoreAccessor

`StoreAccessor` provides access to dispatch, state selection, and logic selection:

```kotlin
class MyLogic(private val storeAccessor: StoreAccessor) : ModuleLogic<MyAction>() {

    suspend fun doSomething() {
        // Dispatch actions
        storeAccessor.dispatch(MyAction.Loading)

        // Select state from any module
        val otherState = storeAccessor.selectState<OtherState>().first()

        // Select logic from any module
        val otherLogic = storeAccessor.selectLogic<OtherLogic>()
        otherLogic.someMethod()
    }
}
```

## Usage

### 1. Define your state, actions, and module

```kotlin
@Serializable
data class TodoState(
    val items: List<TodoItem> = emptyList(),
    val filter: TodoFilter = TodoFilter.ALL
) : ModuleState

sealed class TodoAction : ModuleAction(TodoModule::class) {
    data class AddItem(val text: String) : TodoAction()
    data class RemoveItem(val id: String) : TodoAction()
    data class ToggleItem(val id: String) : TodoAction()
    data class SetFilter(val filter: TodoFilter) : TodoAction()
}

class TodoLogic(private val storeAccessor: StoreAccessor) : ModuleLogic<TodoAction>() {
    private val repository = TodoRepository()

    suspend fun loadTodos() {
        val todos = repository.getAll()
        todos.forEach { todo ->
            storeAccessor.dispatch(TodoAction.AddItem(todo.text))
        }
    }

    suspend fun saveTodo(text: String) {
        repository.save(text)
        storeAccessor.dispatch(TodoAction.AddItem(text))
    }
}

object TodoModule : ModuleWithLogic<TodoState, TodoAction, TodoLogic> {
    override val initialState = TodoState()

    override val reducer: (TodoState, TodoAction) -> TodoState = { state, action ->
        when (action) {
            is TodoAction.AddItem -> state.copy(
                items = state.items + TodoItem(text = action.text)
            )
            is TodoAction.RemoveItem -> state.copy(
                items = state.items.filter { it.id != action.id }
            )
            is TodoAction.ToggleItem -> state.copy(
                items = state.items.map {
                    if (it.id == action.id) it.copy(completed = !it.completed) else it
                }
            )
            is TodoAction.SetFilter -> state.copy(filter = action.filter)
        }
    }

    override val createLogic: (StoreAccessor) -> TodoLogic = { TodoLogic(it) }
}
```

### 2. Create a store

```kotlin
val store = createStore {
    module(TodoModule)
    module(UserModule)
    coroutineContext(Dispatchers.Default)
}
```

### 3. Dispatch actions and observe state

```kotlin
// Dispatch actions
store.dispatch(TodoAction.AddItem("Buy groceries"))

// Observe state changes
store.selectState<TodoState>().collect { state ->
    println("Todo count: ${state.items.size}")
}

// Access logic for complex operations
val todoLogic = store.selectLogic<TodoLogic>()
todoLogic.loadTodos()
```

## Advanced Features

### Middleware

Middleware allows you to intercept actions and perform additional operations:

```kotlin
val loggingMiddleware: Middleware = { action, getAllStates, storeAccessor, updatedState ->
    println("Before: $action")
    println("States: ${getAllStates()}")

    val newState = updatedState(action)

    println("After: $newState")
}

val analyticsMiddleware: Middleware = { action, getAllStates, storeAccessor, updatedState ->
    analytics.track("action_dispatched", mapOf("type" to action::class.simpleName))
    updatedState(action)
}

val store = createStore {
    module(MyModule)
    middlewares(loggingMiddleware, analyticsMiddleware)
}
```

### High Priority Actions

For time-sensitive actions, implement `HighPriorityAction` to bypass the normal queue:

```kotlin
sealed class UrgentAction : ModuleAction(UrgentModule::class), HighPriorityAction {
    data object CancelOperation : UrgentAction()
    data object EmergencyStop : UrgentAction()
}
```

### Custom Type Registration

For polymorphic types in your state, implement `CustomTypeRegistrar`:

```kotlin
@Serializable
sealed interface PaymentMethod

@Serializable
data class CreditCard(val last4: String) : PaymentMethod

@Serializable
data class BankAccount(val accountNumber: String) : PaymentMethod

@Serializable
data class PaymentState(
    val selectedMethod: PaymentMethod? = null
) : ModuleState

object PaymentModule : ModuleWithLogic<PaymentState, PaymentAction, PaymentLogic>,
    CustomTypeRegistrar {

    override val initialState = PaymentState()
    override val reducer = { state: PaymentState, action: PaymentAction -> /* ... */ state }
    override val createLogic = { storeAccessor: StoreAccessor -> PaymentLogic(storeAccessor) }

    override fun registerAdditionalSerializers(builder: SerializersModuleBuilder) {
        builder.polymorphic(PaymentMethod::class) {
            subclass(CreditCard::class)
            subclass(BankAccount::class)
        }
    }
}
```

### State Persistence

Save and restore state with custom persistence strategies:

```kotlin
class AndroidPersistenceStrategy(context: Context) : PersistenceStrategy {
    private val prefs = context.getSharedPreferences("reaktiv", Context.MODE_PRIVATE)

    override suspend fun saveState(serializedState: String) {
        prefs.edit().putString("state", serializedState).apply()
    }

    override suspend fun loadState(): String? {
        return prefs.getString("state", null)
    }

    override suspend fun hasPersistedState(): Boolean {
        return prefs.contains("state")
    }
}

val store = createStore {
    module(MyModule)
    persistenceManager(AndroidPersistenceStrategy(context))
}

// Save state
store.saveState()

// Load state
store.loadState()

// Check if state exists
if (store.hasPersistedState()) {
    store.loadState()
}
```

### Debug Utilities

Enable debug logging during development:

```kotlin
// Enable all logging
ReaktivDebug.enable()

// Enable specific categories
ReaktivDebug.enableOnly("NAV", "STATE")

// Development mode (verbose logging)
ReaktivDebug.developmentMode()

// Production mode (disable all)
ReaktivDebug.productionMode()
```

### Module-Provided Middleware

Modules can provide their own middleware:

```kotlin
object AnalyticsModule : ModuleWithLogic<AnalyticsState, AnalyticsAction, AnalyticsLogic> {
    override val initialState = AnalyticsState()
    override val reducer = { state: AnalyticsState, action: AnalyticsAction -> state }
    override val createLogic = { storeAccessor: StoreAccessor -> AnalyticsLogic(storeAccessor) }

    override val createMiddleware: (() -> Middleware)? = {
        { action, getAllStates, storeAccessor, updatedState ->
            trackAction(action)
            updatedState(action)
        }
    }
}
```

## Best Practices

1. **Keep states immutable** - Always use `copy()` to create new state instances
2. **Use `ModuleWithLogic`** - Prefer `ModuleWithLogic` over deprecated `Module` for type-safe logic access
3. **Logic for side effects** - Keep reducers pure; use Logic classes for API calls, navigation, etc.
4. **Named suspend methods** - Define specific methods on Logic instead of using `invoke()`
5. **Middleware for cross-cutting concerns** - Use middleware for logging, analytics, error handling
6. **Serialize your states** - Mark states with `@Serializable` for persistence and DevTools support
7. **Use `HighPriorityAction`** - For time-critical actions that shouldn't wait in queue

## API Reference

### Store

```kotlin
class Store {
    val dispatch: Dispatch
    val initialized: StateFlow<Boolean>

    suspend fun <S : ModuleState> selectState(): StateFlow<S>
    fun <S : ModuleState> selectStateNonSuspend(): StateFlow<S>
    suspend fun <L : ModuleLogic<*>> selectLogic(): L
    fun <T : Module<*, *>> getModule(): T?

    suspend fun saveState()
    suspend fun loadState()
    suspend fun hasPersistedState(): Boolean

    fun reset()
    fun cleanup()
}
```

### StoreAccessor

```kotlin
abstract class StoreAccessor {
    abstract val dispatch: Dispatch
    abstract suspend fun <S : ModuleState> selectState(stateClass: KClass<S>): StateFlow<S>
    abstract suspend fun <L : ModuleLogic> selectLogic(logicClass: KClass<L>): L

    // Module retrieval — Kotlin
    abstract fun <M : Any> getModule(moduleClass: KClass<M>): M?
    inline fun <reified M : Any> getModule(): M?

    // Module retrieval — Swift/Obj-C interop
    // KClass cannot be constructed from Swift; use getRegisteredModules() instead:
    //   store.getRegisteredModules().first { $0 is MyModule } as? MyModule
    abstract fun getRegisteredModules(): List<Module<*, *>>
}

// Extension functions
suspend inline fun <reified S : ModuleState> StoreAccessor.selectState(): StateFlow<S>
suspend inline fun <reified L : ModuleLogic> StoreAccessor.selectLogic(): L
```

### Middleware

```kotlin
typealias Middleware = suspend (
    action: ModuleAction,
    getAllStates: suspend () -> Map<String, ModuleState>,
    storeAccessor: StoreAccessor,
    updatedState: suspend (ModuleAction) -> ModuleState
) -> Unit
```

For more detailed examples and advanced usage, please refer to the sample projects and API documentation.
