import org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalWasmDsl
import java.net.URI

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.dokka")
    id("io.github.syrou.central-publisher-plugin")
    id("io.github.syrou.version")
}

group = "io.github.syrou"

dokka {
    moduleName.set("reaktiv-devtools")
    dokkaSourceSets.configureEach {
        includes.from("module.md")
        sourceLink {
            localDirectory.set(file("src"))
            remoteUrl.set(URI("https://github.com/Syrou/Reaktiv/blob/main/reaktiv-devtools/src"))
            remoteLineSuffix.set("#L")
        }
    }
}

centralPublisher {
    username.set(CentralPublisherCredentials.credentialProvider(project, "CENTRAL_TOKEN"))
    password.set(CentralPublisherCredentials.credentialProvider(project, "CENTRAL_PASSWORD"))
    publishingType = PublishingType.AUTOMATIC

    // GPG signing
    signingPassword.set(CentralPublisherCredentials.credentialProvider(project, "SIGNING_PASSWORD"))
    signingSecretKey.set(CentralPublisherCredentials.credentialProvider(project, "SIGNING_SECRET_KEY"))

    projectName = "Reaktiv DevTools"
    projectDescription = "DevTools middleware and server for Reaktiv state management library"
    projectUrl = "https://github.com/Syrou/Reaktiv"

    licenseName = "Apache License 2.0"
    licenseUrl = "https://opensource.org/license/apache-2-0"

    developerId = "Syrou"
    developerName = "Syrou"
    developerEmail = "me@syrou.eu"

    scmUrl = "https://github.com/Syrou/Reaktiv"
    scmConnection = "scm:git:https://github.com/Syrou/Reaktiv.git"
    scmDeveloperConnection = "scm:git:ssh://github.com/Syrou/Reaktiv.git"
}

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

android {
    namespace = "io.github.syrou.reaktiv.devtools"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    androidTarget {
        publishLibraryVariants("release")
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
    macosX64 {
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
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":reaktiv-core"))
                api(project(":reaktiv-introspection"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        }

        val nativeMain by getting {
            dependencies {
                implementation("io.ktor:ktor-server-core:3.1.0")
                implementation("io.ktor:ktor-server-cio:3.1.0")
                implementation("io.ktor:ktor-server-websockets:3.1.0")
                implementation("io.ktor:ktor-server-content-negotiation:3.1.0")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.0")

                implementation("io.ktor:ktor-client-core:3.1.0")
                implementation("io.ktor:ktor-client-cio:3.1.0")
                implementation("io.ktor:ktor-client-websockets:3.1.0")

                implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.8.2")

                // Compose runtime required by compose compiler plugin
                implementation(compose.runtime)
            }
        }

        val androidMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-core:3.1.0")
                implementation("io.ktor:ktor-client-okhttp:3.1.0")
                implementation("io.ktor:ktor-client-websockets:3.1.0")

                implementation(compose.runtime)
            }
        }

        val wasmJsMain by getting {
            dependencies {
                implementation(project(":reaktiv-compose"))

                implementation(compose.runtime)
                implementation(compose.ui)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)

                implementation("io.ktor:ktor-client-core:3.1.0")
                implementation("io.ktor:ktor-client-js:3.1.0")
                implementation("io.ktor:ktor-client-websockets:3.1.0")

                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
    }

    jvmToolchain(17)
}

compose {
    experimental {
        web.application {}
    }
}

tasks {
    register("buildDevToolsServer") {
        group = "build"
        description = "Builds the WASM UI and native executable for DevTools server"

        dependsOn("wasmJsBrowserDistribution")
        dependsOn("linkReleaseExecutableLinuxX64")
        dependsOn("linkReleaseExecutableLinuxArm64")
        dependsOn("linkReleaseExecutableMacosX64")
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
            println("  ./reaktiv-devtools/build/bin/macosX64/releaseExecutable/reaktiv-devtools.kexe")
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
            currentOs.contains("mac") -> "linkReleaseExecutableMacosX64"
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
