import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.binary.compatibility.validator)
}

@OptIn(kotlinx.validation.ExperimentalBCVApi::class)
apiValidation {
    ignoredProjects += listOf("androidexample", "webexample", "example-shared", "example-tooling", "reaktiv-devtools-ui")
    klib {
        enabled = true
    }
}

subprojects {
    group = "io.github.syrou"

    tasks.withType<AbstractTestTask>().configureEach {
        testLogging {
            events(TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(17)
            compilerOptions {
                freeCompilerArgs.add("-Xexpect-actual-classes")
            }
        }
    }

    plugins.withId("io.github.syrou.central-publisher-plugin") {
        apply(plugin = "org.jetbrains.dokka")
        extensions.configure<DokkaExtension> {
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
            extensions.configure<KotlinMultiplatformExtension> {
                explicitApi()
                sourceSets.named("commonTest") {
                    dependencies {
                        implementation(kotlin("test"))
                        implementation(rootProject.libs.kotlinx.coroutines.test)
                    }
                }
                targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
                    namespace = "io.github.syrou.reaktiv." + project.name.removePrefix("reaktiv-").replace('-', '.')
                    compileSdk = 37
                    minSdk = 23
                }
            }
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
    dokka(project(":reaktiv-network-ktor"))
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}