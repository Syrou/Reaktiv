plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.library")
    id("convention.publication")
    id("io.github.syrou.version")
}

group = "io.github.syrou"

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
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
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