import org.gradle.kotlin.dsl.support.kotlinCompilerOptions

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("kotlinx-atomicfu")
    kotlin("plugin.serialization")
    id("convention.publication")
    id("io.github.syrou.version")
}

group = "io.github.syrou"

repositories {
    mavenCentral()
}

kotlin {
    // Targets
    androidTarget {
        publishLibraryVariants("release")
    }
    jvm()
    /*js {
        // Configuration for JavaScript target
    }*/
    macosArm64()
    macosX64()
    iosArm64()
    iosSimulatorArm64()

    // Apply the default hierarchy explicitly. It'll create, for example, the iosMain source set:
    applyDefaultHierarchyTemplate()
    // Linux target
    linuxX64()
    // Windows target
    mingwX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }
    }

    jvmToolchain(17)
}

android {
    namespace = "io.github.syrou"
    compileSdk = 34

    sourceSets {
        named("main") {
            res.srcDir("src/commonMain/resources")
        }
    }

    lint {
        disable.addAll(listOf("MissingTranslation", "ExtraTranslation"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}