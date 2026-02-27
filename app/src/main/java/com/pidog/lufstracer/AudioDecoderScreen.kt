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
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    var progress by remember { mutableFloatStateOf(0f) }

    var lastPickedFileUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var startTime by remember { mutableLongStateOf(0L) }
    var elapsedTime by remember { mutableLongStateOf(0L) }
    var predictedTotalTime by remember { mutableLongStateOf(0L) }
    var predictedRemaining by remember { mutableLongStateOf(0L) }

    var fileName by remember { mutableStateOf("") }
    var isProcessingPhase by remember { mutableStateOf(false) }
    var hasStarted by remember { mutableStateOf(false) }

    val channelId = "lufs_analysis_channel"
    val notificationId = 1001

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "LUFS Analysis",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of background audio analysis"
            }
            val notificationManager =
                context.getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null) {
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

                val notificationManager = NotificationManagerCompat.from(context)
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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

                val hasNotificationPerm =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
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

                    if (now - lastNotifyTime > 500) {
                        notificationBuilder.setProgress(
                            100,
                            (progressUpdate * 100).toInt(),
                            false
                        ).setContentText("Decoding... ${(progressUpdate * 100).toInt()}%")
                        if (hasNotificationPerm) {
                            notificationManager.notify(notificationId, notificationBuilder.build())
                        }
                        lastNotifyTime = now
                    }
                }

                isProcessingPhase = true
                progress = 0f

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
            permissions[Manifest.permission.READ_EXTERNAL_STORAGE]
                ?: (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED)
        }

        if (storageGranted) {
            openFilePicker(context, filePickerLauncher)
        } else {
            statusText = "Storage Permission denied."
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

    if (!hasStarted) {
        DecoderIdleScreen(
            modifier = modifier,
            onPickAudioClick = onPickFile
        )
    } else {
        DecoderActiveScreen(
            modifier = modifier,
            isDecoding = isDecoding,
            isProcessingPhase = isProcessingPhase,
            progress = progress,
            fileName = fileName,
            elapsedTime = elapsedTime,
            predictedRemaining = predictedRemaining,
            audioInfo = audioInfo,
            metrics = metrics,
            statusText = statusText,
            onPickNewAudioClick = onPickFile
        )
    }
}