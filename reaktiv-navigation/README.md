# Reaktiv Navigation Module

The Navigation module provides a powerful, type-safe navigation system for Kotlin Multiplatform projects. It integrates seamlessly with Reaktiv Core to manage application flow, screen transitions, modals, and deep linking.

## Features

- **Type-safe Navigation**: Navigate using route paths or direct screen/modal references
- **Nested Graph Hierarchies**: Organize screens into logical groups with unlimited nesting
- **Screen & Modal Support**: Full modal system with dimming, backdrop handling, and layering
- **Deep Linking**: Automatic backstack synthesis from URL paths
- **Animated Transitions**: 15+ built-in transitions with custom animation support
- **Screen Layouts**: Graph-level scaffolds for shared UI (app bars, bottom navigation)
- **Lifecycle Callbacks**: `onLifecycleCreated()` with `BackstackLifecycle` for visibility tracking and cleanup
- **Type-safe Parameters**: `Params` class with typed access and serialization
- **RenderLayer System**: CONTENT, GLOBAL_OVERLAY, and SYSTEM layers with z-ordering
- **NotFoundScreen**: Configurable fallback for undefined routes
- **Navigation Guards**: `intercept { }` for group-level access control and `entry(...)` for per-graph guards
- **GuardResult**: Typed guard decisions — `Allow`, `Reject`, `RedirectTo`, `PendAndRedirectTo`
- **Threshold-based Loading**: Loading screens shown only when guard evaluation exceeds a configurable threshold
- **Pending Navigation**: Store-and-resume deferred auth flows with `resumePendingNavigation()`

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

    override suspend fun onLifecycleCreated(lifecycle: BackstackLifecycle) {
        // Load data when added to backstack
        lifecycle.dispatch(ProfileAction.LoadProfile)

        // Register cleanup when removed from backstack
        // `this` is StoreAccessor — runs before lifecycle scope is cancelled
        lifecycle.invokeOnRemoval {
            // Non-suspend context; use launch for suspend work
            dispatch(ProfileAction.ClearProfile)
        }
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

### Navigation Guards

Guards let you intercept navigation and decide — before any state change — whether to allow it,
reject it, redirect to another screen, or store it as a pending navigation to resume later.

Two tools are available:

- **`intercept { }`** — wraps a group of graphs; the guard fires for every route inside the block
- **`entry(...)`** — attaches access control and/or dynamic routing directly to a single graph

Both are fully optional. Graphs without guards behave exactly as before.

#### Type aliases

```kotlin
typealias NavigationGuard = suspend (StoreAccessor) -> GuardResult
typealias RouteSelector   = suspend (StoreAccessor) -> Navigatable
```

#### GuardResult

| Variant | Description |
|---|---|
| `GuardResult.Allow` | Let the navigation proceed normally |
| `GuardResult.Reject` | Silently drop the navigation; no state change, no redirect |
| `GuardResult.RedirectTo(route)` | Navigate to another route without storing the original destination |
| `GuardResult.RedirectTo(navigatable)` | Same, using a typed screen reference |
| `GuardResult.PendAndRedirectTo(route, metadata, displayHint)` | Store the original destination as pending, then redirect |
| `GuardResult.PendAndRedirectTo(navigatable, metadata, displayHint)` | Same, using a typed screen reference |

#### intercept { } — protecting groups of graphs

`intercept` applies a single guard to every route inside the block, including nested graphs and
deep links. The guard is a **suspend function** — network calls, token validation, and database
lookups all work inside it. Use `selectLogic` to reach your auth logic:

```kotlin
val requireAuth: NavigationGuard = { store ->
    // selectLogic gives you access to suspend functions in your own logic classes.
    val authLogic = store.selectLogic<AuthLogic>()
    when (authLogic.checkSession()) {       // suspend fun — may hit network or token store
        SessionStatus.Valid   -> GuardResult.Allow
        SessionStatus.Expired -> GuardResult.RedirectTo(SessionExpiredScreen)
        SessionStatus.None    -> GuardResult.PendAndRedirectTo(
            navigatable = LoginScreen,
            displayHint = "Please log in to continue"
        )
    }
}

createNavigationModule {
    rootGraph {
        entry(SplashScreen)
        screens(SplashScreen, LoginScreen, SessionExpiredScreen)

        intercept(
            guard = requireAuth,
            loadingScreen = AuthLoadingScreen,   // shown only if checkSession() takes > 300ms
            loadingThreshold = 300.milliseconds
        ) {
            graph("workspace") {
                entry(HomeScreen)
                screens(HomeScreen, InviteScreen)
            }

            graph("settings") {
                entry(SettingsScreen)
                screens(SettingsScreen, ProfileScreen)
            }
        }
    }
}
```

#### entry(screen) — static graph start destination

`entry(screen)` sets the start destination for a graph and registers the screen. It is the
direct replacement for the deprecated `startScreen()`:

```kotlin
graph("workspace") {
    entry(HomeScreen)          // replaces: startScreen(HomeScreen)
    screens(HomeScreen, InviteScreen)
}
```

`startScreen()` still compiles but is marked `@Deprecated(ReplaceWith("entry(screen)"))`.

#### entry(access, route) — dynamic per-graph entry

`entry` with named parameters attaches per-graph behaviour:

- **`access`** — guard evaluated when navigating into this graph (including deep links);
  returns `GuardResult` to allow or redirect
- **`route`** — selector evaluated only for direct graph navigation (not deep links);
  returns which screen inside the graph to navigate to
- **`loadingScreen`** / **`loadingThreshold`** — same threshold semantics as `intercept`

Both parameters are optional; use one or both as needed.

```kotlin
graph("content") {
    entry(
        access = { store ->
            val contentLogic = store.selectLogic<ContentLogic>()
            // suspend call — e.g. checks if initial data fetch has completed
            if (!contentLogic.isReady()) GuardResult.RedirectTo(LoadingScreen)
            else GuardResult.Allow
        },
        route = { store ->
            val contentLogic = store.selectLogic<ContentLogic>()
            // suspend call — picks the right start screen based on loaded data
            if (contentLogic.hasReleases()) ReleasesScreen else NoContentScreen
        },
        loadingScreen = SpinnerScreen,
        loadingThreshold = 200.milliseconds
    )
    screens(ReleasesScreen, NoContentScreen, LoadingScreen, SpinnerScreen)
}
```

#### Loading threshold behaviour

When a guard (or `entry.route`) completes within `loadingThreshold`, the loading screen is never
shown — the result is applied immediately. Only if evaluation takes longer than the threshold does
the navigation system navigate to the loading screen first, then apply the final result when the
guard finishes.

- Default threshold: **200ms**
- Fast guards (e.g. reading cached in-memory state) never trigger the loading screen
- Slow guards (e.g. a network token check) show a spinner until the result is ready

#### Pending navigation — PendAndRedirectTo + resumePendingNavigation

`GuardResult.PendAndRedirectTo` stores the original destination in `NavigationState.pendingNavigation`
before redirecting the user to a gate screen (typically login). This works identically whether
the blocked navigation came from a normal `navigation { }` call or from a deep link.

**Full flow:**

1. Guard returns `PendAndRedirectTo(LoginScreen, displayHint = "Please log in to continue")`
2. The original route + params are stored in `NavigationState.pendingNavigation`
3. The user is navigated to `LoginScreen`
4. After login, auth logic navigates to the post-auth landing screen (e.g. `HomeScreen`)
5. `HomeScreen` calls `resumePendingNavigation()` in a `LaunchedEffect` — no-op if nothing is pending

`resumePendingNavigation()` navigates to the stored route with backstack synthesis and **bypasses
guard evaluation** for the resumed navigation — guards will not fire a second time for the
destination the user was already trying to reach.

```kotlin
// HomeScreen — the post-auth landing screen handles pending navigation for all flows.
object HomeScreen : Screen {
    override val route = "home"

    @Composable
    override fun Content(params: Params) {
        val store = rememberStore()
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            store.resumePendingNavigation()  // no-op if nothing pending
        }

        // ... rest of UI
    }
}
```

```kotlin
// AuthLogic — just navigates to HomeScreen after login. HomeScreen handles the rest.
suspend fun onLoginSuccess() {
    dispatch(AuthAction.SetAuthenticated(user))
    navigation { navigateTo(HomeScreen) }
}
```

To read `displayHint` in the login screen and show context to the user:

```kotlin
@Composable
override fun Content(params: Params) {
    val navState by selectState<NavigationState>().collectAsState()
    val hint = navState.pendingNavigation?.displayHint

    if (hint != null) {
        Text(text = hint)     // e.g. "Please log in to continue"
    }
    // ... rest of login UI
}
```

To discard a pending navigation explicitly without resuming, use `dispatchAndAwait` so the
state is cleared before any follow-up navigation executes:

```kotlin
scope.launch {
    store.dispatchAndAwait(NavigationAction.ClearPendingNavigation)
    store.navigation { navigateTo(SomeOtherScreen) }
}
```

#### Combining intercept + entry

When both an outer `intercept` and an inner per-graph `entry` are present, the outer guard fires
first. If it returns `Allow`, the inner `entry.access` fires next. A typical layering:

```kotlin
intercept(guard = requireAuth) {           // outer: is user logged in? (suspend auth check)
    graph("content") {
        entry(
            access = { store ->
                val contentLogic = store.selectLogic<ContentLogic>()
                // inner: is content loaded? (suspend data-readiness check)
                if (contentLogic.isReady()) GuardResult.Allow
                else GuardResult.RedirectTo(LoadingScreen)
            },
            route = { store ->
                val contentLogic = store.selectLogic<ContentLogic>()
                if (contentLogic.hasReleases()) ReleasesScreen else NoContentScreen
            }
        )
        screens(ReleasesScreen, NoContentScreen, LoadingScreen)
    }
}
```

#### Guards and deep links

`navigateDeepLink()` resolves aliases and then calls the normal navigation path, so guards fire
exactly the same way as for any other navigation.

| Scenario | intercept | entry.access | entry.route |
|---|---|---|---|
| Navigate to a screen route | Fires | Not evaluated | Not evaluated |
| Navigate to a graph route directly | Fires | Fires | Evaluated |
| `navigateDeepLink()` to a screen route | Fires | Not evaluated | Not evaluated |

`entry.route` is never evaluated for deep links — the deep link already specifies the exact
destination screen, so there is nothing to select.

**Full deep link + guard denied + resume flow:**

```kotlin
// 1. App receives an external deep link to a protected screen.
store.launch {
    store.navigateDeepLink("workspace/invite/abc-123")
}

// 2. The intercept guard fires (requireAuth).
//    checkSession() is a suspend fun — may do a token refresh network call.
//    Guard decides the session is missing → returns PendAndRedirectTo.
//
//    NavigationState.pendingNavigation = PendingNavigation(
//        route  = "workspace/invite/abc-123",
//        params = Params.of("inviteCode" to "abc-123"),
//        displayHint = "Please log in to continue"
//    )
//    User is navigated to LoginScreen.

// 3. User logs in. AuthLogic just navigates to HomeScreen — no pending navigation logic here.
suspend fun onLoginSuccess() {
    dispatch(AuthAction.SetAuthenticated(user))
    navigation { navigateTo(HomeScreen) }
}

// 4. HomeScreen's LaunchedEffect fires. resumePendingNavigation() finds the stored route,
//    clears it from state, and navigates to "workspace/invite/abc-123" with backstack synthesis.
//    Guards are bypassed for the resumed navigation.
//    If the user had opened login directly (no pending), this is a no-op.
@Composable
override fun Content(params: Params) {
    val store = rememberStore()

    LaunchedEffect(Unit) {
        store.resumePendingNavigation()
    }
    // ...
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
5. **Keep navigation logic in screens** - Use `onLifecycleCreated` for data loading, not in Composables
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
    // Preferred: set static start destination (replaces deprecated startScreen)
    fun entry(screen: Screen)

    // Dynamic per-graph entry with optional guard and/or route selector
    fun entry(
        access: NavigationGuard? = null,
        route: RouteSelector? = null,
        loadingScreen: Screen? = null,
        loadingThreshold: Duration = 200.milliseconds
    )

    // Protect a group of graphs with a shared intercept guard
    fun intercept(
        guard: NavigationGuard,
        loadingScreen: Screen? = null,
        loadingThreshold: Duration = 200.milliseconds,
        block: NavigationGraphBuilder.() -> Unit
    )

    @Deprecated("Use entry(screen) instead", ReplaceWith("entry(screen)"))
    fun startScreen(screen: Screen)
    fun startGraph(graphId: String)
    fun screens(vararg screens: Screen)
    fun modals(vararg modals: Modal)
    fun screenGroup(screenGroup: ScreenGroup)
    fun graph(graphId: String, builder: NavigationGraphBuilder.() -> Unit): NavigationGraph
    fun layout(layoutComposable: @Composable (@Composable () -> Unit) -> Unit)
}

// Type aliases
typealias NavigationGuard = suspend (StoreAccessor) -> GuardResult
typealias RouteSelector   = suspend (StoreAccessor) -> Navigatable
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
suspend fun StoreAccessor.navigateDeepLink(route: String, params: Params = Params.empty())

// Resume a pending navigation stored by GuardResult.PendAndRedirectTo.
// No-op if NavigationState.pendingNavigation is null.
suspend fun StoreAccessor.resumePendingNavigation()
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

---

## Migration Guide — Adding Guards to an Existing Setup

This guide walks through upgrading a plain unguarded navigation setup (using `startScreen` / `startGraph`,
no guards) to the current guard system.

### Starting point — original API, no guards

```kotlin
createNavigationModule {
    rootGraph {
        startScreen(SplashScreen)
        screens(SplashScreen, LoginScreen)

        graph("workspace") {
            startScreen(HomeScreen)
            screens(HomeScreen, InviteScreen)
        }

        graph("settings") {
            startScreen(SettingsScreen)
            screens(SettingsScreen, ProfileScreen)
        }
    }
}
```

### Step 1 — Replace `startScreen()` with `entry()`

`startScreen` is deprecated. `entry(screen)` is the direct replacement — same behaviour,
new name:

```kotlin
graph("workspace") {
    entry(HomeScreen)          // was: startScreen(HomeScreen)
    screens(HomeScreen, InviteScreen)
}
```

### Step 2 — Define a guard lambda

A `NavigationGuard` is a **suspend** function — you can call any suspending code inside it,
including network requests, token refreshes, or database lookups via `selectLogic`:

```kotlin
val requireAuth: NavigationGuard = { store ->
    val authLogic = store.selectLogic<AuthLogic>()
    if (authLogic.hasValidSession())    // suspend fun — e.g. reads token from encrypted storage
        GuardResult.Allow
    else
        GuardResult.PendAndRedirectTo(
            navigatable = LoginScreen,
            displayHint = "Please log in to continue"
        )
}
```

### Step 3 — Wrap protected graphs with `intercept { }`

```kotlin
createNavigationModule {
    rootGraph {
        entry(SplashScreen)
        screens(SplashScreen, LoginScreen)

        intercept(guard = requireAuth) {
            graph("workspace") {
                entry(HomeScreen)
                screens(HomeScreen, InviteScreen)
            }

            graph("settings") {
                entry(SettingsScreen)
                screens(SettingsScreen, ProfileScreen)
            }
        }
    }
}
```

### Step 4 — Resume pending navigation from the post-auth landing screen

Auth logic just navigates to `HomeScreen` after login. `HomeScreen` calls `resumePendingNavigation()`
in a `LaunchedEffect` — it is a no-op when nothing is pending, so it is always safe to include:

```kotlin
// AuthLogic — navigate to HomeScreen after login. HomeScreen handles the rest.
suspend fun onLoginSuccess() {
    dispatch(AuthAction.SetAuthenticated(user))
    navigation { navigateTo(HomeScreen) }
}

// HomeScreen — consumes any pending navigation for all flows (login, signup, etc.)
object HomeScreen : Screen {
    override val route = "home"

    @Composable
    override fun Content(params: Params) {
        val store = rememberStore()

        LaunchedEffect(Unit) {
            store.resumePendingNavigation()  // no-op if nothing pending
        }

        // ... rest of UI
    }
}
```

### Step 5 (optional) — Show a hint from pending navigation in the login screen

```kotlin
@Composable
override fun Content(params: Params) {
    val navState by selectState<NavigationState>().collectAsState()
    val hint = navState.pendingNavigation?.displayHint

    if (hint != null) {
        Text(text = hint)
    }
    // ... rest of login UI
}
```

### Step 6 (optional) — Add dynamic entry routing per graph

If a graph should route to different screens depending on app state, add a dynamic `entry`:

```kotlin
graph("content") {
    entry(
        access = { store ->
            val state = store.selectState<ContentState>().value
            if (!state.isReady) GuardResult.RedirectTo(LoadingScreen)
            else GuardResult.Allow
        },
        route = { store ->
            val state = store.selectState<ContentState>().value
            if (state.hasReleases) ReleasesScreen else NoContentScreen
        }
    )
    screens(ReleasesScreen, NoContentScreen, LoadingScreen)
}
```
