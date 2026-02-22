package com.pidog.lufstracer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.Int
import kotlin.math.*

private class PrimitiveDoubleList(initialCapacity: Int = 256) {
    var data = DoubleArray(initialCapacity)
        private set
    var size = 0
        private set

    fun add(value: Double) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }

    fun get(index: Int): Double = data[index]
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

    private val powers400ms = PrimitiveDoubleList(8192)
    private val channelPowers400ms = Array(channelCount) { PrimitiveDoubleList(8192) }
    private val truePeakDetector = TruePeakDetector(channelCount)

    private var overlapBuffer: Array<DoubleArray> = Array(channelCount) { DoubleArray(0) }
    private var leftoverSamples = FloatArray(0)

    private var channelsBuf: Array<DoubleArray> = Array(channelCount) { DoubleArray(0) }
    private var filtBuf1: Array<DoubleArray> = Array(channelCount) { DoubleArray(0) }
    private var filtBuf2: Array<DoubleArray> = Array(channelCount) { DoubleArray(0) }

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

    // MULTITHREADED PROCESSING
    suspend fun processSamples(interleavedSamples: FloatArray) = coroutineScope {
        val source: FloatArray
        if (leftoverSamples.isNotEmpty()) {
            source = FloatArray(leftoverSamples.size + interleavedSamples.size)
            leftoverSamples.copyInto(source)
            interleavedSamples.copyInto(source, leftoverSamples.size)
        } else {
            source = interleavedSamples
        }

        val totalFloats = source.size
        val completeFrames = totalFloats / channelCount
        val usedFloats = completeFrames * channelCount

        leftoverSamples = if (usedFloats < totalFloats) source.copyOfRange(usedFloats, totalFloats) else FloatArray(0)

        if (completeFrames == 0) return@coroutineScope

        // Exponentially grow array to prevent constant GC reallocation freezes
        if (channelsBuf.isEmpty() || channelsBuf[0].size < completeFrames) {
            val newCap = max(channelsBuf.getOrNull(0)?.size?.times(2) ?: completeFrames, completeFrames)
            channelsBuf = Array(channelCount) { DoubleArray(newCap) }
            filtBuf1 = Array(channelCount) { DoubleArray(newCap) }
            filtBuf2 = Array(channelCount) { DoubleArray(newCap) }
        }

        // Deinterleave on main scope (very fast, no need to thread)
        for (frame in 0 until completeFrames) {
            val base = frame * channelCount
            for (ch in 0 until channelCount) {
                channelsBuf[ch][frame] = source[base + ch].toDouble()
            }
        }

        // --- Execute channels in parallel ---
        val jobs = (0 until channelCount).map { ch ->
            async(Dispatchers.Default) {
                // 1. True Peak Math
                truePeakDetector.processChannel(ch, channelsBuf[ch], completeFrames)

                // 2. K-Weighting Math
                filter1.processChannel(ch, channelsBuf[ch], completeFrames, filtBuf1[ch])
                filter2.processChannel(ch, filtBuf1[ch], completeFrames, filtBuf2[ch])

                // 3. Slice 400ms overlap strictly for this channel
                var pos = 0
                val ovCh = overlapBuffer[ch]
                val newCh = filtBuf2[ch]
                val chPowers = channelPowers400ms[ch]
                val overlapLen = ovCh.size
                val totalLen = overlapLen + completeFrames

                while (pos + blockSize <= totalLen) {
                    var sumSq = 0.0
                    for (i in 0 until blockSize) {
                        val idx = pos + i
                        val v = if (idx < overlapLen) ovCh[idx] else newCh[idx - overlapLen]
                        sumSq += v * v
                    }
                    chPowers.add(sumSq / blockSize)
                    pos += stepSize
                }
                pos // return position block finished at
            }
        }

        val endPositions = jobs.awaitAll()
        val pos = endPositions[0] // Since block sizes match, pos is identical across channels

        // Synchronously aggregate block sums to overall metrics
        val oldBlocks = powers400ms.size
        val newBlocks = channelPowers400ms[0].size
        for (i in oldBlocks until newBlocks) {
            var weightedSum = 0.0
            for (ch in 0 until channelCount) {
                weightedSum += channelPowers400ms[ch].get(i) * weights[ch]
            }
            powers400ms.add(weightedSum)
        }

        // Update Overlap buffers
        val overlapLen = overlapBuffer[0].size
        val totalLen = overlapLen + completeFrames
        val remaining = totalLen - pos

        if (remaining == 0) {
            overlapBuffer = Array(channelCount) { DoubleArray(0) }
        } else {
            val newOverlap = if (overlapBuffer[0].size == remaining) overlapBuffer else Array(channelCount) { DoubleArray(remaining) }
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

        val (integrated, relThreshold) = computeGatedLoudnessWithThreshold(powers)
        val momentary = computeMomentaryMax(powers)
        val stValues = computeShortTermValues(powers)
        val shortTerm = if (stValues.isNotEmpty()) stValues.max() else integrated
        val histogram = buildHistogram(stValues)
        val minval = histogram[0].rangeStartDb;
        val maxval = histogram.last().rangeStartDb;
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
            val chShortTerm = if (chStValues.isNotEmpty()) chStValues.max() else chIntegrated

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
            histogramMin = minval,
            histogramMax = maxval,
            duration = 0.0
        )
    }

    fun calculateLrShift(): Double {
        if (channelCount < 2) return 0.0
        val leftPowers = channelPowers400ms[0].toDoubleArray()
        val rightPowers = channelPowers400ms[1].toDoubleArray()
        if (leftPowers.isEmpty() || rightPowers.isEmpty()) return 0.0
        return computeGatedLoudness(rightPowers) - computeGatedLoudness(leftPowers)
    }

    private fun computeGatedLoudness(powers: DoubleArray): Double = computeGatedLoudnessWithThreshold(powers).first

    private fun computeGatedLoudnessWithThreshold(powers: DoubleArray): Pair<Double, Double> {
        if (powers.isEmpty()) return Pair(-70.0, -70.0)

        val blockLoudness = DoubleArray(powers.size) { -0.691 + 10.0 * log10(powers[it] + 1e-12) }

        var absCount = 0
        var absSum = 0.0
        for (i in powers.indices) {
            if (blockLoudness[i] > -70.0) {
                absSum += powers[i]
                absCount++
            }
        }
        if (absCount == 0) return Pair(-70.0, -70.0)

        val relThreshold = -0.691 + 10.0 * log10(absSum / absCount + 1e-12) - 10.0

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

        val integrated = -0.691 + 10.0 * log10(relSum / relCount + 1e-12)
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
        val hi = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)]
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
        return (minKey..maxKey).map { key -> HistogramBucket(key, buckets[key] ?: 0) }
    }

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
        histogramMin = -70,
        histogramMax = 30,
        duration = 0.0
    )

    private class ManualBiquad(
        val b: DoubleArray,
        val a: DoubleArray,
        numChannels: Int
    ) {
        private val state = Array(numChannels) { DoubleArray(4) }

        // Process a single channel thread-safely
        fun processChannel(ch: Int, inCh: DoubleArray, frameCount: Int, outCh: DoubleArray) {
            val st = state[ch]
            var x1 = st[0]; var x2 = st[1]
            var y1 = st[2]; var y2 = st[3]

            val b0 = b[0]; val b1c = b[1]; val b2c = b[2]
            val a1c = a[1]; val a2c = a[2]

            for (n in 0 until frameCount) {
                val xn = inCh[n]
                val yn = b0 * xn + b1c * x1 + b2c * x2 - a1c * y1 - a2c * y2
                outCh[n] = yn
                x2 = x1; x1 = xn; y2 = y1; y1 = yn
            }
            st[0] = x1; st[1] = x2; st[2] = y1; st[3] = y2
        }
    }

    companion object {
        private fun getKWeightingCoeffs(fs: Int): Pair<Pair<DoubleArray, DoubleArray>, Pair<DoubleArray, DoubleArray>> {
            val fsD = fs.toDouble()
            val f0 = 1681.97445095022
            val G = 3.9988776988675
            val Q = 0.7071
            val K = tan(PI * f0 / fsD)
            val Vh = 10.0.pow(G / 20.0)
            val Vb = sqrt(Vh)
            val d = 1.0 + Vb / Q * K + K * K
            val b1 = doubleArrayOf((Vh + Vb / Q * K + K * K) / d, 2.0 * (K * K - Vh) / d, (Vh - Vb / Q * K + K * K) / d)
            val a1 = doubleArrayOf(1.0, 2.0 * (K * K - 1.0) / d, (1.0 - Vb / Q * K + K * K) / d)

            val f0Hp = 38.1354708760226
            val kHp = tan(PI * f0Hp / fsD)
            val dHp = 1.0 + kHp / 0.5 + kHp * kHp
            val b2 = doubleArrayOf(1.0 / dHp, -2.0 / dHp, 1.0 / dHp)
            val a2 = doubleArrayOf(1.0, 2.0 * (kHp * kHp - 1.0) / dHp, (1.0 - kHp / 0.5 + kHp * kHp) / dHp)

            return Pair(Pair(b1, a1), Pair(b2, a2))
        }
    }
}