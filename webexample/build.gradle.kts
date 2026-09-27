import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val devTools = providers.gradleProperty("exampleDevtools").map { it.toBoolean() }.getOrElse(false)

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        getByName("wasmJsMain") {
            kotlin.srcDir(if (devTools) "src/toolingOn/kotlin" else "src/toolingOff/kotlin")
            dependencies {
                implementation(project(":example-shared"))
                if (devTools) {
                    implementation(project(":example-tooling"))
                }
            }
        }
    }
}
