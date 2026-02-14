package com.pidog.lufstracer

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.*

class LufsAnalyzer(private val sampleRate: Int, private val channelCount: Int) {

    private val filter1: ManualBiquad
    private val filter2: ManualBiquad
    private val weights: DoubleArray
    private val blockSize = (0.4 * sampleRate).toInt()
    private val stepSize = (0.1 * sampleRate).toInt()
    private val powers400ms = mutableListOf<Double>()
    private var maxTp = 0.0
    private var maxSp = 0.0
    private var overlapBuffer: Array<DoubleArray> = Array(channelCount) { DoubleArray(0) }
    private var leftoverSamples = FloatArray(0)

    init {
        val (coeffs1, coeffs2) = getKWeightingCoeffs(sampleRate)
        filter1 = ManualBiquad(coeffs1.first, coeffs1.second, channelCount)
        filter2 = ManualBiquad(coeffs2.first, coeffs2.second, channelCount)

        weights = DoubleArray(channelCount) { 1.0 }
        if (channelCount >= 6) {
            weights[4] = 1.41
            weights[5] = 1.41
        }
    }

    fun processSamples(interleavedSamples: FloatArray) {
        val combined = if (leftoverSamples.isNotEmpty()) {
            FloatArray(leftoverSamples.size + interleavedSamples.size).also {
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

        val channels = Array(channelCount) { DoubleArray(completeFrames) }
        for (frame in 0 until completeFrames) {
            val base = frame * channelCount
            for (ch in 0 until channelCount) {
                val s = combined[base + ch].toDouble()
                channels[ch][frame] = s
                if (abs(s) > maxSp) maxSp = abs(s)
            }
        }

        // True Peak using JTransforms
        for (ch in 0 until channelCount) {
            val tp = computeTruePeakJTransforms(channels[ch])
            if (tp > maxTp) maxTp = tp
        }

        val filtered = filter2.process(filter1.process(channels))
        val combinedFiltered = Array(channelCount) { ch ->
            val prev = overlapBuffer[ch]
            DoubleArray(prev.size + filtered[ch].size).also {
                prev.copyInto(it)
                filtered[ch].copyInto(it, prev.size)
            }
        }

        val totalLen = combinedFiltered[0].size
        var pos = 0
        while (pos + blockSize <= totalLen) {
            var zIj = 0.0
            for (ch in 0 until channelCount) {
                var sumSq = 0.0
                for (i in pos until pos + blockSize) {
                    val v = combinedFiltered[ch][i]
                    sumSq += v * v
                }
                zIj += (sumSq / blockSize) * weights[ch]
            }
            powers400ms.add(zIj)
            pos += stepSize
        }

        overlapBuffer = Array(channelCount) { ch ->
            combinedFiltered[ch].copyOfRange(pos, totalLen)
        }
    }

    private fun computeTruePeakJTransforms(data: DoubleArray, oversample: Int = 4): Double {
        val n = data.size
        if (n == 0) return 0.0

        val fftSize = nextPowerOf2(n)
        val fft = DoubleFFT_1D(fftSize.toLong())

        // Forward FFT
        val realBuffer = DoubleArray(fftSize)
        data.copyInto(realBuffer, 0, 0, n)
        fft.realForward(realBuffer)

        // Create padded buffer for oversampled size
        val newSize = fftSize * oversample
        val padded = DoubleArray(newSize)

        // Pack into JTransforms format for the new size
        // Format: [DC, Nyquist_new, Re1, Im1, Re2, Im2, ...]
        padded[0] = realBuffer[0]                    // DC
        padded[1] = 0.0                              // New Nyquist (zero)

        val halfOld = fftSize / 2
        for (i in 1 until halfOld) {
            padded[2 * i] = realBuffer[2 * i]        // Re
            padded[2 * i + 1] = realBuffer[2 * i + 1] // Im
        }
        // Original Nyquist goes at position 2*halfOld (as Re, Im=0)
        padded[2 * halfOld] = realBuffer[1]
        padded[2 * halfOld + 1] = 0.0
        // Rest stays zero (padding)

        // Inverse FFT
        val iFft = DoubleFFT_1D(newSize.toLong())
        iFft.realInverse(padded, true)

        // Find peak
        return (0 until newSize).maxOf { abs(padded[it]) } * oversample
    }

    private fun nextPowerOf2(n: Int): Int {
        var v = n
        if (v <= 0) return 1
        v--
        v = v or (v shr 1)
        v = v or (v shr 2)
        v = v or (v shr 4)
        v = v or (v shr 8)
        v = v or (v shr 16)
        return v + 1
    }

    fun calculateMetrics(): LufsMetrics {
        val powers = powers400ms.toDoubleArray()
        if (powers.isEmpty()) return LufsMetrics(-70.0, -70.0, -70.0, -70.0, 0.0, 0.0, 0.0, -70.0, -70.0, leftRightShift = 0.0)

        val lM = DoubleArray(powers.size) { -0.691 + 10.0 * log10(powers[it] + 1e-12) }
        val absGateIndices = lM.indices.filter { lM[it] > -70.0 }

        var integrated = -70.0
        var relThreshold = -70.0

        if (absGateIndices.isNotEmpty()) {
            val zAvgAbs = absGateIndices.sumOf { powers[it] } / absGateIndices.size
            relThreshold = -0.691 + 10.0 * log10(zAvgAbs + 1e-12) - 10.0
            val finalIndices = lM.indices.filter { lM[it] > relThreshold && lM[it] > -70.0 }
            if (finalIndices.isNotEmpty()) {
                integrated = -0.691 + 10.0 * log10(finalIndices.sumOf { powers[it] } / finalIndices.size + 1e-12)
            }
        }

        val stWindow = 30
        var shortTerm = integrated
        var lra = 0.0
        if (powers.size >= stWindow) {
            val stCount = powers.size - stWindow + 1
            val lSt = DoubleArray(stCount)
            var sum = 0.0
            for (i in 0 until stWindow) sum += powers[i]
            lSt[0] = -0.691 + 10.0 * log10(sum / stWindow + 1e-12)
            for (i in 1 until stCount) {
                sum += powers[i + stWindow - 1] - powers[i - 1]
                lSt[i] = -0.691 + 10.0 * log10(sum / stWindow + 1e-12)
            }
            shortTerm = lSt.maxOrNull() ?: -70.0
            val sorted = lSt.sorted()
            lra = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)] - sorted[(sorted.size * 0.10).toInt()]
        }

        val tpDb = 20.0 * log10(maxTp + 1e-12)
        return LufsMetrics(
            integrated = integrated,
            truePeak = tpDb,
            shortTerm = shortTerm,
            momentary = lM.maxOrNull() ?: -70.0,
            dynamicRange = lra,
            plr = tpDb - integrated,
            psr = tpDb - shortTerm,
            relativeThreshold = relThreshold,
            samplePeak = 20.0 * log10(maxSp + 1e-12),
            leftRightShift = -70.0

        )
    }

    private class ManualBiquad(val b: DoubleArray, val a: DoubleArray, numChannels: Int) {
        private val state = Array(numChannels) { DoubleArray(4) }
        fun process(input: Array<DoubleArray>): Array<DoubleArray> {
            val output = Array(input.size) { DoubleArray(input[0].size) }
            for (ch in input.indices) {
                var (x1, x2, y1, y2) = state[ch]
                for (n in input[ch].indices) {
                    val xn = input[ch][n]
                    val yn = b[0] * xn + b[1] * x1 + b[2] * x2 - a[1] * y1 - a[2] * y2
                    output[ch][n] = yn
                    x2 = x1; x1 = xn; y2 = y1; y1 = yn
                }
                state[ch][0] = x1; state[ch][1] = x2; state[ch][2] = y1; state[ch][3] = y2
            }
            return output
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