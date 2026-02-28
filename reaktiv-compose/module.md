# Module reaktiv-compose

The Compose module provides seamless integration between Reaktiv and Jetpack Compose /
Compose Multiplatform. It bridges the store and Compose's reactive model with a minimal API
surface: observe state, dispatch actions, call logic, and render navigation — all from
Composable functions.

## Setup

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.syrou:reaktiv-compose:<version>")
}
```

---

## Providing the Store

Wrap your root Composable with [StoreProvider] so every descendant can access the store.

```kotlin
@Composable
fun App(store: Store) {
    StoreProvider(store) {
        NavigationRender(modifier = Modifier.fillMaxSize())
    }
}
```

---

## Observing State

`composeState<S>()` is the primary hook. It returns a Compose `State<S>` that triggers
recomposition whenever the module state changes.

```kotlin
@Composable
fun CounterScreen() {
    val state by composeState<CounterState>()
    val dispatch = rememberDispatcher()

    Column {
        Text("Count: ${state.count}")
        Button(onClick = { dispatch(CounterAction.Increment) }) {
            Text("Increment")
        }
    }
}
```

Supply an `initialValue` for Compose Previews or tests that run outside a [StoreProvider]:

```kotlin
@Preview
@Composable
fun CounterPreview() {
    val state by composeState<CounterState>(initialValue = CounterState(count = 5))
    Text("Count: ${state.count}")
}
```

---

## Derived State with `select`

`select<S, R>()` derives a value from module state and only triggers recomposition when
the derived value changes — useful for large state objects.

```kotlin
@Composable
fun TodoCount() {
    // Only recomposes when the item count changes, not on other TodoState field changes
    val count by select<TodoState, Int> { it.items.size }
    Text("$count items")
}

@Composable
fun UserGreeting() {
    val name by select<UserState, String>(
        selector = { it.user?.name ?: "Guest" },
        areEqual = { old, new -> old == new }
    )
    Text("Hello, $name")
}
```

---

## Calling Logic

Use `rememberLogic<M, L>()` to obtain a logic instance and call its suspend methods from
a `rememberCoroutineScope`.

```kotlin
@Composable
fun ProfileScreen() {
    val state by composeState<UserState>()
    val logic = rememberLogic<UserModule, UserLogic>()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        logic.loadProfile()
    }

    Column {
        Text("Welcome, ${state.user?.name}")
        Button(onClick = { scope.launch { logic.refreshProfile() } }) {
            Text("Refresh")
        }
    }
}
```

---

## Watching Side Effects

`onActiveValueChange<S, T>()` fires a callback whenever a selected value changes while
the Composable is active — ideal for analytics, toast messages, and one-off reactions.

```kotlin
@Composable
fun ScreenTracker() {
    onActiveValueChange<NavigationState, String>(
        selector = { it.currentEntry.navigatable.route }
    ) { route ->
        analytics.trackScreenView(route)
    }
}
```

---

## Full Screen Example

```kotlin
@Composable
fun TodoListScreen() {
    val state by composeState<TodoState>()
    val dispatch = rememberDispatcher()
    val logic = rememberLogic<TodoModule, TodoLogic>()
    val scope = rememberCoroutineScope()
    var newText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { logic.loadTodos() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Todos (${state.items.size})", style = MaterialTheme.typography.headlineMedium)

        LazyColumn(Modifier.weight(1f)) {
            items(state.items) { todo ->
                TodoItem(
                    todo = todo,
                    onToggle = { dispatch(TodoAction.ToggleItem(todo.id)) }
                )
            }
        }

        Row(Modifier.fillMaxWidth()) {
            TextField(value = newText, onValueChange = { newText = it }, Modifier.weight(1f))
            Button(
                onClick = { scope.launch { logic.saveTodo(newText); newText = "" } },
                enabled = newText.isNotBlank()
            ) { Text("Add") }
        }
    }
}
```

---

## Key Types

- [StoreProvider] — provides the [Store] to the Compose composition tree
- `composeState<S>()` — primary state-observation hook; returns `State<S>`
- `select<S, R>()` — derived state with equality-based recomposition control
- `rememberDispatcher()` — returns the store's `Dispatch` function
- `rememberLogic<M, L>()` — returns the typed [ModuleLogic] instance
- `rememberStore()` — returns the raw [Store] (for advanced use)
- `onActiveValueChange<S, T>()` — side-effect hook that fires on value changes
- [NavigationRender] — renders the current navigation screen with animated transitions
