import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
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
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.assign
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.plugins.signing.Sign
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
        var checksumCount = 0

        // Track publications to ensure we have complete sets
        val publications = mutableSetOf<String>()
        val javadocPublications = mutableSetOf<String>()
        val orphanedSignatures = mutableSetOf<String>()

        stagingDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val relativePath = file.relativeTo(stagingDir).path.replace('\\', '/')

                when {
                    file.name.endsWith(".pom") -> {
                        pomCount++
                        validatePomFile(file, issues)
                        validateSignature(file, issues)
                        validateChecksums(file, issues)

                        // Extract publication name from path for tracking
                        val pathSegments = relativePath.split('/')
                        if (pathSegments.size >= 2) {
                            publications.add(pathSegments[pathSegments.size - 2]) // artifact directory
                        }
                    }

                    file.name.endsWith(".jar") || file.name.endsWith(".klib") || file.name.endsWith(".aar") -> {
                        artifactCount++
                        validateSignature(file, issues)
                        validateChecksums(file, issues)

                        when {
                            file.name.contains("-sources.jar") -> sourcesCount++
                            file.name.contains("-javadoc.jar") -> {
                                javadocCount++
                                // Track which publication has javadoc
                                val pathSegments = relativePath.split('/')
                                if (pathSegments.size >= 2) {
                                    javadocPublications.add(pathSegments[pathSegments.size - 2])
                                }
                            }
                        }
                    }

                    file.name.endsWith(".zip") -> {
                        // Validate kotlin_resources and other zip files
                        validateSignature(file, issues)
                        validateChecksums(file, issues)
                        artifactCount++
                    }

                    file.name.endsWith(".asc") -> {
                        signedCount++
                        // Check if the signed file actually exists
                        val originalFile = File(file.parent, file.name.removeSuffix(".asc"))
                        if (!originalFile.exists()) {
                            orphanedSignatures.add(file.name)
                        }
                    }

                    file.name.endsWith(".md5") || file.name.endsWith(".sha1") ||
                            file.name.endsWith(".sha256") || file.name.endsWith(".sha512") -> checksumCount++
                }
            }
        }

        // Validate overall structure
        if (pomCount == 0) issues.add("No POM files found")
        if (artifactCount == 0) issues.add("No artifact files found")

        // Check for orphaned signature files
        if (orphanedSignatures.isNotEmpty()) {
            issues.add("Found ${orphanedSignatures.size} orphaned signature files (signatures without original files):")
            orphanedSignatures.take(5).forEach { sig ->
                issues.add("  • $sig")
            }
        }

        // Check javadoc requirements for each publication
        publications.forEach { pub ->
            if (!javadocPublications.contains(pub) && !pub.contains("metadata") && !pub.contains("sources")) {
                issues.add("Missing javadoc for publication: $pub")
                logger.warn("⚠️  Publication '$pub' missing javadoc - Central Portal requires javadoc for all publications")
            }
        }

        // Report results
        logger.lifecycle("📊 Validation Results:")
        logger.lifecycle("   📦 Artifacts: $artifactCount")
        logger.lifecycle("   📝 POM files: $pomCount")
        logger.lifecycle("   📚 Publications: ${publications.size}")
        logger.lifecycle("   ✍️ Signatures: $signedCount")
        logger.lifecycle("   🔢 Checksums: $checksumCount")
        logger.lifecycle("   📚 Sources: $sourcesCount")
        logger.lifecycle("   📖 Javadoc: $javadocCount")
        logger.lifecycle("   👻 Orphaned signatures: ${orphanedSignatures.size}")

        // List discovered publications
        if (publications.isNotEmpty()) {
            logger.lifecycle("   🎯 Discovered publications:")
            publications.sorted().forEach { pub ->
                val hasJavadoc = javadocPublications.contains(pub)
                val status = if (hasJavadoc) "✅" else "❌"
                logger.lifecycle("      $status $pub")
            }
        }

        if (issues.isNotEmpty()) {
            logger.lifecycle("❌ Validation Issues Found:")
            issues.forEach { issue ->
                logger.lifecycle("   • $issue")
            }

            // Provide specific guidance
            if (orphanedSignatures.isNotEmpty()) {
                logger.lifecycle("")
                logger.lifecycle("🔧 To fix orphaned signatures:")
                logger.lifecycle("   1. Check if kotlin_resources files are missing")
                logger.lifecycle("   2. Verify all signed files exist in Maven local repository")
                logger.lifecycle("   3. Re-run 'publishToMavenLocal' if needed")
            }

            throw GradleException("❌ Bundle validation failed. Fix the issues above and try again.")
        } else {
            logger.lifecycle("✅ Bundle validation passed!")
            logger.lifecycle("📂 Bundle follows correct Maven repository structure")
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

    private fun validateChecksums(file: File, issues: MutableList<String>) {
        val requiredChecksums = listOf("md5", "sha1", "sha256", "sha512")
        requiredChecksums.forEach { ext ->
            val checksumFile = File(file.parent, "${file.name}.$ext")
            if (!checksumFile.exists()) {
                issues.add("Missing $ext checksum for: ${file.name}")
            }
        }
    }
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
        val mavenLocalDir = File(System.getProperty("user.home"), ".m2/repository")

        if (!mavenLocalDir.exists()) {
            throw GradleException("Maven local repository not found. Run 'publishToMavenLocal' first.")
        }

        // Copy artifacts maintaining full Maven repository structure (including groupId path)
        copyArtifactsToStaging(mavenLocalDir, outputDir, project.group.toString(), project.version.toString())

        // Create ZIP bundle
        createZipBundle(outputDir, bundleFile.get().asFile)

        logger.lifecycle("✅ Created bundle: ${bundleFile.get().asFile.absolutePath}")
        logger.lifecycle("📏 Bundle size: ${bundleFile.get().asFile.length() / 1024}KB")
    }

    private fun copyArtifactsToStaging(mavenLocalDir: File, stagingDir: File, group: String, version: String) {
        val projectName = project.name
        var artifactCount = 0
        val targets = mutableSetOf<String>()
        val groupPath = group.replace('.', '/')

        // Gradle plugin markers are published under plugin ID path (e.g., io/github/syrou/reaktiv/tracing/)
        val pluginMarkerPaths = mutableSetOf<String>()
        val gradlePluginExtension = project.extensions.findByName("gradlePlugin")
        if (gradlePluginExtension != null) {
            try {
                val pluginsContainer = gradlePluginExtension.javaClass.getMethod("getPlugins").invoke(gradlePluginExtension)
                val iterator = pluginsContainer.javaClass.getMethod("iterator").invoke(pluginsContainer) as Iterator<*>
                iterator.forEach { plugin ->
                    val pluginId = plugin!!.javaClass.getMethod("getId").invoke(plugin) as String
                    pluginMarkerPaths.add(pluginId.replace('.', '/'))
                }
            } catch (e: Exception) {
                logger.warn("⚠️  Could not extract plugin IDs: ${e.message}")
            }
        }

        logger.lifecycle("🔍 Debugging artifact discovery:")
        logger.lifecycle("   📁 Maven local: ${mavenLocalDir.absolutePath}")
        logger.lifecycle("   🏷️ Group path: $groupPath")
        logger.lifecycle("   📦 Project name: $projectName")
        logger.lifecycle("   🔢 Version: $version")
        if (pluginMarkerPaths.isNotEmpty()) {
            logger.lifecycle("   🔌 Plugin markers: ${pluginMarkerPaths.size}")
        }

        val groupDir = File(mavenLocalDir, groupPath)
        if (!groupDir.exists()) {
            logger.warn("⚠️  Group directory doesn't exist: ${groupDir.absolutePath}")
            return
        }

        logger.lifecycle("   ✅ Group directory found: ${groupDir.absolutePath}")

        var totalFilesInGroup = 0
        var matchingProjectFiles = 0

        mavenLocalDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val relativePath = file.relativeTo(mavenLocalDir).path.replace('\\', '/')

                val belongsToThisProject = relativePath.startsWith("$groupPath/$projectName/") ||
                        relativePath.startsWith("$groupPath/$projectName-")

                val belongsToPluginMarker = pluginMarkerPaths.any { markerPath ->
                    relativePath.startsWith("$markerPath/")
                }

                if (relativePath.startsWith(groupPath)) {
                    totalFilesInGroup++
                }

                if ((belongsToThisProject || belongsToPluginMarker) &&
                    file.name.contains(version) &&
                    (file.name.endsWith(".jar") || file.name.endsWith(".klib") ||
                            file.name.endsWith(".aar") || file.name.endsWith(".pom") ||
                            file.name.endsWith(".module") || file.name.endsWith(".asc") ||
                            file.name.endsWith(".md5") || file.name.endsWith(".sha1") ||
                            file.name.endsWith(".sha256") || file.name.endsWith(".sha512") ||
                            file.name.endsWith(".json") || file.name.endsWith(".zip"))
                ) {
                    matchingProjectFiles++

                    val targetFile = File(stagingDir, relativePath)
                    targetFile.parentFile.mkdirs()
                    file.copyTo(targetFile, overwrite = true)

                    if (!file.name.endsWith(".md5") && !file.name.endsWith(".sha1") &&
                        !file.name.endsWith(".sha256") && !file.name.endsWith(".sha512") &&
                        !file.name.endsWith(".asc")
                    ) {
                        generateChecksums(targetFile)
                    }

                    if (file.name.startsWith(projectName)) {
                        val artifactPattern = "$projectName-(.+?)-$version\\.(klib|jar|aar|module|pom).*".toRegex()
                        val match = artifactPattern.find(file.name)
                        if (match != null) {
                            val target = match.groupValues[1]
                            if (target !in setOf("sources", "javadoc")) {
                                targets.add(target)
                            }
                        }
                    }

                    artifactCount++
                    logger.lifecycle("   ✅ Copied: $relativePath")
                }
            }
        }

        logger.lifecycle("📊 Discovery Summary:")
        logger.lifecycle("   📁 Total files in group: $totalFilesInGroup")
        logger.lifecycle("   📦 Files for THIS project: $matchingProjectFiles")
        logger.lifecycle("   ✅ Files copied: $artifactCount")

        if (artifactCount == 0) {
            logger.lifecycle("❌ No artifacts found! Possible issues:")
            logger.lifecycle("   1. Run 'publishToMavenLocal' first")
            logger.lifecycle("   2. Check if group/version/project name are correct")
            logger.lifecycle("   3. Verify artifacts exist in: ${groupDir.absolutePath}")

            File(groupDir, projectName).walkTopDown().take(5).forEach { file ->
                if (file.isFile) {
                    logger.lifecycle("   📄 Example file found: ${file.name}")
                }
            }
        }

        validateBundleStructure(stagingDir)

        logger.lifecycle("📦 Auto-discovered ${targets.size} Kotlin Multiplatform targets:")
        targets.sorted().forEach { target ->
            logger.lifecycle("   ✓ $target")
        }
        logger.lifecycle("📁 Total artifacts copied: $artifactCount")
        logger.lifecycle("📂 Bundle structure: Preserving full Maven repository layout ($groupPath/...)")
        logger.lifecycle("🎯 Project-specific bundle created for: $projectName")
    }

    private fun validateBundleStructure(bundleDir: File) {
        val issues = mutableListOf<String>()

        bundleDir.walkTopDown().forEach { file ->
            if (file.isFile && !file.name.endsWith(".asc") &&
                !file.name.endsWith(".md5") && !file.name.endsWith(".sha1") &&
                !file.name.endsWith(".sha256") && !file.name.endsWith(".sha512")
            ) {

                // Check for required checksums
                val requiredChecksums = listOf("md5", "sha1", "sha256", "sha512")
                requiredChecksums.forEach { ext ->
                    val checksumFile = File(file.parent, "${file.name}.$ext")
                    if (!checksumFile.exists()) {
                        logger.warn("⚠️  Missing checksum: ${checksumFile.name}")
                        // Generate missing checksum
                        generateChecksum(file, ext)
                    }
                }

                // Check for signatures (except checksum files)
                val signatureFile = File(file.parent, "${file.name}.asc")
                if (!signatureFile.exists()) {
                    issues.add("Missing signature for: ${file.name}")
                }
            }
        }

        if (issues.isNotEmpty()) {
            logger.warn("❌ Bundle validation issues:")
            issues.forEach { issue ->
                logger.warn("   • $issue")
            }
        } else {
            logger.lifecycle("✅ Bundle structure validation passed!")
        }
    }

    private fun generateChecksums(file: File) {
        if (!file.exists()) return

        val algorithms = listOf("md5", "sha1", "sha256", "sha512")
        algorithms.forEach { algorithm ->
            generateChecksum(file, algorithm)
        }
    }

    private fun generateChecksum(file: File, algorithm: String) {
        if (!file.exists()) return

        val digest = when (algorithm.lowercase()) {
            "md5" -> java.security.MessageDigest.getInstance("MD5")
            "sha1" -> java.security.MessageDigest.getInstance("SHA-1")
            "sha256" -> java.security.MessageDigest.getInstance("SHA-256")
            "sha512" -> java.security.MessageDigest.getInstance("SHA-512")
            else -> return
        }

        val checksum = file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead = input.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        val checksumFile = File("${file.absolutePath}.$algorithm")
        checksumFile.writeText(checksum)
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

    @get:InputFile
    abstract val bundleFile: RegularFileProperty

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

        val bundleZipFile = bundleFile.get().asFile
        if (!bundleZipFile.exists()) {
            throw GradleException("Bundle file not found: ${bundleZipFile.absolutePath}")
        }

        logger.lifecycle("🚀 Uploading bundle to Sonatype Central Portal...")
        logger.lifecycle("📦 Bundle: ${bundleZipFile.absolutePath}")
        logger.lifecycle("📏 Size: ${bundleZipFile.length() / 1024}KB")

        runBlocking {
            uploadBundle(bundleZipFile)
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
            logger.lifecycle("📤 Uploading to Central Portal...")

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

            val statusResponse = client.get("https://central.sonatype.com/api/v1/publisher/status") {
                parameter("id", deploymentId)
                header(HttpHeaders.Authorization, "Bearer $bearerToken")
            }

            if (statusResponse.status == HttpStatusCode.OK) {
                try {
                    val statusBody = statusResponse.bodyAsText()
                    logger.info("Raw status response: $statusBody")

                    val json = Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    }
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
                } catch (e: Exception) {
                    logger.warn("⚠️  Could not parse deployment status response: ${e.message}")
                    logger.lifecycle("📋 Status check completed - check the portal for details")
                }
            } else {
                logger.warn("⚠️  Status check failed with HTTP ${statusResponse.status}")
                logger.warn("⚠️  Status body: ${statusResponse.bodyAsText()}")
                logger.lifecycle("📋 Check status manually at: https://central.sonatype.com/publishing/deployments")
            }
        } catch (e: Exception) {
            logger.warn("⚠️  Could not check deployment status: ${e.message}")
            logger.lifecycle("📋 Check status manually at: https://central.sonatype.com/publishing/deployments")
        }
    }
}

/**
 * Plugin for publishing Kotlin Multiplatform projects to Sonatype Central Portal
 */
class CentralPublisherPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Apply required plugins first
        project.plugins.apply("maven-publish")
        project.plugins.apply("signing")

        // Create extension immediately so it's available for configuration
        val extension = project.extensions.create<CentralPublisherExtension>("centralPublisher")

        // Defer configuration until after project evaluation
        project.afterEvaluate {
            // Configure everything after plugins are applied and project is evaluated
            configurePublishing(project, extension)
            configureSigning(project, extension)
            registerTasks(project, extension)
        }
    }

    private fun registerTasks(project: Project, extension: CentralPublisherExtension) {
        // Register debug task to check signing configuration
        project.tasks.register("debugSigning") {
            group = "debugging"
            description = "Debug signing configuration and tasks"

            doLast {
                println("🔍 Signing Debug Information:")

                val signingTasks = project.tasks.withType<Sign>()
                println("   📝 Found ${signingTasks.size} signing tasks:")
                signingTasks.forEach { task ->
                    println("      - ${task.name}")
                }

                val publishingTasks = project.tasks.matching {
                    it.name.startsWith("publish") && it.name.contains("ToMavenLocal")
                }
                println("   📦 Found ${publishingTasks.size} publishing tasks:")
                publishingTasks.forEach { task ->
                    println("      - ${task.name}")
                }

                val signingExtension = project.extensions.findByType<SigningExtension>()
                println("   🔐 Signing extension configured: ${signingExtension != null}")

                if (signingExtension != null) {
                    println("   📋 Signing configuration:")
                    println("      - Required: ${signingExtension.isRequired}")

                    val publishingExtension = project.extensions.findByType<PublishingExtension>()
                    publishingExtension?.publications?.forEach { publication ->
                        println("      - Publication '${publication.name}' configured for signing")
                    }
                }
            }
        }

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
            bundleFile.set(project.layout.buildDirectory.file("central-bundle.zip"))

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
        val publishingExtension = project.extensions.getByType<PublishingExtension>()

        // Check if Kotlin Multiplatform plugin is applied (not just kotlin-jvm)
        val isMultiplatform = project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")
        if (isMultiplatform) {
            configureKotlinMultiplatformPublishing(project, publishingExtension, extension)
        } else {
            // Regular Kotlin JVM or Java projects
            configureRegularKotlinPublishing(project, publishingExtension, extension)
        }
    }

    private fun configureKotlinMultiplatformPublishing(
        project: Project,
        publishing: PublishingExtension,
        extension: CentralPublisherExtension
    ) {
        // Create ONE shared javadoc jar for KMP and JVM publications
        val sharedJavadocJar = if (project.plugins.hasPlugin("org.jetbrains.dokka")) {
            project.tasks.register<Jar>("javadocJar") {
                group = "documentation"
                archiveClassifier.set("javadoc")
                from(project.tasks.named("dokkaHtml"))
                archiveBaseName.set(project.name)
            }
        } else {
            // Create empty javadoc jar for Maven Central compliance
            project.tasks.register<Jar>("javadocJar") {
                group = "documentation"
                archiveClassifier.set("javadoc")
                archiveBaseName.set(project.name)

                // Create minimal HTML content for empty javadoc
                doFirst {
                    val tempDir = File(project.layout.buildDirectory.asFile.get(), "tmp/javadoc")
                    tempDir.mkdirs()
                    File(tempDir, "index.html").writeText(
                        """
                        <!DOCTYPE html>
                        <html>
                        <head><title>${project.name} Documentation</title></head>
                        <body>
                            <h1>${project.name}</h1>
                            <p>Documentation for ${project.name} version ${project.version}</p>
                            <p>For detailed API documentation, please refer to the source code and KDoc comments.</p>
                            <p>To generate rich documentation, apply the Dokka plugin: <code>id("org.jetbrains.dokka")</code></p>
                        </body>
                        </html>
                    """.trimIndent()
                    )
                    from(tempDir)
                }
            }
        }

        project.afterEvaluate {
            publishing.publications.withType<MavenPublication> {
                when {
                    name == "kotlinMultiplatform" -> {
                        // Main KMP publication gets javadoc
                        artifact(sharedJavadocJar.get())
                        project.logger.lifecycle("   📚 Added javadoc to main kotlinMultiplatform publication")
                    }

                    name.contains("jvm", ignoreCase = true) ||
                            name.endsWith("-java") ||
                            artifactId.contains("jvm", ignoreCase = true) ||
                            artifactId.endsWith("-java") -> {
                        // JVM target publications also get javadoc (Central Portal requires this)
                        artifact(sharedJavadocJar.get())
                        project.logger.lifecycle("   📚 Added javadoc to JVM publication: $name")
                    }

                    else -> {
                        // Other target publications: skip javadoc to avoid conflicts
                        project.logger.info("   🎯 Skipping javadoc for non-JVM target publication: $name")
                    }
                }

                // Configure POM for all publications
                configurePom(this, extension)
            }
        }

        // Fix signing and checksum generation
        configureSigningDependencies(project)

        project.logger.lifecycle("🚀 Configured Kotlin Multiplatform publishing (javadoc for KMP + JVM publications)")
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
                archiveBaseName.set("${project.name}")
            }
        } else {
            project.tasks.register<Jar>("javadocJar") {
                group = "documentation"
                archiveClassifier.set("javadoc")
                archiveBaseName.set("${project.name}")
                // Empty jar for Maven Central compliance
            }
        }

        // Create sources jar task
        val sourcesJar = project.tasks.register<Jar>("sourcesJar") {
            archiveClassifier.set("sources")
            from(project.extensions.getByType<SourceSetContainer>()["main"].allSource)
        }

        // Check if java-gradle-plugin is applied (creates its own publications)
        val hasGradlePlugin = project.plugins.hasPlugin("java-gradle-plugin")

        if (hasGradlePlugin) {
            // Configure existing publications created by java-gradle-plugin
            project.afterEvaluate {
                publishing.publications.withType<MavenPublication> {
                    // Skip plugin marker publications (they don't need javadoc)
                    if (!name.endsWith("PluginMarkerMaven")) {
                        artifact(javadocJar.get())
                        artifact(sourcesJar.get())
                        configurePom(this, extension)
                        project.logger.lifecycle("   📚 Added javadoc/sources to gradle plugin publication: $name")
                    } else {
                        // Plugin markers still need POM metadata
                        configurePom(this, extension)
                    }
                }
            }
            project.logger.lifecycle("🚀 Configured Gradle plugin publishing with javadoc")
        } else {
            // Create standard maven publication for regular JVM projects
            publishing.publications.create<MavenPublication>("maven") {
                from(project.components["java"])

                artifact(sourcesJar)
                artifact(javadocJar.get())

                configurePom(this, extension)
            }
            project.logger.lifecycle("🚀 Configured regular Kotlin publishing")
        }

        // Configure signing dependencies for regular projects too
        configureSigningDependencies(project)
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
        project.afterEvaluate {
            val signingKeyId = extension.signingKeyId.orNull
            val signingPassword = extension.signingPassword.orNull
            val signingSecretKey = extension.signingSecretKey.orNull

            if (signingPassword != null && signingSecretKey != null) {
                project.extensions.configure<SigningExtension> {
                    // Make signing required so it doesn't get skipped
                    setRequired { true }

                    var processedKey = signingSecretKey

                    // Check for and fix literal \n characters
                    if (signingSecretKey.contains("\\n")) {
                        project.logger.lifecycle("🔧 Detected literal \\n in key, converting to actual newlines")
                        processedKey = processedKey.replace("\\n", "\n")
                    }

                    // Check for and fix Windows line endings
                    if (processedKey.contains("\r\n")) {
                        project.logger.lifecycle("🔧 Detected Windows line endings, converting to Unix format")
                        processedKey = processedKey.replace("\r\n", "\n")
                    }

                    // Use in-memory keys with fallback to 2-parameter version
                    if (signingKeyId != null) {
                        useInMemoryPgpKeys(signingKeyId, processedKey, signingPassword)
                    } else {
                        useInMemoryPgpKeys(processedKey, signingPassword)
                    }

                    // Sign all publications
                    val publishingExtension = project.extensions.findByType<PublishingExtension>()
                    publishingExtension?.let { publishing ->
                        sign(*publishing.publications.toTypedArray())
                        project.logger.lifecycle("🔐 Configured automatic GPG signing for ${publishing.publications.size} publications")

                        // List all publications being signed
                        publishing.publications.forEach { publication ->
                            project.logger.info("   📝 Signing publication: ${publication.name}")
                        }
                    }
                }

                // Configure task dependencies after signing is set up
                configureSigningDependencies(project)
            } else {
                project.logger.warn("⚠️  GPG signing not configured - missing signing credentials")
                project.logger.warn("   Add SIGNING_PASSWORD and SIGNING_SECRET_KEY")
                if (signingKeyId == null) project.logger.warn("   SIGNING_KEY_ID is optional but recommended")
            }
        }
    }

    /**
     * Configure proper task dependencies for signing in KMP projects
     */
    private fun configureSigningDependencies(project: Project) {
        project.tasks.configureEach {
            // Make all publishToMavenLocal tasks depend on signing tasks
            if (this.name.startsWith("publish") && this.name.contains("ToMavenLocal")) {
                // Get all signing tasks and make this publish task depend on them
                val signingTasks = project.tasks.withType<Sign>()
                this.dependsOn(signingTasks)

                project.logger.info("🔗 Task ${this.name} now depends on ${signingTasks.size} signing tasks")
            }
        }

        // Also ensure publishToMavenLocal (the aggregate task) depends on all signing
        project.tasks.matching { it.name == "publishToMavenLocal" }.configureEach {
            val signingTasks = project.tasks.withType<Sign>()
            this.dependsOn(signingTasks)
            project.logger.info("🔗 Main publishToMavenLocal task now depends on ${signingTasks.size} signing tasks")
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
        project.logger.lifecycle("🔍 Looking for credential: $key")

        // Try local.properties first
        val localProps = getLocalProperties(project)

        // Check local.properties
        val localPropertyValue = localProps.getProperty(key)
        if (!localPropertyValue.isNullOrBlank()) {
            project.logger.lifecycle("✅ Found $key in local.properties")
            return localPropertyValue
        }

        // Check environment variables
        val systemEnvironmentValue = System.getenv(key)
        if (!systemEnvironmentValue.isNullOrBlank()) {
            project.logger.lifecycle("✅ Found $key in environment")
            return systemEnvironmentValue
        } else {
            project.logger.lifecycle("❌ Environment variable $key is null or blank")
        }

        project.logger.lifecycle("❌ Credential $key not found anywhere")

        return null
    }

    /**
     * Get credential with fallback and helpful error message
     */
    fun getRequiredCredential(project: Project, key: String): String {
        return getCredential(project, key)
            ?: throw GradleException("Missing credential '$key'. Add to local.properties or environment variables.")
    }

    /**
     * Returns a lazy [Provider] that resolves the credential only when a task executes.
     * Use this in `centralPublisher { }` configuration blocks via `property.set(...)` so
     * that tasks like `dokkaGeneratePublicationHtml` can run in environments without
     * publishing credentials. The `uploadToCentral` task validates that the resolved
     * value is non-blank before attempting to publish.
     */
    fun credentialProvider(project: Project, key: String): Provider<String> {
        return project.providers.provider { getCredential(project, key) ?: "" }
    }

    private fun getLocalProperties(project: Project): Properties {
        // First try the current project's root
        var propertiesFile = project.rootProject.file("local.properties")

        // If not found, search parent directories (for included builds)
        if (!propertiesFile.exists()) {
            var dir = project.rootProject.projectDir.parentFile
            while (dir != null) {
                val parentProps = java.io.File(dir, "local.properties")
                if (parentProps.exists()) {
                    propertiesFile = parentProps
                    break
                }
                dir = dir.parentFile
            }
        }

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