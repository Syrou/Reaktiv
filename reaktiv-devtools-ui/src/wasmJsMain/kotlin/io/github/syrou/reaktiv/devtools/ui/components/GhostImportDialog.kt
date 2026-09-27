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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.syrou.reaktiv.introspection.decodeSessionBytes
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.coroutines.launch

/**
 * Dialog for importing a ghost device session from JSON.
 * Supports both file upload and paste.
 *
 * Usage:
 * ```kotlin
 * if (showImportDialog) {
 *     GhostImportDialog(
 *         importError = state.ghostImportError,
 *         onImport = { json -> logic.importGhostSession(json) },
 *         onDismiss = { dispatch(DevToolsUiAction.SetOverlay(Overlay.None)) }
 *     )
 * }
 * ```
 */
@Composable
internal fun GhostImportDialog(
    importError: String?,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var jsonInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

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
                        scope.launch {
                            try {
                                val picked = pickSessionFile().await() ?: return@launch
                                jsonInput = decodeSessionBytes(Base64.decode(picked.base64))
                                fileName = picked.name
                                errorMessage = null
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (failure: Throwable) {
                                errorMessage = failure.message ?: "Could not read the file"
                                fileName = null
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
                    isError = (errorMessage ?: importError) != null,
                    supportingText = (errorMessage ?: importError)?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
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
                            onImport(jsonInput)
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

private external interface PickedSessionFile : JsAny {
    val name: String
    val base64: String
}

private fun pickSessionFile(): Promise<PickedSessionFile?> = js("""
    new Promise(function(resolve, reject) {
        var input = document.createElement('input');
        input.type = 'file';
        input.accept = '.json,.gz,application/json,application/gzip';
        input.oncancel = function() { resolve(null); };
        input.onchange = function() {
            var file = input.files && input.files[0];
            if (!file) {
                resolve(null);
                return;
            }
            var reader = new FileReader();
            reader.onload = function() {
                var url = String(reader.result);
                resolve({ name: file.name, base64: url.substring(url.indexOf(',') + 1) });
            };
            reader.onerror = function() {
                reject(reader.error || new Error('Could not read ' + file.name));
            };
            reader.readAsDataURL(file);
        };
        input.click();
    })
""")
