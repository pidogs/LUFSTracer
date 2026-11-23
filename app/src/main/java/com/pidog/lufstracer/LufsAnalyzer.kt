package com.pidog.lufstracer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

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
    companion object {
        // 4096 doubles = 32 KB per channel, fits comfortably in L1 data cache
        private const val TILE_FRAMES = 4096
        private const val INV_32768 = 1.0 / 32768.0

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

    // K-weighting biquad coefficients (scalar fields for register-resident hot loop)
    private val f1b0: Double
    private val f1b1: Double
    private val f1b2: Double
    private val f1a1: Double
    private val f1a2: Double

    private val f2b0: Double
    private val f2b1: Double
    private val f2b2: Double
    private val f2a1: Double
    private val f2a2: Double

    // Per-channel biquad states: [x1, x2, y1, y2]
    private val f1State = Array(channelCount) { DoubleArray(4) }
    private val f2State = Array(channelCount) { DoubleArray(4) }

    private val weights: DoubleArray

    private val blockSize = (0.4 * sampleRate).toInt()
    private val stepSize = (0.1 * sampleRate).toInt()
    private val invBlockSize = if (blockSize > 0) 1.0 / blockSize else 0.0
    private val useSubBlockAccum = (stepSize > 0 && blockSize == 4 * stepSize)

    private val powers400ms = PrimitiveDoubleList(8192)
    private val channelPowers400ms = Array(channelCount) { PrimitiveDoubleList(8192) }
    private val truePeakDetector = TruePeakDetector(channelCount)

    // Fixed small L1-resident tile buffer per channel (32 KB/ch, never grows)
    private val tileBufs = Array(channelCount) { DoubleArray(TILE_FRAMES) }

    // Fast 100ms sub-block accumulators (used when blockSize == 4 * stepSize)
    private val subBlockRings = Array(channelCount) { DoubleArray(4) }
    private val subBlockCompleted = IntArray(channelCount)
    private val curSubSumSq = DoubleArray(channelCount)
    private val curSubCount = IntArray(channelCount)

    // General overlap fallback (used only for rare rates where blockSize != 4 * stepSize, e.g. 11025 Hz)
    private val fallbackOverlap: Array<DoubleArray> =
        if (useSubBlockAccum) emptyArray() else Array(channelCount) { DoubleArray(blockSize + TILE_FRAMES) }
    private val fallbackOverlapLen = IntArray(channelCount)

    // Misaligned partial-frame carryover between chunks
    private var leftoverFloats = FloatArray(0)
    private var leftoverShorts = ShortArray(0)

    init {
        val (coeffs1, coeffs2) = getKWeightingCoeffs(sampleRate)
        f1b0 = coeffs1.first[0]
        f1b1 = coeffs1.first[1]
        f1b2 = coeffs1.first[2]
        f1a1 = coeffs1.second[1]
        f1a2 = coeffs1.second[2]

        f2b0 = coeffs2.first[0]
        f2b1 = coeffs2.first[1]
        f2b2 = coeffs2.first[2]
        f2a1 = coeffs2.second[1]
        f2a2 = coeffs2.second[2]

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

    // ── Public Processing Entry Points ────────────────────────────────────────

    /**
     * Processes interleaved normalized float samples in [-1f, 1f].
     * Uses L1-cache tiling and fused single-pass K-weighting + power accumulation.
     */
    suspend fun processSamples(interleavedSamples: FloatArray) = coroutineScope {
        val source: FloatArray
        if (leftoverFloats.isNotEmpty()) {
            source = FloatArray(leftoverFloats.size + interleavedSamples.size)
            leftoverFloats.copyInto(source)
            interleavedSamples.copyInto(source, leftoverFloats.size)
        } else {
            source = interleavedSamples
        }

        val totalFloats = source.size
        val completeFrames = totalFloats / channelCount
        val usedFloats = completeFrames * channelCount

        leftoverFloats = if (usedFloats < totalFloats) {
            source.copyOfRange(usedFloats, totalFloats)
        } else {
            FloatArray(0)
        }

        if (completeFrames == 0) return@coroutineScope

        if (channelCount == 1) {
            processChannelFloats(0, source, completeFrames)
        } else {
            val jobs = (1 until channelCount).map { ch ->
                async(Dispatchers.Default) {
                    processChannelFloats(ch, source, completeFrames)
                }
            }
            processChannelFloats(0, source, completeFrames)
            jobs.awaitAll()
        }

        aggregateOverallBlocks()
    }

    /**
     * Fast path for interleaved 16-bit signed PCM directly from MediaCodec or WAV streams.
     * Bit-identical to converting each Short via `sample.toFloat() / 32768.0f` before `.toDouble()`.
     */
    suspend fun processPcm16(interleavedPcm16: ShortArray, sampleCount: Int = interleavedPcm16.size) = coroutineScope {
        if (sampleCount <= 0) return@coroutineScope

        val source: ShortArray
        val totalShorts: Int
        if (leftoverShorts.isNotEmpty()) {
            totalShorts = leftoverShorts.size + sampleCount
            source = ShortArray(totalShorts)
            leftoverShorts.copyInto(source)
            interleavedPcm16.copyInto(source, leftoverShorts.size, 0, sampleCount)
        } else {
            source = interleavedPcm16
            totalShorts = sampleCount
        }

        val completeFrames = totalShorts / channelCount
        val usedShorts = completeFrames * channelCount

        leftoverShorts = if (usedShorts < totalShorts) {
            source.copyOfRange(usedShorts, totalShorts)
        } else {
            ShortArray(0)
        }

        if (completeFrames == 0) return@coroutineScope

        if (channelCount == 1) {
            processChannelPcm16(0, source, completeFrames)
        } else {
            val jobs = (1 until channelCount).map { ch ->
                async(Dispatchers.Default) {
                    processChannelPcm16(ch, source, completeFrames)
                }
            }
            processChannelPcm16(0, source, completeFrames)
            jobs.awaitAll()
        }

        aggregateOverallBlocks()
    }

    // ── Per-Channel L1-Tiled Fused DSP Core ───────────────────────────────────

    private fun processChannelFloats(ch: Int, source: FloatArray, completeFrames: Int) {
        val tile = tileBufs[ch]
        val stride = channelCount
        var frameOffset = 0

        while (frameOffset < completeFrames) {
            val tileLen = min(TILE_FRAMES, completeFrames - frameOffset)
            if (stride == 1) {
                var i = 0
                while (i < tileLen) {
                    tile[i] = source[frameOffset + i].toDouble()
                    i++
                }
            } else {
                var srcIdx = frameOffset * stride + ch
                var i = 0
                while (i < tileLen) {
                    tile[i] = source[srcIdx].toDouble()
                    srcIdx += stride
                    i++
                }
            }

            processChannelTile(ch, tile, tileLen)
            frameOffset += tileLen
        }
    }

    private fun processChannelPcm16(ch: Int, source: ShortArray, completeFrames: Int) {
        val tile = tileBufs[ch]
        val stride = channelCount
        val scale = INV_32768
        var frameOffset = 0

        while (frameOffset < completeFrames) {
            val tileLen = min(TILE_FRAMES, completeFrames - frameOffset)
            if (stride == 1) {
                var i = 0
                while (i < tileLen) {
                    tile[i] = source[frameOffset + i].toDouble() * scale
                    i++
                }
            } else {
                var srcIdx = frameOffset * stride + ch
                var i = 0
                while (i < tileLen) {
                    tile[i] = source[srcIdx].toDouble() * scale
                    srcIdx += stride
                    i++
                }
            }

            processChannelTile(ch, tile, tileLen)
            frameOffset += tileLen
        }
    }

    private fun processChannelTile(ch: Int, tile: DoubleArray, tileLen: Int) {
        // 1. True Peak + Sample Peak while tile is hot in L1 cache
        truePeakDetector.processChannel(ch, tile, tileLen)

        // 2. Fused K-weighting (Filter 1 + Filter 2) + 400ms block power accumulation
        if (useSubBlockAccum) {
            processFusedSubBlocks(ch, tile, tileLen)
        } else {
            processFusedFallback(ch, tile, tileLen)
        }
    }

    /**
     * Single-pass fused biquad1 -> biquad2 -> 100ms sub-block sum-of-squares.
     * Each sample is squared once instead of 4 times, with zero intermediate arrays.
     */
    private fun processFusedSubBlocks(ch: Int, tile: DoubleArray, tileLen: Int) {
        val st1 = f1State[ch]
        var x1_1 = st1[0]; var x1_2 = st1[1]
        var y1_1 = st1[2]; var y1_2 = st1[3]

        val st2 = f2State[ch]
        var x2_1 = st2[0]; var x2_2 = st2[1]
        var y2_1 = st2[2]; var y2_2 = st2[3]

        val b1_0 = f1b0; val b1_1 = f1b1; val b1_2 = f1b2
        val a1_1 = f1a1; val a1_2 = f1a2
        val b2_0 = f2b0; val b2_1 = f2b1; val b2_2 = f2b2
        val a2_1 = f2a1; val a2_2 = f2a2

        val ring = subBlockRings[ch]
        var completed = subBlockCompleted[ch]
        var curSum = curSubSumSq[ch]
        var curCnt = curSubCount[ch]
        val step = stepSize
        val invBlock = invBlockSize
        val chPowers = channelPowers400ms[ch]

        var i = 0
        while (i < tileLen) {
            val segLen = min(tileLen - i, step - curCnt)
            val end = i + segLen
            while (i < end) {
                val xn = tile[i]
                val y1 = b1_0 * xn + b1_1 * x1_1 + b1_2 * x1_2 - a1_1 * y1_1 - a1_2 * y1_2
                x1_2 = x1_1; x1_1 = xn; y1_2 = y1_1; y1_1 = y1

                val y2 = b2_0 * y1 + b2_1 * x2_1 + b2_2 * x2_2 - a2_1 * y2_1 - a2_2 * y2_2
                x2_2 = x2_1; x2_1 = y1; y2_2 = y2_1; y2_1 = y2

                curSum += y2 * y2
                i++
            }
            curCnt += segLen
            if (curCnt == step) {
                ring[completed and 3] = curSum
                completed++
                curSum = 0.0
                curCnt = 0
                if (completed >= 4) {
                    val blockSum = ring[0] + ring[1] + ring[2] + ring[3]
                    chPowers.add(blockSum * invBlock)
                }
            }
        }

        st1[0] = x1_1; st1[1] = x1_2; st1[2] = y1_1; st1[3] = y1_2
        st2[0] = x2_1; st2[1] = x2_2; st2[2] = y2_1; st2[3] = y2_2
        subBlockCompleted[ch] = completed
        curSubSumSq[ch] = curSum
        curSubCount[ch] = curCnt
    }

    /**
     * Exact overlap-buffer fallback for rare sample rates where blockSize != 4 * stepSize (e.g. 11025 Hz).
     */
    private fun processFusedFallback(ch: Int, tile: DoubleArray, tileLen: Int) {
        val st1 = f1State[ch]
        var x1_1 = st1[0]; var x1_2 = st1[1]
        var y1_1 = st1[2]; var y1_2 = st1[3]

        val st2 = f2State[ch]
        var x2_1 = st2[0]; var x2_2 = st2[1]
        var y2_1 = st2[2]; var y2_2 = st2[3]

        val b1_0 = f1b0; val b1_1 = f1b1; val b1_2 = f1b2
        val a1_1 = f1a1; val a1_2 = f1a2
        val b2_0 = f2b0; val b2_1 = f2b1; val b2_2 = f2b2
        val a2_1 = f2a1; val a2_2 = f2a2

        val ov = fallbackOverlap[ch]
        val ovLen = fallbackOverlapLen[ch]

        var i = 0
        while (i < tileLen) {
            val xn = tile[i]
            val y1 = b1_0 * xn + b1_1 * x1_1 + b1_2 * x1_2 - a1_1 * y1_1 - a1_2 * y1_2
            x1_2 = x1_1; x1_1 = xn; y1_2 = y1_1; y1_1 = y1

            val y2 = b2_0 * y1 + b2_1 * x2_1 + b2_2 * x2_2 - a2_1 * y2_1 - a2_2 * y2_2
            x2_2 = x2_1; x2_1 = y1; y2_2 = y2_1; y2_1 = y2

            ov[ovLen + i] = y2
            i++
        }

        st1[0] = x1_1; st1[1] = x1_2; st1[2] = y1_1; st1[3] = y1_2
        st2[0] = x2_1; st2[1] = x2_2; st2[2] = y2_1; st2[3] = y2_2

        val totalLen = ovLen + tileLen
        val chPowers = channelPowers400ms[ch]
        var pos = 0
        while (pos + blockSize <= totalLen) {
            var sumSq = 0.0
            val end = pos + blockSize
            var k = pos
            while (k < end) {
                val v = ov[k]
                sumSq += v * v
                k++
            }
            chPowers.add(sumSq * invBlockSize)
            pos += stepSize
        }

        val remaining = totalLen - pos
        if (remaining > 0 && pos > 0) {
            ov.copyInto(ov, destinationOffset = 0, startIndex = pos, endIndex = totalLen)
        }
        fallbackOverlapLen[ch] = remaining
    }

    private fun aggregateOverallBlocks() {
        val oldBlocks = powers400ms.size
        val newBlocks = channelPowers400ms[0].size
        for (i in oldBlocks until newBlocks) {
            var weightedSum = 0.0
            for (ch in 0 until channelCount) {
                weightedSum += channelPowers400ms[ch].get(i) * weights[ch]
            }
            powers400ms.add(weightedSum)
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
        val minval = histogram[0].rangeStartDb
        val maxval = histogram.last().rangeStartDb
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
        // Primitive copy + in-place sort avoids boxing every Double into List<Double>
        val sorted = stValues.copyOf()
        sorted.sort()
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
}
