package io.github.syrou.reaktiv.devtools.server

/**
 * Entry point for the DevTools server.
 *
 * Run this to start the server:
 * ```
 * ./gradlew :reaktiv-devtools:macosArm64Binaries
 * ./reaktiv-devtools/build/bin/macosArm64/releaseExecutable/reaktiv-devtools.kexe [ui-path]
 * ```
 *
 * If ui-path is provided, the WASM UI will be served from that directory.
 * Otherwise, only the WebSocket endpoint will be available.
 *
 * Example with UI:
 * ```
 * ./reaktiv-devtools.kexe build/dist/wasmJs/productionExecutable
 * ```
 */
fun main(args: Array<String>) {
    println("=".repeat(60))
    println("Reaktiv DevTools Server")
    println("=".repeat(60))
    println()

    val uiPath = args.getOrNull(0)

    DevToolsServer.start(port = 8080, host = "0.0.0.0", uiPath = uiPath)
}
