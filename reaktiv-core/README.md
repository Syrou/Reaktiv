# Reaktiv Core Module

The Core module is the foundation of the Reaktiv library, providing the essential components for implementing the
Model-View-Logic-Intent (MVLI) architecture in Kotlin Multiplatform projects.

## Features

- **State Management**: Efficient and predictable state handling.
- **Action Dispatching**: Type-safe action creation and dispatching.
- **Module System**: Organize your application logic into reusable modules with a distinct Logic layer.
- **Middleware Support**: Extend functionality with custom middleware.
- **Coroutine Integration**: Built with Kotlin Coroutines for smooth asynchronous operations.

## Key Components

### Store

The `Store` class is the central piece of the Reaktiv architecture. It manages the state, handles actions, and
coordinates between different modules.

```kotlin
val store = createStore {
    module<CounterState, CounterAction>(CounterModule)
    middlewares(loggingMiddleware)
    coroutineContext(Dispatchers.Default)
}
```

### Module

A `Module` defines a specific feature or domain in your application. It includes:

- Initial state
- Reducer function
- Logic creation

```kotlin
object CounterModule : Module<CounterState, CounterAction> {
    override val initialState = CounterState(0)
    override val reducer: (CounterState, CounterAction) -> CounterState = { state, action ->
        when (action) {
            is CounterAction.Increment -> state.copy(count = state.count + 1)
            is CounterAction.Decrement -> state.copy(count = state.count - 1)
        }
    }
    override val createLogic: (Dispatch) -> ModuleLogic<CounterAction> = { dispatch ->
        CounterLogic(dispatch)
    }
}
```

### Action

Actions are events that describe changes in your application. They are dispatched to the store to trigger state updates.

```kotlin
sealed class CounterAction : ModuleAction(CounterModule::class) {
    object Increment : CounterAction()
    object Decrement : CounterAction()
}
```

### State

States represent the data of your application at a given point in time.

```kotlin
data class CounterState(val count: Int) : ModuleState
```

### Logic

Logic classes handle side effects and complex operations triggered by actions. This is a key differentiator in the MVLI
architecture, providing a clear separation of concerns for business logic.

```kotlin
class CounterLogic(private val dispatch: Dispatch) : ModuleLogic<CounterAction>() {
    override suspend fun invoke(action: ModuleAction) {
        when (action) {
            is CounterAction.Increment -> {
                // Perform some side effect or complex logic
                println("Incrementing counter")
                // You can dispatch additional actions if needed
                // dispatch(SomeOtherAction)
            }
            is CounterAction.Decrement -> {
                // Perform some side effect or complex logic
                println("Decrementing counter")
            }
        }
    }
}
```

## Usage

1. Define your state, actions, and module:

```kotlin
data class CounterState(val count: Int) : ModuleState
sealed class CounterAction : ModuleAction(CounterModule::class)
object CounterModule : Module<CounterState, CounterAction> { /* ... */ }
```

2. Create a store:

```kotlin
val store = createStore {
    module<CounterState, CounterAction>(CounterModule)
}
```

3. Dispatch actions and observe state changes:

```kotlin
store.dispatch(CounterAction.Increment)
store.selectState<CounterState>().collect { state ->
    // Handle state changes
    println("Current count: ${state.count}")
}
```

## Advanced Features

### Middleware

Middleware allows you to intercept actions and perform additional operations. The Middleware type is defined as:

```kotlin
typealias Middleware = suspend (
    action: ModuleAction,
    getAllStates: suspend () -> Map<String, ModuleState>,
    updatedState: suspend (ModuleAction) -> ModuleState
) -> Unit
```

Here's an example of a logging middleware:

```kotlin
val loggingMiddleware: Middleware = { action, getAllStates, updatedState ->
    println("Action: $action")
    val newState = updatedState(action)
    println("Updated State: $newState")
    println("All States: ${getAllStates()}")
}

val store = createStore {
    module<CounterState, CounterAction>(CounterModule)
    middlewares(loggingMiddleware)
}
```

### Persistence

Reaktiv Core supports state persistence out of the box:

```kotlin
val store = createStore {
    module<CounterState, CounterAction>(CounterModule)
    persistenceManager(FilePersistenceStrategy("counter_state.json"))
}

// Save state
store.saveState()

// Load state
store.loadState()
```

## Best Practices

1. Keep your states immutable.
2. Design fine-grained actions that represent specific changes.
3. Use the module system to organize related functionality.
4. Leverage the Logic layer for complex operations and side effects.
5. Use middleware for cross-cutting concerns like logging or analytics.
6. Use coroutines for asynchronous operations in your logic classes.

For more detailed examples and advanced usage, please refer to the API documentation and sample projects.