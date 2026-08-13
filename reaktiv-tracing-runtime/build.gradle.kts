import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    kotlin("plugin.serialization")
    id("org.jetbrains.dokka")
    id("io.github.syrou.central-publisher-plugin")
    id("io.github.syrou.version")
}

centralPublisher {
    projectName = "Reaktiv Tracing Runtime"
    projectDescription = "Runtime event bus for Reaktiv logic tracing, added automatically by the tracing Gradle plugin"
}

kotlin {
    jvm()
    android {
        namespace = "io.github.syrou.reaktiv.tracing.runtime"
        compileSdk = 36
        minSdk = 23
    }
    macosArm64()
    iosArm64()
    iosSimulatorArm64()
    linuxX64()
    linuxArm64()
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
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}
