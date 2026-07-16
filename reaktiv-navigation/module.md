# Module reaktiv-navigation

The Navigation module provides a type-safe, state-driven navigation system for Kotlin
Multiplatform projects. It integrates with Reaktiv Core to manage screen transitions,
back-stack, modals, deep linking, and navigation guards — all as pure state.

## Setup

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.syrou:reaktiv-navigation:<version>")
}
```

---

## Defining Screens

A [Screen] is a single destination with a route, optional transitions, and a Composable
`Content` function.

```kotlin
object HomeScreen : Screen {
    override val route = "home"
    override val enterTransition = NavTransition.FadeIn
    override val exitTransition = NavTransition.FadeOut

    @Composable
    override fun Content(params: Params) {
        Text("Home")
    }
}

object ProfileScreen : Screen {
    override val route = "profile/{userId}"
    override val enterTransition = NavTransition.SlideInRight
    override val exitTransition = NavTransition.SlideOutLeft

    @Composable
    override fun Content(params: Params) {
        val userId = params.getString("userId")
        ProfileView(userId)
    }
}
```

---

## Building the Navigation Module

Use `createNavigationModule { rootGraph { ... } }` to wire all screens, graphs, and guards
into a single [NavigationModule] ready to be registered in the store.

```kotlin
val navigationModule = createNavigationModule {
    rootGraph {
        start(HomeScreen)
        screens(HomeScreen)

        graph("main") {
            start(HomeScreen)
            screens(HomeScreen, ProfileScreen)
        }
    }

    notFoundScreen(NotFoundScreen)
}

val store = createStore {
    module(navigationModule)
}
```

---

## Navigation Guards with `intercept`

`intercept` wraps child graphs and screens inside a guard. The guard is evaluated before
navigation is committed to any route inside the block. Return `GuardResult.Allow` to permit
the navigation, or `GuardResult.RedirectTo(screen)` to redirect.

```kotlin
val navigationModule = createNavigationModule {
    rootGraph {
        start(HomeScreen)
        screens(HomeScreen, LoginScreen)

        // Routes outside intercept are publicly accessible
        // Routes inside intercept require the guard to pass
        intercept(
            guard = { store ->
                val authState = store.selectState<AuthState>().first()
                if (authState.isLoggedIn) GuardResult.Allow
                else GuardResult.RedirectTo(LoginScreen)
            }
        ) {
            graph("workspace") {
                start(DashboardScreen)
                screens(DashboardScreen, SettingsScreen)
            }
            graph("admin") {
                start(AdminScreen)
                screens(AdminScreen)
            }
        }
    }
}
```

---

## Dynamic Entry Points

Use `start(route = { store -> ... })` instead of `start(screen)` when the start screen
depends on runtime state (e.g. onboarding vs. dashboard).

```kotlin
graph("content") {
    start(
        route = { store ->
            val state = store.selectState<ContentState>().first()
            if (state.hasContent) DashboardScreen else EmptyScreen
        }
    )
    screens(DashboardScreen, EmptyScreen)
}
```

---

## Navigating

Use the `navigation { }` DSL extension on [StoreAccessor] from within a coroutine scope.

```kotlin
// Navigate to a screen with params
store.navigation {
    navigateTo(ProfileScreen.route)
    param("userId", "42")
}

// Navigate back
store.navigateBack()

// Navigate and trim the back-stack
store.navigation {
    navigateTo("dashboard")
    popUpTo("home", inclusive = false)
}

// Navigate to a modal overlay
store.navigation {
    navigateTo(SettingsModal.route)
}
```

---

## Deep Links

Register deep-link aliases in `deepLinkAliases { }` and dispatch them from any entry point.

```kotlin
val navigationModule = createNavigationModule {
    rootGraph { /* ... */ }
    deepLinkAliases {
        alias(
            pattern = "artist/invite",
            targetRoute = "workspace/invite/{token}"
        ) { params ->
            Params.of("token" to (params["token"] as? String ?: ""))
        }
    }
}

// Handle an incoming URI (e.g. from Activity.onCreate or a push notification handler)
store.navigation {
    navigateDeepLink("myapp://workspace/invite/abc123")
}
```

---

## Interactive Gestures

`NavigationRender` ships a complete interactive gesture system with no wiring required:

- **Edge-swipe back** — a horizontal drag from the screen edge scrubs the pop transition,
  iOS-style. On Android the system predictive-back gesture drives the same preview. The
  edge swipe wins over horizontally scrollable content, matching native iOS edge-pan.
- **Swipe to dismiss** — vertically presented screens (e.g. `SlideUpBottom`) and modals
  dismiss with a downward drag. Scrollable content hands off to the gesture when scrolled
  to the top; a default grabber indicator marks a dismiss zone at the top of the screen
  content that always dismisses, even over drag-consuming content such as pull-to-refresh.
- **Premounted reveals** — the screen beneath a dismissible sheet stays composed (modal and
  iOS sheet semantics), so dismiss gestures reveal it instantly and its state survives.
  Regular push/pop navigation keeps Compose semantics: the previous screen is disposed and
  its effects re-run when navigated back to.

Per-navigatable knobs (all with sensible defaults):

```kotlin
object FilterSheet : Screen {
    override val route = "filters"
    override val enterTransition = NavTransition.SlideUpBottom
    override val exitTransition = NavTransition.SlideOutBottom

    override val backGestureEnabled = true
    override val swipeToDismiss = true
    override val showsDismissIndicator = true
    override val onDismissRequest: (suspend StoreAccessor.() -> Unit) = {
        navigation { popUpTo("home") }
    }

    @Composable
    override fun Content(params: Params) { FilterContent() }
}
```

`onDismissRequest` is the unified dismiss funnel: edge-swipe commits, swipe-down commits,
tap-outside on modals, and system back all route through it when set.

---

## Reading Navigation State

Observe [NavigationState] to react to the current screen, back-stack depth, or open modals.

```kotlin
store.selectState<NavigationState>().collect { navState ->
    println("Current screen: ${navState.currentEntry.navigatable.route}")
    println("Back-stack depth: ${navState.backStack.size}")
    println("Modal open: ${navState.activeModalContexts.isNotEmpty()}")
}
```

---

## Key Types

- [NavigationModule] / `createNavigationModule` — DSL entry point
- [NavigationLogic] — `navigate`, `navigateBack`, `popUpTo`, `clearBackStack`, `navigateDeepLink`
- [NavigationState] — full navigation state (currentEntry, backStack, modals)
- [Screen] — destination with route, transitions, and Composable content
- [Modal] — overlay destination rendered above the current screen
- [NavigationGraph] — hierarchical grouping of screens with a start destination
- [NavigationEntry] — a resolved position in the back-stack (navigatable + params)
- [NavTransition] — sealed class with 18 named transition variants plus `Custom` and `None`
- [ModalContext] — tracks an open modal and its underlying screen
