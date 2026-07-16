import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.dokka")
    id("io.github.syrou.central-publisher-plugin")
    id("io.github.syrou.version")
}

centralPublisher {
    projectName = "Reaktiv"
    projectDescription = "A flexible and powerful state management library..."
}

kotlin {
    android {
        namespace = "io.github.syrou.reaktiv.compose"
        compileSdk = 36
        minSdk = 23
        androidResources {
            enable = true
        }
    }
    jvm()
    macosArm64()
    iosArm64()
    iosSimulatorArm64()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.components.resources)
                implementation(project(":reaktiv-core"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
