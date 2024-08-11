# Reaktiv Compose Module

The Compose module provides seamless integration between Reaktiv and Jetpack Compose, allowing you to easily use Reaktiv's state management and navigation features in your declarative UI applications.

## Features

- **Compose-specific Extensions**: Simplify the use of Reaktiv within Compose functions.
- **State Observation Utilities**: Easily observe and react to state changes in your Composables.
- **Navigation Integration**: Render navigation changes smoothly in your Compose UI.
- **Store Provider**: Easily provide the Reaktiv store to your Compose hierarchy.

## Key Components

### StoreProvider

The `StoreProvider` is a Composable that provides the Reaktiv store to the Compose hierarchy.

```kotlin
@Composable
fun App(store: Store) {
    StoreProvider(store) {
        // Your app content here
    }
}
```

### rememberStore

The `rememberStore` function allows you to access the current store within a Composable.

```kotlin
@Composable
fun SomeScreen() {
    val store = rememberStore()
    // Use the store
}
```

### rememberDispatcher

The `rememberDispatcher` function provides access to the store's dispatch function within a Composable.

```kotlin
@Composable
fun ActionButton() {
    val dispatch = rememberDispatcher()
    Button(onClick = { dispatch(SomeAction) }) {
        Text("Perform Action")
    }
}
```

### selectState

The `selectState` function is a Composable that allows you to observe a specific state from the store.

```kotlin
@Composable
fun Counter() {
    val counterState by selectState<CounterState>().collectAsState()
    
    Text("Count: ${counterState.count}")
    Button(onClick = { /* dispatch increment action */ }) {
        Text("Increment")
    }
}
```

### selectLogic

The `selectLogic` function allows you to access a specific module's logic within a Composable.

```kotlin
@Composable
fun LogicAccessExample() {
    val counterLogic = selectLogic<CounterLogic>()
    // Use the logic
}
```

### NavigationRender

The `NavigationRender` Composable handles the rendering of screens based on the current navigation state.

```kotlin
@Composable
fun App(store: Store) {
    StoreProvider(store) {
        NavigationRender(
            modifier = Modifier.fillMaxSize(),
            isAuthenticated = true, // Implement your auth logic
            loadingContent = { LoadingScreen() },
            screenContent = { screen, params ->
                screen.Content(params)
            }
        )
    }
}
```

## Usage

1. Wrap your app content with `StoreProvider`:

```kotlin
@Composable
fun App(store: Store) {
    StoreProvider(store) {
        MainContent()
    }
}
```

2. Use `selectState` to observe state changes:

```kotlin
@Composable
fun CounterDisplay() {
    val counterState by selectState<CounterState>().collectAsState()
    
    Text("Current count: ${counterState.count}")
}
```

3. Dispatch actions using `rememberDispatcher`:

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

4. Use `NavigationRender` for screen transitions:

```kotlin
@Composable
fun App(store: Store) {
    StoreProvider(store) {
        NavigationRender(
            modifier = Modifier.fillMaxSize(),
        ) { screen, params, isLoading ->
            screen.Content(params = params)
        }
    }
}
```

## Best Practices

1. Always use `StoreProvider` at the top level of your Compose hierarchy.
2. Prefer `selectState` over directly accessing the store for better performance and recomposition behavior.
3. Use `rememberDispatcher` to dispatch actions instead of accessing the store directly.
4. Leverage `NavigationRender` for handling navigation in your app.
5. Keep your Composables focused on UI rendering, and use Reaktiv's Logic layer for complex operations.
6. Use `remember` and `derivedStateOf` when appropriate to optimize performance.

## Example: Todo List

Here's a more comprehensive example of how to use the Reaktiv Compose module to create a simple Todo list:

```kotlin
// Define the state and actions
data class TodoState(val items: List<String>) : ModuleState
sealed class TodoAction : ModuleAction(TodoModule::class) {
    data class AddItem(val item: String) : TodoAction()
    data class RemoveItem(val index: Int) : TodoAction()
}

// Create the module
object TodoModule : Module<TodoState, TodoAction> {
    override val initialState = TodoState(emptyList())
    override val reducer: (TodoState, TodoAction) -> TodoState = { state, action ->
        when (action) {
            is TodoAction.AddItem -> state.copy(items = state.items + action.item)
            is TodoAction.RemoveItem -> state.copy(items = state.items.filterIndexed { index, _ -> index != action.index })
        }
    }
    override val createLogic: (StoreAccessor) -> ModuleLogic<TodoAction> = { TodoLogic(it) }
}

// Create the Composable
@Composable
fun TodoList() {
    val todoState by selectState<TodoState>().collectAsState()
    val dispatch = rememberDispatcher()
    
    Column {
        todoState.items.forEachIndexed { index, item ->
            Row {
                Text(item)
                Button(onClick = { dispatch(TodoAction.RemoveItem(index)) }) {
                    Text("Remove")
                }
            }
        }
        
        var newItem by remember { mutableStateOf("") }
        Row {
            TextField(
                value = newItem,
                onValueChange = { newItem = it },
                label = { Text("New Todo Item") }
            )
            Button(
                onClick = {
                    dispatch(TodoAction.AddItem(newItem))
                    newItem = ""
                },
                enabled = newItem.isNotBlank()
            ) {
                Text("Add")
            }
        }
    }
}
```

This example demonstrates how to create a simple Todo list using Reaktiv and Jetpack Compose, showcasing state management, action dispatching, and UI updates.

For more advanced usage and detailed API documentation, please refer to the Reaktiv Compose module's API reference.