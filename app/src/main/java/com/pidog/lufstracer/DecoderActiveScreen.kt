package com.pidog.lufstracer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DecoderActiveScreen(
    modifier: Modifier = Modifier,
    isDecoding: Boolean,
    isProcessingPhase: Boolean,
    progress: Float,
    fileName: String,
    elapsedTime: Long,
    predictedRemaining: Long,
    audioInfo: DecodedAudioInfo?,
    metrics: LufsMetrics?,
    statusText: String,
    onPickNewAudioClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(30.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = onPickNewAudioClick,
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