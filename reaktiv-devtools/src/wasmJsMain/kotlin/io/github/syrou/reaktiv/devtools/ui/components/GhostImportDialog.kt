package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Dialog for importing a ghost device session from JSON.
 * Supports both file upload and paste.
 *
 * Usage:
 * ```kotlin
 * if (showImportDialog) {
 *     GhostImportDialog(
 *         onImport = { json -> logic.importGhostSession(json) },
 *         onDismiss = { dispatch(DevToolsUiAction.SetOverlay(Overlay.None)) }
 *     )
 * }
 * ```
 */
@Composable
internal fun GhostImportDialog(
    onImport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var jsonInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "Import Ghost Session",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Upload a ghost session JSON file or paste the content below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // File upload button
                OutlinedButton(
                    onClick = {
                        openFilePicker { content, name ->
                            if (name.startsWith("error:")) {
                                errorMessage = name.removePrefix("error:")
                                fileName = null
                            } else {
                                jsonInput = content
                                fileName = name
                                errorMessage = null
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.UploadFile,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(fileName ?: "Choose Session File (.json or .json.gz)")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Or paste JSON below:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = jsonInput,
                    onValueChange = {
                        jsonInput = it
                        errorMessage = null
                        fileName = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp, max = 300.dp),
                    label = { Text("Session JSON") },
                    placeholder = { Text("Paste your ghost session JSON here...") },
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    minLines = 6
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            if (jsonInput.isBlank()) {
                                errorMessage = "Please select a file or paste valid JSON"
                                return@Button
                            }
                            try {
                                onImport(jsonInput)
                            } catch (e: Exception) {
                                errorMessage = "Invalid JSON: ${e.message}"
                            }
                        },
                        enabled = jsonInput.isNotBlank()
                    ) {
                        Text("Import")
                    }
                }
            }
        }
    }
}

/**
 * Opens a file picker for session files and returns the JSON they contain.
 *
 * Sessions are exported gzipped, so the file is read as bytes and inflated when it carries the
 * gzip magic number, falling back to plain text for exports written before compression existed.
 * Inflating here rather than in Kotlin keeps the work in the host, where `DecompressionStream` is
 * a single pipe and no byte array has to cross the wasm boundary.
 */
private fun openFilePicker(onFileSelected: (content: String, fileName: String) -> Unit) {
    js("""
        (function(callback) {
            var input = document.createElement('input');
            input.type = 'file';
            input.accept = '.json,.gz,application/json,application/gzip';
            input.onchange = function(e) {
                var file = e.target.files[0];
                if (!file) return;
                file.arrayBuffer().then(function(buffer) {
                    var head = new Uint8Array(buffer.slice(0, 2));
                    var gzipped = head.length === 2 && head[0] === 0x1f && head[1] === 0x8b;
                    if (!gzipped) {
                        return new Response(buffer).text();
                    }
                    if (typeof DecompressionStream === 'undefined') {
                        throw new Error(
                            'This browser cannot open gzipped sessions. Decompress the file first.'
                        );
                    }
                    var stream = new Response(buffer).body
                        .pipeThrough(new DecompressionStream('gzip'));
                    return new Response(stream).text();
                }).then(function(text) {
                    callback(text, file.name);
                }).catch(function(err) {
                    callback('', 'error:' + (err && err.message ? err.message : 'unreadable file'));
                });
            };
            input.click();
        })(onFileSelected)
    """)
}
