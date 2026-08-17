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
bytecode carries no injected calls, and no tracing runtime dependency is added
to variants that are not instrumented. reaktiv-tracing-annotations stays a plain
implementation dependency: it is a few-KB annotations-only artifact so main
sources may carry @NoTrace.

Tracing is off unless something activates it. Declaring `buildTypes` is one way,
and it suits an application module that owns real variants. A library module has
no build types of its own and cannot see which variant its consumer is building,
so it keys on the invocation and on the configuration Xcode is building:

```kotlin
reaktivTracing {
    enableForTasksMatching("staging")
    conflictsWithTasksMatching("production")
    enableForXcodeConfigurations("Debug")
}
```

`conflictsWithTasksMatching` matters for any module with a single compilation per
target, which includes every `com.android.kotlin.multiplatform.library`. Such a
module produces one artifact per invocation, so `./gradlew testStagingUnitTest
assembleProduction` would hand instrumented code to the production consumer.
Declaring the conflicting pattern turns that into a configuration-time failure.

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

## Crash capture only (no server)

To intercept crashes without any server or networking, install no service. The
crash handler and full-session capture are on by default, so the debug seam is
just `createToolingModule` with no `install` block:

```kotlin
// src/debug/java/.../tooling/ToolingAttachment.kt
fun toolingModule(context: Context): Module<*, *>? = createToolingModule(
    config = IntrospectionConfig(
        clientName = "${Build.MANUFACTURER} ${Build.MODEL}",
        platform = "Android ${Build.VERSION.RELEASE}",
        installCrashHandler = true
    ),
    platformContext = PlatformContext(context)
)
```

On an uncaught exception this writes a crash session file to the device. Export
it on demand with `exportCapturedSession(store)`, which calls
`ToolingLogic.exportSessionToDownloads()`. Sensitive state keys (password,
token, secret and similar) are redacted by default, so the crash file is safe
to share. Set `installCrashHandler = false` to opt out.

Debug-only screens (debug menus) live in the debug source set and enter the
navigation graph via `screens(*toolingScreens().toTypedArray())`.

Verification: `assembleRelease` compiling is the proof that release contains no
tooling on the classpath.
