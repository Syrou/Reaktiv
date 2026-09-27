import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    kotlin("plugin.serialization")
    id("io.github.syrou.reaktiv.tracing")
}

reaktivTracing {
    tracePrivateMethods.set(true)
    enableForTasksMatching("debug")
    conflictsWithTasksMatching("release")
    if (providers.gradleProperty("exampleDevtools").orNull == "true") {
        enableForTasksMatching("wasmJs")
    }
}

kotlin {
    android {
        namespace = "eu.syrou.example"
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
                api(project(":reaktiv-core"))
                api(project(":reaktiv-compose"))
                api(project(":reaktiv-navigation"))
                api(libs.compose.runtime)
                api(libs.compose.foundation)
                api(libs.compose.material3)
                api(libs.compose.ui)
                implementation(libs.compose.material.icons.extended)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                api(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.client.logging)
                implementation(libs.coil3.compose)
                implementation(libs.coil3.network.ktor3)
            }
        }
        getByName("androidMain") {
            dependencies {
                implementation(libs.ktor.client.okhttp)
            }
        }
        getByName("wasmJsMain") {
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }
    }
}
