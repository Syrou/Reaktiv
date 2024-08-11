# Reaktiv Navigation Module

The Navigation module extends the Reaktiv Core with a powerful and type-safe navigation system for Kotlin Multiplatform projects. It provides a seamless way to manage application flow and screen transitions.

## Features

- **Type-safe Route Definitions**: Define your screens and routes in a type-safe manner.
- **Deep Linking Support**: Easily handle deep links in your application.
- **Navigation State Management**: Keep track of the navigation stack and current screen.
- **Animated Transitions**: Support for custom transition animations between screens.
- **Integration with Reaktiv Core**: Leverages the power of Reaktiv's state management for navigation.

## Key Components

### NavigationModule

The `NavigationModule` is the main component that manages navigation state and logic.

```kotlin
val navigationModule = createNavigationModule {
    setInitialScreen(HomeScreen)
    addScreen(ProfileScreen)
    addScreenGroup(SettingsScreenGroup)
}
```

### Screen

A `Screen` represents a destination in your application.

```kotlin
object HomeScreen : Screen {
    override val route = "home"
    override val titleResourceId = R.string.home_title
    override val enterTransition = NavTransition.Slide
    override val exitTransition = NavTransition.Fade
    override val requiresAuth = false

    @Composable
    override fun Content(params: Map<String, Any>) {
        // Your screen content here
    }
}
```

### NavigationAction

`NavigationAction`s are used to trigger navigation events.

```kotlin
sealed class NavigationAction : ModuleAction(NavigationModule::class) {
    data class Navigate(val route: String, val params: Map<String, Any> = emptyMap()) : NavigationAction()
    data object Back : NavigationAction()
    // ... other navigation actions
}
```

### NavigationLogic

The `NavigationLogic` class handles the execution of navigation actions.

```kotlin
class NavigationLogic(
    private val coroutineScope: CoroutineScope,
    private val availableScreens: Map<String, Screen>,
    val dispatch: Dispatch
) : ModuleLogic<NavigationAction>() {
    // ... implementation
}
```

## Usage

1. Set up the NavigationModule in your store:

```kotlin
val store = createStore {
    module(createNavigationModule {
        setInitialScreen(HomeScreen)
        addScreen(ProfileScreen)
        addScreenGroup(SettingsScreenGroup)
    })
    // ... other modules
}
```

2. Use the navigation extensions to navigate:

```kotlin
store.navigate("profile", mapOf("userId" to 123))
store.navigateBack()
store.popUpTo("home", inclusive = true)
```
or
store.navigate(ProfileScreen, mapOf("userId" to 123))
store.navigateBack()
store.popUpTo(HomeScreen, inclusive = true)

3. Render the current screen using NavigationRender:

```kotlin
@Composable
fun App() {
    NavigationRender(
        modifier = Modifier.fillMaxSize(),
    ) { screen, params, isLoading ->
        screen.Content(params)
    }
}
```

## Advanced Features

### Custom Transitions

Define custom transitions for your screens:

```kotlin
object CustomScreen : Screen {
    override val enterTransition = NavTransition.Custom(
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    )
    // ... other properties
}
```

### Deep Linking

Handle deep links by parsing the URL and navigating to the appropriate screen, for android this could look like:

```kotlin
private fun handleDeepLink(intent: Intent, source: String) {
        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null) {
                val path = uri.path // This will give you "/navigation/user/edit/456"
                store.navigate(path?.replace("/navigation/", "") ?: "")
            }
        }
    }
```
This just allows your deeplinks the be prefixes by /navigation/ but otherwise work like the internal navigation system.

### Navigation with Validation

Perform validation before navigation:

```kotlin
store.navigateWithValidation(
    route = "checkout",
    params = mapOf("cartId" to 456)
) { store, params ->
    val cartState = store.selectState<CartState>().value
    cartState.items.isNotEmpty() && cartState.totalAmount > 0
}
```

## Best Practices

1. Define a consistent naming convention for your routes.
2. Use typed parameters when possible to ensure type safety.
3. Keep your screen content decoupled from navigation logic.
4. Use screen groups to organize related screens.
5. Implement proper error handling for navigation actions.

For more detailed examples and advanced usage, please refer to the API documentation and sample projects.