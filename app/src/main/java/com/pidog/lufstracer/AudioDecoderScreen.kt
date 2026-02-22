package com.pidog.lufstracer

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
@Composable
fun AudioDecoderScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val decoder = remember { AudioDecoder(context) }
    val scope = rememberCoroutineScope()

    var statusText by remember { mutableStateOf("Select an audio file to analyze") }
    var isDecoding by remember { mutableStateOf(false) }
    var metrics by remember { mutableStateOf<LufsMetrics?>(null) }
    var audioInfo by remember { mutableStateOf<DecodedAudioInfo?>(null) }
    var analyzeAllChannels by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    
    // Remember the last picked file URI string to persist across config changes
    var lastPickedFileUriString by rememberSaveable { mutableStateOf<String?>(null) }
    val lastPickedFileUri = lastPickedFileUriString?.let { Uri.parse(it) }

    // Time tracking states
    var startTime by remember { mutableLongStateOf(0L) }
    var elapsedTime by remember { mutableLongStateOf(0L) }
    var predictedTotalTime by remember { mutableLongStateOf(0L) }
    var predictedRemaining by remember { mutableLongStateOf(0L) }

    // File and phase tracking
    var fileName by remember { mutableStateOf("") }
    var isProcessingPhase by remember { mutableStateOf(false) }

    // Track whether we've ever started an analysis
    var hasStarted by remember { mutableStateOf(false) }

    // Notification Details
    val channelId = "lufs_analysis_channel"
    val notificationId = 1001

    // Initialize Notification Channel for Android O+
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "LUFS Analysis",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of background audio analysis"
            }
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null) {
            // Save the URI so the file picker opens to this folder next time
            saveLastSelectedUri(context, uri)
            lastPickedFileUriString = uri.toString()

            fileName = getFileNameFromUri(context, uri)
            scope.launch {
                hasStarted = true
                isDecoding = true
                isProcessingPhase = false
                progress = 0f
                startTime = System.currentTimeMillis()
                elapsedTime = 0L
                predictedTotalTime = 0L
                predictedRemaining = 0L
                statusText = "Decoding and analyzing audio..."
                metrics = null
                audioInfo = null


                // --- Setup Notification ---
                val notificationManager = NotificationManagerCompat.from(context)
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notificationBuilder = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Analyzing $fileName")
                    .setContentText("Decoding audio...")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(pendingIntent)
                    .setProgress(100, 0, false)

                val hasNotificationPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                } else true

                if (hasNotificationPerm) {
                    notificationManager.notify(notificationId, notificationBuilder.build())
                }

                var lastNotifyTime = 0L

                val res = decoder.decodeUri(uri) { progressUpdate ->
                    progress = progressUpdate
                    val now = System.currentTimeMillis()
                    elapsedTime = now - startTime
                    if (progressUpdate > 0.01f) {
                        predictedTotalTime = (elapsedTime / progressUpdate).toLong()
                        predictedRemaining = predictedTotalTime - elapsedTime
                    }

                    // Update Notification periodically (max twice a second)
                    if (now - lastNotifyTime > 500) {
                        notificationBuilder.setProgress(100, (progressUpdate * 100).toInt(), false)
                            .setContentText("Decoding... ${(progressUpdate * 100).toInt()}%")
                        if (hasNotificationPerm) {
                            notificationManager.notify(notificationId, notificationBuilder.build())
                        }
                        lastNotifyTime = now
                    }
                }

                isProcessingPhase = true
                progress = 0f

                // Show indeterminate progress for final metric calculation phase
                notificationBuilder.setContentText("Calculating loudness metrics...")
                    .setProgress(100, 100, true)
                if (hasNotificationPerm) {
                    notificationManager.notify(notificationId, notificationBuilder.build())
                }

                res.fold(
                    onSuccess = { info ->
                        audioInfo = info
                        metrics = info.lufsMetrics
                        statusText = "Analysis complete!"

                        notificationBuilder.setContentText("Analysis complete!")
                            .setSmallIcon(android.R.drawable.stat_sys_download_done)
                            .setProgress(0, 0, false)
                            .setOngoing(false)
                        if (hasNotificationPerm) {
                            notificationManager.notify(notificationId, notificationBuilder.build())
                        }
                    },
                    onFailure = { error ->
                        statusText = "Error: ${error.message}"

                        notificationBuilder.setContentText("Error: ${error.message}")
                            .setSmallIcon(android.R.drawable.stat_notify_error)
                            .setProgress(0, 0, false)
                            .setOngoing(false)
                        if (hasNotificationPerm) {
                            notificationManager.notify(notificationId, notificationBuilder.build())
                        }
                    }
                )
                isDecoding = false
                isProcessingPhase = false
            }
        } else {
            statusText = "No file selected"
        }
    }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                openFilePicker(context, filePickerLauncher)
            } else {
                statusText = "Storage management permission denied."
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
        }

        if (storageGranted) {
            openFilePicker(context, filePickerLauncher)
        } else {
            statusText = "Storage Permission denied."
            Toast.makeText(context, "Storage Permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    if (isDecoding) {
        KeepScreenOn()
    }

    LaunchedEffect(isDecoding) {
        while (isDecoding) {
            delay(100)
            if (isDecoding) {
                val now = System.currentTimeMillis()
                elapsedTime = now - startTime
                if (progress > 0.01f && !isProcessingPhase) {
                    predictedTotalTime = (elapsedTime / progress).toLong()
                    predictedRemaining = predictedTotalTime - elapsedTime
                }
            }
        }
    }

    // --- Initial "empty" state ---
    if (!hasStarted) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "Select an audio file to analyze",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    checkAndRequestPermissions(
                        context,
                        permissionLauncher,
                        manageStorageLauncher,
                        filePickerLauncher
                    )
                },
                modifier = Modifier.size(180.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AudioFile,
                        contentDescription = "Pick Audio",
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Pick Audio File",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        return
    }

    // --- Active state (decoding / results) ---
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(30.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = {
                checkAndRequestPermissions(
                    context,
                    permissionLauncher,
                    manageStorageLauncher,
                    filePickerLauncher
                )
            },
            enabled = !isDecoding,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = if (isDecoding) "Analyzing..." else "Pick New Audio File",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Progress Section
        if (isDecoding) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Decoding and analyzing audio...",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (fileName.isNotEmpty()) {
                        Text(
                            text = fileName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    if (isProcessingPhase) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Calculating loudness metrics...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            TimeInfoItem(label = "Elapsed", time = formatTime(elapsedTime))
                            TimeInfoItem(
                                label = "Remaining",
                                time = if (progress > 0.01f) formatTime(predictedRemaining) else "--:--"
                            )
                            TimeInfoItem(
                                label = "ETA",
                                time = if (progress > 0.01f) formatEtaTime(predictedRemaining) else "--:--:--"
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        audioInfo?.let { info ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "File Information",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (fileName.isNotEmpty()) {
                        Text("File: $fileName")
                    }
                    Text("Sample Rate: ${info.sampleRate} Hz")
                    Text("Channels: ${info.channelCount}")
                    Text("Layout: ${info.channelLayout}")
                    Text("Duration: ${"%.2f".format(info.durationSeconds)}s")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        metrics?.let { m ->
            LoudnessTabs(
                metrics = m,
                channelList = audioInfo?.channelList
            )
        }

        if (metrics == null && !isDecoding) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}
