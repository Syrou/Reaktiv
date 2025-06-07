import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.*
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.*
import org.gradle.plugins.signing.SigningExtension
import java.io.File
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Task to validate bundle meets Central Portal requirements
 */
abstract class ValidateBundleTask : DefaultTask() {

    @get:InputDirectory
    abstract val bundleDirectory: DirectoryProperty

    @TaskAction
    fun validate() {
        val stagingDir = bundleDirectory.get().asFile
        if (!stagingDir.exists()) {
            throw GradleException("❌ Staging directory not found. Run 'createCentralBundle' first.")
        }

        logger.lifecycle("🔍 Validating bundle structure...")

        val issues = mutableListOf<String>()
        var artifactCount = 0
        var signedCount = 0
        var sourcesCount = 0
        var javadocCount = 0
        var pomCount = 0

        stagingDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                when {
                    file.name.endsWith(".pom") -> {
                        pomCount++
                        validatePomFile(file, issues)
                        validateSignature(file, issues)
                    }

                    file.name.endsWith(".jar") || file.name.endsWith(".klib") -> {
                        artifactCount++
                        validateSignature(file, issues)

                        when {
                            file.name.contains("-sources.jar") -> sourcesCount++
                            file.name.contains("-javadoc.jar") -> javadocCount++
                        }
                    }

                    file.name.endsWith(".asc") -> signedCount++
                }
            }
        }

        // Validate overall structure
        if (pomCount == 0) issues.add("No POM files found")
        if (artifactCount == 0) issues.add("No artifact files found")

        // Report results
        logger.lifecycle("📊 Validation Results:")
        logger.lifecycle("   📦 Artifacts: $artifactCount")
        logger.lifecycle("   📝 POM files: $pomCount")
        logger.lifecycle("   ✍️ Signatures: $signedCount")
        logger.lifecycle("   📚 Sources: $sourcesCount")
        logger.lifecycle("   📖 Javadoc: $javadocCount")

        if (issues.isNotEmpty()) {
            logger.lifecycle("❌ Validation Issues Found:")
            issues.forEach { issue ->
                logger.lifecycle("   • $issue")
            }
            throw GradleException("❌ Bundle validation failed. Fix the issues above and try again.")
        } else {
            logger.lifecycle("✅ Bundle validation passed!")
        }
    }

    private fun validatePomFile(pomFile: File, issues: MutableList<String>) {
        try {
            val pomContent = pomFile.readText()
            val requiredElements = listOf("name", "description", "url", "licenses", "developers", "scm")

            requiredElements.forEach { element ->
                if (!pomContent.contains("<$element>")) {
                    issues.add("POM ${pomFile.name} missing required element: <$element>")
                }
            }
        } catch (e: Exception) {
            issues.add("Could not read POM file: ${pomFile.name}")
        }
    }

    private fun validateSignature(file: File, issues: MutableList<String>) {
        val signatureFile = File(file.parent, "${file.name}.asc")
        if (!signatureFile.exists()) {
            issues.add("Missing signature for: ${file.name}")
        }
    }
}

/**
 * Plugin for publishing Kotlin Multiplatform projects to Sonatype Central Portal
 */
class CentralPublisherPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Apply required plugins
        project.plugins.apply("maven-publish")
        project.plugins.apply("signing")

        // Create extension
        val extension = project.extensions.create<CentralPublisherExtension>("centralPublisher")

        // Configure automatic publishing
        configurePublishing(project, extension)

        // Configure automatic signing
        configureSigning(project, extension)

        // Register validation task
        project.tasks.register<ValidateBundleTask>("validateCentralBundle") {
            group = "publishing"
            description = "Validate bundle meets Central Portal requirements"

            bundleDirectory.set(project.layout.buildDirectory.dir("central-staging"))
            dependsOn("createCentralBundle")
        }

        // Register upload task
        project.tasks.register<UploadToCentralTask>("uploadToCentral") {
            group = "publishing"
            description = "Upload all publications to Sonatype Central Portal"

            username.set(extension.username)
            password.set(extension.password)
            publishingType.set(extension.publishingType.map { it.apiValue })
            bundleDirectory.set(project.layout.buildDirectory.dir("central-bundle"))

            dependsOn("validateCentralBundle")
        }

        // Register bundle creation task
        project.tasks.register<CreateBundleTask>("createCentralBundle") {
            group = "publishing"
            description = "Create bundle for Central Portal upload"

            bundleFile.set(project.layout.buildDirectory.file("central-bundle.zip"))
            outputDirectory.set(project.layout.buildDirectory.dir("central-staging"))

            dependsOn("publishToMavenLocal")
        }
    }

    private fun configurePublishing(project: Project, extension: CentralPublisherExtension) {
        // Configure publishing after project evaluation to ensure KMP plugin is applied
        project.afterEvaluate {
            val publishingExtension = project.extensions.getByType<PublishingExtension>()

            // Check if Kotlin Multiplatform plugin is applied
            val kotlinExtension = project.extensions.findByName("kotlin")
            if (kotlinExtension != null) {
                configureKotlinMultiplatformPublishing(project, publishingExtension, extension)
            } else {
                // Fallback for regular Kotlin projects
                configureRegularKotlinPublishing(project, publishingExtension, extension)
            }
        }
    }

    private fun configureKotlinMultiplatformPublishing(
        project: Project,
        publishing: PublishingExtension,
        extension: CentralPublisherExtension
    ) {
        // Create javadocJar task if Dokka plugin is applied
        val javadocJar = if (project.plugins.hasPlugin("org.jetbrains.dokka")) {
            project.tasks.register<Jar>("javadocJar") {
                group = "documentation"
                archiveClassifier.set("javadoc")
                from(project.tasks.named("dokkaHtml"))
            }
        } else {
            // Create empty javadoc jar if Dokka is not available
            project.tasks.register<Jar>("javadocJar") {
                group = "documentation"
                archiveClassifier.set("javadoc")
                // Empty jar for Maven Central compliance
            }
        }

        publishing.publications.withType<MavenPublication> {
            // Add javadoc.jar artifact
            artifact(javadocJar.get())

            // Configure POM with provided metadata
            configurePom(this, extension)
        }

        project.logger.lifecycle("🚀 Configured Kotlin Multiplatform publishing for all targets")
    }

    private fun configureRegularKotlinPublishing(
        project: Project,
        publishing: PublishingExtension,
        extension: CentralPublisherExtension
    ) {
        // Create javadocJar task
        val javadocJar = if (project.plugins.hasPlugin("org.jetbrains.dokka")) {
            project.tasks.register<Jar>("javadocJar") {
                group = "documentation"
                archiveClassifier.set("javadoc")
                from(project.tasks.named("dokkaHtml"))
            }
        } else {
            project.tasks.register<Jar>("javadocJar") {
                group = "documentation"
                archiveClassifier.set("javadoc")
                // Empty jar for Maven Central compliance
            }
        }

        publishing.publications.create<MavenPublication>("maven") {
            from(project.components["java"])

            // Add sources jar
            val sourcesJar = project.tasks.register<Jar>("sourcesJar") {
                archiveClassifier.set("sources")
                from(project.extensions.getByType<SourceSetContainer>()["main"].allSource)
            }
            artifact(sourcesJar)

            // Add javadoc.jar artifact
            artifact(javadocJar.get())

            configurePom(this, extension)
        }

        project.logger.lifecycle("🚀 Configured regular Kotlin publishing")
    }

    private fun configurePom(publication: MavenPublication, extension: CentralPublisherExtension) {
        publication.pom {
            name = extension.projectName.getOrElse(publication.artifactId)
            description = extension.projectDescription.getOrElse("")
            url = extension.projectUrl.getOrElse("")

            licenses {
                license {
                    name = extension.licenseName.getOrElse("")
                    url = extension.licenseUrl.getOrElse("")
                }
            }

            developers {
                developer {
                    id = extension.developerId.getOrElse("")
                    name = extension.developerName.getOrElse("")
                    email = extension.developerEmail.getOrElse("")
                }
            }

            scm {
                url = extension.scmUrl.getOrElse("")
                connection = extension.scmConnection.getOrElse("")
                developerConnection = extension.scmDeveloperConnection.getOrElse("")
            }
        }
    }

    private fun configureSigning(project: Project, extension: CentralPublisherExtension) {
        project.extensions.configure<SigningExtension> {
            // Configure signing when credentials are available
            project.afterEvaluate {
                val signingKeyId = extension.signingKeyId.orNull
                val signingPassword = extension.signingPassword.orNull
                val signingSecretKey = extension.signingSecretKey.orNull

                if (signingKeyId != null && signingPassword != null && signingSecretKey != null) {
                    useInMemoryPgpKeys(signingKeyId, signingSecretKey, signingPassword)

                    // Sign all publications
                    val publishingExtension = project.extensions.findByType<PublishingExtension>()
                    publishingExtension?.let {
                        sign(it.publications)
                        project.logger.lifecycle("🔐 Configured automatic GPG signing for all publications")
                    }
                } else {
                    project.logger.warn("⚠️  GPG signing not configured - missing signing credentials")
                    project.logger.warn("   Add SIGNING_KEY_ID, SIGNING_PASSWORD, and SIGNING_SECRET_KEY")
                }
            }
        }
    }
}

/**
 * Publishing type for Central Portal
 */
enum class PublishingType(val apiValue: String) {
    /**
     * Upload for manual release via Central Portal UI
     */
    USER_MANAGED("USER_MANAGED"),

    /**
     * Upload and automatically release after validation
     */
    AUTOMATIC("AUTOMATIC")
}

/**
 * Utility functions for reading credentials from local.properties and environment
 */
object CentralPublisherCredentials {

    /**
     * Read credential from local.properties or environment variables
     * Tries multiple naming conventions automatically
     */
    fun getCredential(project: Project, key: String): String? {
        // Try local.properties first
        val localProps = getLocalProperties(project)
        val possibleKeys = getPossibleKeys(key)

        // Check local.properties
        for (possibleKey in possibleKeys) {
            val value = localProps.getProperty(possibleKey)
            if (!value.isNullOrBlank()) {
                return value
            }
        }

        // Check environment variables
        for (possibleKey in possibleKeys) {
            val value = System.getenv(possibleKey)
            if (!value.isNullOrBlank()) {
                return value
            }
        }

        return null
    }

    /**
     * Get credential with fallback and helpful error message
     */
    fun getRequiredCredential(project: Project, key: String): String {
        return getCredential(project, key)
            ?: throw GradleException("Missing credential '$key'. Add to local.properties or environment variables.")
    }

    private fun getLocalProperties(project: Project): Properties {
        val propertiesFile = project.rootProject.file("local.properties")
        return Properties().apply {
            if (propertiesFile.exists()) {
                try {
                    propertiesFile.reader().use { load(it) }
                } catch (e: Exception) {
                    // Ignore errors, just return empty properties
                }
            }
        }
    }

    private fun getPossibleKeys(key: String): List<String> {
        return listOf(
            key,                          // CENTRAL_TOKEN
            key.lowercase(),              // central_token
            "SONATYPE_$key",             // SONATYPE_CENTRAL_TOKEN
            "sonatype_${key.lowercase()}" // sonatype_central_token
        )
    }
}

/**
 * Extension for configuring the Central Publisher plugin
 */
interface CentralPublisherExtension {
    // Central Portal credentials
    val username: Property<String>
    val password: Property<String>
    val publishingType: Property<PublishingType>
    val timeout: Property<Int>

    // GPG Signing properties
    val signingKeyId: Property<String>
    val signingPassword: Property<String>
    val signingSecretKey: Property<String>

    // Project metadata for POM generation
    val projectName: Property<String>
    val projectDescription: Property<String>
    val projectUrl: Property<String>

    // License information
    val licenseName: Property<String>
    val licenseUrl: Property<String>

    // Developer information
    val developerId: Property<String>
    val developerName: Property<String>
    val developerEmail: Property<String>

    // SCM information
    val scmUrl: Property<String>
    val scmConnection: Property<String>
    val scmDeveloperConnection: Property<String>
}

/**
 * Task to create a bundle for Central Portal
 */
abstract class CreateBundleTask : DefaultTask() {

    @get:OutputFile
    abstract val bundleFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun createBundle() {
        val outputDir = outputDirectory.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        // Get all publications from the project
        val publishing = project.extensions.getByType<PublishingExtension>()
        val localRepoDir = File(project.gradle.gradleUserHomeDir, "caches/modules-2/files-2.1")
        val mavenLocalDir = File(System.getProperty("user.home"), ".m2/repository")

        // Find artifacts in maven local repository
        val groupPath = project.group.toString().replace('.', '/')
        val artifactDir = File(mavenLocalDir, "$groupPath")

        if (!artifactDir.exists()) {
            throw GradleException("No artifacts found in Maven local repository. Run 'publishToMavenLocal' first.")
        }

        // Copy all artifacts to staging directory
        copyArtifactsToStaging(artifactDir, outputDir, project.group.toString(), project.version.toString())

        // Create ZIP bundle
        createZipBundle(outputDir, bundleFile.get().asFile)

        logger.lifecycle("Created bundle: ${bundleFile.get().asFile.absolutePath}")
        logger.lifecycle("Bundle size: ${bundleFile.get().asFile.length() / 1024}KB")
    }

    private fun copyArtifactsToStaging(sourceDir: File, stagingDir: File, group: String, version: String) {
        val projectName = project.name
        var artifactCount = 0
        val targets = mutableSetOf<String>()

        // Auto-discover all Kotlin Multiplatform targets
        sourceDir.walkTopDown().forEach { file ->
            if (file.isFile && file.name.contains(version) && file.name.startsWith(projectName)) {
                val relativePath = file.relativeTo(sourceDir).path
                val targetFile = File(stagingDir, relativePath)
                targetFile.parentFile.mkdirs()
                file.copyTo(targetFile, overwrite = true)

                // Extract target from filename (e.g., reaktiv-core-linuxx64-0.9.0.klib -> linuxx64)
                val artifactPattern = "$projectName-(.+?)-$version\\.(klib|jar|module|pom).*".toRegex()
                val match = artifactPattern.find(file.name)
                if (match != null) {
                    val target = match.groupValues[1]
                    if (target !in setOf("sources", "javadoc")) { // Skip classifier artifacts
                        targets.add(target)
                    }
                }

                artifactCount++
                logger.info("Copied: ${file.name}")
            }
        }

        logger.lifecycle("📦 Auto-discovered ${targets.size} Kotlin Multiplatform targets:")
        targets.sorted().forEach { target ->
            logger.lifecycle("   ✓ $target")
        }
        logger.lifecycle("📁 Total artifacts copied: $artifactCount")
    }

    private fun createZipBundle(sourceDir: File, zipFile: File) {
        zipFile.parentFile.mkdirs()
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            sourceDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val relativePath = file.relativeTo(sourceDir).path.replace('\\', '/')
                    zip.putNextEntry(ZipEntry(relativePath))
                    file.inputStream().use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                }
            }
        }
    }
}

/**
 * Task to upload bundle to Sonatype Central Portal
 */
abstract class UploadToCentralTask : DefaultTask() {

    @get:Input
    abstract val username: Property<String>

    @get:Input
    abstract val password: Property<String>

    @get:Input
    abstract val publishingType: Property<String>

    @get:InputDirectory
    abstract val bundleDirectory: DirectoryProperty

    @TaskAction
    fun upload() {
        // Validate that credentials are provided
        if (username.get().isBlank() || password.get().isBlank()) {
            throw GradleException(
                """
                ❌ Missing Central Portal credentials!
                
                Configure in your build.gradle.kts:
                centralPublisher {
                    username.set(CentralPublisherCredentials.getRequiredCredential(project, "CENTRAL_TOKEN"))
                    password.set(CentralPublisherCredentials.getRequiredCredential(project, "CENTRAL_PASSWORD"))
                }
                
                🔗 Get credentials at: https://central.sonatype.com/account
            """.trimIndent()
            )
        }

        // Create bundle first
        val createBundleTask = project.tasks.getByName("createCentralBundle") as CreateBundleTask
        createBundleTask.createBundle()

        val bundleFile = createBundleTask.bundleFile.get().asFile
        if (!bundleFile.exists()) {
            throw GradleException("Bundle file not found: ${bundleFile.absolutePath}")
        }

        logger.lifecycle("Uploading bundle to Sonatype Central Portal...")
        logger.lifecycle("Bundle: ${bundleFile.absolutePath}")
        logger.lifecycle("Size: ${bundleFile.length() / 1024}KB")

        runBlocking {
            uploadBundle(bundleFile)
        }
    }

    private suspend fun uploadBundle(bundleFile: File) {
        // Validate bundle size first
        val fileSizeGB = bundleFile.length() / (1024.0 * 1024.0 * 1024.0)
        if (fileSizeGB > 1.0) {
            throw GradleException("❌ Bundle size (${String.format("%.2f", fileSizeGB)}GB) exceeds 1GB limit")
        }

        // Create Bearer token from username:password
        val credentials = "${username.get()}:${password.get()}"
        val bearerToken = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())

        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    prettyPrint = true
                })
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 600_000 // 10 minutes
                connectTimeoutMillis = 60_000  // 1 minute
                socketTimeoutMillis = 600_000  // 10 minutes
            }
        }

        try {
            logger.lifecycle("🚀 Uploading to Central Portal...")

            val response = client.submitFormWithBinaryData(
                url = "https://central.sonatype.com/api/v1/publisher/upload",
                formData = formData {
                    append("bundle", bundleFile.readBytes(), Headers.build {
                        append(HttpHeaders.ContentType, "application/zip")
                        append(HttpHeaders.ContentDisposition, "filename=\"bundle.zip\"")
                    })

                    if (publishingType.isPresent) {
                        append("publishingType", publishingType.get())
                    }

                    // Add deployment name for better tracking
                    append("name", "${project.name}-${project.version}")
                }
            ) {
                // Set Bearer token authentication
                header(HttpHeaders.Authorization, "Bearer $bearerToken")
            }

            when (response.status) {
                HttpStatusCode.Created -> {
                    val deploymentId = response.bodyAsText().trim()
                    logger.lifecycle("✅ Successfully uploaded to Central Portal!")
                    logger.lifecycle("📦 Deployment ID: $deploymentId")
                    logger.lifecycle("🌐 View at: https://central.sonatype.com/publishing/deployments")

                    // Check deployment status
                    checkDeploymentStatus(client, deploymentId, bearerToken)

                    if (publishingType.get() == "USER_MANAGED") {
                        logger.lifecycle("⚠️  Manual action required: Go to the portal to publish your deployment")
                    } else {
                        logger.lifecycle("🚀 Automatic publishing enabled - deployment will be published after validation")
                    }
                }

                HttpStatusCode.Unauthorized -> {
                    throw GradleException("❌ Authentication failed. Check your username and password.")
                }

                HttpStatusCode.BadRequest -> {
                    val errorBody = response.bodyAsText()
                    throw GradleException("❌ Bad request: $errorBody")
                }

                HttpStatusCode.PayloadTooLarge -> {
                    throw GradleException("❌ Bundle too large. Maximum size is 1GB.")
                }

                else -> {
                    val errorBody = response.bodyAsText()
                    throw GradleException("❌ Upload failed (${response.status}): $errorBody")
                }
            }

        } catch (e: Exception) {
            if (e is GradleException) throw e
            throw GradleException("❌ Upload failed: ${e.message}", e)
        } finally {
            client.close()
        }
    }

    private suspend fun checkDeploymentStatus(client: HttpClient, deploymentId: String, bearerToken: String) {
        try {
            logger.lifecycle("⏳ Checking deployment status...")

            val statusResponse = client.post("https://central.sonatype.com/api/v1/publisher/status") {
                parameter("id", deploymentId)
                header(HttpHeaders.Authorization, "Bearer $bearerToken")
            }

            if (statusResponse.status == HttpStatusCode.OK) {
                val statusBody = statusResponse.bodyAsText()
                val json = Json { ignoreUnknownKeys = true }
                val statusInfo = json.decodeFromString<DeploymentStatus>(statusBody)

                when (statusInfo.deploymentState) {
                    "PENDING" -> logger.lifecycle("📋 Status: Pending validation")
                    "VALIDATING" -> logger.lifecycle("🔍 Status: Validating artifacts")
                    "VALIDATED" -> logger.lifecycle("✅ Status: Validation passed!")
                    "PUBLISHING" -> logger.lifecycle("🚀 Status: Publishing to Maven Central")
                    "PUBLISHED" -> logger.lifecycle("🎉 Status: Published to Maven Central!")
                    "FAILED" -> {
                        logger.lifecycle("❌ Status: Validation failed")
                        logger.lifecycle("Check the portal for validation errors: https://central.sonatype.com/publishing/deployments")
                    }

                    else -> logger.lifecycle("📋 Status: ${statusInfo.deploymentState}")
                }
            }
        } catch (e: Exception) {
            logger.warn("⚠️  Could not check deployment status: ${e.message}")
        }
    }
}

@Serializable
data class UploadResponse(
    val deploymentId: String? = null,
    val message: String? = null
)

@Serializable
data class DeploymentStatus(
    val deploymentId: String,
    val deploymentName: String? = null,
    val deploymentState: String,
    val purls: List<String>? = null
)