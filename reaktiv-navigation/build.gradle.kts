import org.jetbrains.compose.ExperimentalComposeLibrary

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.dokka")
    id("io.github.syrou.central-publisher-plugin")
    kotlin("plugin.serialization")
    id("io.github.syrou.version")
}

centralPublisher {
    projectName = "Reaktiv"
    projectDescription = "A flexible and powerful state management library..."
}

kotlin {
    jvm()
    android {
        namespace = "io.github.syrou.reaktiv.navigation"
        compileSdk = 36
        minSdk = 23
        androidResources {
            enable = true
        }
    }
    macosArm64()
    iosArm64()
    iosSimulatorArm64()
    applyDefaultHierarchyTemplate()
    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.components.resources)
                implementation(project(":reaktiv-core"))
                implementation(project(":reaktiv-compose"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                @OptIn(ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
            }
        }
        getByName("jvmMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.desktop.uiTestJUnit4)
            }
        }
        getByName("androidMain") {
            dependencies {
                implementation(libs.androidx.activity.compose)
            }
        }
        getByName("jvmTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

// The UI test suite deliberately stays on the v1 runComposeUiTest: the v2 API defaults to
// StandardTestDispatcher, which changes coroutine execution timing the gesture tests depend on.
// Suppress the deprecation in test compilations only, leaving production code fully warning-checked.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    if (name.contains("Test")) {
        compilerOptions.freeCompilerArgs.add("-Xwarning-level=DEPRECATION:disabled")
        compilerOptions.optIn.add("androidx.compose.ui.test.ExperimentalTestApi")
    }
}
