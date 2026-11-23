package com.pidog.lufstracer

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioViewModel(application: Application) : AndroidViewModel(application) {

    private val decoder = AudioDecoder(application)
    private val historyManager = HistoryManager(application)
    private val dbHelper = AudioDatabaseHelper(application)

    var isDbPaused by mutableStateOf(false)
        private set

    @Volatile
    private var dbPausedFlag = false

    var statusText by mutableStateOf("Select an audio file to analyze")
        private set
    var isDecoding by mutableStateOf(false)
        private set
    var metrics by mutableStateOf<LufsMetrics?>(null)
        private set
    var audioInfo by mutableStateOf<DecodedAudioInfo?>(null)
        private set
    var progress by mutableFloatStateOf(0f)
        private set

    var startTime by mutableLongStateOf(0L)
        private set
    var elapsedTime by mutableLongStateOf(0L)
        private set
    var predictedTotalTime by mutableLongStateOf(0L)
        private set
    var predictedRemaining by mutableLongStateOf(0L)
        private set
    var etaTimestampMs by mutableLongStateOf(0L)
        private set

    private data class ProgressSnapshot(val progress: Float, val timeMs: Long)

    private val etaEstimator = EtaEstimator()
    @Volatile
    private var latestProgressSnapshot = ProgressSnapshot(0f, 0L)

    var fileName by mutableStateOf("")
        private set
    var isProcessingPhase by mutableStateOf(false)
        private set
    var hasStarted by mutableStateOf(false)
        private set

    var recentHistory by mutableStateOf<List<HistoryEntry>>(emptyList())
        private set

    private var decodingJob: Job? = null
    private var timerJob: Job? = null

    private val channelId = "lufs_analysis_channel"
    private val notificationId = 1001

    init {
        createNotificationChannel()
        loadHistory()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "LUFS Analysis",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows progress of background audio analysis"
            }
            val notificationManager =
                getApplication<Application>().getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    fun loadHistory() {
        recentHistory = historyManager.loadHistory()
    }

    fun togglePauseDb() {
        val next = !isDbPaused
        dbPausedFlag = next
        isDbPaused = next
    }

    fun clearDatabase() {
        dbHelper.clearAll()
        historyManager.clearHistory()
        recentHistory = emptyList()
    }

    fun cancelDecoding() {
        decodingJob?.cancel()
        timerJob?.cancel()
        decodingJob = null
        timerJob = null
        isDecoding = false
        isProcessingPhase = false
        hasStarted = false
        metrics = null
        audioInfo = null
        progress = 0f
        latestProgressSnapshot = ProgressSnapshot(0f, 0L)
        etaTimestampMs = 0L
        statusText = "Select an audio file to analyze"
        val app = getApplication<Application>()
        val notificationManager = NotificationManagerCompat.from(app)
        try {
            notificationManager.cancel(notificationId)
        } catch (_: Exception) { }
    }

    fun resetToHome() {
        cancelDecoding()
    }

    fun selectHistoryItem(id: String) {
        val entry = recentHistory.find { it.id == id } ?: return
        fileName = entry.fileName
        metrics = entry.metrics
        audioInfo = dbHelper.getCachedResultByUri(entry.fileUri)
        statusText = "Analysis complete!"
        hasStarted = true
        isDecoding = false
        isProcessingPhase = false
    }

    fun startDecoding(uri: Uri) {
        val app = getApplication<Application>()
        saveLastSelectedUri(app, uri)
        fileName = getFileNameFromUri(app, uri)

        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            app.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (_: Exception) {
            // Ignore if provider doesn't support persistable permissions
        }

        val precomputedFileHash = if (!dbPausedFlag) compute100kHash(app, uri) else null
        if (precomputedFileHash != null) {
            val cachedInfo = dbHelper.getCachedResult(precomputedFileHash)
            if (cachedInfo != null) {
                audioInfo = cachedInfo
                metrics = cachedInfo.lufsMetrics
                statusText = "Analysis complete!"
                hasStarted = true
                isDecoding = false
                isProcessingPhase = false
                progress = 1f
                cachedInfo.lufsMetrics?.let { m ->
                    historyManager.saveEntry(fileName, uri.toString(), m, cachedInfo.processingTimeMs)
                    loadHistory()
                }
                return
            }
        }

        hasStarted = true
        isDecoding = true
        isProcessingPhase = false
        progress = 0f
        latestProgressSnapshot = ProgressSnapshot(0f, 0L)
        startTime = System.currentTimeMillis()
        elapsedTime = 0L
        predictedTotalTime = 0L
        predictedRemaining = 0L
        etaTimestampMs = 0L
        etaEstimator.reset(startTime)
        statusText = "Decoding and analyzing audio..."
        metrics = null
        audioInfo = null

        timerJob?.cancel()
        decodingJob?.cancel()

        timerJob = viewModelScope.launch {
            while (isActive && isDecoding) {
                delay(100)
                if (isDecoding && !isProcessingPhase) {
                    val now = System.currentTimeMillis()
                    val snap = latestProgressSnapshot
                    progress = snap.progress
                    elapsedTime = now - startTime
                    val remaining = etaEstimator.update(snap.progress, snap.timeMs, now)
                    if (remaining != null) {
                        predictedRemaining = remaining
                        etaTimestampMs = etaEstimator.getEtaEpochMs()
                        predictedTotalTime = elapsedTime + remaining
                    }
                }
            }
        }

        decodingJob = viewModelScope.launch(Dispatchers.Default) {
            val notificationManager = NotificationManagerCompat.from(app)
            try {
                val intent = Intent(app, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    app,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notificationBuilder = NotificationCompat.Builder(app, channelId)
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
                            app,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    } else true

                if (hasNotificationPerm) {
                    try {
                        notificationManager.notify(notificationId, notificationBuilder.build())
                    } catch (_: SecurityException) {
                        // Ignore missing permission edge case
                    }
                }

                var lastNotifyTime = 0L

                val res = decoder.decodeUri(
                    uri = uri,
                    onCalculatingMetrics = {
                        viewModelScope.launch {
                            isProcessingPhase = true
                            progress = 1f
                        }
                        notificationBuilder.setContentText("Calculating loudness metrics...")
                            .setProgress(100, 100, true)
                        if (hasNotificationPerm) {
                            try {
                                notificationManager.notify(notificationId, notificationBuilder.build())
                            } catch (_: SecurityException) {
                                // Ignore missing permission edge case
                            }
                        }
                    }
                ) { progressUpdate ->
                    val now = System.currentTimeMillis()
                    latestProgressSnapshot = ProgressSnapshot(progressUpdate, now)
                    if (now - lastNotifyTime > 500) {
                        notificationBuilder.setProgress(
                            100,
                            (progressUpdate * 100).toInt(),
                            false
                        ).setContentText("Decoding... ${(progressUpdate * 100).toInt()}%")
                        if (hasNotificationPerm) {
                            try {
                                notificationManager.notify(notificationId, notificationBuilder.build())
                            } catch (_: SecurityException) {
                                // Ignore missing permission edge case
                            }
                        }
                        lastNotifyTime = now
                    }
                }

                res.fold(
                    onSuccess = { info ->
                        val totalProcessTime = System.currentTimeMillis() - startTime
                        val finalInfo = info.copy(processingTimeMs = totalProcessTime)

                        audioInfo = finalInfo
                        metrics = finalInfo.lufsMetrics
                        statusText = "Analysis complete!"

                        if (!dbPausedFlag) {
                            val fileHash = precomputedFileHash ?: compute100kHash(app, uri)
                            if (fileHash != null) {
                                dbHelper.saveResult(fileHash, fileName, uri.toString(), finalInfo)
                            }

                            finalInfo.lufsMetrics?.let { m ->
                                historyManager.saveEntry(fileName, uri.toString(), m, totalProcessTime)
                                loadHistory()
                            }
                        }

                        notificationBuilder.setContentText("Analysis complete!")
                            .setSmallIcon(android.R.drawable.stat_sys_download_done)
                            .setProgress(0, 0, false)
                            .setOngoing(false)
                        if (hasNotificationPerm) {
                            try {
                                notificationManager.notify(notificationId, notificationBuilder.build())
                            } catch (_: SecurityException) {
                                // Ignore missing permission edge case
                            }
                        }
                    },
                    onFailure = { error ->
                        statusText = "Error: ${error.message}"

                        notificationBuilder.setContentText("Error: ${error.message}")
                            .setSmallIcon(android.R.drawable.stat_notify_error)
                            .setProgress(0, 0, false)
                            .setOngoing(false)
                        if (hasNotificationPerm) {
                            try {
                                notificationManager.notify(notificationId, notificationBuilder.build())
                            } catch (_: SecurityException) {
                                // Ignore missing permission edge case
                            }
                        }
                    }
                )

                isDecoding = false
                isProcessingPhase = false
            } catch (e: CancellationException) {
                try {
                    notificationManager.cancel(notificationId)
                } catch (_: Exception) { }
                throw e
            }
        }
    }
}