import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import java.net.URI

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    kotlin("plugin.serialization")
    id("org.jetbrains.dokka")
    id("io.github.syrou.central-publisher-plugin")
    id("io.github.syrou.version")
}

group = "io.github.syrou"

dokka {
    moduleName.set("reaktiv-core")
    dokkaSourceSets.configureEach {
        includes.from("module.md")
        sourceLink {
            localDirectory.set(file("src"))
            remoteUrl.set(URI("https://github.com/Syrou/Reaktiv/blob/main/reaktiv-core/src"))
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
        namespace = "io.github.syrou.reaktiv.core"
        compileSdk = 36
        minSdk = 23
    }
    macosArm64()
    macosX64()
    iosArm64()
    iosSimulatorArm64()
    linuxX64()
    linuxArm64()
    mingwX64()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.experimental.ExperimentalObjCName")
    }

    jvmToolchain(17)
}