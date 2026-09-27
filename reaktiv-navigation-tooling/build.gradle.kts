import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.kotlin.multiplatform.library")
    kotlin("plugin.serialization")
    id("io.github.syrou.central-publisher-plugin")
    id("io.github.syrou.version")
}

centralPublisher {
    projectName = "Reaktiv Navigation Tooling"
    projectDescription = "Optional tooling plugin that publishes the navigation link map to DevTools and opens links on the device"
}

kotlin {
    jvm()
    android {}
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
                api(project(":reaktiv-navigation"))
                api(project(":reaktiv-introspection"))
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.compose.material3)
            }
        }
    }

    compilerOptions {
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}
