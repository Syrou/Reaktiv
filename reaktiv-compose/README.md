# Reaktiv Compose Module

The Compose module provides seamless integration between Reaktiv and Jetpack Compose (and Compose Multiplatform), allowing you to easily use Reaktiv's state management and navigation features in your declarative UI applications.

## Features

- **Store Provider**: Provide the Reaktiv store to your Compose hierarchy
- **State Observation**: Reactive state observation with `composeState<S>()`
- **Dispatcher Access**: Easy action dispatching with `rememberDispatcher()`
- **Logic Access**: Type-safe logic access with `rememberLogic<M, L>()`
- **Derived State**: Efficient derived state with `select<S, R>()`
- **Navigation Rendering**: Render navigation state with animations via `NavigationRender`

## Key Components

### StoreProvider

`StoreProvider` is a Composable that provides the Reaktiv store to the Compose hierarchy via CompositionLocal.

```kotlin
@Composable
fun App(store: Store) {
    StoreProvider(store) {
        // Your app content - all children can access the store
        MainContent()
    }
}
```

### composeState

`composeState<S>()` is the primary API for observing module state in Composables. It returns a Compose `State<S>` that automatically updates when the state changes.

```kotlin
@Composable
fun CounterDisplay() {
    val state by composeState<CounterState>()

    Text("Count: ${state.count}")
}

// With initial value for previews
@Composable
fun CounterDisplayPreview() {
    val state by composeState<CounterState>(initialValue = CounterState(count = 42))

    Text("Count: ${state.count}")
}
```

### rememberStore

`rememberStore()` retrieves the Store from the CompositionLocal provided by `StoreProvider`.

```kotlin
@Composable
fun MyComponent() {
    val store = rememberStore()

    // Access store properties directly
    LaunchedEffect(Unit) {
        store.selectState<MyState>().collect { state ->
            // Handle state changes
        }
    }
}
```

### rememberDispatcher

`rememberDispatcher()` provides access to the store's dispatch function for firing actions.

```kotlin
@Composable
fun CounterButtons() {
    val dispatch = rememberDispatcher()

    Row {
        Button(onClick = { dispatch(CounterAction.Increment) }) {
            Text("Increment")
        }
        Button(onClick = { dispatch(CounterAction.Decrement) }) {
            Text("Decrement")
        }
    }
}
```

### rememberLogic

`rememberLogic<M, L>()` provides type-safe access to a module's logic instance for calling suspend methods.

```kotlin
@Composable
fun ProfileScreen() {
    val logic = rememberLogic<UserModule, UserLogic>()
    val scope = rememberCoroutineScope()

    Button(onClick = {
        scope.launch {
            logic.refreshProfile()
        }
    }) {
        Text("Refresh")
    }
}
```

### select

`select<S, R>()` creates derived state from a module state using a selector function. It only triggers recomposition when the selected value changes.

```kotlin
@Composable
fun TodoCount() {
    // Only recomposes when the count changes, not when other TodoState fields change
    val count by select<TodoState, Int> { state -> state.items.size }

    Text("$count items")
}

// With custom equality
@Composable
fun UserDisplay() {
    val userName by select<UserState, String>(
        selector = { it.user?.name ?: "Guest" },
        areEqual = { old, new -> old == new }
    )

    Text("Hello, $userName")
}
```

### onActiveValueChange

`onActiveValueChange<S, T>()` watches a selected value and triggers a callback when it changes while the Composable is active.

```kotlin
@Composable
fun AnalyticsTracker() {
    onActiveValueChange<NavigationState, String>(
        selector = { it.currentEntry.navigatable.route }
    ) { route ->
        analytics.trackScreenView(route)
    }
}
```

## Usage Examples

### Complete Screen Example

```kotlin
@Composable
fun TodoListScreen() {
    val state by composeState<TodoState>()
    val dispatch = rememberDispatcher()
    val logic = rememberLogic<TodoModule, TodoLogic>()
    val scope = rememberCoroutineScope()

    // Load todos on first composition
    LaunchedEffect(Unit) {
        logic.loadTodos()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header with count
        Text(
            text = "Todos (${state.items.size})",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Todo list
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.items) { todo ->
                TodoItem(
                    todo = todo,
                    onToggle = { dispatch(TodoAction.ToggleItem(todo.id)) },
                    onDelete = { dispatch(TodoAction.RemoveItem(todo.id)) }
                )
            }
        }

        // Add new todo
        var newTodoText by remember { mutableStateOf("") }
        Row(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = newTodoText,
                onValueChange = { newTodoText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("New todo...") }
            )
            Button(
                onClick = {
                    scope.launch {
                        logic.saveTodo(newTodoText)
                        newTodoText = ""
                    }
                },
                enabled = newTodoText.isNotBlank()
            ) {
                Text("Add")
            }
        }
    }
}
```

### Navigation with Compose

```kotlin
@Composable
fun App(store: Store) {
    StoreProvider(store) {
        // NavigationRender handles all screen rendering and transitions
        NavigationRender(
            modifier = Modifier.fillMaxSize()
        )
    }
}

// Define a screen
object ProfileScreen : Screen {
    override val route = "profile"
    override val enterTransition = NavTransition.SlideInRight
    override val exitTransition = NavTransition.SlideOutLeft

    @Composable
    override fun Content(params: Params) {
        val userId = params.getString("userId")
        ProfileContent(userId)
    }
}

@Composable
fun ProfileContent(userId: String?) {
    val state by composeState<UserState>()
    val store = rememberStore()
    val scope = rememberCoroutineScope()

    Column {
        Text("User: ${state.user?.name ?: "Loading..."}")

        Button(onClick = {
            scope.launch {
                store.navigation {
                    navigateTo("settings")
                }
            }
        }) {
            Text("Go to Settings")
        }

        Button(onClick = {
            scope.launch {
                store.navigation {
                    navigateBack()
                }
            }
        }) {
            Text("Back")
        }
    }
}
```

### Using Multiple States

```kotlin
@Composable
fun DashboardScreen() {
    val userState by composeState<UserState>()
    val statsState by composeState<StatsState>()
    val settingsState by composeState<SettingsState>()

    Column {
        Text("Welcome, ${userState.user?.name}")
        Text("Total items: ${statsState.totalCount}")

        if (settingsState.darkMode) {
            DarkThemeContent()
        } else {
            LightThemeContent()
        }
    }
}
```

### Conditional Logic Access

```kotlin
@Composable
fun AdminPanel() {
    val userState by composeState<UserState>()

    if (userState.isAdmin) {
        val adminLogic = rememberLogic<AdminModule, AdminLogic>()
        val scope = rememberCoroutineScope()

        Button(onClick = {
            scope.launch {
                adminLogic.performAdminAction()
            }
        }) {
            Text("Admin Action")
        }
    }
}
```

## Best Practices

1. **Use `composeState<S>()`** - Prefer `composeState` over `selectState` for cleaner code with destructuring
2. **Use `rememberDispatcher()`** - For simple action dispatching instead of accessing the store directly
3. **Use `rememberLogic<M, L>()`** - For calling suspend methods on logic classes
4. **Use `select<S, R>()`** - When you only need a subset of state to minimize recompositions
5. **Wrap with `StoreProvider`** - Always wrap your app's root composable with `StoreProvider`
6. **Use `rememberCoroutineScope()`** - For launching suspend functions from click handlers
7. **Keep UI logic in Composables** - Business logic belongs in `ModuleLogic`, UI logic in Composables

## API Reference

### State Selection

```kotlin
// Primary state observation
@Composable
inline fun <reified S : ModuleState> composeState(): State<S>

@Composable
inline fun <reified S : ModuleState> composeState(initialValue: S): State<S>

// StateFlow access (for advanced use cases)
@Composable
inline fun <reified S : ModuleState> selectState(): StateFlow<S>

@Composable
inline fun <reified S : ModuleState> selectState(initialValue: S): StateFlow<S>

// Derived state with selector
@Composable
inline fun <reified S : ModuleState, R> select(
    crossinline selector: (S) -> R,
    noinline areEqual: (R, R) -> Boolean = { old, new -> old == new }
): State<R>
```

### Store Access

```kotlin
// Get store instance
@Composable
fun rememberStore(): Store

// Get dispatch function
@Composable
fun rememberDispatcher(): Dispatch

// Get typed logic instance
@Composable
inline fun <reified M : ModuleWithLogic<*, *, L>, reified L : ModuleLogic<*>> rememberLogic(): L
```

### Effects

```kotlin
// React to state value changes
@Composable
inline fun <reified S : ModuleState, T> onActiveValueChange(
    crossinline selector: (S) -> T,
    crossinline onChange: suspend (T) -> Unit
)
```

### Provider

```kotlin
// Provide store to Compose hierarchy
@Composable
fun StoreProvider(
    store: Store,
    content: @Composable () -> Unit
)
```

For more detailed examples and advanced usage, please refer to the sample projects and API documentation.
