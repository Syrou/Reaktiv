package io.github.syrou.reaktiv.devtools.server

/**
 * Entry point for the DevTools server.
 *
 * Run this to start the server:
 * ```
 * ./gradlew :reaktiv-devtools:runDevToolsServer
 * ```
 *
 * Or run a built binary directly, optionally passing the UI directory to serve and a port:
 * ```
 * ./reaktiv-devtools.kexe build/dist/wasmJs/productionExecutable 8081
 * ```
 *
 * Without a UI directory only the WebSocket endpoint is available.
 */
public fun main(args: Array<String>) {
    println("=".repeat(60))
    println("Reaktiv DevTools Server")
    println("=".repeat(60))
    println()

    val uiPath = args.getOrNull(0)?.takeIf { it.isNotBlank() }
    val port = args.getOrNull(1)?.toIntOrNull() ?: 8080

    DevToolsServer.start(port = port, host = "0.0.0.0", uiPath = uiPath)
}
