package com.pidog.lufstracer

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun AudioDecoderScreen(
    modifier: Modifier = Modifier,
    viewModel: AudioViewModel = viewModel()
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }

    // Intercept system back button to return to home screen
    BackHandler(enabled = viewModel.hasStarted) {
        viewModel.resetToHome()
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null) {
            viewModel.startDecoding(uri)
        }
    }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                openFilePicker(context, filePickerLauncher)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            permissions[Manifest.permission.READ_EXTERNAL_STORAGE]
                ?: (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED)
        }

        if (storageGranted) {
            openFilePicker(context, filePickerLauncher)
        } else {
            Toast.makeText(context, "Storage Permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    val onPickFile = {
        checkAndRequestPermissions(
            context,
            permissionLauncher,
            manageStorageLauncher,
            filePickerLauncher
        )
    }

    if (viewModel.isDecoding) {
        KeepScreenOn()
    }

    val recentFileEntries = viewModel.recentHistory.map { entry ->
        FileHistoryEntry(
            id = entry.id,
            fileName = entry.fileName,
            timestamp = entry.timestamp,
            integratedLufs = entry.metrics.integrated,
            truePeak = entry.metrics.truePeak,
            processingTimeMs = entry.processingTimeMs
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "LUFS Tracer",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (viewModel.isDbPaused) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "DB PAUSED",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (viewModel.hasStarted) {
                        IconButton(onClick = { viewModel.resetToHome() }) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = "Home"
                            )
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Menu"
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (viewModel.isDbPaused) "Resume DB" else "Pause DB (until reload)")
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (viewModel.isDbPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.togglePauseDb()
                                    val toastMsg = if (viewModel.isDbPaused) "Database paused until app reload" else "Database resumed"
                                    Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear DB") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.clearDatabase()
                                    Toast.makeText(context, "Database cleared", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        if (!viewModel.hasStarted) {
            DecoderIdleScreen(
                modifier = Modifier.padding(innerPadding),
                recentFiles = recentFileEntries,
                onPickAudioClick = onPickFile,
                onHistoryItemClick = { entry ->
                    viewModel.selectHistoryItem(entry.id)
                }
            )
        } else {
            DecoderActiveScreen(
                modifier = Modifier.padding(innerPadding),
                isDecoding = viewModel.isDecoding,
                isProcessingPhase = viewModel.isProcessingPhase,
                progress = viewModel.progress,
                fileName = viewModel.fileName,
                elapsedTime = viewModel.elapsedTime,
                predictedRemaining = viewModel.predictedRemaining,
                audioInfo = viewModel.audioInfo,
                metrics = viewModel.metrics,
                statusText = viewModel.statusText,
                onPickNewAudioClick = onPickFile,
                onCancelClick = { viewModel.cancelDecoding() }
            )
        }
    }
}