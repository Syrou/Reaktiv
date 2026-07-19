# Reaktiv DevTools {{VERSION}}

The DevTools server plus a browser UI for inspecting Reaktiv apps: live state and
action streaming, time-travel, performance diagnostics, and importing crash
session files exported from a device.

## Run the server

### Linux/macOS
```bash
./reaktiv-devtools resources
```

### Windows
```cmd
reaktiv-devtools.exe resources
```

Then open the URL shown in the console (default: http://localhost:8080). To use a
custom port:

```bash
./reaktiv-devtools resources --port 3000
```

## Connect your app

Tooling attaches to your `Store` through one module, wired behind a debug-only
seam so no tooling code ships in release.

### 1. Add the tooling artifacts to a debug/staging build only

```kotlin
debugImplementation("io.github.syrou:reaktiv-introspection:{{VERSION}}")
debugImplementation("io.github.syrou:reaktiv-devtools:{{VERSION}}")
```

### 2. Define `toolingModule(...)` in your debug source set

Pick one of the two variants. Both capture the whole session. They differ in
whether the app also streams to this server.

**Crash capture only** (no server, no networking) is the safe default to reach
for. It captures the session in memory and, on an uncaught exception, writes a
crash session file to the device. Send that file to support and import it in
this UI. Each crash file also carries a plain-text diagnosis (what failed, where,
the recent actions, and a likely cause) that appears as a copyable Crash
Diagnosis panel when you import it. `installCrashHandler` and `autoStart` default
to `true`, so nothing else is needed. Values under sensitive keys (`password`,
`token`, `secret`, `apiKey` and similar) are masked with `[REDACTED]` before
anything is written, so crash files are safe to share (see
[Redaction](#redaction) to customize):

```kotlin
// src/debug/.../ToolingAttachment.kt
import android.content.Context
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.introspection.IntrospectionConfig
import io.github.syrou.reaktiv.introspection.PlatformContext
import io.github.syrou.reaktiv.introspection.tooling.createToolingModule

fun toolingModule(context: Context): Module<*, *>? = createToolingModule(
    config = IntrospectionConfig(
        clientName = "My App",
        platform = "Android 14",
        installCrashHandler = true
    ),
    platformContext = PlatformContext(context)
)
```

`installCrashHandler` is written out here only to make the crash-only intent
explicit. Set it to `false` to opt out of the uncaught-exception handler.

**Full setup** installs `DevToolsService` so the app also streams state and
actions to this server for live viewing, time-travel and multi-device
replication. Crash capture stays active as well:

```kotlin
// src/debug/.../ToolingAttachment.kt
import android.content.Context
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.devtools.middleware.DevToolsConfig
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.service.DevToolsService
import io.github.syrou.reaktiv.introspection.IntrospectionConfig
import io.github.syrou.reaktiv.introspection.PlatformContext
import io.github.syrou.reaktiv.introspection.tooling.createToolingModule

fun toolingModule(context: Context): Module<*, *>? = createToolingModule(
    config = IntrospectionConfig(clientName = "My App", platform = "Android 14"),
    platformContext = PlatformContext(context)
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
```

Use `ws://10.0.2.2:8080/ws` from the Android emulator, or the host machine's LAN
address (for example `ws://192.168.1.100:8080/ws`) from a physical device.

### 3. Add a release stub so production ships no tooling

```kotlin
// src/release/.../ToolingAttachment.kt
import android.content.Context
import io.github.syrou.reaktiv.core.Module

fun toolingModule(context: Context): Module<*, *>? = null
```

### 4. Wire it into the store from the main source set

```kotlin
val store = createStore {
    module(navigationModule)
    toolingModule(context)?.let { module(it) }
}
```

To export the captured session on demand (for example from a debug menu):

```kotlin
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.introspection.tooling.ToolingLogic

val path = store.selectLogic<ToolingLogic>().exportSessionToDownloads()
```

`PlatformContext(context)` is the Android form. Other platforms construct
`PlatformContext` for their platform.

## Redaction

Captured state (the initial snapshot, every delta, and therefore crash files and
the live stream) masks values under sensitive keys with `[REDACTED]` by default.
Keys match case-insensitively and ignore `_`/`-`, so `userPassword`, `api_key`
and `access-token` are all caught, and a matched key masks its whole value
including nested objects and arrays.

Customize or extend the key set, or disable it:

```kotlin
IntrospectionConfig(
    clientName = "My App",
    platform = "Android 14",
    // add your own sensitive keys on top of the defaults
    redactor = sensitiveKeyRedactor(keys = DEFAULT_SENSITIVE_KEYS + "otp"),
    // set false only if you are sure the state holds no secrets
    redactSensitiveKeys = true
)
```

A custom `redactor` runs on top of the built-in masking, so providing one never
disables the default password protection.

## Configuration reference

`IntrospectionConfig` (the tooling module itself):

- `platform: String` (required) - platform description shown in the UI, e.g. `"Android 14"`.
- `clientName: String` - display name for this device in the UI and file names.
- `clientId: String` - stable id for this client, auto-generated if omitted.
- `enabled: Boolean = true` - master switch (set `false` to disable all capture).
- `autoStart: Boolean = true` - start capturing immediately. Set `false` to capture only on demand via `ToolingLogic.startCapture()` / `stopCapture()`, for example gated behind user consent.
- `installCrashHandler: Boolean = true` - install the uncaught-exception handler that writes a crash session file. It shows no UI (see Crash export vs crash screen below).
- `installStallWatchdog: Boolean = true` - watch the main thread and record UI freezes (ANR-style) on the same timeline as actions and crashes.
- `stallThresholdMs: Long = 300` - a main-thread freeze longer than this many ms is recorded as a stall.
- `clientMetadata: ClientMetadata? = null` - appVersion / osVersion / reaktivVersion / locale embedded in exports.
- `redactSensitiveKeys: Boolean = true` - mask values under sensitive keys (see Redaction).
- `redactor: StateRedactor? = null` - additional custom redaction, composed on top of the built-in masking.
- `maxActions: Int? = null` / `maxLogicEvents: Int? = null` - optional retention caps (`null` keeps the whole session).

`DevToolsConfig` (only when you `install(DevToolsService(...))`):

- `serverUrl: String? = null` - the DevTools server WebSocket URL.
- `enabled: Boolean = true` - master switch for the service.
- `autoConnect: Boolean = true` - connect on start (set `false` to wait for an explicit connect command).
- `autoReconnect: Boolean = true` - retry with backoff after a dropped connection.
- `defaultRole: ClientRole? = null` - role to request on connect, usually `ClientRole.PUBLISHER`.
- `allowActionCapture: Boolean = true` / `allowStateCapture: Boolean = true` - gate what is streamed to the server.

### Crash export vs crash screen

`installCrashHandler` only writes a crash session file and then lets the crash
propagate as usual and never shows a screen. The optional in-app crash screen is
a separate navigation feature (`crashScreen(...)` in your graph DSL), so
exporting crashes without a crash screen is simply the default: enable
`installCrashHandler` and do not configure a `crashScreen`.

## Documentation

For full documentation, visit: https://github.com/{{REPO}}
