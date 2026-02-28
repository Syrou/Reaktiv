# Module reaktiv-devtools

Real-time debugging and state inspection tools for Reaktiv applications. DevTools provides a
WebSocket-based bridge between your app and a browser-based UI for watching state changes,
replaying action streams, and importing crash sessions.

## Architecture

```
Your App  ──WebSocket──►  DevTools Server (native binary)
                                   │
                          WebSocket▼
                          DevTools UI  (WASM, runs in browser)
```

## Setup

### 1. Add the dependency

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.syrou:reaktiv-devtools:<version>")
}
```

### 2. Configure and add to your store

```kotlin
val introspectionConfig = IntrospectionConfig(
    clientName = "My App",
    platform = "${Build.MANUFACTURER} ${Build.MODEL}"  // any descriptive string
)
val sessionCapture = SessionCapture()

val store = createStore {
    module(CounterModule)
    module(navigationModule)

    module(IntrospectionModule(introspectionConfig, sessionCapture, platformContext))

    module(DevToolsModule(
        config = DevToolsConfig(
            introspectionConfig = introspectionConfig,
            serverUrl = "ws://192.168.1.100:8080/ws",
            defaultRole = ClientRole.PUBLISHER
        ),
        scope = lifecycleScope,
        sessionCapture = sessionCapture
    ))
}
```

---

## Running the Server and UI

Build and run the native server for your platform:

```bash
# Linux
./gradlew :reaktiv-devtools:linkDebugExecutableLinuxX64
./reaktiv-devtools/build/bin/linuxX64/debugExecutable/reaktiv-devtools.kexe

# macOS
./gradlew :reaktiv-devtools:linkDebugExecutableMacosX64

# Windows
./gradlew :reaktiv-devtools:linkDebugExecutableMingwX64
```

Build the WASM browser UI, then serve it with any static file server:

```bash
./gradlew :reaktiv-devtools:wasmJsBrowserDevelopmentExecutableDistribution
# Output: reaktiv-devtools/build/dist/wasmJs/developmentExecutable/
```

---

## WebSocket URL by Target

```kotlin
serverUrl = "ws://10.0.2.2:8080/ws"       // Android Emulator → host machine
serverUrl = "ws://192.168.1.100:8080/ws"   // Real device on same WiFi
serverUrl = "ws://localhost:8080/ws"        // iOS Simulator
```

---

## Runtime Connection Control

Connect, disconnect, or reconnect without rebuilding:

```kotlin
store.dispatch(DevToolsAction.Connect("ws://192.168.1.100:8080/ws"))
store.dispatch(DevToolsAction.Disconnect)
store.dispatch(DevToolsAction.Reconnect)
```

---

## Ghost Sessions and Crash Capture

Export a recorded session from the DevTools UI and import it later for offline debugging
or post-mortem analysis.

```kotlin
// Install crash handler so sessions are saved on crash
val store = createStore {
    module(IntrospectionModule(config, sessionCapture, platformContext))
    module(CrashModule(platformContext, sessionCapture))
}

// Export a session manually (e.g. from a debug menu)
val devToolsMiddleware = store.getMiddleware<DevToolsMiddleware>()
val json = devToolsMiddleware.exportSessionJson()
// Save or share `json` however is appropriate for your platform
```

Crash sessions are saved to the device's Downloads (Android) or Documents (iOS) folder.
Import them in the DevTools UI as *ghost sessions* and use time travel to replay events.

---

## Tracing Integration

Add the tracing Gradle plugin to automatically instrument all `ModuleLogic` methods.
The DevTools UI shows method names, parameters, execution duration, and return values.

```kotlin
// build.gradle.kts
plugins {
    id("io.github.syrou.reaktiv.tracing") version "<version>"
}
```

Use `@NoTrace` to exclude helpers, and `@Sensitive` / `@PII` to obfuscate parameter
values in the trace output — see `reaktiv-tracing-annotations` for details.

---

## Key Types

- `DevToolsModule` — the Reaktiv module that connects your app to the server
- `DevToolsConfig` — configuration (server URL, role, capture flags)
- `IntrospectionConfig` — shared identity used by both DevTools and Introspection modules
- `SessionCapture` — records actions and logic events for export or crash reports
- `IntrospectionModule` — manages session recording and platform context
- `CrashModule` — installs a crash handler that saves the session to disk
- `DevToolsAction` — `Connect`, `Disconnect`, `Reconnect` dispatch targets
- `ClientRole` — `PUBLISHER`, `LISTENER`, `ORCHESTRATOR`
