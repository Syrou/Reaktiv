import org.jetbrains.compose.ExperimentalComposeLibrary
import java.net.URI

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.library")
    id("org.jetbrains.dokka")
    id("io.github.syrou.central-publisher-plugin")
    kotlin("plugin.serialization")
    id("io.github.syrou.version")
}

group = "io.github.syrou"

dokka {
    moduleName.set("reaktiv-navigation")
    dokkaSourceSets.configureEach {
        includes.from("module.md")
        sourceLink {
            localDirectory.set(file("src"))
            remoteUrl.set(URI("https://github.com/Syrou/Reaktiv/blob/main/reaktiv-navigation/src"))
            remoteLineSuffix.set("#L")
        }
    }
}

centralPublisher {
    username = CentralPublisherCredentials.getRequiredCredential(project, "CENTRAL_TOKEN")
    password = CentralPublisherCredentials.getRequiredCredential(project, "CENTRAL_PASSWORD")
    publishingType = PublishingType.AUTOMATIC

    // GPG signing
    signingPassword = CentralPublisherCredentials.getRequiredCredential(project, "SIGNING_PASSWORD")
    signingSecretKey = CentralPublisherCredentials.getRequiredCredential(project, "SIGNING_SECRET_KEY")

    projectName = "Reaktiv"
    projectDescription = "A flexible and powerful state management library..."
    projectUrl = "https://github.com/Syrou/Reaktiv"

    licenseName = "Apache License 2.0"
    licenseUrl = "https://opensource.org/license/apache-2-0"

    developerId = "Syrou"
    developerName = "Syrou"
    developerEmail = "me@syrou.eu"

    scmUrl = "https://github.com/Syrou/Reaktiv"
    scmConnection = "scm:git:https://github.com/Syrou/Reaktiv.git"
    scmDeveloperConnection = "scm:git:ssh://github.com/Syrou/Reaktiv.git"
}

repositories {
    mavenCentral()
}

val osName = System.getProperty("os.name")
val targetOs = when {
    osName == "Mac OS X" -> "macos"
    osName.startsWith("Win") -> "windows"
    osName.startsWith("Linux") -> "linux"
    else -> error("Unsupported OS: $osName")
}

val targetArch = when (val osArch = System.getProperty("os.arch")) {
    "x86_64", "amd64" -> "x64"
    "aarch64" -> "arm64"
    else -> error("Unsupported arch: $osArch")
}

kotlin {
    jvm()
    androidTarget {
        publishLibraryVariants("release")
    }
    macosArm64()
    macosX64()
    iosArm64()
    iosSimulatorArm64()
    applyDefaultHierarchyTemplate()
    val version = "0.8.10" // or any more recent version
    val target = "${targetOs}-${targetArch}"
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.components.resources)
                implementation(project(":reaktiv-core"))
                implementation(project(":reaktiv-compose"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                @OptIn(ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("org.jetbrains.skiko:skiko-awt-runtime-$target:$version")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
                implementation(compose.desktop.currentOs)
                implementation(compose.desktop.uiTestJUnit4)
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xcontext-receivers")
        freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
    }

    jvmToolchain(17)
}

android {
    namespace = "io.github.syrou"
    compileSdk = 35

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