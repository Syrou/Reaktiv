plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.kotlin.multiplatform.library")
    kotlin("plugin.serialization")
    id("org.jetbrains.dokka")
    id("io.github.syrou.central-publisher-plugin")
    id("io.github.syrou.version")
}

centralPublisher {
    projectName = "Reaktiv"
    projectDescription = "Navigation assertions for the Reaktiv test kit: route and back stack checks plus a guard harness"
}

kotlin {
    jvm()
    android {
        namespace = "io.github.syrou.reaktiv.test.navigation"
        compileSdk = 36
        minSdk = 23
    }
    macosArm64()
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":reaktiv-test"))
                api(project(":reaktiv-navigation"))
                implementation(compose.runtime)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }

    compilerOptions {
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}
