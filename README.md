# Reaktiv Library

A flexible and powerful state management library for Kotlin applications, inspired by other MVLI solutions but tailored
for Kotlin's coroutine-based concurrency model.

## Table of Contents

1. [Installation](#installation)
2. [Core Concepts](#core-concepts)
3. [Architecture](#architecture)
4. [Usage](#usage)

- [Defining a Module](#defining-a-module)
- [Creating a Store](#creating-a-store)
- [Dispatching Actions](#dispatching-actions)
- [Selecting State](#selecting-state)
- [Using Logic](#using-logic)

5. [Advanced Features](#advanced-features)

- [Middleware](#middleware)
- [Custom Coroutine Context](#custom-coroutine-context)

6. [Best Practices](#best-practices)

## Installation

*Note: Add installation instructions here once the library is published to a repository.*

## Core Concepts

- **Store**: The central hub that holds the application state and manages state updates.
- **Module**: A self-contained unit that defines a piece of state, actions that can modify that state, and logic for
  side effects.
- **Action**: A description of a change to be made to the state.
- **Reducer**: A pure function that specifies how the state should change in response to an action.
- **Logic**: A place to handle side effects and complex operations in response to actions.
- **Middleware**: A way to intercept actions and perform additional operations before or after they reach the reducer.

## Architecture

Here's a diagram illustrating the main components of the Kotlin Store Library:

![PlantUML model](https://www.planttext.com/api/plantuml/svg/ZLJBRjim4BppAnO-MOeS84LFWY14S14qYDsB-WCqjN49qwH1hfAYJVwz52ahagC4lP6xmvbzZ7JhY5jgZugvWzGdRlaHXEBEtHbDjVo3SFDrBlM37n6-etf2ae9V8YeYAtSjuHlBbqyn7zBjk3ZihMbripRAM23BjVCfrCHOu86QZ4Nuom1MmdWeIOrhpuc_AOVrHQH37MNJA7ps92w7ZNHKR8T86G2cQHNUwqUJmvQVYeTr9rIaPGRR8LMa6QHbXxzgf2-9nAyb5oIDpdjK5Mx9656txeIw-HZeKA6WcZXXKP_puLekanp2wKwPvw8kt-0RivUYs9pXyUfhwrvis2jZqlTk7xd07b7KCE7Ee89fCMKOw9NKaYHN0UJLkv35fXLeSrZM_ba2gpB6LQSwBQ4gvyG36MDyv_mo1KKtJCIZTjp2JxxFOLZ0bAzNcI_zFF8SllMdsD2JEU0m_og3zxYaKnbNzQdEVpEEvZ3ORPPYB9B17gaV_yYAtjCJRsGNDntCxwBRO3cnMRUn4JgPEA4hD7vGwMe5YtJH9_kWEGU73bZCH_ZDOi8rJWrCiOhvPs7GR7I6w-kNCkwPiLlVY_zJ_GC0)
This diagram shows the relationships between the main components of the library:

- The `Store` is the central component that manages multiple `Module`s and aggregates multiple `Middleware`s.
- Each `Module` contains one `ModuleState`, multiple `ModuleAction`s, and one `ModuleLogic`.
- `ModuleLogic` implements the `Logic` interface.
- `Middleware` can intercept and process actions before they reach the modules.

## Usage

### Defining a Module

```kotlin
object CounterModule : Module<CounterState, CounterAction> {
    data class CounterState(val count: Int = 0) : ModuleState

    sealed class CounterAction : ModuleAction(CounterModule::class) {
        object Increment : CounterAction()
        object Decrement : CounterAction()
    }

    override val initialState = CounterState()
    override val reducer: (CounterState, CounterAction) -> CounterState = { state, action ->
        when (action) {
            is CounterAction.Increment -> state.copy(count = state.count + 1)
            is CounterAction.Decrement -> state.copy(count = state.count - 1)
        }
    }

    override val logic = ModuleLogic<CounterAction> { action, dispatch ->
        when (action) {
            is CounterAction.Increment -> println("Incremented!")
            is CounterAction.Decrement -> println("Decremented!")
        }
    }
}
```

### Creating a Store

```kotlin
val store = createStore {
    modules(CounterModule)
    middlewares(loggingMiddleware)
    coroutineContext(Dispatchers.Default)
}
```

### Dispatching Actions

```kotlin
store.dispatch(CounterAction.Increment)

// Or suspending version
suspend fun incrementCounter() {
    store.dispatchSuspend(CounterAction.Increment)
}
```

### Selecting State

```kotlin
val counterState: StateFlow<CounterState> = store.selectState()

// In a coroutine
counterState.collect { state ->
    println("Current count: ${state.count}")
}
```

### Using Logic

```kotlin
val counterLogic: CounterLogic = store.selectLogic()
```

## Advanced Features

### Middleware

```kotlin
val loggingMiddleware: Middleware = { action, getState, next ->
    println("Before action: $action")
    val result = next(action)
    println("After action: $action, New State: ${getState[CounterState::class]}")
    result
}
```

### Custom Coroutine Context

```kotlin
val store = createStore {
    modules(CounterModule)
    coroutineContext(Dispatchers.Default + SupervisorJob())
}
```

## Best Practices

1. Keep your state immutable.
2. Design your actions to be as granular and specific as possible.
3. Use logic for side effects and complex operations.
4. Leverage Kotlin's strong type system in your state and actions.
5. Use middleware for cross-cutting concerns like logging or analytics.

## License

This project is licensed under the Apache License Version 2.0. See the LICENSE file for details.
