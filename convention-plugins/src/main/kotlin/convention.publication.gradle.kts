import java.util.*

plugins {
    `maven-publish`
    signing
    id("org.jreleaser")
}

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

// Helper function to get property from local.properties or environment
fun getLocalProperty(key: String, envKey: String = key): String? {
    return localProperties.getProperty(key) ?: System.getenv(envKey)
}

signing {
    useGpgCmd()
    sign(publishing.publications)
}

publishing {
    // Configure maven central repository
    repositories {
        maven {
            name = "staging"
            // Use the same path as your JReleaser stagingRepository
            url = layout.buildDirectory.dir("staging-deploy").get().asFile.toURI()
        }
    }

    // Configure all publications
    publications.withType<MavenPublication> {

        // Stub javadoc.jar artifact
        artifact(javadocJar.get())

        // Provide artifacts information requited by Maven Central
        pom {
            name = "Reaktiv"
            description = "A flexible and powerful state management library for Kotlin applications, inspired by other MVLI solutions but tailored for Kotlin's coroutine-based concurrency model."
            url = "https://github.com/Syrou/Reaktiv"

            licenses {
                license {
                    name = "Apache License 2.0"
                    url = "https://opensource.org/license/apache-2-0"
                }
            }
            developers {
                developer {
                    id = "Syrou"
                    name = "Syrou"
                    email = "me@syrou.eu"
                }
            }
            scm {
                url = "https://github.com/Syrou/Reaktiv"
                connection = "scm:git:https://github.com/Syrou/Reaktiv.git"
                developerConnection = "scm:git:ssh://github.com/Syrou/Reaktiv.git"
            }
        }
    }
}

jreleaser {
    project {
        name = "reaktiv"
        description =
            "A flexible and powerful state management library for Kotlin applications, inspired by other MVLI solutions but tailored for Kotlin's coroutine-based concurrency model."
        longDescription = """
            Reaktiv is a comprehensive state management solution for Kotlin applications that leverages 
            Kotlin's coroutine-based concurrency model to provide a reactive and efficient way to manage 
            application state across different platforms.
        """.trimIndent()
        authors = listOf("Syrou")
        license = "Apache-2.0"
        links {
            homepage = "https://github.com/Syrou/Reaktiv"
        }
        copyright = "2025 Syrou"
    }

    release {
        github {
            skipRelease = true
            enabled = true
        }
    }

    signing {
        active = org.jreleaser.model.Active.ALWAYS
        armored = true
        getLocalProperty("JRELEASER_GPG_PASSPHRASE")?.let { passphrase ->
            this.passphrase = passphrase
        }
        getLocalProperty("JRELEASER_GPG_PUBLIC_KEY")?.let { publicKey ->
            this.publicKey = publicKey
        }
        getLocalProperty("JRELEASER_GPG_SECRET_KEY")?.let { secretKey ->
            this.secretKey = secretKey
        }
    }

    deploy {
        maven {
            mavenCentral {
                create("sonatype") {
                    active = org.jreleaser.model.Active.ALWAYS
                    url = "https://central.sonatype.com/api/v1/publisher"
                    username = getLocalProperty("JRELEASER_MAVENCENTRAL_SONATYPE_USERNAME")
                    password = getLocalProperty("JRELEASER_MAVENCENTRAL_SONATYPE_PASSWORD")
                    stagingRepository(
                        layout.buildDirectory.dir("staging-deploy").get().asFile.path
                    )
                    setAuthorization("BASIC")

                    verifyPom = false
                    checksums = true
                    sourceJar = true
                    javadocJar = true
                }
            }

        }
    }
}

tasks.register("cleanStaging") {
    group = "publishing"
    description = "Cleans the staging directory"

    doLast {
        val stagingDir = layout.buildDirectory.dir("staging-deploy").get().asFile
        if (stagingDir.exists()) {
            stagingDir.deleteRecursively()
            println("🧹 Cleaned staging directory: ${stagingDir.path}")
        }
    }
}

tasks.register("verifyStaging") {
    group = "publishing"
    description = "Verifies that all required artifacts are staged"

    doLast {
        val stagingDir = layout.buildDirectory.dir("staging-deploy").get().asFile
        if (!stagingDir.exists()) {
            throw GradleException("❌ Staging directory doesn't exist: ${stagingDir.path}")
        }

        val artifacts = stagingDir.walkTopDown().filter { it.isFile }.toList()
        val jarFiles = artifacts.filter { it.name.endsWith(".jar") }
        val pomFiles = artifacts.filter { it.name.endsWith(".pom") }
        val signatures = artifacts.filter { it.name.endsWith(".asc") }

        println("📦 Staged artifacts summary:")
        println("   JAR files: ${jarFiles.size}")
        println("   POM files: ${pomFiles.size}")
        println("   Signatures: ${signatures.size}")

        if (pomFiles.isEmpty()) {
            throw GradleException("❌ No POM files found in staging directory")
        }

        if (signatures.isEmpty()) {
            println("⚠️  Warning: No signature files found. Make sure signing is enabled.")
        }

        println("✅ Staging verification completed")
    }
}

tasks.register("deployToMavenCentral") {
    group = "publishing"
    description = "Publishes to staging repository then deploys to Maven Central"

    dependsOn("cleanStaging")

    // First stage the artifacts
    dependsOn("publishAllPublicationsToStagingRepository")

    dependsOn("verifyStaging")

    // Then deploy them
    finalizedBy("jreleaserDeploy")

    tasks.named("publishAllPublicationsToStagingRepository") {
        mustRunAfter("cleanStaging")
    }

    tasks.named("verifyStaging") {
        mustRunAfter("publishAllPublicationsToStagingRepository")
    }

    doLast {
        println("✅ Deployment process completed!")
        println("📍 Check your artifacts at: https://central.sonatype.com/")
        println("📍 It may take a few minutes for artifacts to appear in search")
    }
}

// Make sure jreleaserDeploy waits for staging
tasks.named("jreleaserDeploy") {
    mustRunAfter("publishAllPublicationsToStagingRepository")
}