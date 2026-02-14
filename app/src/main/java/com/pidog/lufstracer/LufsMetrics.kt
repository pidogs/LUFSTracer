package com.pidog.lufstracer

data class LufsMetrics(
    val integrated: Double,       // LUFS
    val truePeak: Double,         // dBTP
    val shortTerm: Double,        // LUFS
    val momentary: Double,        // LUFS
    val dynamicRange: Double,     // LU (LRA)
    val plr: Double,              // Peak to Loudness Ratio (LU)
    val psr: Double,              // Peak to Short-Term Ratio (LU)
    val relativeThreshold: Double, // dBFS
    val samplePeak: Double,        // dBFS
    val leftRightShift: Double
)