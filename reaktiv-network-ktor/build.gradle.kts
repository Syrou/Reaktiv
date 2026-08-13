import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.dokka")
    id("io.github.syrou.central-publisher-plugin")
    id("io.github.syrou.version")
}

centralPublisher {
    projectName = "Reaktiv Network Ktor"
    projectDescription = "Ktor client plugin capturing HTTP request and response data for Reaktiv DevTools"
}

kotlin {
    android {
        namespace = "io.github.syrou.reaktiv.network.ktor"
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
                api(project(":reaktiv-introspection"))
                implementation(project(":reaktiv-core"))
                api(libs.ktor.client.core)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
    }

    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}
