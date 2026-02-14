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
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp



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

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isDecoding = true
                statusText = "Decoding and analyzing audio..."
                metrics = null
                audioInfo = null

                val result = decoder.decodeUri(uri)
                result.fold(
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
            }
        } else {
            statusText = "No file selected"
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
            Toast.makeText(context, "Permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = {
                checkAndRequestPermissions(context, permissionLauncher, filePickerLauncher)
            },
            enabled = !isDecoding
        ) {
            Text(if (isDecoding) "Analyzing..." else "Pick Audio File")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Audio Info Card
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
                    Text("Sample Rate: ${info.sampleRate} Hz")
                    Text("Channels: ${info.channelCount}")
                    Text("Duration: ${"%.2f".format(info.durationSeconds)}s")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Metrics Grid
        metrics?.let { m ->
            MetricsGrid(metrics = m)
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
fun MetricsGrid(metrics: LufsMetrics) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Loudness Metrics",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
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

        Row(modifier = Modifier.fillMaxWidth()) {
            val shiftText = if (metrics.leftRightShift >= 0) {
                "${"%.2f".format(metrics.leftRightShift)} dB Right"
            } else {
                "${"%.2f".format(-metrics.leftRightShift)} dB Left"
            }
            MetricCard("L/R Shift", shiftText, Modifier.fillMaxWidth())
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
    filePickerLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val allGranted = permissionsToRequest.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    if (allGranted) {
        openFilePicker(filePickerLauncher)
    } else {
        permissionLauncher.launch(permissionsToRequest)
    }
}

private fun openFilePicker(
    launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    launcher.launch(
        arrayOf(
            "audio/mpeg",
            "audio/x-wav",
            "audio/wav",
            "audio/mp4",
            "audio/aac",
            "audio/flac",
            "audio/ogg",
            "application/ogg"
        )
    )
}