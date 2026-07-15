import org.jetbrains.compose.ExperimentalComposeLibrary
import java.net.URI

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
    username.set(CentralPublisherCredentials.credentialProvider(project, "CENTRAL_TOKEN"))
    password.set(CentralPublisherCredentials.credentialProvider(project, "CENTRAL_PASSWORD"))
    publishingType = PublishingType.AUTOMATIC

    // GPG signing
    signingPassword.set(CentralPublisherCredentials.credentialProvider(project, "SIGNING_PASSWORD"))
    signingSecretKey.set(CentralPublisherCredentials.credentialProvider(project, "SIGNING_SECRET_KEY"))

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
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.components.resources)
                implementation(project(":reaktiv-core"))
                implementation(project(":reaktiv-compose"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
                @OptIn(ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.desktop.uiTestJUnit4)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.activity:activity-compose:1.13.0")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
    }

    jvmToolchain(17)
}