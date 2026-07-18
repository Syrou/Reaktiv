# Attaching Reaktiv tooling on Android (debug/staging only)

Production builds must contain zero tooling code. The mechanism is the Android
variant system, never BuildConfig flags.

## Dependencies (app build.gradle.kts)

```kotlin
dependencies {
    implementation(project(":reaktiv-core"))
    implementation(project(":reaktiv-navigation"))
    implementation(project(":reaktiv-tracing-annotations"))
    debugImplementation(project(":reaktiv-introspection"))
    debugImplementation(project(":reaktiv-devtools"))
}

reaktivTracing {
    buildTypes.set(setOf("debug"))
}
```

The tracing compiler plugin only instruments the listed build types, so release
bytecode carries no injected calls. reaktiv-tracing-annotations stays a plain
implementation dependency: it is a few-KB annotations-only artifact so main
sources may carry @NoTrace.

## The seam

Main sources reference tooling only through functions typed in core/navigation
terms, implemented per variant with identical signatures:

```kotlin
// src/debug/java/.../tooling/ToolingAttachment.kt
fun toolingModule(context: Context): Module<*, *>? = createToolingModule(
    config = IntrospectionConfig(platform = "Android ${Build.VERSION.RELEASE}", ...),
    platformContext = PlatformContext(context)
) {
    install(DevToolsService(DevToolsConfig(serverUrl = "ws://host:8080/ws", autoConnect = false)))
}

fun toolingScreens(): List<Screen> = listOf(DevToolsScreen)

suspend fun exportCapturedSession(store: StoreAccessor): String? =
    store.selectLogic<ToolingLogic>().exportSessionToDownloads()
```

```kotlin
// src/release/java/.../tooling/ToolingAttachment.kt
fun toolingModule(context: Context): Module<*, *>? = null
fun toolingScreens(): List<Screen> = emptyList()
suspend fun exportCapturedSession(store: StoreAccessor): String? = null
```

Main wires the seam:

```kotlin
val store = createStore {
    module(navigationModule)
    toolingModule(applicationContext)?.let { module(it) }
}
```

Debug-only screens (debug menus) live in the debug source set and enter the
navigation graph via `screens(*toolingScreens().toTypedArray())`.

Verification: `assembleRelease` compiling is the proof that release contains no
tooling on the classpath.
