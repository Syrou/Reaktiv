# Reaktiv Library

A flexible and powerful state management library for Kotlin applications, leveraging Kotlin's coroutine-based
concurrency model to provide a robust MVLI (Model-View-Logic-Intent) solution.

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
7. [License](#license)

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

Here's a diagram illustrating the main components of the Reaktiv Library:

![Architecture Diagram](https://www.planttext.com/api/plantuml/svg/hLR1Rk8m4Btp5IFs4hfGzLOfYg8YAgHguPJk1nZ7W4LYHxPJThVYtzU9dMH2GjkLbWj4xtbctimRXoTjY39bdatqYOk2A983pOZMIsCKtvM6lL0f4lw7mGEYv598UbGoPs1KoH2YZoILPouEi2UPnaZ61JE_2mPIcQESJ0f2J-J0OBgIYi6hHVGKtYTWFrmOflQ4CjZAjCnOOeXXDK9ssYX2ZIlImqjgk1J-RFNvRWkiW5To2E77gg96Tt4DNwuIJ9-vBeYXnob4KR2UVrkl7nyV7cQmBaKTDevHu00ddC4YXf-26n_uWZGv7gnaNaZ1X6cbZGhOo0EdqHB2dg2ufoZrTORGL8p0zoRi1NIf2oPIA_5DdbX0wb3zmFEHO3CSpPh2S7ffOcHLUM4REUZ7QYCqxPY5VLbDuqT76oMjwdhAS_Yu3Tmcu2IhkI4a254iBIbJ8GI93L9NWs4ludPbU20lOYz7_DGSZ-xcuFtaOEtAsPQ6xBoGTIRF4H8MVroBB4rVzOYf4bDjoj1JoCrRdgOPEnF5lkJd6oFTaYBoQzFLvLJChvv68zJDHxphvs7RdRIki0LGgMnkUsSaL1QsjwlUCoBchMGTmePh4tLDY36ldMo81TwraYee7jcuobE3dLk0swpLaGZ1CbkV-n4DX-WjmZ96JIt_slDnO5UcCqqoFEOyie5FF5C7sg3JD6D4b4Kmkz7nvsJp7vhkRV_LtZls5qRmpprsQNJmPbyuwW22dHrJznSNttL823xMazS-u8DjgrRIhjyl4r3tZPhcaHaIj2jvbhfns_PtEux62WkxB8tVxCHBpRJ7QmWPvoRgtuYtO9SdX2DfmFh2wmWMg-GGBcuGclGViveyBSkjUKreczfKYR0kLWWv4VRom_1rRwuFjk8B9IdJBQKo-o2rBMtF3wKQa70DOwJx-zZVMXPNpBP3JoPFzYgqVwR-0W00)

This diagram illustrates the relationships between the main components of the library:

- The `Store` is the central component that manages multiple `Module`s and aggregates multiple `Middleware`s.
- Each `Module` contains one `ModuleState`, multiple `ModuleAction`s, and one `ModuleLogic`.
- `ModuleLogic` handles side effects and complex operations.
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
            is Increment -> state.copy(count = state.count + 1)
            is Decrement -> state.copy(count = state.count - 1)
        }
    }

    override val logic = ModuleLogic<CounterAction> { action, dispatch ->
        when (action) {
            is Increment -> println("Incremented to ${(action as Increment).count}")
            is Decrement -> println("Decremented to ${(action as Decrement).count}")
        }
    }
}
```

### Creating a Store

```kotlin
val store = createStore {
    modules(CounterModule)
    middlewares(loggingMiddleware)
    coroutineContext(Dispatchers.Default + SupervisorJob())
}
```

### Dispatching Actions

```kotlin
// Using the dispatcher property
store.dispatcher(CounterAction.Increment)

// Or in a coroutine
launch {
    store.dispatcher(CounterAction.Increment)
}
```

### Selecting State

```kotlin
val counterState: StateFlow<CounterState> = store.selectState()

// In a coroutine
launch {
    counterState.collect { state ->
        println("Current count: ${state.count}")
    }
}
```

### Using Logic

```kotlin
val counterLogic: CounterLogic = store.selectLogic()
counterLogic.someCustomMethod()
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

1. Keep your state immutable to prevent unexpected side effects.
2. Design your actions to be as granular and specific as possible for better traceability.
3. Use logic for handling side effects and complex operations, keeping your reducers pure.
4. Leverage Kotlin's strong type system in your state and actions for compile-time safety.
5. Use middleware for cross-cutting concerns like logging, analytics, or error handling.
6. Prefer using `StateFlow` for observing state changes in your UI layer.
7. Utilize Kotlin's coroutines and flow for asynchronous operations within your logic.

## License

This project is licensed under the Apache License Version 2.0. See the [LICENSE](LICENSE) file for details.