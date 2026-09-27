import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
    id("io.github.syrou.central-publisher-plugin")
    id("io.github.syrou.version")
}

centralPublisher {
    projectName = "Reaktiv DevTools"
    projectDescription = "DevTools middleware and server for Reaktiv state management library"
}

kotlin {
    android {}
    jvm {
        mainRun {
            mainClass.set("io.github.syrou.reaktiv.devtools.server.MainKt")
        }
    }

    iosArm64()
    iosSimulatorArm64()

    linuxX64 {
        binaries {
            executable {
                entryPoint = "io.github.syrou.reaktiv.devtools.server.main"
            }
        }
    }
    linuxArm64 {
        binaries {
            executable {
                entryPoint = "io.github.syrou.reaktiv.devtools.server.main"
            }
        }
    }
    macosArm64 {
        binaries {
            executable {
                entryPoint = "io.github.syrou.reaktiv.devtools.server.main"
            }
        }
    }
    mingwX64 {
        binaries {
            executable {
                entryPoint = "io.github.syrou.reaktiv.devtools.server.main"
            }
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    applyDefaultHierarchyTemplate {
        common {
            group("desktop") {
                withJvm()
                withLinux()
                withMacos()
                withMingw()
            }
        }
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":reaktiv-core"))
                api(project(":reaktiv-tracing-runtime"))
                api(project(":reaktiv-introspection"))

                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.websockets)
            }
        }

        getByName("desktopMain") {
            dependencies {
                api(libs.ktor.server.core)
                api(libs.ktor.server.websockets)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)

                implementation(libs.ktor.client.cio)

                api(libs.kotlinx.io.core)
            }
        }

        getByName("iosMain") {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }

        getByName("androidMain") {
            dependencies {
                implementation(libs.ktor.client.okhttp)
            }
        }

        getByName("wasmJsMain") {
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(project(":reaktiv-navigation"))
                implementation(project(":reaktiv-navigation-tooling"))
                implementation(project(":reaktiv-network-ktor"))
                implementation(libs.ktor.client.mock)
            }
        }
    }

    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("io.github.syrou.reaktiv.devtools.DevToolsInternalApi")
        optIn.add("kotlinx.coroutines.DelicateCoroutinesApi")
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

val wasmDistDir = project(":reaktiv-devtools-ui").layout.buildDirectory.dir("dist/wasmJs/productionExecutable")
val devToolsPort = providers.gradleProperty("port").orElse("8080")

tasks.register<JavaExec>("runDevToolsServer") {
    group = "reaktiv"
    description = "Builds the WASM UI and serves it with the DevTools websocket server, -Pport to override 8080"
    dependsOn(":reaktiv-devtools-ui:wasmJsBrowserDistribution")

    val jvmMain = kotlin.targets.getByName("jvm").compilations.getByName("main")
    classpath = files(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
    mainClass.set("io.github.syrou.reaktiv.devtools.server.MainKt")
    argumentProviders.add { listOf(wasmDistDir.get().asFile.absolutePath, devToolsPort.get()) }
}

tasks.register<JavaExec>("runDevToolsServerHeadless") {
    group = "reaktiv"
    description = "Serves only the DevTools websocket endpoint, without building the WASM UI"

    val jvmMain = kotlin.targets.getByName("jvm").compilations.getByName("main")
    classpath = files(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
    mainClass.set("io.github.syrou.reaktiv.devtools.server.MainKt")
    argumentProviders.add { listOf("", devToolsPort.get()) }
}

tasks {
    register("buildDevToolsServer") {
        group = "build"
        description = "Builds the WASM UI and native executable for DevTools server"

        dependsOn(":reaktiv-devtools-ui:wasmJsBrowserDistribution")
        dependsOn("linkReleaseExecutableLinuxX64")
        dependsOn("linkReleaseExecutableLinuxArm64")
        dependsOn("linkReleaseExecutableMacosArm64")
        dependsOn("linkReleaseExecutableMingwX64")

        doLast {
            println("=" .repeat(60))
            println("DevTools Server Build Complete!")
            println("=" .repeat(60))
            println()
            println("WASM UI built at:")
            println("  ./reaktiv-devtools-ui/build/dist/wasmJs/productionExecutable/")
            println()
            println("Native executables built at:")
            println("  ./reaktiv-devtools/build/bin/linuxX64/releaseExecutable/reaktiv-devtools.kexe")
            println("  ./reaktiv-devtools/build/bin/linuxArm64/releaseExecutable/reaktiv-devtools.kexe")
            println("  ./reaktiv-devtools/build/bin/macosArm64/releaseExecutable/reaktiv-devtools.kexe")
            println("  ./reaktiv-devtools/build/bin/mingwX64/releaseExecutable/reaktiv-devtools.exe")
            println()
            println("Run the server with UI:")
            println("  ./reaktiv-devtools/build/bin/linuxX64/releaseExecutable/reaktiv-devtools.kexe reaktiv-devtools-ui/build/dist/wasmJs/productionExecutable")
            println()
        }
    }

    register("buildDevToolsServerFast") {
        group = "build"
        description = "Builds the WASM UI and native executable for the current platform only"

        dependsOn(":reaktiv-devtools-ui:wasmJsBrowserDistribution")

        val currentOs = System.getProperty("os.name").lowercase()
        val currentArch = System.getProperty("os.arch").lowercase()

        val nativeTask = when {
            currentOs.contains("linux") && currentArch.contains("aarch64") -> "linkReleaseExecutableLinuxArm64"
            currentOs.contains("linux") -> "linkReleaseExecutableLinuxX64"
            currentOs.contains("mac") && currentArch.contains("aarch64") -> "linkReleaseExecutableMacosArm64"
            currentOs.contains("win") -> "linkReleaseExecutableMingwX64"
            else -> null
        }

        if (nativeTask != null) {
            dependsOn(nativeTask)
        }

        doLast {
            println("=" .repeat(60))
            println("DevTools Server Build Complete (Fast)!")
            println("=" .repeat(60))
            println()
            println("WASM UI: ./reaktiv-devtools-ui/build/dist/wasmJs/productionExecutable/")
            println()
        }
    }
}
