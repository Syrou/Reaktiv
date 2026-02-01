# Reaktiv Navigation Module

The Navigation module provides a powerful, type-safe navigation system for Kotlin Multiplatform projects. It integrates seamlessly with Reaktiv Core to manage application flow, screen transitions, modals, and deep linking.

## Features

- **Type-safe Navigation**: Navigate using route paths or direct screen/modal references
- **Nested Graph Hierarchies**: Organize screens into logical groups with unlimited nesting
- **Screen & Modal Support**: Full modal system with dimming, backdrop handling, and layering
- **Deep Linking**: Automatic backstack synthesis from URL paths
- **Animated Transitions**: 15+ built-in transitions with custom animation support
- **Screen Layouts**: Graph-level scaffolds for shared UI (app bars, bottom navigation)
- **Lifecycle Callbacks**: `onAddedToBackstack()` and `onRemovedFromBackstack()` hooks
- **Type-safe Parameters**: `Params` class with typed access and serialization
- **RenderLayer System**: CONTENT, GLOBAL_OVERLAY, and SYSTEM layers with z-ordering
- **NotFoundScreen**: Configurable fallback for undefined routes

## Key Components

### createNavigationModule

Create a navigation module using the DSL:

```kotlin
val navigationModule = createNavigationModule {
    rootGraph {
        startScreen(HomeScreen)
        screens(ProfileScreen, SettingsScreen)

        graph("auth") {
            startScreen(LoginScreen)
            screens(SignUpScreen, ForgotPasswordScreen)
        }

        graph("admin") {
            startScreen(DashboardScreen)
            screens(UsersScreen, ReportsScreen)

            graph("tools") {
                startScreen(ToolsHomeScreen)
                screens(LogViewerScreen, ConfigEditorScreen)
            }
        }

        modals(ConfirmationModal, AlertModal)
    }

    notFoundScreen(NotFoundScreen)
    screenRetentionDuration(10.seconds)
}
```

### Screen

A `Screen` represents a destination in your application:

```kotlin
@Serializable
object ProfileScreen : Screen {
    override val route = "profile"
    override val titleResource: TitleResource? = null
    override val actionResource: ActionResource? = null
    override val enterTransition = NavTransition.SlideInRight
    override val exitTransition = NavTransition.SlideOutLeft
    override val popEnterTransition: NavTransition? = NavTransition.SlideInLeft
    override val popExitTransition: NavTransition? = NavTransition.SlideOutRight
    override val requiresAuth = true

    override suspend fun onAddedToBackstack(storeAccessor: StoreAccessor) {
        storeAccessor.dispatch(ProfileAction.LoadProfile)
    }

    override suspend fun onRemovedFromBackstack(storeAccessor: StoreAccessor) {
        storeAccessor.dispatch(ProfileAction.ClearProfile)
    }

    @Composable
    override fun Content(params: Params) {
        val userId = params.getString("userId")
        ProfileContent(userId)
    }
}
```

### Modal

A `Modal` displays over other content with backdrop dimming:

```kotlin
@Serializable
object ConfirmationModal : Modal {
    override val route = "confirmation"
    override val enterTransition = NavTransition.SlideUpBottom
    override val exitTransition = NavTransition.SlideOutBottom
    override val requiresAuth = false

    override val shouldDimBackground = true
    override val backgroundDimAlpha = 0.5f

    override val onDismissTapOutside: (suspend StoreAccessor.() -> Unit)? = {
        navigateBack()
    }

    @Composable
    override fun Content(params: Params) {
        val message = params.getString("message") ?: "Are you sure?"
        ConfirmationDialog(message)
    }
}
```

### Navigation DSL

Use the `navigation { }` DSL for atomic navigation operations:

```kotlin
// Navigate to a route
scope.launch {
    store.navigation {
        navigateTo("profile") {
            putString("userId", "123")
        }
    }
}

// Type-safe navigation with screen reference
scope.launch {
    store.navigation {
        navigateTo<ProfileScreen> {
            putString("userId", "123")
            putInt("tabIndex", 2)
        }
    }
}

// Navigate back
scope.launch {
    store.navigation {
        navigateBack()
    }
}

// Pop to a specific route with optional fallback
scope.launch {
    store.navigation {
        popUpTo("home", inclusive = false, fallback = "root")
    }
}

// Clear backstack and navigate to new root
scope.launch {
    store.navigation {
        clearBackStack()
        navigateTo("home")
    }
}

// Dismiss all modals before navigating
scope.launch {
    store.navigation {
        dismissModals()
        navigateTo("settings")
    }
}

// Multiple operations in one atomic block
scope.launch {
    store.navigation {
        popUpTo("auth")
        navigateTo("dashboard")
    }
}
```

### Deep Linking with Backstack Synthesis

For deep links, use `synthesizeBackstack` to automatically build the proper navigation history:

```kotlin
// Deep link to "auth/signup/verify" will create backstack:
// 1. auth's start screen (e.g., LoginScreen)
// 2. auth/signup (e.g., SignUpScreen)
// 3. auth/signup/verify (the target)
scope.launch {
    store.navigation {
        navigateTo("auth/signup/verify", synthesizeBackstack = true)
    }
}

// Or use the dedicated method
scope.launch {
    store.selectLogic<NavigationLogic>().navigateDeepLink(
        route = "auth/signup/verify",
        params = Params.of("referrer" to "email")
    )
}
```

Umbrella graphs (graphs without `startScreen` or `startGraph`) are automatically skipped during backstack synthesis.

### PopUpTo with Fallback

Handle deep link scenarios where the expected backstack may not exist:

```kotlin
scope.launch {
    store.navigation {
        // If "dashboard" is not in backstack, navigate to "home" as fallback
        popUpTo("dashboard", inclusive = false, fallback = "home")
    }
}
```

If the fallback is also not in the backstack, it navigates to the fallback as a fresh destination with a cleared backstack.

### Params (Type-safe Parameters)

The `Params` class provides type-safe parameter handling:

```kotlin
// Creating params
val params = Params.of(
    "userId" to "123",
    "count" to 42,
    "isActive" to true
)

// Fluent builder API
val params = Params.empty()
    .with("userId", "123")
    .with("count", 42)
    .withTyped("user", userObject)

// In navigation
store.navigation {
    navigateTo("profile") {
        putString("userId", "123")
        putInt("tabIndex", 2)
        putBoolean("showHeader", true)
        put("complexData", mySerializableObject)
    }
}

// Reading params in screens
@Composable
override fun Content(params: Params) {
    val userId = params.getString("userId")
    val count = params.getInt("count") ?: 0
    val isActive = params.getBoolean("isActive") ?: false
    val user = params.getTyped<User>("user")

    // Required params (throws if missing)
    val requiredId = params.requireString("userId")
    val requiredUser = params.requireTyped<User>("user")
}
```

### NavTransition (Animations)

Built-in transition animations:

```kotlin
// No animation
NavTransition.None

// Fade transitions
NavTransition.Fade
NavTransition.FadeOut

// Slide transitions
NavTransition.SlideInRight
NavTransition.SlideOutRight
NavTransition.SlideInLeft
NavTransition.SlideOutLeft
NavTransition.SlideUpBottom
NavTransition.SlideOutBottom

// Scale transitions
NavTransition.Scale()
NavTransition.ScaleOut()

// Platform-style transitions
NavTransition.IOSSlideIn
NavTransition.IOSSlideOut
NavTransition.MaterialSlideIn
NavTransition.MaterialSlideOut

// Stack transitions
NavTransition.StackPush
NavTransition.StackPop

// Custom transition
NavTransition.Custom(
    durationMillis = 300,
    alpha = { progress -> progress },
    scaleX = { progress -> 0.8f + progress * 0.2f },
    scaleY = { progress -> 0.8f + progress * 0.2f },
    translationX = { progress -> (1f - progress) * screenWidth },
    translationY = { 0f },
    rotationZ = { 0f }
)
```

### Graph Layouts (Scaffolds)

Define shared UI for screens within a graph:

```kotlin
createNavigationModule {
    rootGraph {
        startScreen(HomeScreen)

        graph("main") {
            startScreen(DashboardScreen)
            screens(ProfileScreen, SettingsScreen)

            // All screens in this graph will be wrapped with this layout
            layout { content ->
                Scaffold(
                    topBar = { TopAppBar(title = { Text("My App") }) },
                    bottomBar = { BottomNavigationBar() }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        content()
                    }
                }
            }
        }
    }
}
```

### RenderLayer System

Control where navigatables render in the layer hierarchy:

```kotlin
enum class RenderLayer {
    CONTENT,        // Normal navigation flow, respects graph layouts
    GLOBAL_OVERLAY, // Renders above all graphs (default for Modal)
    SYSTEM          // Renders at absolute top, above everything
}

// Custom screen in SYSTEM layer
@Serializable
object SystemAlertScreen : Screen {
    override val route = "system-alert"
    override val renderLayer = RenderLayer.SYSTEM
    override val elevation = 10000f  // Higher = on top
    // ...
}
```

### Screen Groups

Organize related screens:

```kotlin
object SettingsScreenGroup : ScreenGroup(
    GeneralSettingsScreen,
    PrivacySettingsScreen,
    NotificationSettingsScreen,
    AccountSettingsScreen
)

createNavigationModule {
    rootGraph {
        startScreen(HomeScreen)
        screenGroup(SettingsScreenGroup)
    }
}
```

### NavigationRender

Render the current navigation state in your Compose UI:

```kotlin
@Composable
fun App() {
    StoreProvider(store) {
        NavigationRender(
            modifier = Modifier.fillMaxSize()
        )
    }
}
```

### NavigationState

Access navigation state for UI decisions:

```kotlin
@Composable
fun NavigationAwareComponent() {
    val navigationState by composeState<NavigationState>()

    // Current entry info
    val currentRoute = navigationState.currentEntry.navigatable.route
    val currentParams = navigationState.currentEntry.params

    // Navigation capabilities
    val canGoBack = navigationState.canGoBack
    val isModal = navigationState.isCurrentModal

    // Path and hierarchy
    val fullPath = navigationState.currentFullPath // e.g., "auth/signup/verify"
    val pathSegments = navigationState.currentPathSegments // ["auth", "signup", "verify"]
    val graphHierarchy = navigationState.currentGraphHierarchy // ["auth", "signup"]
    val breadcrumbs = navigationState.breadcrumbs

    // Backstack info
    val backStackSize = navigationState.backStack.size
    val hasModals = navigationState.hasModalsInStack

    // Layer-specific entries
    val contentEntries = navigationState.contentLayerEntries
    val overlayEntries = navigationState.globalOverlayEntries
    val systemEntries = navigationState.systemLayerEntries

    // Check if in specific graph
    if (navigationState.isInGraph("admin")) {
        AdminToolbar()
    }
}
```

### Store Extensions

Convenient extension functions for navigation:

```kotlin
// Navigation DSL
scope.launch {
    store.navigation {
        navigateTo("profile")
    }
}

// Simple navigation
scope.launch {
    store.navigate("profile", Params.of("userId" to "123"))
}

// Type-safe navigation
scope.launch {
    store.navigate<ProfileScreen>(Params.of("userId" to "123"))
}

// Navigate back
scope.launch {
    store.navigateBack()
}

// Present modal
scope.launch {
    store.presentModal<ConfirmationModal>()
}

// Dismiss modal
scope.launch {
    store.dismissModal()
}

// Clear all modals
scope.launch {
    store.clearAllModals()
}
```

## Usage with Reaktiv Core

### Store Setup

```kotlin
val store = createStore {
    module(CounterModule)
    module(UserModule)
    module(navigationModule)

    middlewares(loggingMiddleware)
    coroutineContext(Dispatchers.Default)
}
```

### Compose Integration

```kotlin
@Composable
fun App() {
    StoreProvider(store) {
        NavigationRender(modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun HomeScreen() {
    val store = rememberStore()
    val scope = rememberCoroutineScope()

    Column {
        Button(onClick = {
            scope.launch {
                store.navigation {
                    navigateTo("profile") {
                        putString("userId", "current-user")
                    }
                }
            }
        }) {
            Text("Go to Profile")
        }
    }
}
```

### Android Deep Link Handling

```kotlin
class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        // ...
    }

    private fun handleDeepLink(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null) {
                val path = uri.path
                val route = path?.removePrefix("/navigation/") ?: return

                customApp.store.launch {
                    customApp.store.navigation {
                        navigateTo(route, synthesizeBackstack = true)
                    }
                }
            }
        }
    }
}
```

AndroidManifest.xml:

```xml
<activity android:name=".MainActivity" android:launchMode="singleTask" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.VIEW"/>
        <category android:name="android.intent.category.DEFAULT"/>
        <category android:name="android.intent.category.BROWSABLE"/>
        <data android:scheme="myapp"
              android:host="myapp.com"
              android:pathPrefix="/navigation"/>
    </intent-filter>
</activity>
```

Test deep links via ADB:

```bash
adb shell am start -a android.intent.action.VIEW -d "myapp://myapp.com/navigation/auth/signup/verify"
```

## Best Practices

1. **Use `@Serializable`** - Mark screens and modals with `@Serializable` for persistence and DevTools support
2. **Prefer type-safe navigation** - Use `navigateTo<ScreenType>()` when possible for compile-time safety
3. **Use synthesizeBackstack for deep links** - Ensures proper back navigation for external entry points
4. **Use popUpTo with fallback** - Handle cases where expected navigation history may not exist
5. **Keep navigation logic in screens** - Use `onAddedToBackstack` for data loading, not in Composables
6. **Use graph layouts** - Share common UI (app bars, bottom navigation) across related screens
7. **Leverage RenderLayer** - Use GLOBAL_OVERLAY for modals, SYSTEM for critical alerts
8. **Use Params for parameters** - Type-safe parameter passing with proper serialization

## API Reference

### NavigationModule Configuration

```kotlin
fun createNavigationModule(block: GraphBasedBuilder.() -> Unit): NavigationModule

class GraphBasedBuilder {
    fun rootGraph(block: NavigationGraphBuilder.() -> Unit)
    fun notFoundScreen(screen: Screen)
    fun screenRetentionDuration(duration: Duration)
}

class NavigationGraphBuilder {
    fun startScreen(screen: Screen)
    fun startGraph(graphId: String)
    fun screens(vararg screens: Screen)
    fun modals(vararg modals: Modal)
    fun screenGroup(screenGroup: ScreenGroup)
    fun graph(graphId: String, builder: NavigationGraphBuilder.() -> Unit): NavigationGraph
    fun layout(layoutComposable: @Composable (@Composable () -> Unit) -> Unit)
}
```

### NavigationBuilder DSL

```kotlin
class NavigationBuilder {
    fun navigateTo(path: String, replaceCurrent: Boolean = false, synthesizeBackstack: Boolean = false, paramBuilder: (NavigationParameterBuilder.() -> Unit)? = null): NavigationBuilder
    suspend fun <T : Navigatable> navigateTo(replaceCurrent: Boolean = false, synthesizeBackstack: Boolean = false, paramBuilder: (NavigationParameterBuilder.() -> Unit)? = null): NavigationBuilder
    fun navigateBack(): NavigationBuilder
    fun popUpTo(path: String, inclusive: Boolean = false, fallback: String? = null): NavigationBuilder
    suspend fun <T : Navigatable> popUpTo(inclusive: Boolean = false, preferredGraphId: String? = null, fallback: String? = null): NavigationBuilder
    fun clearBackStack(): NavigationBuilder
    fun dismissModals(): NavigationBuilder
    fun params(params: Params): NavigationBuilder
    fun putString(key: String, value: String): NavigationBuilder
    fun putInt(key: String, value: Int): NavigationBuilder
    fun putBoolean(key: String, value: Boolean): NavigationBuilder
    inline fun <reified T : Any> put(key: String, value: T): NavigationBuilder
}
```

### NavigationLogic

```kotlin
class NavigationLogic {
    suspend fun navigate(block: suspend NavigationBuilder.() -> Unit)
    suspend fun navigate(route: String, params: Params = Params.empty(), replaceCurrent: Boolean = false)
    suspend fun navigateBack()
    suspend fun popUpTo(route: String, inclusive: Boolean = false, fallback: String? = null)
    suspend fun navigateDeepLink(route: String, params: Params = Params.empty())
    suspend fun clearBackStack(newRoute: String? = null, params: Params = Params.empty())
}
```

### StoreAccessor Extensions

```kotlin
suspend fun StoreAccessor.navigation(block: suspend NavigationBuilder.() -> Unit)
suspend fun StoreAccessor.navigateBack()
suspend fun StoreAccessor.navigate(route: String, params: Params? = null)
suspend inline fun <reified T : Navigatable> StoreAccessor.navigate(params: Params? = null)
suspend inline fun <reified T : Modal> StoreAccessor.presentModal()
suspend fun StoreAccessor.dismissModal()
suspend fun StoreAccessor.clearAllModals()
```

### Params

```kotlin
class Params {
    companion object {
        fun empty(): Params
        fun of(vararg pairs: Pair<String, Any>): Params
        fun fromUrl(encodedQuery: String): Params
        fun fromMap(map: Map<String, Any>): Params
    }

    fun getString(key: String): String?
    fun getInt(key: String): Int?
    fun getBoolean(key: String): Boolean?
    fun getDouble(key: String): Double?
    fun getLong(key: String): Long?
    fun getFloat(key: String): Float?
    inline fun <reified T> getTyped(key: String): T?

    fun requireString(key: String): String
    fun requireInt(key: String): Int
    fun requireBoolean(key: String): Boolean
    inline fun <reified T> requireTyped(key: String): T

    fun with(key: String, value: String): Params
    fun with(key: String, value: Int): Params
    inline fun <reified T : Any> withTyped(key: String, value: T): Params

    operator fun plus(other: Params): Params
    fun without(key: String): Params
    fun contains(key: String): Boolean
    fun isEmpty(): Boolean
}
```

For more detailed examples and advanced usage, please refer to the sample projects and API documentation.
