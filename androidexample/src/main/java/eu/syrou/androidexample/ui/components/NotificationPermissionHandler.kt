package eu.syrou.androidexample.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
fun NotificationPermissionHandler(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var showRationale by remember { mutableStateOf(false) }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        var hasNotificationPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            )
        }

        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            hasNotificationPermission = isGranted
            showRationale = !isGranted
        }

        LaunchedEffect(key1 = true) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        when {
            hasNotificationPermission -> {
                content()
            }

            else -> {
                content()
            }
        }
    } else {
        content()
    }
}

@Composable
fun PermissionRationaleDialog(
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    Column {
        Text("Notification permission is required to keep you updated about important changes.")
        Button(onClick = onRequestPermission) {
            Text("Grant Permission")
        }
        Button(onClick = onDismiss) {
            Text("Dismiss")
        }
    }
}

@Composable
fun PermissionDeniedContent(
    onOpenSettings: () -> Unit
) {
    Column {
        Text("Notification permission is required for this feature.")
        Button(onClick = onOpenSettings) {
            Text("Open Settings")
        }
    }
}