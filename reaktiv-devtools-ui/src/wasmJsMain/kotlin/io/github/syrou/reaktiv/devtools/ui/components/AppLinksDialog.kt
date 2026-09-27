package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.syrou.reaktiv.devtools.ui.AppLinksForm
import io.github.syrou.reaktiv.devtools.ui.AppLinksTab
import io.github.syrou.reaktiv.devtools.ui.DevToolsColors
import io.github.syrou.reaktiv.devtools.ui.RequestStatus
import io.github.syrou.reaktiv.devtools.ui.ServiceCall
import io.github.syrou.reaktiv.devtools.ui.navmap.AppLinkFilesModel
import io.github.syrou.reaktiv.devtools.ui.navmap.parseAppLinkFiles

internal const val APPLE_APP_SITE_ASSOCIATION_FILE: String = "apple-app-site-association"
internal const val ASSET_LINKS_FILE: String = "assetlinks.json"
internal const val MANIFEST_SNIPPET_FILE: String = "AndroidManifest-app-links.xml"

private val PREVIEW_HEIGHT = 340.dp

@Composable
internal fun AppLinksDialog(
    form: AppLinksForm,
    tab: AppLinksTab,
    call: ServiceCall?,
    defaultBasePath: String,
    blockedReason: String?,
    onFormChange: (AppLinksForm) -> Unit,
    onTabChange: (AppLinksTab) -> Unit,
    onGenerate: (AppLinksForm) -> Unit,
    onDownload: (fileName: String, content: String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val files: AppLinkFilesModel? = call?.takeIf { it.status == RequestStatus.ANSWERED && it.error == null }
        ?.let { parseAppLinkFiles(it.result) }

    fun regenerate(next: AppLinksForm) {
        onFormChange(next)
        if (next.host.isNotBlank() && blockedReason == null) onGenerate(next)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier.widthIn(max = 920.dp).fillMaxWidth(0.92f).heightIn(max = 860.dp).padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("App links", style = MaterialTheme.typography.headlineSmall, color = colors.onSurface)
                Text(
                    "Generates the files iOS universal links and Android App Links need, from the routes this app " +
                        "registers. Pick the routes that should open the app, then download the files.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = form.host,
                        onValueChange = { onFormChange(form.copy(host = it)) },
                        label = { Text("Host") },
                        placeholder = { Text("example.com") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = form.basePath,
                        onValueChange = { onFormChange(form.copy(basePath = it)) },
                        label = { Text("Base path") },
                        placeholder = { Text(defaultBasePath) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = form.appleAppIds,
                    onValueChange = { onFormChange(form.copy(appleAppIds = it)) },
                    label = { Text("iOS app IDs (Team ID and bundle ID)") },
                    placeholder = { Text("ABCDE12345.com.example.app") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = form.androidPackage,
                        onValueChange = { onFormChange(form.copy(androidPackage = it)) },
                        label = { Text("Android package") },
                        placeholder = { Text("com.example.app") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = form.androidCertFingerprints,
                        onValueChange = { onFormChange(form.copy(androidCertFingerprints = it)) },
                        label = { Text("Android SHA-256 signing fingerprints") },
                        placeholder = { Text("14:6D:E9:83:...") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onFormChange(form.copy(androidDynamicPaths = !form.androidDynamicPaths)) }
                ) {
                    Checkbox(checked = form.androidDynamicPaths, onCheckedChange = { onFormChange(form.copy(androidDynamicPaths = it)) })
                    Text(
                        "List the paths in assetlinks.json too (dynamic app links, Android 15 and newer)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onGenerate(form) }, enabled = form.host.isNotBlank() && blockedReason == null) {
                        Text(if (files == null) "Generate" else "Generate again")
                    }
                    val status = when {
                        blockedReason != null -> blockedReason
                        form.host.isBlank() -> "Enter the host the links use"
                        call == null -> null
                        call.progressText != null -> call.progressText
                        files == null -> "The device answered without files. Is NavigationLinks up to date?"
                        else -> null
                    }
                    status?.let { Caption(it) }
                }

                if (files != null) {
                    SecondaryTabRow(selectedTabIndex = tab.ordinal, containerColor = colors.surface) {
                        AppLinksTab.entries.forEach { entry ->
                            Tab(
                                selected = entry == tab,
                                onClick = { onTabChange(entry) },
                                text = {
                                    Text(
                                        if (entry == AppLinksTab.ROUTES) {
                                            "${entry.label} ${files.paths.size}/${files.candidates.size}"
                                        } else {
                                            entry.label
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = if (entry == AppLinksTab.ROUTES) null else FontFamily.Monospace,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            )
                        }
                    }
                    val host = form.host.ifBlank { "host" }
                    when (tab) {
                        AppLinksTab.ROUTES -> RouteChoices(
                            files = files,
                            selected = form.selectedPaths ?: files.candidates.map { it.path }.toSet(),
                            onSelect = { regenerate(form.copy(selectedPaths = it)) }
                        )
                        AppLinksTab.APPLE -> GeneratedFile(
                            where = "Serve at https://$host/.well-known/$APPLE_APP_SITE_ASSOCIATION_FILE " +
                                "as application/json without redirects.",
                            content = files.appleAppSiteAssociation,
                            missing = "Enter an iOS app ID to generate it.",
                            copyLabel = "Copy $APPLE_APP_SITE_ASSOCIATION_FILE",
                            onDownload = { onDownload(APPLE_APP_SITE_ASSOCIATION_FILE, it) }
                        )
                        AppLinksTab.ASSET_LINKS -> GeneratedFile(
                            where = "Serve at https://$host/.well-known/$ASSET_LINKS_FILE.",
                            content = files.assetLinks,
                            missing = "Enter the Android package to generate it.",
                            copyLabel = "Copy $ASSET_LINKS_FILE",
                            onDownload = { onDownload(ASSET_LINKS_FILE, it) }
                        )
                        AppLinksTab.MANIFEST -> GeneratedFile(
                            where = "Add this intent filter inside the activity that handles the links.",
                            content = files.androidManifestIntentFilter,
                            missing = "",
                            copyLabel = "Copy intent filter",
                            onDownload = { onDownload(MANIFEST_SNIPPET_FILE, it) }
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun RouteChoices(
    files: AppLinkFilesModel,
    selected: Set<String>,
    onSelect: (Set<String>?) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onSelect(null) }, enabled = selected.size < files.candidates.size) {
                Text("Select all", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = { onSelect(emptySet()) }, enabled = selected.isNotEmpty()) {
                Text("Select none", style = MaterialTheme.typography.labelSmall)
            }
            Caption("${files.paths.size} of ${files.candidates.size} selected")
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = PREVIEW_HEIGHT)
                .background(colors.surfaceVariant, RoundedCornerShape(6.dp))
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp)
        ) {
            files.candidates.forEach { candidate ->
                val checked = candidate.path in selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        onSelect(if (checked) selected - candidate.path else selected + candidate.path)
                    }.padding(horizontal = 4.dp)
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(
                        candidate.pattern,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(320.dp)
                    )
                    Caption(candidate.screen ?: candidate.target, singleLine = true)
                }
            }
        }
    }
}

@Composable
private fun GeneratedFile(
    where: String,
    content: String?,
    missing: String,
    copyLabel: String,
    onDownload: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (content == null) {
            Caption(missing)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onDownload(content) }) { Text("Download", style = MaterialTheme.typography.labelSmall) }
            CopyControl(actions = listOf(CopyAction(copyLabel) { content }))
            Caption(where)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = PREVIEW_HEIGHT)
                .background(colors.surfaceVariant, RoundedCornerShape(6.dp))
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            Text(content, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = DevToolsColors.success)
        }
    }
}
