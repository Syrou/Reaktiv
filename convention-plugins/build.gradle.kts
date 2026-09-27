plugins {
    `kotlin-dsl`

}

gradlePlugin {
    plugins {
        create("versionPlugin") {
            id = "io.github.syrou.version"
            implementationClass = "VersionPlugin"
        }
    }
}

gradlePlugin {
    plugins {
        create("centralPublisherPlugin") {
            id = "io.github.syrou.central-publisher-plugin"
            implementationClass = "CentralPublisherPlugin"
            displayName = "Central Publisher Plugin"
            description = "Publishes Kotlin Multiplatform artifacts to Sonatype Central Portal"
        }
    }
}

repositories {
    gradlePluginPortal()
}