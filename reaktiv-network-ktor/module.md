# Module reaktiv-network-ktor

Captures every request and response made through a Ktor `HttpClient` and feeds them to the
Reaktiv DevTools UI, on the same timeline as your dispatched actions and logic calls.

Redaction happens on the device, before anything leaves it.

## What you get

- Method, URL, status, duration and body sizes for every exchange
- Request and response headers, with configured headers redacted
- Request and response bodies for textual content types, pretty printed when they are JSON
- Failures, including requests that never got a response
- A network lane in the session timeline, interleaved with actions, logic spans and markers
- Copy as cURL, copy URL, copy response

---

## Setup

Network capture rides on the DevTools connection, so there are three pieces: the tooling module
in your store, the plugin on your `HttpClient`, and a running server. Capture is inert until the
DevTools UI is attached, so an installed plugin costs nothing on its own.

### 1. Add the dependency

```kotlin
dependencies {
    debugImplementation("io.github.syrou:reaktiv-network-ktor:<version>")
    debugImplementation("io.github.syrou:reaktiv-devtools:<version>")
}
```

`debugImplementation` keeps the whole thing out of release builds. If you use
`implementation`, set `enabled = false` on `IntrospectionConfig` for release.

### 2. Put the tooling module in your store

This is what connects to the server. Without it nothing is captured, because the plugin only
records while something is listening.

```kotlin
val store = createStore {
    module(CounterModule)
    module(navigationModule)

    module(
        createToolingModule(
            config = IntrospectionConfig(
                clientName = "${Build.MANUFACTURER} ${Build.MODEL}",
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

`PlatformContext()` takes no argument outside Android.

### 3. Install the plugin on your HttpClient

```kotlin
val httpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) { json() }
    install(ReaktivNetworkInspection)
}
```

That is the whole integration. Install it on every client you want to see; a client without it
is invisible to the UI.

### 4. Run the server and open the UI

```bash
./gradlew :reaktiv-devtools:runDevToolsServer
```

Open `http://localhost:8080`, then start your app. If port 8080 is taken, use
`-Pport=8081` and point `serverUrl` at the same port. The device appears in the client list as a
publisher, and the **Network** tab fills as requests happen.

---

## Choosing the right serverUrl

The device has to reach the machine running the server, so `localhost` is usually wrong.

```kotlin
serverUrl = "ws://10.0.2.2:8080/ws"        // Android emulator, the host machine
serverUrl = "ws://192.168.1.100:8080/ws"   // physical device on the same network
serverUrl = "ws://localhost:8080/ws"       // iOS simulator
```

On iOS, cleartext `ws://` is blocked by App Transport Security unless a debug Info.plist opts
in. See the `reaktiv-devtools` module documentation for the exact keys.

---

## Configuration

```kotlin
install(ReaktivNetworkInspection) {
    captureBodies = true
    maxBodyBytes = 64 * 1024
    hardBodyLimitBytes = 2L * 1024 * 1024
    redactedHeaders = setOf("Authorization", "Cookie", "Set-Cookie", "Proxy-Authorization")
    bodyRetentionCount = 50
    bodyRetentionBytes = 8L * 1024 * 1024
    shouldCaptureBody = { contentType -> isTextualContent(contentType) }
}
```

| Setting | Meaning |
|---|---|
| `captureBodies` | Capture request and response bodies at all |
| `maxBodyBytes` | How much of a body rides along inline with every captured exchange |
| `hardBodyLimitBytes` | A response larger than this is not read |
| `redactedHeaders` | Replaced with `<redacted>` before leaving the device |
| `bodyRetentionCount` | How many recent exchanges keep their full bodies |
| `bodyRetentionBytes` | Total budget for retained bodies, oldest evicted first |
| `shouldCaptureBody` | Decides per content type; defaults to textual types only |

Bodies are captured only for textual content types, so images and other binary payloads are
recorded as an exchange without a body.

---

## Large bodies

`maxBodyBytes` bounds only the slice that travels inline with every exchange, which keeps the
event stream cheap. The full body stays on the device, bounded by `bodyRetentionCount` entries and
the `bodyRetentionBytes` total.

When you open a request whose body was truncated, the UI streams the rest in 64 KB chunks and
renders the reassembled body, so a large JSON response still lands in the tree viewer. The panel
shows the progress while it loads, and chunks are cut on character boundaries so multi byte text
survives the round trip.

If the body has already been evicted the panel says so and keeps showing the inline preview,
with a retry. Raise `bodyRetentionBytes` if you inspect large responses well after they happen.

---

## Troubleshooting

**The Network tab says no requests captured.** The plugin records only while the DevTools UI is
attached. Check that the client list shows your device as a publisher, and that
`ReaktivNetworkInspection` is installed on the client actually making the calls.

**The device never appears.** The `serverUrl` is the usual cause. See the table above, and note
that a device on a different network cannot reach your machine at all.

**A request shows no body.** Either the content type is not textual, `captureBodies` is off, or
the response exceeded `hardBodyLimitBytes`.

**Nothing is captured in release.** Intended, if the module is `debugImplementation` or
`IntrospectionConfig.enabled` is false.

---

## Using a different HTTP client

The Network tab is not tied to Ktor. `NetworkTap` in `reaktiv-introspection` is the seam, and this
module is just one adapter for it. If your app uses a different client, feed the same tap:

```kotlin
NetworkTap.emit(
    NetworkRequestCapture(
        id = "net-1",
        startedAtMs = start,
        durationMs = elapsed,
        method = "GET",
        url = url,
        responseStatus = 200,
        responseBody = bodyPreview,
        responseBodySize = size
    )
)
```

That alone populates the list, the timeline lane and the detail panel. One optional extra:

- `NetworkTap.addBodyProvider { requestId, part, offset, maxBytes -> ... }` enables chunked
  streaming of bodies larger than `maxBodyBytes`, using `ByteArray.sliceOnCharBoundary`.

Without a body provider a request still appears, it just cannot stream bodies past `maxBodyBytes`.
