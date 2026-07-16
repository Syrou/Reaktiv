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

### Included Build Commands (reaktiv-tracing-compiler & reaktiv-tracing-gradle)
These are separate Gradle builds and must be built/tested/published with `-p`:
```bash
# Build tracing compiler plugin
./gradlew -p reaktiv-tracing-compiler build

# Build tracing Gradle plugin
./gradlew -p reaktiv-tracing-gradle build

# Test tracing compiler plugin
./gradlew -p reaktiv-tracing-compiler test

# Test tracing Gradle plugin
./gradlew -p reaktiv-tracing-gradle test

# Publish included builds separately
./gradlew -p reaktiv-tracing-compiler uploadToCentral
./gradlew -p reaktiv-tracing-gradle uploadToCentral
```

### Test Commands
```bash
# Run core module tests (KMP modules have no plain `test` task, use jvmTest)
./gradlew :reaktiv-core:jvmTest

# Run navigation module tests
./gradlew :reaktiv-navigation:jvmTest

# Run introspection module tests
./gradlew :reaktiv-introspection:jvmTest

# Verify devtools compilation (no JVM target)
./gradlew :reaktiv-devtools:compileKotlinMingwX64

# Run Android tests
./gradlew :androidexample:testDebugUnitTest
```

**Important**: The `--tests` flag (e.g., `./gradlew test --tests "SomeTest"`) does NOT work with this Gradle setup. Use the module-specific test tasks above instead.

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

### Custom Serializers Pattern

**Important**: When your module's state contains polymorphic types, sealed classes, or types requiring custom serializers, implement `CustomTypeRegistrar`:

```kotlin
@Serializable
sealed interface AppSettings

@Serializable
data class BasicSettings(val theme: String) : AppSettings

@Serializable
data class AdvancedSettings(val theme: String, val locale: String) : AppSettings

@Serializable
data class UserState(
    val settings: AppSettings,
    val timestamp: Instant
) : ModuleState

object UserModule : Module<UserState, UserAction>, CustomTypeRegistrar {
    override val initialState = UserState(...)
    override val reducer = { state, action -> ... }
    override val createLogic = { dispatch -> UserLogic(dispatch) }

    override fun registerAdditionalSerializers(builder: SerializersModuleBuilder) {
        builder.polymorphic(AppSettings::class) {
            subclass(BasicSettings::class)
            subclass(AdvancedSettings::class)
        }
        builder.contextual(Instant::class, InstantSerializer)
    }
}
```

**How it works:**
- Store automatically detects modules implementing `CustomTypeRegistrar`
- Serializers are collected during Store construction
- Available to both persistence and DevToolsMiddleware
- No manual SerializersModule building required

**Example modules:**
- `NavigationModule` uses this for Screen, Modal, and NavigationGraph types
- User modules can use it for their own polymorphic types

### NavigationLogic Pattern - Imperative Methods

**Current Pattern**: NavigationLogic uses the imperative pattern with public suspend methods, NOT the invoke() pattern. When extending NavigationLogic with new features, continue following this pattern:

**How NavigationLogic Currently Works:**

1. **Public suspend methods** for navigation operations:
   - `suspend fun navigate(block: suspend NavigationBuilder.() -> Unit)`
   - `suspend fun navigate(route: String, params: Params, ...)`
   - `suspend fun navigateBack()`
   - `suspend fun popUpTo(route: String, inclusive: Boolean)`
   - `suspend fun clearBackStack(...)`

2. **StoreAccessor extensions** call NavigationLogic methods directly:
   ```kotlin
   suspend fun StoreAccessor.navigation(block: suspend NavigationBuilder.() -> Unit) {
       val navigationLogic = selectLogic<NavigationLogic>()
       navigationLogic.navigate(block)
   }
   ```

3. **Methods dispatch actions internally** for state updates:
   ```kotlin
   private suspend fun executeNavigation(builder: NavigationBuilder, source: String) {
       val finalState = computeFinalNavigationState(...)
       val action = determineNavigationAction(...)
       storeAccessor.dispatch(action)  // Dispatch to reducer
   }
   ```

**When Adding New Navigation Features:**

1. **Add a public suspend method** to NavigationLogic (NOT an invoke() handler)
2. **Optionally add a StoreAccessor extension** for convenience
3. **Dispatch actions internally** to update state via the reducer
4. **Keep reducers simple** - only handle pure state transformations

```kotlin
// ✅ Correct: Add public method to NavigationLogic
suspend fun newNavigationFeature(param: String) {
    // Complex logic, validation, state computation
    val action = NavigationAction.UpdateState(...)
    storeAccessor.dispatch(action)
}

// ✅ Correct: Add StoreAccessor extension
suspend fun StoreAccessor.newNavigationFeature(param: String) {
    val navLogic = selectLogic<NavigationLogic>()
    navLogic.newNavigationFeature(param)
}

// ❌ Wrong: Don't add invoke() handling
override suspend fun invoke(action: NavigationAction) {
    // DON'T DO THIS - NavigationLogic doesn't use invoke()
}
```

**Architecture Notes:**
- NavigationLogic uses `precomputedData` for static configuration (graphs, routes, etc.)
- NavigationLogic accesses `NavigationState` only for dynamic runtime state (currentEntry, backStack, etc.)
- Actions (Navigate, Back, Replace, BatchUpdate) are for state updates, not logic triggers

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

0. **Comments are a separate pass** - Never add comments or KDoc when writing or modifying code. Comments and documentation are only generated when explicitly requested as their own complete pass. The guidelines below define the style for that dedicated pass. Also: never use the em-dash character anywhere (code, docs, commit messages); use commas, colons, or parentheses instead.

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
    * Executes navigation operations atomically.
    * 
    * Example usage:
    * ```kotlin
    * store.navigation {
    *     navigateTo(ProfileScreen)
    *     popUpTo("home")
    * }
    * ```
    * 
    * @param block Lambda containing navigation operations to execute
    */
   ```

3. **Usage examples for complex classes** - Classes that provide tooling or complex functionality must include usage examples:
   ```kotlin
   /**
    * Builder for creating atomic navigation operations.
    * 
    * Usage:
    * ```kotlin
    * val builder = NavigationBuilder(storeAccessor, encoder)
    * builder.navigateTo("profile")
    * builder.validate()
    * ```
    */
   class NavigationBuilder { ... }
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

## Publishing

### CentralPublisherPlugin

The project uses a custom `CentralPublisherPlugin` (in `convention-plugins/`) for publishing to Maven Central via Sonatype.

**Key behaviors:**
- Detects project type (KMP vs JVM vs Gradle plugin) and configures publications accordingly
- Automatically adds javadoc and sources JARs required by Maven Central
- Handles GPG signing with in-memory keys
- Creates bundle ZIP for Sonatype Central Portal upload

**Gradle Plugin Publishing:**
When publishing Gradle plugins (`java-gradle-plugin`), the plugin creates:
1. Main plugin artifact at `io/github/syrou/reaktiv-tracing-gradle/`
2. Plugin marker artifact at `io/github/syrou/reaktiv/tracing/` (based on plugin ID)

Both artifacts must be published for the plugin to be resolvable. The `CreateBundleTask` automatically discovers and includes plugin marker paths.

**Publishing commands:**
```bash
# Publish all main modules
./gradlew uploadToCentral

# Publish included builds separately
./gradlew -p reaktiv-tracing-compiler uploadToCentral
./gradlew -p reaktiv-tracing-gradle uploadToCentral
```

**Using the tracing plugin in external projects:**
```toml
# libs.versions.toml
[plugins]
reaktiv-tracing = { id = "io.github.syrou.reaktiv.tracing", version.ref = "reaktiv" }
```

```kotlin
// settings.gradle.kts - must include mavenCentral()
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
```

## Configuration

- **Kotlin**: 2.4.10
- **Gradle**: 9.6.1 (runs on JDK 17-26; the system-default JDK 25 works)
- **Compose Multiplatform**: 1.11.1
- **Android Gradle Plugin**: 9.3.0 (KMP modules use `com.android.kotlin.multiplatform.library` with the android target configured inside `kotlin { android { } }`; androidexample uses AGP built-in Kotlin — no `kotlin("android")` plugin)
- **AtomicFU**: 0.33.0
- **kotlinx**: coroutines 1.11.0, serialization 1.11.0, datetime 0.8.0
- Target platforms: JVM, Android, iOS/macOS, wasmJs (module-dependent)

## Migration Documentation

All API changes — breaking removals and significant additions — must be recorded in
`migrations/pending.md` so that users on older versions can migrate mechanically and
LLMs know what new APIs are available. See `migrations/README.md` for the full format.

### Files

| File | Purpose |
|------|---------|
| `migrations/README.md` | Canonical entry format and LLM workflow |
| `migrations/v{old}-to-v{new}.md` | Released migration guide |
| `migrations/pending.md` | Accumulates changes during active development |

### When you introduce a breaking change

**Immediately** append a `BC-NN` entry to `migrations/pending.md`. Use the next
available ID (inspect the last BC entry in the file).

```
### [BC-NN] Short Title

**Type:** Breaking | Deprecation-removal | Behavioural

**Grep:** `old pattern to search in user's code`
**File glob:** `**/*.kt`

**Before:**
```kotlin
// old code
```

**After:**
```kotlin
// replacement
```

**Notes:** Any caveats.

---
```

### When you add a significant new API

**Immediately** append an `AD-NN` entry to `migrations/pending.md`. This covers:
- New public classes, functions, or DSLs
- New properties on existing types
- Replacements for deprecated or removed APIs (cross-reference the BC entry)

```
### [AD-NN] Short Title

**Type:** Addition | Replaces-deprecated

**Grep:** `new API pattern to check adoption`
**File glob:** `**/*.kt`

**Replaces:** (Replaces-deprecated only) brief description of the old API

**Example:**
```kotlin
// self-contained usage example
```

**Notes:** When to use, caveats, related entries.

---
```

### Rule: removed deprecated API → always two entries

When a previously deprecated API is removed, write:
1. A `BC-NN` entry for the removal (Grep = old pattern, Before/After = migration path)
2. An `AD-NN` entry for the replacement API (Grep = new pattern, Example = usage)

Cross-reference them with "see BC-NN" / "see AD-NN" in the Notes field.

### On version release

1. Rename `migrations/pending.md` → `migrations/v{old}-to-v{new}.md`
2. Reset `migrations/pending.md` to its template header (both section headings,
   no actual entries)