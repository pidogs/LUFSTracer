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