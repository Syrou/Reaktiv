import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    kotlin("plugin.serialization")
}

kotlin {
    android {
        namespace = "eu.syrou.example.tooling"
        compileSdk = 37
        minSdk = 26
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(project(":example-shared"))
                api(project(":reaktiv-introspection"))
                api(project(":reaktiv-devtools"))
                api(project(":reaktiv-network-ktor"))
                api(project(":reaktiv-navigation-tooling"))
                implementation(libs.compose.material.icons.extended)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.client.mock)
            }
        }
    }
}
