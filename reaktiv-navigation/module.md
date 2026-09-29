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
    override val enterTransition = NavTransition.Fade
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

## Routes and Path Params

A route is a path template, and it works like a route in any web router. Its full path is the
route of every graph around it followed by its own route, so `ProfileScreen` registered in
`graph("main")` lives at `main/profile/{userId}`.

- A `{name}` segment is a path param. Its value is always text, taken from the path and decoded
  once, so `main/profile/Ada%20L` gives `userId = "Ada L"`. A `+` in a path stays a `+`.
- A segment can mix text and a placeholder, such as `item-{id}`, and a route may start with a
  placeholder, such as `{slug}`.
- A route without placeholders wins over a parameterized one. Between parameterized routes, the one
  with a static segment earliest wins, so `en/{page}` beats `{lang}/home` for `en/home`.

Navigate with the screen object and its values instead of writing the graph path by hand, and ask
for the location when you need a link to it:

```kotlin
store.navigation { navigateTo(ProfileScreen, "userId" to 42) }

val link = store.locationOf(ProfileScreen, "userId" to 42)
```

Here `link` is `main/profile/42`. Navigating to a screen without a value for every path param
throws `MissingPathParamsException`, which names the missing keys.

Each back-stack entry carries three forms of where it is:

- `entry.path` is the template, `main/profile/{userId}`. It identifies the screen.
- `entry.location` is the concrete path with values encoded, `main/profile/Ada%20L`. Navigating to
  it, or passing it to `popUpTo`, reaches that exact entry.
- `NavigationState.currentFullPath` is the readable form of the current location,
  `main/profile/Ada L`, for display.

Params that are not in the route, including typed objects passed with `put<T>()`, travel with the
entry inside the app. They are never part of a location or URL, so a screen that must be reachable
from a link should carry an id in its path and load the object from it.

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
// Navigate to a screen with its path params
store.navigation {
    navigateTo(ProfileScreen, "userId" to "42")
}

// Navigate back
store.navigation {
    navigateBack()
}

// Navigate and trim the back-stack
store.navigation {
    navigateTo("main/dashboard")
    popUpTo("home", inclusive = false)
}

// Navigate to a modal overlay
store.navigation {
    navigateTo(SettingsModal)
}
```

---

## Deep Links

A deep link names a full path, such as `workspace/invite/abc123`, and lands on it with the screens
above it in the graph built beneath it. Register aliases in `deepLinkAliases { }` for external URL
shapes that differ from your routes. An alias pattern uses the same `{name}` placeholders as a route.

```kotlin
val navigationModule = createNavigationModule {
    rootGraph { /* ... */ }
    deepLinkAliases {
        alias(
            pattern = "artist/invite/{token}",
            targetRoute = "workspace/invite/{token}"
        )
    }
}

// Handle an incoming URI (e.g. from Activity.onCreate or a push notification handler).
// Pass it in its encoded form, on Android Uri.encodedPath and Uri.encodedQuery.
store.navigation { navigateDeepLink("artist/invite/abc123") }
```

---

## Running in a Browser

A wasm app in a top-level browser window behaves the way people expect from a web page, with no
extra wiring:

- the address bar names the current screen, so any screen can be bookmarked or shared
- every navigation adds a browser history entry, and the back and forward buttons move through them
- a reload restores the exact stack, including params such as numbers and objects
- a shared link opens the screen it names with its parent screens beneath it, while browser back
  returns to the page the visitor came from

### URL styles

Clean paths (`https://example.com/profile/7`) are the default. The server has to answer every app
path with the app's `index.html`, or a reload or a shared link gets a 404 from the server before the
app can load. Most hosts need one rule for that:

```
# nginx
location / {
    try_files $uri $uri/ /index.html;
}

# Netlify _redirects
/*  /index.html  200

# Firebase hosting, firebase.json
"rewrites": [{ "source": "**", "destination": "/index.html" }]
```

The Kotlin dev server started by `wasmJsBrowserDevelopmentRun` needs the same, through a file in the
app module at `webpack.config.d/devServer.js`:

```js
config.devServer = config.devServer || {};
config.devServer.historyApiFallback = true;
```

The page also needs a `<base href>`, for example `<base href="/">`, so its scripts and wasm files load
from nested paths and the app knows where it lives.

```kotlin
val navigationModule = createNavigationModule {
    browserHistory {
        basePath = "/app/"
        documentTitle = { title -> if (title == null) "My app" else "$title - My app" }
    }
    rootGraph { /* ... */ }
}
```

`basePath` defaults to the path of `document.baseURI`, so a `<base href="/app/">` tag is usually
enough on its own. `documentTitle` turns the current screen's `titleResource` into the page title,
which is also what the browser shows in its history menu.

Hash URLs (`https://example.com/#/profile/7`) work on any static host, including hosts that cannot
rewrite paths such as GitHub Pages, because the server only ever sees the page itself. The app then
owns only the part after `#`, and the query string the page was opened with is left alone.

```kotlin
browserHistory {
    urlStyle = UrlStyle.Hash
}
```

Prefer paths when the app signs in through OAuth, since providers often refuse redirect addresses
containing `#` and some return their result in the fragment, and when the same address should also
open the Android or iOS app.

### What goes into the address

Path params always appear in the path. Other params appear as query parameters when they are strings,
numbers or booleans. Objects and lists never appear in the URL. They are kept in the browser history
entry instead, so back, forward and reload restore them, but a copied link does not carry them. List
keys in `hiddenUrlParams` to keep simple params out of the URL as well. Keys that look like secrets,
such as `token`, `password` or `secret`, never reach the URL or the browser history entry.

With paths the whole query string belongs to the screen. A parameter the page was opened with, such
as `?campaign=spring`, becomes a param of the first screen and leaves the address on the next
navigation.

### Back that a screen refuses

A screen whose `dismissal.back` is `Ignore` or `Run` holds the first browser back. A `Run` handler
runs, for example to ask whether to discard changes. Browsers do not let a page trap its visitors, so
a second back without any click or key press in between goes through. A system layer alert on top of
a screen is dismissed by browser back and the screen stays.

### Guarded screens and sign in

A guard that answers `PendAndRedirectTo` keeps the pending navigation across a reload and across a
sign in flow that leaves the page, such as an OAuth redirect. `resumePendingNavigation()` after the
visitor returns lands where they were going. Param keys that look like secrets are not kept.

### Things to know

- Do not persist `NavigationState` yourself on the web. The browser history already restores it,
  and a stale persisted stack would compete with the address the visitor opened.
- Only one store per page drives the browser history. An app inside an iframe leaves the parent's
  history alone unless it sets `mode = BrowserHistoryMode.Always`, and `BrowserHistoryMode.Off`
  turns the feature off entirely.
- Browsers never let a page delete history entries, so after going back the forward entries stay
  until the next navigation replaces them.
- The app draws to a canvas, so there are no real links to open in a new tab.
- Chrome may skip history entries that were added without any click or key press when the visitor
  presses back. Navigation in response to user input is never affected.
- On touch devices the browser owns the screen edges, so an edge swipe goes back through the browser
  and the app's own swipe starts further in. A mouse drag never starts a back swipe.

---

## Link Map

Every place the app can navigate to can be listed without reading the navigation DSL, for example
when marketing asks which universal links or deep links exist.

```kotlin
val map: NavigationLinkMap = navigationModule.linkMap()

map.routes.forEach { route ->
    println("${route.path} opens ${route.screen} (${route.access})")
}
```

`linkMap()` returns a serializable description of the module:
- `routes`: every registered path with its screen, its own route, its graph, its path params, the
  guards in front of it and whether a link can reach it
- `graphs`: every graph with its path, its parent and how it starts
- `aliases`: every deep link alias with the route it leads to
- `webPrefix`: the browser address prefix when browser history is active

`access` tells whether a link can land on a route:

| Access | Meaning |
|---|---|
| `Linkable` | A link opens this route |
| `Fallback` | The not found screen, shown for links that match nothing |
| `Internal` | System layer screens, the loading modal and the crash screen, which no link reaches |
| `Shadowed` | Another route with the same shape wins, so links never land here |

### App link files

iOS universal links and Android App Links need files on the website and in the app that list which
paths open the app. `appLinkFiles` generates them from the map:

```kotlin
val files = navigationModule.linkMap().appLinkFiles(
    AppLinksConfig(
        host = "example.com",
        appleAppIds = listOf("ABCDE12345.com.example.app"),
        androidPackage = "com.example.app",
        androidCertFingerprints = listOf("14:6D:E9:83:..."),
        paths = setOf("home/leaderboard/player/*", "home/news/*"),
        deepLinkScheme = "myapp"
    )
)

File("site/.well-known/apple-app-site-association").writeText(files.appleAppSiteAssociation!!)
File("site/.well-known/assetlinks.json").writeText(files.assetLinks!!)
println(files.androidManifestIntentFilter)
println(files.androidDeepLinkIntentFilter)
```

- `appleAppSiteAssociation` lists every chosen path for each iOS app ID. It is generated when at least
  one app ID is given.
- `assetLinks` ties the site to the Android package and its signing certificate fingerprints. With
  `androidDynamicPaths` (the default) it also lists the paths, which Android 15 and newer read as
  dynamic app links. It is generated when a package is given.
- `androidManifestIntentFilter` is the `<intent-filter android:autoVerify="true">` to add to the
  activity that handles the links, with one `<data>` entry per path.
- `androidDeepLinkIntentFilter` is a plain `<intent-filter>` for custom scheme deep links such as
  `myapp://example.com/home/news`. It is generated when `deepLinkScheme` is given, uses `deepLinkHost`
  or else `host`, and lists the paths without the web base path, since the app receives them as they are.
  Android does not verify custom schemes, so another app can claim the same one.
- Both filters are `null` when no path is chosen, because a filter without paths opens the app for every
  address on the host.

Candidate paths are every linkable route, every graph that has a start, and every deep link alias
whose pattern is a web address or a plain path. Path params become `*` and the web base path (from
`browserHistory`, or `basePath`) is put in front. `paths` picks which of them to include, by the
`path` of an `AppLinkPath`, and `null` includes them all. `files.candidates` always lists every path,
so a tool can offer the full choice.

Running this in a JVM test or a small script keeps the files in step with the navigation graph.

`openLink` opens a link the way a universal link is opened, and reports what happened:

```kotlin
when (val outcome = store.openLink("home/leaderboard/player/{playerId}", mapOf("playerId" to "42"))) {
    is LinkOutcome.Landed -> println("landed on ${outcome.location}")
    is LinkOutcome.Redirected -> println("a guard sent it to ${outcome.to}")
    is LinkOutcome.MissingParams -> println("fill in ${outcome.names}")
    else -> println(outcome)
}
```

The link can be a route template, a concrete path, a path with a query, an alias address such as
`myapp://example.com/p/42` or a web address under the app's base path. Parent screens are built
beneath it, guards run and the back stack is replaced.

The DevTools Nav tab draws the map and opens links on a connected device when the app installs
`NavigationLinks` from `reaktiv-navigation-tooling`.

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
