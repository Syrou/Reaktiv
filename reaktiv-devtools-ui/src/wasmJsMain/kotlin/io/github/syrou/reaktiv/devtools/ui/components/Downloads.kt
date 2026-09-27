package io.github.syrou.reaktiv.devtools.ui.components

import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.introspection.PlatformContext
import io.github.syrou.reaktiv.introspection.SessionFileExport
import kotlinx.serialization.json.Json

internal val PrettyJson: Json = Json { prettyPrint = true }

internal fun downloadFile(bytes: ByteArray, fileName: String) {
    try {
        SessionFileExport(PlatformContext()).saveToDownloads(bytes, fileName)
    } catch (e: Exception) {
        ReaktivDebug.error("DevTools UI could not download $fileName", e)
    }
}
