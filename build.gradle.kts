plugins {
    kotlin("jvm") apply false
    kotlin("multiplatform") apply false
    id("com.android.application") apply false
    id("com.android.kotlin.multiplatform.library") apply false
    id("org.jetbrains.dokka") version "2.2.0"
    id("org.jetbrains.compose") version "1.11.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
}

@OptIn(kotlinx.validation.ExperimentalBCVApi::class)
apiValidation {
    ignoredProjects += listOf("androidexample")
    klib {
        enabled = true
    }
}

buildscript {

    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        google()
    }

    val kotlinVersion = project.extra["kotlinVersion"] as String
    val atomicFuVersion = project.extra["atomicFuVersion"] as String

    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.kotlinx:atomicfu-gradle-plugin:$atomicFuVersion")
    }
}

subprojects {
    apply(plugin = "org.jetbrains.dokka")
    group = "io.github.syrou"

    extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension> {
        dokkaSourceSets.configureEach {
            if (project.file("module.md").exists()) {
                includes.from("module.md")
            }
            sourceLink {
                localDirectory.set(project.file("src"))
                remoteUrl.set(java.net.URI("https://github.com/Syrou/Reaktiv/blob/main/${project.name}/src"))
                remoteLineSuffix.set("#L")
            }
        }
    }

    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
            jvmToolchain(17)
            explicitApi()
            compilerOptions {
                freeCompilerArgs.add("-Xexpect-actual-classes")
            }
        }
    }

    tasks {
        withType<PublishToMavenRepository> {
            dependsOn(withType<Sign>())
        }
    }
}

dokka {
    moduleName.set("Reaktiv")
    dokkaPublications.html {
        outputDirectory.set(rootDir.resolve("docs"))
    }
}

dependencies {
    dokka(project(":reaktiv-core"))
    dokka(project(":reaktiv-navigation"))
    dokka(project(":reaktiv-compose"))
    dokka(project(":reaktiv-devtools"))
    dokka(project(":reaktiv-tracing-annotations"))
    dokka(project(":reaktiv-introspection"))
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}