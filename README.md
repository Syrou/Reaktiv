# Reaktiv

Reaktiv is a powerful MVLI (Model-View-Logic-Intent) library for Kotlin Multiplatform, designed to simplify state management and navigation in modern applications. It provides a robust architecture for building scalable and maintainable applications across various platforms.

## Features

- **MVLI Architecture**: Implements a unidirectional data flow pattern with a distinct Logic layer for enhanced separation of concerns
- **Kotlin Multiplatform**: Supports Android, iOS, Desktop, and Web from a single codebase
- **Type-safe Navigation**: Powerful navigation system with nested graphs, deep linking, modals, and automatic backstack synthesis
- **Jetpack Compose Integration**: Seamless integration with Compose Multiplatform for declarative UI
- **DevTools Support**: Real-time debugging with state inspection and time-travel capabilities
- **State Persistence**: Built-in support for saving and restoring application state
- **Coroutine-First**: Built entirely on Kotlin Coroutines for efficient asynchronous operations

## Modules

### Core

The foundation of Reaktiv providing the MVLI architecture components:

- `ModuleWithLogic<S, A, L>` - Type-safe module definition with state, actions, and logic
- `Store` - Central state manager coordinating all modules
- `StoreAccessor` - Interface for accessing state, logic, and dispatch
- Middleware system for cross-cutting concerns (logging, analytics, etc.)
- State persistence with customizable strategies
- `CustomTypeRegistrar` for polymorphic serialization
- `HighPriorityAction` for urgent action processing
- `ReaktivDebug` utilities for development logging

[Learn more about the Core module](https://github.com/Syrou/Reaktiv/blob/main/reaktiv-core/README.md)

### Navigation

A comprehensive type-safe navigation system:

- **Nested Graph Hierarchies** - Organize screens into logical groups with unlimited nesting
- **Screen & Modal Support** - Full modal system with dimming, backdrop handling, and layering
- **Deep Linking** - Automatic backstack synthesis from URL paths
- **Transitions** - 20+ built-in transitions with custom animation support
- **Screen Layouts** - Graph-level scaffolds for shared UI (app bars, bottom navigation)
- **Lifecycle Callbacks** - `onAddedToBackstack()` and `onRemovedFromBackstack()` hooks
- **Type-safe Parameters** - `Params` class with typed access and serialization
- **RenderLayer System** - CONTENT, GLOBAL_OVERLAY, and SYSTEM layers with z-ordering
- **NotFoundScreen** - Configurable fallback for undefined routes

[Learn more about the Navigation module](https://github.com/Syrou/Reaktiv/blob/main/reaktiv-navigation/README.md)

### Compose

Jetpack Compose integration for reactive UI:

- `StoreProvider` - Provide store to Compose hierarchy
- `composeState<S>()` - Observe module state as Compose State
- `rememberDispatcher()` - Access dispatch function
- `rememberLogic<M, L>()` - Access typed module logic
- `select<S, R>()` - Derived state with custom selectors
- `NavigationRender` - Render navigation state with animations

[Learn more about the Compose module](https://github.com/Syrou/Reaktiv/blob/main/reaktiv-compose/README.md)

### DevTools

Real-time debugging and state inspection:

- WebSocket-based connection to DevTools UI
- Action capture and replay
- State inspection and modification
- Time-travel debugging support
- Platform-aware middleware integration

## Getting Started

Add the dependencies to your project:

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.syrou:reaktiv-core:<version>")
    implementation("io.github.syrou:reaktiv-navigation:<version>")
    implementation("io.github.syrou:reaktiv-compose:<version>")

    // Optional: DevTools support
    implementation("io.github.syrou:reaktiv-devtools:<version>")
}
```

## Quick Example

### Define a Module

```kotlin
@Serializable
data class CounterState(val count: Int = 0) : ModuleState

sealed class CounterAction : ModuleAction(CounterModule::class) {
    data object Increment : CounterAction()
    data object Decrement : CounterAction()
    data class SetCount(val value: Int) : CounterAction()
}

class CounterLogic(private val storeAccessor: StoreAccessor) : ModuleLogic<CounterAction>() {

    suspend fun incrementDelayed() {
        delay(1000)
        storeAccessor.dispatch(CounterAction.Increment)
    }

    suspend fun fetchAndSetCount() {
        val count = api.fetchCount()
        storeAccessor.dispatch(CounterAction.SetCount(count))
    }
}

object CounterModule : ModuleWithLogic<CounterState, CounterAction, CounterLogic> {
    override val initialState = CounterState()

    override val reducer: (CounterState, CounterAction) -> CounterState = { state, action ->
        when (action) {
            is CounterAction.Increment -> state.copy(count = state.count + 1)
            is CounterAction.Decrement -> state.copy(count = state.count - 1)
            is CounterAction.SetCount -> state.copy(count = action.value)
        }
    }

    override val createLogic: (StoreAccessor) -> CounterLogic = { CounterLogic(it) }
}
```

### Create the Store

```kotlin
val navigationModule = createNavigationModule {
    rootGraph {
        startScreen(HomeScreen)
        screens(ProfileScreen, SettingsScreen)

        graph("auth") {
            startScreen(LoginScreen)
            screens(SignUpScreen)
        }
    }
    notFoundScreen(NotFoundScreen)
}

val store = createStore {
    module(CounterModule)
    module(navigationModule)
    middlewares(loggingMiddleware)
    coroutineContext(Dispatchers.Default)
}
```

### Use in Compose

```kotlin
@Composable
fun App() {
    StoreProvider(store) {
        NavigationRender(modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun CounterScreen() {
    val state by composeState<CounterState>()
    val dispatch = rememberDispatcher()
    val logic = rememberLogic<CounterModule, CounterLogic>()
    val scope = rememberCoroutineScope()

    Column {
        Text("Count: ${state.count}")

        Button(onClick = { dispatch(CounterAction.Increment) }) {
            Text("Increment")
        }

        Button(onClick = { scope.launch { logic.incrementDelayed() } }) {
            Text("Increment Delayed")
        }
    }
}
```

### Navigate

```kotlin
// Using the navigation DSL
scope.launch {
    store.navigation {
        navigateTo("profile") {
            putString("userId", "123")
        }
    }
}

// Type-safe navigation
scope.launch {
    store.navigation {
        navigateTo<ProfileScreen> {
            put("user", userObject)
        }
    }
}

// Deep link with backstack synthesis
scope.launch {
    store.navigation {
        navigateTo("auth/signup/verify", synthesizeBackstack = true)
    }
}

// Pop with fallback for deep links
scope.launch {
    store.navigation {
        popUpTo("home", inclusive = false, fallback = "root")
    }
}
```

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                         Store                                │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │   Module A  │  │   Module B  │  │  NavigationModule   │  │
│  │  ┌───────┐  │  │  ┌───────┐  │  │  ┌───────────────┐  │  │
│  │  │ State │  │  │  │ State │  │  │  │ NavigationState│  │  │
│  │  └───────┘  │  │  └───────┘  │  │  └───────────────┘  │  │
│  │  ┌───────┐  │  │  ┌───────┐  │  │  ┌───────────────┐  │  │
│  │  │ Logic │  │  │  │ Logic │  │  │  │NavigationLogic│  │  │
│  │  └───────┘  │  │  └───────┘  │  │  └───────────────┘  │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │                    Middleware Chain                     │ │
│  │  Logging → Analytics → DevTools → ... → Reducer        │ │
│  └────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                      Compose UI Layer                        │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ composeState│  │rememberLogic│  │  NavigationRender   │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## Documentation

- [Core Module Documentation](https://github.com/Syrou/Reaktiv/blob/main/reaktiv-core/README.md)
- [Navigation Module Documentation](https://github.com/Syrou/Reaktiv/blob/main/reaktiv-navigation/README.md)
- [Compose Module Documentation](https://github.com/Syrou/Reaktiv/blob/main/reaktiv-compose/README.md)

## License

Reaktiv is released under the Apache License Version 2.0. See the [LICENSE](LICENSE) file for details.
