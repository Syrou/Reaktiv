import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.dokka")
    id("io.github.syrou.central-publisher-plugin")
    id("io.github.syrou.version")
    id("io.github.syrou.reaktiv.tracing")
}

reaktivTracing {
    enabled.set(true)
    tracePrivateMethods.set(true)
}

centralPublisher {
    projectName = "Reaktiv Introspection"
    projectDescription = "Session capture, protocol definitions, and crash handling for Reaktiv state management library"
}

kotlin {
    android {
        namespace = "io.github.syrou.reaktiv.introspection"
        compileSdk = 36
        minSdk = 23
    }

    jvm()

    iosArm64()
    iosSimulatorArm64()

    linuxX64()
    linuxArm64()
    macosArm64()
    mingwX64()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(project(":reaktiv-core"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.io.core)
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
    }

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions.optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
            }
        }
    }
}
