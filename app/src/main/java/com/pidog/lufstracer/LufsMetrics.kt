package com.pidog.lufstracer

data class HistogramBucket(
    val rangeStartDb: Int, // e.g. -14 means [-14, -13)
    val count: Int
)

data class LufsMetrics(
    val integrated: Double,
    val truePeak: Double,
    val shortTerm: Double,
    val momentary: Double,
    val dynamicRange: Double,
    val plr: Double,
    val psr: Double,
    val relativeThreshold: Double,
    val samplePeak: Double,
    val leftRightShift: Double,
    val channelMetrics: List<ChannelLoudness> = emptyList(),
    val shortTermHistogram: List<HistogramBucket> = emptyList(),
    val histogramMin: Int,
    val histogramMax: Int,
    var duration: Double
)

data class ChannelLoudness(
    val channelName: String,
    val integrated: Double,
    val truePeak: Double,
    val shortTerm: Double,
    val momentary: Double,
    val samplePeak: Double,
    val isUsedInLufs: Boolean,
    val lufsWeight: Float
)

data class FileHistoryEntry(
    val id: String,
    val fileName: String,
    val timestamp: Long,
    val integratedLufs: Double,
    val truePeak: Double
)