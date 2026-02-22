package com.pidog.lufstracer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle as ComposeTextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor


@Composable
fun TimeInfoItem(
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

@Composable
fun LoudnessTabs(
    metrics: LufsMetrics,
    channelList: List<ChannelMetadata>?
) {
    val channelMetrics = metrics.channelMetrics
    val tabs = mutableListOf("Combined")

    channelMetrics.forEach { cm ->
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
                modifier = Modifier.fillMaxWidth(),
                minVal = metrics.histogramMin,
                maxVal = metrics.histogramMax,
                duration = metrics.duration
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
    modifier: Modifier = Modifier,
    minVal: Int,
    maxVal: Int,
    duration: Double
) {
    if (histogram.isEmpty()) return
    var minCount = (duration/120).toInt()
    minCount = if (minCount <= 1) 1 else minCount

    val startIndex = histogram.indexOfFirst { it.count > minCount }
    val endIndex = histogram.indexOfLast { it.count > minCount }
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
    val dbScale = ((endIndex - startIndex) / 10)
    var numScale = false
    if (dbScale > 15){
        numScale = false
    }else{
        numScale = true
    }

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
                text = ".1 dB buckets · 3 s sliding window",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            val labelHeight = 36.dp
            val topPadding = 20.dp

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(290.dp)
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

                    if (bucket.rangeStartDb % dbScale == 0) {
                        val labelText = if (numScale) "${bucket.rangeStartDb / 10.0}" else "${bucket.rangeStartDb / 10}"
//                        val labelText = "${numScale}"
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
                                x = x + (colW - labelResult.size.width) / 2f,
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
                    val pillW = intLabelResult.size.width + pillPadH * 2
                    val pillH = intLabelResult.size.height + pillPadV * 2

                    // Clamp so label stays within canvas
                    val rawLabelX = lineX - pillW / 2f
                    val clampedX = rawLabelX.coerceIn(0f, size.width - pillW)

                    drawRoundRect(
                        color = colorScheme.surfaceVariant,
                        topLeft = Offset(clampedX, 0f),
                        size = Size(pillW, pillH),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                    drawRoundRect(
                        color = integratedLineColor.copy(alpha = 0.3f),
                        topLeft = Offset(clampedX, 0f),
                        size = Size(pillW, pillH),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                        style = Stroke(width = 1.dp.toPx())
                    )
                    drawText(
                        textLayoutResult = intLabelResult,
                        topLeft = Offset(clampedX + pillPadH, pillPadV)
                    )
                }
            }
        }
    }
}