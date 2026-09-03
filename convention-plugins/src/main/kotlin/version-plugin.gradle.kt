import org.gradle.api.Plugin
import org.gradle.api.Project

class VersionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.version = getVersionName(project)
    }

    private fun getVersionName(project: Project): String {
        return try {
            val output = project.providers.exec {
                commandLine("git", "describe", "--tags", "--abbrev=0")
            }.standardOutput.asText.get().trim()
            output.ifEmpty { DEFAULT_VERSION }
        } catch (e: Exception) {
            val reason = e.message?.lineSequence()?.firstOrNull() ?: e::class.simpleName
            project.logger.warn(
                "Version: no Git tag describes HEAD ($reason), using $DEFAULT_VERSION. " +
                    "Fetch tags (fetch-depth: 0 in CI) to build with the real version."
            )
            DEFAULT_VERSION
        }
    }

    private companion object {
        const val DEFAULT_VERSION = "0.7.6-SNAPSHOT"
    }
}
