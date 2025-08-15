# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Reaktiv is a Kotlin Multiplatform library implementing the MVLI (Model-View-Logic-Intent) architecture pattern for state management and navigation. It consists of three main modules:

- **reaktiv-core**: Core MVLI architecture with Store, Module, State, Action, and Logic components
- **reaktiv-navigation**: Type-safe navigation system with screen definitions, routing, and transitions
- **reaktiv-compose**: Jetpack Compose integration with state observation utilities
- **androidexample**: Example Android application demonstrating usage

## Build System & Commands

This project uses Gradle with Kotlin Multiplatform setup.

### Core Commands
```bash
# Build all modules
./gradlew build

# Run tests
./gradlew test

# Run Android example
./gradlew :androidexample:assembleDebug

# Clean build
./gradlew clean

# Run JVM tests specifically
./gradlew jvmTest

# Publish to local repository
./gradlew publishToMavenLocal
```

### Test Commands
```bash
# Run core module tests
./gradlew :reaktiv-core:test

# Run navigation module tests  
./gradlew :reaktiv-navigation:test

# Run Android tests
./gradlew :androidexample:testDebugUnitTest
```

## Architecture Overview

### MVLI Pattern Components

**Store**: Central state manager that coordinates modules, handles actions, and manages state
- Created with `createStore { }` DSL
- Manages multiple modules and their states
- Handles action dispatching and middleware execution

**Module**: Defines a feature domain with initial state, reducer, and logic creation
- Interface: `Module<State, Action>`
- Contains: `initialState`, `reducer`, `createLogic`
- Example modules: `CounterModule`, `NavigationModule`

**State**: Immutable data representation implementing `ModuleState`
- Data classes representing application state at a point in time
- Must be immutable and serializable

**Action**: Events that trigger state changes, extending `ModuleAction`
- Sealed classes/objects for type safety
- Tagged with module class for routing
- Can implement `HighPriorityAction` for urgent processing

**Logic**: Side effect handling and complex business logic
- Extends `ModuleLogic<Action>`
- Handles async operations and additional action dispatching
- Key differentiator of MVLI vs traditional MVI

### Navigation System

**Screen**: Defines a destination with route, transitions, and content
- Properties: `route`, `titleResourceId`, `enterTransition`, `exitTransition`
- Contains `@Composable Content()` function

**NavigationGraph**: Hierarchical screen organization
- Supports nested graphs and screen groups
- Type-safe route definitions with parameters

**NavigationLogic**: Handles navigation action execution
- Manages navigation stack and transitions
- Supports deep linking and validation

### Compose Integration

**StoreProvider**: Provides store to Compose hierarchy
**selectState<T>()**: Observes specific state from store
**rememberDispatcher()**: Accesses dispatch function in Composables
**NavigationRender**: Renders current screen with transitions

## Development Patterns

### Module Structure
```kotlin
object ExampleModule : Module<ExampleState, ExampleAction> {
    override val initialState = ExampleState()
    override val reducer = { state: ExampleState, action: ExampleAction ->
        when (action) {
            // Handle state updates
        }
    }
    override val createLogic = { dispatch: Dispatch -> ExampleLogic(dispatch) }
}
```

### Navigation Definition
```kotlin
object ExampleScreen : Screen {
    override val route = "example"
    @Composable
    override fun Content(params: Map<String, Any>) {
        // Screen UI
    }
}
```

### State Selection in Compose
```kotlin
@Composable
fun ExampleComponent() {
    val state by selectState<ExampleState>().collectAsState()
    val dispatch = rememberDispatcher()
    // Use state and dispatch
}
```

## Key Directories

- `reaktiv-core/src/commonMain/kotlin/`: Core MVLI implementation
- `reaktiv-navigation/src/commonMain/kotlin/`: Navigation system
- `reaktiv-compose/src/commonMain/kotlin/`: Compose integration
- `androidexample/src/main/java/`: Example Android app
- `**/src/commonTest/kotlin/`: Test files for each module

## Testing

The project uses Kotlin Test for multiplatform testing:
- Core module: State management and middleware tests
- Navigation module: Navigation logic and graph tests  
- Example app: Android-specific unit tests

Test files are located in `src/commonTest/kotlin/` for multiplatform tests and `src/test/` for platform-specific tests.

## Configuration

- **Kotlin**: 2.1.21
- **Compose**: 1.8.1  
- **Android Gradle Plugin**: 8.7.0
- **AtomicFU**: 0.27.0
- Target platforms: JVM, Android, potentially iOS/Desktop
- Uses kotlinx.coroutines and kotlinx.serialization