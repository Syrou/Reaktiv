# Module reaktiv-devtools

Real-time debugging and state inspection tools for Reaktiv applications. DevTools provides a
WebSocket-based bridge between your app and a browser-based UI for watching state changes,
replaying action streams, and importing crash sessions.

## Architecture

```
Your App  --WebSocket-->  DevTools Server (native binary)
                                   |
                         WebSocket v
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

### 2. Add the tooling module to your store

```kotlin
val store = createStore {
    module(CounterModule)
    module(navigationModule)

    module(
        createToolingModule(
            config = IntrospectionConfig(
                clientName = "My App",
                platform = "Android ${Build.VERSION.RELEASE}"
            ),
            platformContext = PlatformContext(applicationContext)
        ) {
            install(
                DevToolsService(
                    DevToolsConfig(
                        serverUrl = "ws://10.0.2.2:8080/ws",
                        defaultRole = ClientRole.PUBLISHER
                    )
                )
            )
        }
    )
}
```

`PlatformContext()` takes no argument outside Android. Crash capture, the stall watchdog and
logic tracing are on by default and can be turned off through `IntrospectionConfig`.

### 3. Optional: capture network traffic

Install the Ktor plugin on the client your app uses, and requests appear on the same timeline as
actions and logic calls:

```kotlin
val httpClient = HttpClient(OkHttp) {
    install(ReaktivNetworkInspection)
}
```

See the `reaktiv-network-ktor` module documentation for configuration, redaction and replay.

---

## Running the Server and UI

One command builds the WASM UI and serves it together with the WebSocket endpoint on port 8080:

```bash
./gradlew :reaktiv-devtools:runDevToolsServer
```

Open `http://localhost:8080`. Add `-Pport=8081` to use a different port, and point the client's
`serverUrl` at the same one. To skip the WASM build when you only need the transport:

```bash
./gradlew :reaktiv-devtools:runDevToolsServerHeadless
```

To produce standalone native binaries instead, for example to run the server on another machine:

```bash
./gradlew :reaktiv-devtools:buildDevToolsServerFast   # current platform
./gradlew :reaktiv-devtools:buildDevToolsServer       # linux, macOS and windows
```

Each binary takes the UI directory as an optional argument, and serves only the WebSocket
endpoint without it.

---

## WebSocket URL by Target

```kotlin
serverUrl = "ws://10.0.2.2:8080/ws"       // Android Emulator -> host machine
serverUrl = "ws://192.168.1.100:8080/ws"   // Real device on same WiFi
serverUrl = "ws://localhost:8080/ws"        // iOS Simulator
```

### iOS Info.plist requirements

The DevTools transport is cleartext `ws://`, which App Transport Security blocks by
default. A debug-only Info.plist must opt in, otherwise the connection fails with no
obvious cause:

```xml
<key>NSAppTransportSecurity</key>
<dict>
    <key>NSAllowsLocalNetworking</key>
    <true/>
</dict>
```

`NSAllowsLocalNetworking` covers `localhost` and private-range addresses, so it is
enough for both the simulator and a device on the same WiFi. Connecting to a LAN
address from a physical device additionally triggers the iOS local network permission
prompt, which needs a purpose string:

```xml
<key>NSLocalNetworkUsageDescription</key>
<string>Connects to the Reaktiv DevTools server during development.</string>
```

Keep both keys out of release builds. The per-configuration wiring in
`docs/tooling-attachment-ios.md` keeps the dependency itself debug-only.

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
// Install the crash handler so sessions are saved on crash
CrashHandler(platformContext, sessionCapture).install()

// Export a session manually (e.g. from a debug menu)
store.launch {
    val json = sessionCapture.exportSession()
    // Save or share `json` however is appropriate for your platform
}
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

- `createToolingModule` builds the Reaktiv module that hosts the tooling services
- `DevToolsService` is the service that connects your app to the server
- `DevToolsConfig` — configuration (server URL, role, capture flags)
- `IntrospectionConfig` sets client identity and what gets captured
- `SessionCapture` — records actions and logic events for export or crash reports
- `CrashHandler` — installs a crash handler that saves the session to disk
- `ClientRole` — `PUBLISHER`, `LISTENER`, `ORCHESTRATOR`
