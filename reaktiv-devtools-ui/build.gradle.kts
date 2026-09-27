import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "reaktiv-devtools.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(project(":reaktiv-devtools"))
                implementation(libs.compose.runtime)
            }
        }

        getByName("wasmJsMain") {
            dependencies {
                implementation(project(":reaktiv-compose"))
                implementation(libs.compose.ui)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.material.icons.extended)
                implementation(libs.compose.components.resources)
                implementation(libs.kotlinx.datetime)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(project(":reaktiv-navigation"))
                implementation(project(":reaktiv-navigation-tooling"))
                implementation(project(":reaktiv-network-ktor"))
                implementation(libs.ktor.client.mock)
                implementation(libs.compose.ui)
                implementation(libs.compose.material3)
            }
        }
    }

    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("io.github.syrou.reaktiv.devtools.DevToolsInternalApi")
        optIn.add("kotlinx.coroutines.DelicateCoroutinesApi")
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
    }
}
