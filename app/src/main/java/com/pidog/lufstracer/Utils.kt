// Utils.kt
package com.pidog.lufstracer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.icu.util.Calendar
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat

private const val PREFS_NAME = "LufsTracerPrefs"
private const val PREF_LAST_URI = "last_selected_uri"

@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
        }
    }
}

// --- Persistence for File Picker ---
fun saveLastSelectedUri(context: Context, uri: Uri) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(PREF_LAST_URI, uri.toString()).apply()
}

fun getLastSelectedUri(context: Context): Uri? {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val uriString = prefs.getString(PREF_LAST_URI, null)
    return if (uriString != null) Uri.parse(uriString) else null
}
// -----------------------------------

fun getFileNameFromUri(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    result = cursor.getString(index)
                }
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result ?: "Unknown"
}

// Format milliseconds to MM:SS or HH:MM:SS for duration
fun formatTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

// Format remaining time to clock time (ETA)
fun formatEtaTime(remainingMillis: Long): String {
    val etaTime = System.currentTimeMillis() + remainingMillis
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = etaTime
    return String.format(
        "%02d:%02d:%02d",
        calendar.get(Calendar.HOUR_OF_DAY),
        calendar.get(Calendar.MINUTE),
        calendar.get(Calendar.SECOND)
    )
}

fun checkAndRequestPermissions(
    context: Context,
    permissionLauncher: ActivityResultLauncher<Array<String>>,
    manageStorageLauncher: ActivityResultLauncher<Intent>,
    filePickerLauncher: ActivityResultLauncher<Intent>
) {
    val permissionsToRequest = mutableListOf<String>()

    // Notification Permission Request (Android 13+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (Environment.isExternalStorageManager()) {
            if (permissionsToRequest.isNotEmpty()) {
                permissionLauncher.launch(permissionsToRequest.toTypedArray())
            } else {
                openFilePicker(context, filePickerLauncher)
            }
        } else {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:${'$'}{context.packageName}")
                manageStorageLauncher.launch(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                manageStorageLauncher.launch(intent)
            }
        }
    } else {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (permissionsToRequest.isEmpty()) {
            openFilePicker(context, filePickerLauncher)
        } else {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}

fun openFilePicker(
    context: Context,
    launcher: ActivityResultLauncher<Intent>
) {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "audio/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
            "audio/mpeg", "audio/x-wav", "audio/wav", "audio/mp4",
            "audio/aac", "audio/flac", "audio/ogg", "application/ogg"
        ))

        // Use DocumentsContract.EXTRA_INITIAL_URI to open the last used folder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val lastUri = getLastSelectedUri(context)
            if (lastUri != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, lastUri)
            } else {
                // Fallback to internal storage root hint if no previous file was selected
                val root = Uri.parse("content://com.android.externalstorage.documents/document/primary%3A")
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, root)
            }
        }
    }
    launcher.launch(intent)
}