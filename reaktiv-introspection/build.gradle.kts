import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
    id("io.github.syrou.central-publisher-plugin")
    id("io.github.syrou.version")
    id("io.github.syrou.reaktiv.tracing")
}

reaktivTracing {
    enabled.set(true)
    tracePrivateMethods.set(true)
    buildTypes.set(setOf("test"))
}

centralPublisher {
    projectName = "Reaktiv Introspection"
    projectDescription = "Session capture, protocol definitions, and crash handling for Reaktiv state management library"
}

kotlin {
    android {}

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

    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                withCompilations { it.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":reaktiv-core"))
                api(project(":reaktiv-tracing-runtime"))
                implementation(libs.kotlinx.io.core)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(project(":reaktiv-tracing-annotations"))
            }
        }
    }

    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }

    sourceSets.configureEach {
        val nativePrefixes = listOf("native", "apple", "ios", "macos", "linux", "mingw")
        if (nativePrefixes.any { name.startsWith(it) }) {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlinx.cinterop.BetaInteropApi")
        }
    }
}
