package com.pidog.lufstracer

import kotlin.math.*

class LufsAnalyzer(
    private val sampleRate: Int,
    private val channelCount: Int,
    private val channelList: List<ChannelMetadata>,
    private val analyzeAllChannels: Boolean = false
) {
    private val filter1: ManualBiquad
    private val filter2: ManualBiquad
    private val weights: DoubleArray

    private val blockSize = (0.4 * sampleRate).toInt()
    private val stepSize = (0.1 * sampleRate).toInt()

    // Overall weighted 400ms block powers (sum of G_i * z_ij)
    private val powers400ms = mutableListOf<Double>()

    // Per-channel unweighted 400ms block powers (z_ij per channel)
    private val channelPowers400ms =
        Array(channelCount) { mutableListOf<Double>() }

    // BS.1770-5 Annex 2 true-peak detector (polyphase FIR, 4× OS)
    private val truePeakDetector = TruePeakDetector(channelCount)

    private var overlapBuffer: Array<DoubleArray> =
        Array(channelCount) { DoubleArray(0) }
    private var leftoverSamples = FloatArray(0)

    init {
        val (coeffs1, coeffs2) = getKWeightingCoeffs(sampleRate)
        filter1 = ManualBiquad(coeffs1.first, coeffs1.second, channelCount)
        filter2 = ManualBiquad(coeffs2.first, coeffs2.second, channelCount)

        weights = DoubleArray(channelCount) { ch ->
            when {
                isLfeChannel(ch) -> 0.0       // LFE excluded per spec
                isSurroundChannel(ch) -> 1.41  // +1.5 dB per Table 3
                else -> 1.0
            }
        }
    }

    // ── Channel role detection ──────────────────────────────────

    private fun isLfeChannel(ch: Int): Boolean {
        val name = channelList.getOrNull(ch)?.name?.uppercase() ?: ""
        if ("LFE" in name || "SUB" in name) return true
        // Standard 5.1 layout: index 3 is LFE
        return channelCount >= 6 && ch == 3
    }

    private fun isSurroundChannel(ch: Int): Boolean {
        val name = channelList.getOrNull(ch)?.name?.uppercase() ?: ""
        if ("SURROUND" in name || "SIDE" in name || "REAR" in name)
            return true
        // Standard 5.1 layout: indices 4,5 are Ls/Rs
        return channelCount >= 6 && (ch == 4 || ch == 5)
    }

    // ── Streaming input ─────────────────────────────────────────

    fun processSamples(interleavedSamples: FloatArray) {
        val combined = if (leftoverSamples.isNotEmpty()) {
            FloatArray(leftoverSamples.size + interleavedSamples.size)
                .also {
                    leftoverSamples.copyInto(it)
                    interleavedSamples.copyInto(it, leftoverSamples.size)
                }
        } else {
            interleavedSamples
        }

        val completeFrames = combined.size / channelCount
        val usedFloats = completeFrames * channelCount
        leftoverSamples = if (usedFloats < combined.size) {
            combined.copyOfRange(usedFloats, combined.size)
        } else FloatArray(0)

        if (completeFrames == 0) return

        // Deinterleave
        val channels = Array(channelCount) { DoubleArray(completeFrames) }
        for (frame in 0 until completeFrames) {
            val base = frame * channelCount
            for (ch in 0 until channelCount) {
                channels[ch][frame] = combined[base + ch].toDouble()
            }
        }

        // True-peak detection (BS.1770-5 Annex 2, polyphase FIR 4× OS)
        truePeakDetector.processBlock(channels)

        // K-weighting (stage 1: head model shelf, stage 2: RLB high-pass)
        val filtered = filter2.process(filter1.process(channels))

        // Prepend overlap from previous call
        val combinedFiltered = Array(channelCount) { ch ->
            val prev = overlapBuffer[ch]
            DoubleArray(prev.size + filtered[ch].size).also {
                prev.copyInto(it)
                filtered[ch].copyInto(it, prev.size)
            }
        }

        // Slice into 400 ms gating blocks with 75% overlap
        val totalLen = combinedFiltered[0].size
        var pos = 0
        while (pos + blockSize <= totalLen) {
            var weightedSum = 0.0

            for (ch in 0 until channelCount) {
                var sumSq = 0.0
                for (i in pos until pos + blockSize) {
                    val v = combinedFiltered[ch][i]
                    sumSq += v * v
                }
                val avgPower = sumSq / blockSize

                channelPowers400ms[ch].add(avgPower)
                weightedSum += avgPower * weights[ch]
            }

            powers400ms.add(weightedSum)
            pos += stepSize
        }

        overlapBuffer = Array(channelCount) { ch ->
            combinedFiltered[ch].copyOfRange(pos, totalLen)
        }
    }

    // ── Final metrics ───────────────────────────────────────────

    fun calculateMetrics(): LufsMetrics {
        val powers = powers400ms.toDoubleArray()

        if (powers.isEmpty()) return emptyMetrics()

        // ── Overall (multichannel, weighted) ──
        val (integrated, relThreshold) =
            computeGatedLoudnessWithThreshold(powers)
        val momentary = computeMomentaryMax(powers)
        val stValues = computeShortTermValues(powers)
        val shortTerm = if (stValues.isNotEmpty()) stValues.max() else integrated
        val histogram = buildHistogram(stValues)
        val lra = computeLra(stValues)

        val tpPerCh = truePeakDetector.getTruePeakDb()
        val spPerCh = truePeakDetector.getSamplePeakDb()
        val tpDb = tpPerCh.max()
        val samplePeakDb = spPerCh.max()

        // ── Per-channel metrics ──
        val channelMetrics = List(channelCount) { ch ->
            val chPowers =
                channelPowers400ms[ch].toDoubleArray()
            val chIntegrated = computeGatedLoudness(chPowers)
            val chMomentary = computeMomentaryMax(chPowers)
            val chStValues = computeShortTermValues(chPowers)
            val chShortTerm = if (chStValues.isNotEmpty()) chStValues.max() else chIntegrated

            ChannelLoudness(
                channelName = channelList.getOrNull(ch)?.name
                    ?: "Ch ${ch + 1}",
                integrated = chIntegrated,
                truePeak = tpPerCh[ch],
                shortTerm = chShortTerm,
                momentary = chMomentary,
                samplePeak = spPerCh[ch],
                isUsedInLufs = weights[ch] > 0.0,
                lufsWeight = weights[ch].toFloat()
            )
        }

        return LufsMetrics(
            integrated = integrated,
            truePeak = tpDb,
            shortTerm = shortTerm,
            momentary = momentary,
            dynamicRange = lra,
            plr = tpDb - integrated,
            psr = tpDb - shortTerm,
            relativeThreshold = relThreshold,
            samplePeak = samplePeakDb,
            leftRightShift = calculateLrShift(),
            channelMetrics = channelMetrics,
            shortTermHistogram = histogram
        )
    }

    // ── LR balance ──────────────────────────────────────────────

    fun calculateLrShift(): Double {
        if (channelCount < 2) return 0.0
        val leftPowers = channelPowers400ms[0].toDoubleArray()
        val rightPowers = channelPowers400ms[1].toDoubleArray()
        if (leftPowers.isEmpty() || rightPowers.isEmpty()) return 0.0
        return computeGatedLoudness(rightPowers) -
                computeGatedLoudness(leftPowers)
    }

    // ── Gating helpers (BS.1770-5 §Annex 1, eqs 3-7) ───────────

    /**
     * Two-stage gated loudness. Returns the integrated value only.
     */
    private fun computeGatedLoudness(powers: DoubleArray): Double =
        computeGatedLoudnessWithThreshold(powers).first

    /**
     * Two-stage gated loudness returning both the integrated value
     * and the relative threshold Γ_r.
     */
    private fun computeGatedLoudnessWithThreshold(
        powers: DoubleArray
    ): Pair<Double, Double> {
        if (powers.isEmpty()) return Pair(-70.0, -70.0)

        val blockLoudness = DoubleArray(powers.size) {
            -0.691 + 10.0 * log10(powers[it] + 1e-12)
        }

        // Absolute gate at −70 LKFS
        val absGated = blockLoudness.indices
            .filter { blockLoudness[it] > -70.0 }
        if (absGated.isEmpty()) return Pair(-70.0, -70.0)

        val zAvgAbs = absGated.sumOf { powers[it] } / absGated.size
        val relThreshold =
            -0.691 + 10.0 * log10(zAvgAbs + 1e-12) - 10.0

        // Relative gate
        val relGated = blockLoudness.indices
            .filter { blockLoudness[it] > relThreshold
                    && blockLoudness[it] > -70.0 }
        if (relGated.isEmpty()) return Pair(-70.0, relThreshold)

        val integrated = -0.691 + 10.0 * log10(
            relGated.sumOf { powers[it] } / relGated.size + 1e-12
        )
        return Pair(integrated, relThreshold)
    }

    /** Maximum momentary (400 ms block) loudness. */
    private fun computeMomentaryMax(powers: DoubleArray): Double {
        if (powers.isEmpty()) return -70.0
        return powers.maxOf {
            -0.691 + 10.0 * log10(it + 1e-12)
        }
    }

    /**
     * Maximum short-term (3 s sliding window) loudness,
     * plus loudness range (LRA) as the 10th–95th percentile spread.
     */
    private fun computeShortTermValues(
        powers: DoubleArray
    ): DoubleArray {
        val stWindow = 30 // 3 s = 30 × 100 ms steps
        if (powers.size < stWindow) return DoubleArray(0)

        val stCount = powers.size - stWindow + 1
        val lSt = DoubleArray(stCount)

        var sum = 0.0
        for (i in 0 until stWindow) sum += powers[i]
        lSt[0] = -0.691 + 10.0 * log10(sum / stWindow + 1e-12)

        for (i in 1 until stCount) {
            sum += powers[i + stWindow - 1] - powers[i - 1]
            lSt[i] = -0.691 + 10.0 * log10(sum / stWindow + 1e-12)
        }
        return lSt
    }

    /**
     * Loudness range (LRA): 10th–95th percentile spread
     * of short-term loudness values.
     */
    private fun computeLra(stValues: DoubleArray): Double {
        if (stValues.isEmpty()) return 0.0
        val sorted = stValues.sorted()
        val hi = sorted[
            (sorted.size * 0.95).toInt()
                .coerceAtMost(sorted.lastIndex)
        ]
        val lo = sorted[(sorted.size * 0.10).toInt()]
        return hi - lo
    }

    /**
     * Build a histogram of short-term loudness values using
     * 0.1-dB buckets. Each bucket [N/10, (N+1)/10) counts how many
     * 3 s windows fall into that 0.1-dB range.
     * Values at or below −70 LUFS (silence) are excluded.
     */
    private fun buildHistogram(
        stValues: DoubleArray
    ): List<HistogramBucket> {
        if (stValues.isEmpty()) return emptyList()

        val buckets = mutableMapOf<Int, Int>()
        for (v in stValues) {
            if (v > -70.0) {
                // Scale by 10 so integer keys represent 0.1 dB steps
                val key = floor(v * 10).toInt()
                buckets[key] = (buckets[key] ?: 0) + 1
            }
        }
        if (buckets.isEmpty()) return emptyList()

        val minKey = buckets.keys.min()
        val maxKey = buckets.keys.max()

        // Return contiguous range so gaps show as zero-count bars
        return (minKey..maxKey).map { key ->
            HistogramBucket(key, buckets[key] ?: 0)
        }
    }
    // ── Empty result ────────────────────────────────────────────

    private fun emptyMetrics() = LufsMetrics(
        integrated = -70.0,
        truePeak = -70.0,
        shortTerm = -70.0,
        momentary = -70.0,
        dynamicRange = 0.0,
        plr = 0.0,
        psr = 0.0,
        relativeThreshold = -70.0,
        samplePeak = -70.0,
        leftRightShift = 0.0,
        channelMetrics = emptyList(),
        shortTermHistogram = emptyList()
    )

    // ── Biquad IIR filter (transposed direct form II) ───────────

    private class ManualBiquad(
        val b: DoubleArray,
        val a: DoubleArray,
        numChannels: Int
    ) {
        // State: [x1, x2, y1, y2] per channel
        private val state = Array(numChannels) { DoubleArray(4) }

        fun process(
            input: Array<DoubleArray>
        ): Array<DoubleArray> {
            val output =
                Array(input.size) { DoubleArray(input[0].size) }
            for (ch in input.indices) {
                var (x1, x2, y1, y2) = state[ch]
                for (n in input[ch].indices) {
                    val xn = input[ch][n]
                    val yn = b[0] * xn + b[1] * x1 + b[2] * x2 -
                            a[1] * y1 - a[2] * y2
                    output[ch][n] = yn
                    x2 = x1; x1 = xn; y2 = y1; y1 = yn
                }
                state[ch][0] = x1
                state[ch][1] = x2
                state[ch][2] = y1
                state[ch][3] = y2
            }
            return output
        }
    }

    // ── K-weighting coefficient generation ──────────────────────

    companion object {
        private fun getKWeightingCoeffs(
            fs: Int
        ): Pair<
                Pair<DoubleArray, DoubleArray>,
                Pair<DoubleArray, DoubleArray>
                > {
            val fsD = fs.toDouble()

            // Stage 1: high-shelf to model spherical head
            val f0 = 1681.97445095022
            val G = 3.9988776988675
            val Q = 0.7071
            val K = tan(PI * f0 / fsD)
            val Vh = 10.0.pow(G / 20.0)
            val Vb = sqrt(Vh)
            val d = 1.0 + Vb / Q * K + K * K
            val b1 = doubleArrayOf(
                (Vh + Vb / Q * K + K * K) / d,
                2.0 * (K * K - Vh) / d,
                (Vh - Vb / Q * K + K * K) / d
            )
            val a1 = doubleArrayOf(
                1.0,
                2.0 * (K * K - 1.0) / d,
                (1.0 - Vb / Q * K + K * K) / d
            )

            // Stage 2: RLB high-pass
            val f0Hp = 38.1354708760226
            val kHp = tan(PI * f0Hp / fsD)
            val dHp = 1.0 + kHp / 0.5 + kHp * kHp
            val b2 = doubleArrayOf(
                1.0 / dHp,
                -2.0 / dHp,
                1.0 / dHp
            )
            val a2 = doubleArrayOf(
                1.0,
                2.0 * (kHp * kHp - 1.0) / dHp,
                (1.0 - kHp / 0.5 + kHp * kHp) / dHp
            )

            return Pair(Pair(b1, a1), Pair(b2, a2))
        }
    }
}