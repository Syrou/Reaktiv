import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.kotlin.multiplatform.library")
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
    android {}
    macosArm64()
    iosArm64()
    iosSimulatorArm64()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":reaktiv-core"))
                api(libs.compose.runtime)
                api(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(project(":reaktiv-compose"))
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.compose.ui.test)
            }
        }
        // Compose gesture tests drive touch input against a real composition and a live Store.
        // A headless browser is a poor host for both: the viewport differs from every other target
        // and a Store whose logic runs on Dispatchers.Default deadlocks under wasmJsBrowserTest.
        // They stay on the targets that can actually run them.
        val uiTest by creating {
            dependsOn(getByName("commonTest"))
        }
        getByName("jvmTest").dependsOn(uiTest)
        getByName("appleTest").dependsOn(uiTest)
        getByName("androidMain") {
            dependencies {
                implementation(libs.androidx.activity.compose)
            }
        }
        getByName("jvmTest") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.compose.ui.test.junit4)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
        optIn.add("io.github.syrou.reaktiv.core.ExperimentalReaktivApi")
    }
}

// The UI test suite deliberately stays on the v1 runComposeUiTest: the v2 API defaults to
// StandardTestDispatcher, which changes coroutine execution timing the gesture tests depend on.
// Suppress the deprecation in test compilations only, leaving production code fully warning-checked.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    if (name.contains("Test")) {
        compilerOptions.freeCompilerArgs.add("-Xwarning-level=DEPRECATION:disabled")
        compilerOptions.optIn.add("androidx.compose.ui.test.ExperimentalTestApi")
        compilerOptions.optIn.add("io.github.syrou.reaktiv.core.ExperimentalReaktivApi")
    }
}
