// MainActivity.kt
package com.pidog.lufstracer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.pidog.lufstracer.ui.theme.LUFSTracerTheme
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.icu.util.Calendar
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle as ComposeTextStyle
import androidx.compose.ui.graphics.toArgb
import kotlin.math.floor
import androidx.compose.ui.platform.LocalView


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LUFSTracerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AudioDecoderScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

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

    // Time tracking states
    var startTime by remember { mutableStateOf(0L) }
    var elapsedTime by remember { mutableStateOf(0L) }
    var predictedTotalTime by remember { mutableStateOf(0L) }
    var predictedRemaining by remember { mutableStateOf(0L) }

    // File and phase tracking
    var fileName by remember { mutableStateOf("") }
    var isProcessingPhase by remember { mutableStateOf(false) }

    // Track whether we've ever started an analysis
    var hasStarted by remember { mutableStateOf(false) }


    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null) {
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
                val res = decoder.decodeUri(uri) { progressUpdate ->
                    progress = progressUpdate
                    val now = System.currentTimeMillis()
                    elapsedTime = now - startTime
                    if (progressUpdate > 0.01f) {
                        predictedTotalTime =
                            (elapsedTime / progressUpdate).toLong()
                        predictedRemaining = predictedTotalTime - elapsedTime
                    }
                }
                isProcessingPhase = true
                progress = 0f

                res.fold(
                    onSuccess = { info ->
                        audioInfo = info
                        metrics = info.lufsMetrics
                        statusText = "Analysis complete!"
                    },
                    onFailure = { error ->
                        statusText = "Error: ${error.message}"
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
                openFilePicker(filePickerLauncher)
            } else {
                statusText = "Storage management permission denied."
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            openFilePicker(filePickerLauncher)
        } else {
            statusText = "Permission denied."
            Toast.makeText(context, "Permission denied", Toast.LENGTH_SHORT)
                .show()
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
            Spacer(modifier = Modifier.height(24.dp))

            ChannelAnalysisToggle(
                analyzeAllChannels = analyzeAllChannels,
                onToggle = { analyzeAllChannels = it }
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
                modifier = Modifier
                    .size(180.dp),
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
            .padding(16.dp)
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
            enabled = !isDecoding
        ) {
            Text(if (isDecoding) "Analyzing..." else "Pick Audio File")
        }

        Spacer(modifier = Modifier.height(8.dp))

        ChannelAnalysisToggle(
            analyzeAllChannels = analyzeAllChannels,
            onToggle = { analyzeAllChannels = it },
            enabled = !isDecoding
        )

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
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
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
                            TimeInfoItem(
                                label = "Elapsed",
                                time = formatTime(elapsedTime)
                            )
                            TimeInfoItem(
                                label = "Remaining",
                                time = if (progress > 0.01f)
                                    formatTime(predictedRemaining) else "--:--"
                            )
                            TimeInfoItem(
                                label = "ETA",
                                time = if (progress > 0.01f) {
                                    formatEtaTime(predictedRemaining)
                                } else {
                                    "--:--:--"
                                }
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
                    Text(
                        "Duration: ${"%.2f".format(info.durationSeconds)}s"
                    )
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

@Composable
fun ChannelAnalysisToggle(
    analyzeAllChannels: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (analyzeAllChannels)
                    "All Channels" else "Combined Only",
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
            Text(
                text = if (analyzeAllChannels)
                    "Analyzes combined + each channel (slower)"
                else
                    "Analyzes combined loudness only (faster)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = analyzeAllChannels,
            onCheckedChange = onToggle,
            enabled = enabled
        )
    }
}

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


@Composable
private fun TimeInfoItem(
    label: String,
    time: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = time,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

// Format milliseconds to MM:SS or HH:MM:SS for duration
private fun formatTime(millis: Long): String {
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
private fun formatEtaTime(remainingMillis: Long): String {
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

@Composable
fun LoudnessTabs(
    metrics: LufsMetrics,
    channelList: List<ChannelMetadata>?
) {
    val channelMetrics = metrics.channelMetrics
    val tabs = mutableListOf("Combined")

    // Add tabs for each channel
    channelMetrics.forEachIndexed { index, cm ->
        tabs.add(cm.channelName)
    }

    var selectedTabIndex by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxWidth()) {
        TabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = {
                        Text(
                            text = title,
                            maxLines = 1,
                            fontSize = 12.sp
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (selectedTabIndex) {
            0 -> CombinedMetricsGrid(metrics = metrics)
            else -> {
                val cm = channelMetrics.getOrNull(selectedTabIndex - 1)
                if (cm != null) {
                    ChannelMetricsGrid(
                        channelMetric = cm,
                        channelInfo = channelList?.getOrNull(selectedTabIndex - 1)
                    )
                }
            }
        }
    }
}

@Composable
fun CombinedMetricsGrid(metrics: LufsMetrics) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Loudness Metrics (All Channels)",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard("Integrated", "${"%.2f".format(metrics.integrated)} LUFS", Modifier.weight(1f))
            Spacer(modifier = Modifier.width(8.dp))
            MetricCard("True Peak", "${"%.2f".format(metrics.truePeak)} dBTP", Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard("Short-Term", "${"%.2f".format(metrics.shortTerm)} LUFS", Modifier.weight(1f))
            Spacer(modifier = Modifier.width(8.dp))
            MetricCard("Momentary", "${"%.2f".format(metrics.momentary)} LUFS", Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard("Dynamic Range", "${"%.2f".format(metrics.dynamicRange)} LU", Modifier.weight(1f))
            Spacer(modifier = Modifier.width(8.dp))
            MetricCard("PLR / PSR", "${"%.2f".format(metrics.plr)} / ${"%.2f".format(metrics.psr)} LU", Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard("Relative Threshold", "${"%.2f".format(metrics.relativeThreshold)} dBFS", Modifier.weight(1f))
            Spacer(modifier = Modifier.width(8.dp))
            MetricCard("Sample Peak", "${"%.2f".format(metrics.samplePeak)} dBFS", Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (metrics.channelMetrics.size >= 2) {
            val shiftText = if (metrics.leftRightShift >= 0) {
                "${"%.2f".format(metrics.leftRightShift)} dB Right"
            } else {
                "${"%.2f".format(-metrics.leftRightShift)} dB Left"
            }
            MetricCard("L/R Shift", shiftText, Modifier.fillMaxWidth())
        }
        if (metrics.shortTermHistogram.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            ShortTermHistogramChart(
                histogram = metrics.shortTermHistogram,
                integrated = metrics.integrated,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun ChannelMetricsGrid(
    channelMetric: ChannelLoudness,
    channelInfo: ChannelMetadata?
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${channelMetric.channelName} Metrics",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            if (channelInfo != null) {
                Spacer(modifier = Modifier.width(8.dp))
                if (!channelMetric.isUsedInLufs) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "Not in LUFS",
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                } else if (channelMetric.lufsWeight != 1.0f) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "+${"%.1f".format(20 * kotlin.math.log10(channelMetric.lufsWeight.toDouble()))}dB",
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard("Integrated", "${"%.2f".format(channelMetric.integrated)} LUFS", Modifier.weight(1f))
            Spacer(modifier = Modifier.width(8.dp))
            MetricCard("True Peak", "${"%.2f".format(channelMetric.truePeak)} dBTP", Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard("Short-Term", "${"%.2f".format(channelMetric.shortTerm)} LUFS", Modifier.weight(1f))
            Spacer(modifier = Modifier.width(8.dp))
            MetricCard("Momentary", "${"%.2f".format(channelMetric.momentary)} LUFS", Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard("Sample Peak", "${"%.2f".format(channelMetric.samplePeak)} dBFS", Modifier.weight(1f))
            Spacer(modifier = Modifier.width(8.dp))
            MetricCard(
                "LUFS Weight",
                if (channelMetric.lufsWeight > 0) "${channelMetric.lufsWeight}x" else "Ignored",
                Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun ShortTermHistogramChart(
    histogram: List<HistogramBucket>,
    integrated: Double,
    modifier: Modifier = Modifier
) {
    if (histogram.isEmpty()) return

    val startIndex = histogram.indexOfFirst { it.count > 10 }
    val endIndex = histogram.indexOfLast { it.count > 10 }
    if (startIndex < 0 || endIndex < 0) return

    val trimmed = histogram.subList(startIndex, endIndex + 1)
    if (trimmed.isEmpty()) return

    val maxCount = trimmed.maxOf { it.count }
    val colorScheme = MaterialTheme.colorScheme
    val barColor = colorScheme.primary
    val zeroBarColor = colorScheme.outlineVariant
    val integratedLineColor = colorScheme.error
    val labelColor = colorScheme.onSurface
    val textMeasurer = rememberTextMeasurer()

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Short-Term Histogram",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                text = "1 dB buckets · 3 s sliding window",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            val labelHeight = 36.dp
            val topPadding = 20.dp

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(275.dp)
            ) {
                val topPad = topPadding.toPx()
                val labelH = labelHeight.toPx()
                val barAreaHeight = size.height - labelH - topPad
                val barCount = trimmed.size
                val colW = size.width / barCount
                val gap = (0.5.dp).toPx()

                // --- Draw all bars first ---
                trimmed.forEachIndexed { index, bucket ->
                    val x = index * colW

                    val fraction = if (maxCount > 0) {
                        bucket.count.toFloat() / maxCount
                    } else 0f
                    val barH = (fraction * barAreaHeight).coerceAtLeast(
                        if (bucket.count > 0) 2.dp.toPx() else 0f
                    )
                    val barX = x + gap
                    val barW = (colW - gap * 2).coerceAtLeast(1.dp.toPx())

                    if (bucket.count > 0) {
                        drawRect(
                            color = barColor,
                            topLeft = Offset(
                                barX,
                                topPad + barAreaHeight - barH
                            ),
                            size = Size(barW, barH),
                        )
                    } else {
                        drawRect(
                            color = zeroBarColor,
                            topLeft = Offset(
                                barX,
                                topPad + barAreaHeight - 2.dp.toPx()
                            ),
                            size = Size(barW, 2.dp.toPx()),
                        )
                    }

                    // dB label every 5 dB
                    if (bucket.rangeStartDb % 30 == 0) {
                        val labelText =
                            "${bucket.rangeStartDb / 10.0}"
                        val labelResult = textMeasurer.measure(
                            text = labelText,
                            style = ComposeTextStyle(
                                fontSize = 10.sp,
                                color = labelColor
                            )
                        )
                        drawText(
                            textLayoutResult = labelResult,
                            topLeft = Offset(
                                x = x + (colW - labelResult.size.width)
                                        / 2f,
                                y = topPad + barAreaHeight + 4.dp.toPx()
                            )
                        )
                    }
                }

                // Baseline
                drawLine(
                    color = labelColor.copy(alpha = 0.3f),
                    start = Offset(0f, topPad + barAreaHeight),
                    end = Offset(size.width, topPad + barAreaHeight),
                    strokeWidth = 1.dp.toPx()
                )

                // --- Integrated loudness overlay line + label ---
                val intDb10 = floor(integrated * 10).toInt()
                val matchIndex = trimmed.indexOfFirst {
                    it.rangeStartDb == intDb10
                }
                if (matchIndex >= 0) {
                    val lineX = (matchIndex + 0.5f) * colW

                    // Vertical line over the bars
                    drawLine(
                        color = integratedLineColor,
                        start = Offset(lineX, topPad),
                        end = Offset(lineX, topPad + barAreaHeight),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(6.dp.toPx(), 4.dp.toPx())
                        )
                    )

                    // Label pinned to top
                    val dbText = String.format("%.1f", integrated)
                    val intLabelResult = textMeasurer.measure(
                        text = "$dbText LUFS",
                        style = ComposeTextStyle(
                            fontSize = 10.sp,
                            color = integratedLineColor,
                            fontWeight = FontWeight.Bold
                        )
                    )

                    // Background pill behind label
                    val pillPadH = 4.dp.toPx()
                    val pillPadV = 2.dp.toPx()
                    val pillW =
                        intLabelResult.size.width + pillPadH * 2
                    val pillH =
                        intLabelResult.size.height + pillPadV * 2

                    // Clamp so label stays within canvas
                    val rawLabelX = lineX - pillW / 2f
                    val clampedX = rawLabelX
                        .coerceIn(0f, size.width - pillW)

                    drawRoundRect(
                        color = colorScheme.surfaceVariant,
                        topLeft = Offset(clampedX, 0f),
                        size = Size(pillW, pillH),
                        cornerRadius = CornerRadius(
                            4.dp.toPx(), 4.dp.toPx()
                        )
                    )
                    drawRoundRect(
                        color = integratedLineColor.copy(alpha = 0.3f),
                        topLeft = Offset(clampedX, 0f),
                        size = Size(pillW, pillH),
                        cornerRadius = CornerRadius(
                            4.dp.toPx(), 4.dp.toPx()
                        ),
                        style = Stroke(width = 1.dp.toPx())
                    )
                    drawText(
                        textLayoutResult = intLabelResult,
                        topLeft = Offset(
                            clampedX + pillPadH,
                            pillPadV
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

private fun checkAndRequestPermissions(
    context: android.content.Context,
    permissionLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    manageStorageLauncher: androidx.activity.result.ActivityResultLauncher<Intent>,
    filePickerLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (Environment.isExternalStorageManager()) {
            openFilePicker(filePickerLauncher)
        } else {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:${context.packageName}")
                manageStorageLauncher.launch(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                manageStorageLauncher.launch(intent)
            }
        }
    } else {
        val permissionsToRequest = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val allGranted = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            openFilePicker(filePickerLauncher)
        } else {
            permissionLauncher.launch(permissionsToRequest)
        }
    }
}

private fun openFilePicker(
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "audio/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
            "audio/mpeg", "audio/x-wav", "audio/wav", "audio/mp4",
            "audio/aac", "audio/flac", "audio/ogg", "application/ogg"
        ))
        // This hint tells the picker to show internal storage roots if possible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val root = Uri.parse("content://com.android.externalstorage.documents/document/primary%3A")
            putExtra("android.provider.extra.INITIAL_URI", root)
        }
    }
    launcher.launch(intent)
}
