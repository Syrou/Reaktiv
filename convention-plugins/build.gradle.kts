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

dependencies {
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")

    // Kotlinx serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}