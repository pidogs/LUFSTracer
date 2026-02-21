package com.pidog.lufstracer

import kotlin.math.*

// ── Unboxed growing double array ─────────────────────────────────────────────
private class PrimitiveDoubleList(initialCapacity: Int = 256) {
    var data = DoubleArray(initialCapacity)
        private set
    var size = 0
        private set

    fun add(value: Double) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }

    fun toDoubleArray(): DoubleArray = data.copyOf(size)
    fun isEmpty() = size == 0
}

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

    // Overall weighted 400 ms block powers (unboxed)
    private val powers400ms = PrimitiveDoubleList(8192)

    // Per-channel unweighted 400 ms block powers (unboxed)
    private val channelPowers400ms =
        Array(channelCount) { PrimitiveDoubleList(8192) }

    // BS.1770-5 Annex 2 true-peak detector
    private val truePeakDetector = TruePeakDetector(channelCount)

    // Overlap kept between processSamples calls (at most blockSize - stepSize)
    private var overlapBuffer: Array<DoubleArray> =
        Array(channelCount) { DoubleArray(0) }
    private var leftoverSamples = FloatArray(0)

    // Reusable deinterleave buffer – grown as needed, never shrunk
    private var channelsBuf: Array<DoubleArray> =
        Array(channelCount) { DoubleArray(0) }

    // Reusable filter output buffers
    private var filtBuf1: Array<DoubleArray> =
        Array(channelCount) { DoubleArray(0) }
    private var filtBuf2: Array<DoubleArray> =
        Array(channelCount) { DoubleArray(0) }

    init {
        val (coeffs1, coeffs2) = getKWeightingCoeffs(sampleRate)
        filter1 = ManualBiquad(coeffs1.first, coeffs1.second, channelCount)
        filter2 = ManualBiquad(coeffs2.first, coeffs2.second, channelCount)

        weights = DoubleArray(channelCount) { ch ->
            when {
                isLfeChannel(ch) -> 0.0
                isSurroundChannel(ch) -> 1.41
                else -> 1.0
            }
        }
    }

    // ── Channel role detection ────────────────────────────────────────────────

    private fun isLfeChannel(ch: Int): Boolean {
        val name = channelList.getOrNull(ch)?.name?.uppercase() ?: ""
        if ("LFE" in name || "SUB" in name) return true
        return channelCount >= 6 && ch == 3
    }

    private fun isSurroundChannel(ch: Int): Boolean {
        val name = channelList.getOrNull(ch)?.name?.uppercase() ?: ""
        if ("SURROUND" in name || "SIDE" in name || "REAR" in name) return true
        return channelCount >= 6 && (ch == 4 || ch == 5)
    }

    // ── Streaming input ───────────────────────────────────────────────────────

    fun processSamples(interleavedSamples: FloatArray) {
        // Prepend any leftover from previous call without boxing
        val source: FloatArray
        val sourceOffset: Int
        if (leftoverSamples.isNotEmpty()) {
            val merged = FloatArray(leftoverSamples.size + interleavedSamples.size)
            leftoverSamples.copyInto(merged)
            interleavedSamples.copyInto(merged, leftoverSamples.size)
            source = merged
            sourceOffset = 0
        } else {
            source = interleavedSamples
            sourceOffset = 0
        }

        val completeFrames = source.size / channelCount
        val usedFloats = completeFrames * channelCount
        leftoverSamples =
            if (usedFloats < source.size) source.copyOfRange(usedFloats, source.size)
            else FloatArray(0)

        if (completeFrames == 0) return

        // ── Grow reusable deinterleave buffers if needed ──
        if (channelsBuf[0].size < completeFrames) {
            channelsBuf = Array(channelCount) { DoubleArray(completeFrames) }
            filtBuf1 = Array(channelCount) { DoubleArray(completeFrames) }
            filtBuf2 = Array(channelCount) { DoubleArray(completeFrames) }
        }

        // Deinterleave into reusable buffer
        for (frame in 0 until completeFrames) {
            val base = sourceOffset + frame * channelCount
            for (ch in 0 until channelCount) {
                channelsBuf[ch][frame] = source[base + ch].toDouble()
            }
        }

        // True-peak detection (operates on the full channel views)
        // Wrap in views of the correct length to avoid passing stale tail data
        val channelViews =
            if (channelsBuf[0].size == completeFrames) channelsBuf
            else Array(channelCount) { ch ->
                channelsBuf[ch].copyOf(completeFrames)
            }
        truePeakDetector.processBlock(channelViews)

        // K-weighting: filter in-place into reusable output buffers
        filter1.process(channelsBuf, completeFrames, filtBuf1)
        filter2.process(filtBuf1, completeFrames, filtBuf2)
        // filtBuf2 now holds K-weighted samples for indices [0, completeFrames)

        // ── Slice 400 ms gating blocks with 75 % overlap ──
        // Reads across (overlapBuffer ++ filtBuf2[0..completeFrames))
        // WITHOUT physically concatenating the two arrays.
        val overlapLen = overlapBuffer[0].size
        val totalLen = overlapLen + completeFrames
        var pos = 0

        while (pos + blockSize <= totalLen) {
            var weightedSum = 0.0

            for (ch in 0 until channelCount) {
                val ovCh = overlapBuffer[ch]
                val newCh = filtBuf2[ch]
                var sumSq = 0.0

                for (i in 0 until blockSize) {
                    val idx = pos + i
                    val v = if (idx < overlapLen) ovCh[idx] else newCh[idx - overlapLen]
                    sumSq += v * v
                }

                val avgPower = sumSq / blockSize
                channelPowers400ms[ch].add(avgPower)
                weightedSum += avgPower * weights[ch]
            }

            powers400ms.add(weightedSum)
            pos += stepSize
        }

        // Save the tail as the next overlap (≤ blockSize - stepSize samples)
        val remaining = totalLen - pos
        if (remaining == 0) {
            overlapBuffer = Array(channelCount) { DoubleArray(0) }
        } else {
            // Reuse existing overlap arrays if they are the right size
            val newOverlap =
                if (overlapBuffer[0].size == remaining) overlapBuffer
                else Array(channelCount) { DoubleArray(remaining) }

            for (ch in 0 until channelCount) {
                val ovCh = overlapBuffer[ch]
                val newCh = filtBuf2[ch]
                val dst = newOverlap[ch]
                for (i in 0 until remaining) {
                    val idx = pos + i
                    dst[i] = if (idx < overlapLen) ovCh[idx] else newCh[idx - overlapLen]
                }
            }
            overlapBuffer = newOverlap
        }
    }

    // ── Final metrics ─────────────────────────────────────────────────────────

    fun calculateMetrics(): LufsMetrics {
        val powers = powers400ms.toDoubleArray()
        if (powers.isEmpty()) return emptyMetrics()

        val (integrated, relThreshold) =
            computeGatedLoudnessWithThreshold(powers)
        val momentary = computeMomentaryMax(powers)
        val stValues = computeShortTermValues(powers)
        val shortTerm =
            if (stValues.isNotEmpty()) stValues.max() else integrated
        val histogram = buildHistogram(stValues)
        val lra = computeLra(stValues)

        val tpPerCh = truePeakDetector.getTruePeakDb()
        val spPerCh = truePeakDetector.getSamplePeakDb()
        val tpDb = tpPerCh.max()
        val samplePeakDb = spPerCh.max()

        val channelMetrics = List(channelCount) { ch ->
            val chPowers = channelPowers400ms[ch].toDoubleArray()
            val chIntegrated = computeGatedLoudness(chPowers)
            val chMomentary = computeMomentaryMax(chPowers)
            val chStValues = computeShortTermValues(chPowers)
            val chShortTerm =
                if (chStValues.isNotEmpty()) chStValues.max() else chIntegrated

            ChannelLoudness(
                channelName = channelList.getOrNull(ch)?.name ?: "Ch ${ch + 1}",
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
            shortTermHistogram = histogram,
            duration = 0.0
        )
    }

    // ── LR balance ────────────────────────────────────────────────────────────

    fun calculateLrShift(): Double {
        if (channelCount < 2) return 0.0
        val leftPowers = channelPowers400ms[0].toDoubleArray()
        val rightPowers = channelPowers400ms[1].toDoubleArray()
        if (leftPowers.isEmpty() || rightPowers.isEmpty()) return 0.0
        return computeGatedLoudness(rightPowers) -
                computeGatedLoudness(leftPowers)
    }

    // ── Gating helpers (BS.1770-5 §Annex 1, eqs 3–7) ─────────────────────────

    private fun computeGatedLoudness(powers: DoubleArray): Double =
        computeGatedLoudnessWithThreshold(powers).first

    private fun computeGatedLoudnessWithThreshold(
        powers: DoubleArray
    ): Pair<Double, Double> {
        if (powers.isEmpty()) return Pair(-70.0, -70.0)

        val blockLoudness = DoubleArray(powers.size) {
            -0.691 + 10.0 * log10(powers[it] + 1e-12)
        }

        // Absolute gate at −70 LKFS
        var absCount = 0
        var absSum = 0.0
        for (i in powers.indices) {
            if (blockLoudness[i] > -70.0) {
                absSum += powers[i]
                absCount++
            }
        }
        if (absCount == 0) return Pair(-70.0, -70.0)

        val relThreshold =
            -0.691 + 10.0 * log10(absSum / absCount + 1e-12) - 10.0

        // Relative gate
        var relCount = 0
        var relSum = 0.0
        for (i in powers.indices) {
            val l = blockLoudness[i]
            if (l > relThreshold && l > -70.0) {
                relSum += powers[i]
                relCount++
            }
        }
        if (relCount == 0) return Pair(-70.0, relThreshold)

        val integrated =
            -0.691 + 10.0 * log10(relSum / relCount + 1e-12)
        return Pair(integrated, relThreshold)
    }

    private fun computeMomentaryMax(powers: DoubleArray): Double {
        if (powers.isEmpty()) return -70.0
        return powers.maxOf { -0.691 + 10.0 * log10(it + 1e-12) }
    }

    private fun computeShortTermValues(powers: DoubleArray): DoubleArray {
        val stWindow = 30
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

    private fun computeLra(stValues: DoubleArray): Double {
        if (stValues.isEmpty()) return 0.0
        val sorted = stValues.sorted()
        val hi = sorted[(sorted.size * 0.95).toInt()
            .coerceAtMost(sorted.lastIndex)]
        val lo = sorted[(sorted.size * 0.10).toInt()]
        return hi - lo
    }

    private fun buildHistogram(stValues: DoubleArray): List<HistogramBucket> {
        if (stValues.isEmpty()) return emptyList()

        val buckets = mutableMapOf<Int, Int>()
        for (v in stValues) {
            if (v > -70.0) {
                val key = floor(v * 10).toInt()
                buckets[key] = (buckets[key] ?: 0) + 1
            }
        }
        if (buckets.isEmpty()) return emptyList()

        val minKey = buckets.keys.min()
        val maxKey = buckets.keys.max()
        return (minKey..maxKey).map { key ->
            HistogramBucket(key, buckets[key] ?: 0)
        }
    }

    // ── Empty result ──────────────────────────────────────────────────────────

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
        shortTermHistogram = emptyList(),
        duration = 0.0
    )

    // ── Biquad IIR filter (transposed direct form II) ─────────────────────────

    private class ManualBiquad(
        val b: DoubleArray,
        val a: DoubleArray,
        numChannels: Int
    ) {
        // State: [x1, x2, y1, y2] per channel — persists across calls
        private val state = Array(numChannels) { DoubleArray(4) }

        /**
         * Process [frameCount] frames from [input] into [output].
         * Both arrays must have at least [frameCount] elements per channel.
         * Output arrays are written in-place; no allocation occurs.
         */
        fun process(
            input: Array<DoubleArray>,
            frameCount: Int,
            output: Array<DoubleArray>
        ) {
            val b0 = b[0]; val b1c = b[1]; val b2c = b[2]
            val a1c = a[1]; val a2c = a[2]

            for (ch in input.indices) {
                val st = state[ch]
                var x1 = st[0]; var x2 = st[1]
                var y1 = st[2]; var y2 = st[3]
                val inCh = input[ch]
                val outCh = output[ch]

                for (n in 0 until frameCount) {
                    val xn = inCh[n]
                    val yn = b0 * xn + b1c * x1 + b2c * x2 -
                            a1c * y1 - a2c * y2
                    outCh[n] = yn
                    x2 = x1; x1 = xn; y2 = y1; y1 = yn
                }

                st[0] = x1; st[1] = x2; st[2] = y1; st[3] = y2
            }
        }
    }

    // ── K-weighting coefficient generation ───────────────────────────────────

    companion object {
        private fun getKWeightingCoeffs(
            fs: Int
        ): Pair<
                Pair<DoubleArray, DoubleArray>,
                Pair<DoubleArray, DoubleArray>> {
            val fsD = fs.toDouble()

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

            val f0Hp = 38.1354708760226
            val kHp = tan(PI * f0Hp / fsD)
            val dHp = 1.0 + kHp / 0.5 + kHp * kHp
            val b2 = doubleArrayOf(1.0 / dHp, -2.0 / dHp, 1.0 / dHp)
            val a2 = doubleArrayOf(
                1.0,
                2.0 * (kHp * kHp - 1.0) / dHp,
                (1.0 - kHp / 0.5 + kHp * kHp) / dHp
            )

            return Pair(Pair(b1, a1), Pair(b2, a2))
        }
    }
}