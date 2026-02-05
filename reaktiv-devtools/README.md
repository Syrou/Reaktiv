# Reaktiv DevTools

Real-time debugging and state inspection tools for Reaktiv applications. DevTools provides a WebSocket-based connection between your app and a browser-based UI for monitoring state changes, replaying sessions, and debugging issues.

## Features

- **Real-time State Inspection** - Watch state changes as they happen
- **Action Stream** - See all dispatched actions with timestamps and payloads
- **Logic Method Tracing** - Track logic method calls with parameters and results (requires tracing plugin)
- **Time Travel** - Scrub through state history and replay to any point
- **Ghost Sessions** - Import/export recorded sessions for offline debugging
- **Session Capture** - Automatic session recording for crash reports
- **Multi-device Support** - Connect multiple devices with publisher/listener roles
- **Crash Capture** - Capture session state when crashes occur (Android)

## Architecture

```
┌─────────────────┐     WebSocket      ┌─────────────────┐
│   Your App      │◄──────────────────►│  DevTools Server │
│  (Android/iOS)  │                    │    (Native)      │
└─────────────────┘                    └────────┬────────┘
                                                │
                                       WebSocket│
                                                ▼
                                       ┌─────────────────┐
                                       │  DevTools UI    │
                                       │  (WASM Browser) │
                                       └─────────────────┘
```

## Setup

### 1. Add DevTools to Your App

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.syrou:reaktiv-devtools:<version>")
}
```

### 2. Configure DevTools Module

```kotlin
val store = createStore {
    // Your modules
    module(CounterModule)
    module(navigationModule)

    // Add DevTools module
    module(DevToolsModule(
        config = DevToolsConfig(
            serverUrl = "ws://192.168.1.100:8080/ws",  // Your server IP
            clientName = "My App",
            platform = "${Build.MANUFACTURER} ${Build.MODEL}",
            defaultRole = DefaultDeviceRole.PUBLISHER  // Auto-assign as publisher
        ),
        scope = lifecycleScope
    ))
}
```

### 3. Run the DevTools Server

Build the native server:
```bash
# Linux
./gradlew :reaktiv-devtools:linkDebugExecutableLinuxX64

# macOS
./gradlew :reaktiv-devtools:linkDebugExecutableMacosX64

# Windows
./gradlew :reaktiv-devtools:linkDebugExecutableMingwX64
```

Run the server:
```bash
./reaktiv-devtools/build/bin/linuxX64/debugExecutable/reaktiv-devtools.kexe
```

### 4. Access the WASM UI

Build the UI:
```bash
./gradlew :reaktiv-devtools:wasmJsBrowserDevelopmentExecutableDistribution
```

The built files are in `reaktiv-devtools/build/dist/wasmJs/developmentExecutable/`. Serve them with any static file server and open in a browser.

## Configuration Options

```kotlin
data class DevToolsConfig(
    val serverUrl: String? = null,           // WebSocket URL (null = don't auto-connect)
    val clientName: String = "Client-<uuid>", // Display name in DevTools UI
    val clientId: String = "<uuid>",          // Unique client identifier
    val platform: String,                     // Platform description
    val enabled: Boolean = true,              // Enable/disable DevTools
    val allowActionCapture: Boolean = true,   // Capture dispatched actions
    val allowStateCapture: Boolean = true,    // Capture state changes
    val defaultRole: DefaultDeviceRole = DefaultDeviceRole.NONE,  // Auto-assign role
    val enableSessionCapture: Boolean = true, // Enable session recording
    val maxCapturedActions: Int = 1000,       // Max actions to retain
    val maxCapturedLogicEvents: Int = 2000    // Max logic events to retain
)

enum class DefaultDeviceRole {
    PUBLISHER,  // Source of state (your app)
    LISTENER,   // Receives state sync (other devices)
    NONE        // No auto-assignment
}
```

## Server URL Examples

```kotlin
// Android Emulator connecting to host machine
serverUrl = "ws://10.0.2.2:8080/ws"

// Real device on same WiFi
serverUrl = "ws://192.168.1.100:8080/ws"  // Use your computer's IP

// iOS Simulator
serverUrl = "ws://localhost:8080/ws"
```

## Ghost Sessions

Ghost sessions allow you to export a recorded session and import it later for debugging.

### Export a Session

In the DevTools UI, click the download icon when a publisher is selected to export the current session as JSON.

### Import a Session

1. Click the upload icon in the device panel
2. Select a ghost session JSON file (or paste the content)
3. The ghost device appears in the device list as a virtual publisher
4. Use time travel to scrub through the recorded events
5. Connected listeners will receive state sync during playback

### Crash Capture (Android)

```kotlin
// Set up crash handler to capture session on crash
val crashHandler = AndroidCrashHandler(
    context = applicationContext,
    sessionCapture = devToolsMiddleware.getSessionCapture()
)
crashHandler.install()

// Crash sessions are saved to app's filesDir as JSON
// Import them as ghost sessions for post-mortem debugging
```

## Device Roles

- **Publisher** - The source of state. Actions and state changes are broadcast to listeners.
- **Listener** - Receives state sync from the publisher. Useful for mirroring state on another device.
- **Orchestrator** - The DevTools UI. Can send time travel commands and control playback.

Only one publisher is allowed at a time. When a new device becomes publisher, any existing ghost publisher is automatically removed.

## Time Travel

When time travel is enabled:
1. Actions stop being captured from the publisher
2. You can scrub through the action history
3. Each position's state is synced to connected listeners
4. Playback controls: play/pause, speed (0.5x-10x), skip forward/back

## Tracing Integration

For enhanced debugging, enable the tracing plugin:

```kotlin
// build.gradle.kts
plugins {
    id("io.github.syrou.reaktiv.tracing") version "<version>"
}
```

This automatically instruments all `ModuleLogic` methods, showing:
- Method name and parameters
- Execution duration
- Return values or exceptions
- Source file location with GitHub links

## API Reference

### DevToolsMiddleware

```kotlin
// Export current session as JSON
val json: String? = devToolsMiddleware.exportSessionJson()

// Export session with crash info
val crashJson: String? = devToolsMiddleware.exportCrashSessionJson(throwable)

// Access session capture directly
val capture: SessionCapture? = devToolsMiddleware.getSessionCapture()

// Clean up resources
devToolsMiddleware.cleanup()
```

### Runtime Connection Control

```kotlin
// Connect to server at runtime
store.dispatch(DevToolsAction.Connect("ws://192.168.1.100:8080/ws"))

// Disconnect
store.dispatch(DevToolsAction.Disconnect)

// Reconnect to last server
store.dispatch(DevToolsAction.Reconnect)
```

## Troubleshooting

### Device not appearing in list
- Check the server console for connection logs
- Verify the WebSocket URL is correct
- Ensure your device can reach the server IP

### State not syncing to listeners
- Verify the publisher is selected
- Check that listeners are assigned to the publisher
- The DevTools UI should be assigned as orchestrator

### Ghost import not working
- Check browser console for errors
- Verify the JSON file is valid
- Large files (>1MB) may fail - the session data stays local, only metadata is sent to server
