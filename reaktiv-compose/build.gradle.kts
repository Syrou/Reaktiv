plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("com.android.library")
    id("convention.publication")
}

group = "io.github.syrou"
version = "0.0.1"

repositories {
    mavenCentral()
}

kotlin {
    // Targets
    /*js {
        // Configuration for JavaScript target
    }*/
    androidTarget {
        publishLibraryVariants("release")
    }
    jvm()
    macosArm64()
    macosX64()
    iosArm64()
    iosSimulatorArm64()

    // Apply the default hierarchy explicitly. It'll create, for example, the iosMain source set:
    applyDefaultHierarchyTemplate()
    // Linux target
    //linuxX64()
    // Windows target
    //mingwX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material)
                implementation(compose.components.resources)
                implementation(project(":reaktiv-core"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
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