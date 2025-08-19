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
            // Handle simple state updates only
        }
    }
    override val createLogic = { dispatch: Dispatch -> ExampleLogic(dispatch) }
}
```

### NavigationLogic Pattern for Complex Operations

**Important**: When adding new navigation features that involve complex logic, side effects, or coordinated state updates, follow this pattern:

1. **Keep reducers simple**: Only handle pure state transformations in the reducer
2. **Use NavigationLogic for complex operations**: Add handling in `NavigationLogic.invoke()` for actions that require:
   - Multiple state updates
   - Validation and bounds checking
   - Coordination between different parts of navigation state
   - Side effects or async operations

```kotlin
// In NavigationLogic.invoke()
is NavigationAction.YourComplexAction -> handleYourComplexAction(action)

// Complex logic handled in dedicated method
private suspend fun handleYourComplexAction(action: NavigationAction.YourComplexAction) {
    // Complex validation, state coordination, follow-up actions
}
```

### Code Style Guidelines

**Important coding conventions to follow:**

1. **Always use imports instead of fully qualified class names**
   ```kotlin
   // ✅ Good - use import and short name
   import io.github.syrou.reaktiv.core.serialization.StringAnyMap
   val params: StringAnyMap = mapOf()
   
   // ❌ Bad - don't use fully qualified names
   val params: io.github.syrou.reaktiv.core.serialization.StringAnyMap = mapOf()
   ```

2. **Test all changes comprehensively**
   - Add tests for any new functionality
   - Test edge cases and error conditions
   - Verify existing functionality still works
   - Use descriptive test names that explain the behavior being tested

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

### Guided Flow Operations DSL

**New Pattern**: Use the `guidedFlow { }` DSL for atomic guided flow operations, similar to `navigation { }`:

```kotlin
// Atomic guided flow operations
storeAccessor.guidedFlow("signup-flow") {
    removeSteps(listOf(2, 3))
    updateStepParams(1, mapOf("userId" to "123"))
    nextStep()
}

// Instead of multiple separate dispatches:
store.dispatch(NavigationAction.ModifyGuidedFlow(...))
store.dispatch(NavigationAction.NextStep())
```

**Available operations:**
- `addSteps(steps, insertIndex)` - Add steps to the flow
- `removeSteps(stepIndices)` - Remove steps by index
- `replaceStep(stepIndex, newStep)` - Replace a step
- `updateStepParams(stepIndex, newParams)` - Update step parameters
- `updateOnComplete(onComplete)` - Update completion handler
- `nextStep(params)` - Navigate to next step
- `previousStep()` - Navigate to previous step

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

## Documentation Standards

**Code Comments and Documentation Guidelines:**

1. **No inline comments** - Comments should never appear after code on the same line
   ```kotlin
   // ✅ Good - comment above code
   val isValid = user.isActive && user.hasPermission
   
   // ❌ Bad - inline comment
   val isValid = user.isActive && user.hasPermission // Check user status
   ```

2. **Dokka-compatible formatting** - All code examples must be formatted for Dokka documentation generation:
   ```kotlin
   /**
    * Handles complex guided flow operations atomically.
    * 
    * Example usage:
    * ```kotlin
    * store.guidedFlow("user-onboarding") {
    *     removeSteps(listOf(2, 3))
    *     updateStepParams(1, mapOf("userId" to "123"))
    *     nextStep()
    * }
    * ```
    * 
    * @param flowRoute The route identifier for the guided flow
    * @param block Lambda containing flow operations to execute
    */
   ```

3. **Usage examples for complex classes** - Classes that provide tooling or complex functionality must include usage examples:
   ```kotlin
   /**
    * Builder for creating atomic guided flow operations.
    * 
    * Usage:
    * ```kotlin
    * val builder = GuidedFlowOperationBuilder(flowRoute)
    * builder.addSteps(newSteps, 0)
    * builder.nextStep()
    * ```
    */
   class GuidedFlowOperationBuilder { ... }
   ```

4. **Comments should provide value** - Only add comments that explain:
   - Why something is done (not what is being done)
   - Complex business logic or algorithm explanations
   - Non-obvious design decisions
   - API usage examples for public interfaces

5. **Documentation structure for complex classes:**
   - Brief description of the class purpose
   - Usage example showing typical API interaction
   - Parameter and return value documentation using `@param` and `@return`
   - `@see` references to related classes when appropriate

## Configuration

- **Kotlin**: 2.1.21
- **Compose**: 1.8.1  
- **Android Gradle Plugin**: 8.7.0
- **AtomicFU**: 0.27.0
- Target platforms: JVM, Android, potentially iOS/Desktop
- Uses kotlinx.coroutines and kotlinx.serialization