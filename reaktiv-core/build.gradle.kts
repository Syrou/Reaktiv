plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("kotlinx-atomicfu")
    kotlin("plugin.serialization")
    id("convention.publication")
}

group = "io.github.syrou"
version = "0.0.3"

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
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
            }
        }
        val commonTest by getting{
            dependencies{
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
            }
        }
    }
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
        sourceCompatibility = JavaVersion.VERSION_18
        targetCompatibility = JavaVersion.VERSION_18
    }
}