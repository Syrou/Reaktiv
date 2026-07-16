import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.dokka")
    id("io.github.syrou.central-publisher-plugin")
    id("io.github.syrou.version")
}

centralPublisher {
    projectName = "Reaktiv DevTools"
    projectDescription = "DevTools middleware and server for Reaktiv state management library"
}

kotlin {
    android {
        namespace = "io.github.syrou.reaktiv.devtools"
        compileSdk = 36
        minSdk = 23
    }
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
        binaries.executable()
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions.freeCompilerArgs.add("-Xexplicit-api=disable")
            }
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(project(":reaktiv-core"))
                api(project(":reaktiv-introspection"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        getByName("nativeMain") {
            dependencies {
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.websockets)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)

                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.websockets)

                implementation(libs.kotlinx.io.core)

                // Compose runtime required by compose compiler plugin
                implementation(compose.runtime)
            }
        }

        getByName("androidMain") {
            dependencies {
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.ktor.client.websockets)

                implementation(compose.runtime)
            }
        }

        getByName("wasmJsMain") {
            dependencies {
                implementation(project(":reaktiv-compose"))

                implementation(compose.runtime)
                implementation(compose.ui)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)

                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.js)
                implementation(libs.ktor.client.websockets)

                implementation(libs.kotlinx.datetime)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }

    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlinx.coroutines.DelicateCoroutinesApi")
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

tasks {
    register("buildDevToolsServer") {
        group = "build"
        description = "Builds the WASM UI and native executable for DevTools server"

        dependsOn("wasmJsBrowserDistribution")
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
            println("  ./reaktiv-devtools/build/dist/wasmJs/productionExecutable/")
            println()
            println("Native executables built at:")
            println("  ./reaktiv-devtools/build/bin/linuxX64/releaseExecutable/reaktiv-devtools.kexe")
            println("  ./reaktiv-devtools/build/bin/linuxArm64/releaseExecutable/reaktiv-devtools.kexe")
            println("  ./reaktiv-devtools/build/bin/macosArm64/releaseExecutable/reaktiv-devtools.kexe")
            println("  ./reaktiv-devtools/build/bin/mingwX64/releaseExecutable/reaktiv-devtools.exe")
            println()
            println("Run the server with UI:")
            println("  ./reaktiv-devtools/build/bin/linuxX64/releaseExecutable/reaktiv-devtools.kexe reaktiv-devtools/build/dist/wasmJs/productionExecutable")
            println()
        }
    }

    register("buildDevToolsServerFast") {
        group = "build"
        description = "Builds the WASM UI and native executable for the current platform only"

        dependsOn("wasmJsBrowserDistribution")

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
            println("WASM UI: ./reaktiv-devtools/build/dist/wasmJs/productionExecutable/")
            println()
        }
    }
}
