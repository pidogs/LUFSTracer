package com.pidog.lufstracer

import kotlin.math.abs
import kotlin.math.log10

/**
 * True-peak detector per ITU-R BS.1770-5, Annex 2.
 *
 * Uses the 48th-order, 4-phase polyphase FIR interpolating filter
 * specified in the standard to 4× oversample each channel, then
 * tracks the maximum absolute value across all oversampled outputs.
 *
 * Performance optimizations (mathematically exact):
 * 1. Exact L1-norm bounding: max phase L1 norm is 2.0228271484375.
 *    Any 12-sample window whose samples all satisfy |x| <= tpMax * 0.494
 *    cannot exceed tpMax in any phase and skips the FIR completely.
 * 2. Contiguous 11-sample tail history + fully unrolled 4-phase 12-tap FIR:
 *    eliminates all `% 12` integer modulos and circular-buffer indirection.
 */
class TruePeakDetector(
    private val channelCount: Int
) {
    companion object {
        private const val TAPS_PER_PHASE = 12
        private const val HISTORY_LEN = TAPS_PER_PHASE - 1 // 11

        // Exact max L1 norm across all 4 phases is 2.0228271484375 (1 / L1 = 0.49435761...).
        // Using 0.494 gives a conservative safety margin against FP rounding.
        private const val SAFE_INV_L1 = 0.494

        // Phase 0 coefficients (tap 0 = newest sample .. tap 11 = oldest sample)
        private const val P0_0 = 0.0017089843750
        private const val P0_1 = 0.0109863281250
        private const val P0_2 = -0.0196533203125
        private const val P0_3 = 0.0332031250000
        private const val P0_4 = -0.0594482421875
        private const val P0_5 = 0.1373291015625
        private const val P0_6 = 0.9721679687500
        private const val P0_7 = -0.1022949218750
        private const val P0_8 = 0.0476074218750
        private const val P0_9 = -0.0266113281250
        private const val P0_10 = 0.0148925781250
        private const val P0_11 = -0.0083007812500

        // Phase 1 coefficients
        private const val P1_0 = -0.0291748046875
        private const val P1_1 = 0.0292968750000
        private const val P1_2 = -0.0517578125000
        private const val P1_3 = 0.0891113281250
        private const val P1_4 = -0.1665039062500
        private const val P1_5 = 0.4650878906250
        private const val P1_6 = 0.7797851562500
        private const val P1_7 = -0.2003173828125
        private const val P1_8 = 0.1015625000000
        private const val P1_9 = -0.0582275390625
        private const val P1_10 = 0.0330810546875
        private const val P1_11 = -0.0189208984375

        // Phase 2 coefficients (reverse of Phase 1)
        private const val P2_0 = P1_11
        private const val P2_1 = P1_10
        private const val P2_2 = P1_9
        private const val P2_3 = P1_8
        private const val P2_4 = P1_7
        private const val P2_5 = P1_6
        private const val P2_6 = P1_5
        private const val P2_7 = P1_4
        private const val P2_8 = P1_3
        private const val P2_9 = P1_2
        private const val P2_10 = P1_1
        private const val P2_11 = P1_0

        // Phase 3 coefficients (reverse of Phase 0)
        private const val P3_0 = P0_11
        private const val P3_1 = P0_10
        private const val P3_2 = P0_9
        private const val P3_3 = P0_8
        private const val P3_4 = P0_7
        private const val P3_5 = P0_6
        private const val P3_6 = P0_5
        private const val P3_7 = P0_4
        private const val P3_8 = P0_3
        private const val P3_9 = P0_2
        private const val P3_10 = P0_1
        private const val P3_11 = P0_0
    }

    // Last 11 samples per channel: index 0 = oldest, index 10 = most recent
    private val tail = Array(channelCount) { DoubleArray(HISTORY_LEN) }
    // Scratch buffer per channel for stitching tail[0..10] + head[0..10]
    private val boundaryScratch = Array(channelCount) { DoubleArray(HISTORY_LEN * 2) }
    private val hotCountdown = IntArray(channelCount) { 0 }
    private val truePeakLin = DoubleArray(channelCount) { 0.0 }
    private val samplePeakLin = DoubleArray(channelCount) { 0.0 }

    /** Processes a single channel safely across parallel threads */
    fun processChannel(ch: Int, samples: DoubleArray, numFrames: Int) {
        if (numFrames <= 0) return

        val hist = tail[ch]
        val sc = boundaryScratch[ch]
        var hot = hotCountdown[ch]
        var tpMax = truePeakLin[ch]
        var spMax = samplePeakLin[ch]
        var thresh = tpMax * SAFE_INV_L1

        val headLen = if (numFrames < HISTORY_LEN) numFrames else HISTORY_LEN

        // Copy tail (11) + head (up to 11) into contiguous scratch
        hist.copyInto(sc, destinationOffset = 0, startIndex = 0, endIndex = HISTORY_LEN)
        samples.copyInto(sc, destinationOffset = HISTORY_LEN, startIndex = 0, endIndex = headLen)

        // 1. Boundary head: first up to 11 samples using stitched scratch
        var s = 0
        while (s < headLen) {
            val p = HISTORY_LEN + s
            val sample = sc[p]
            val absSample = abs(sample)
            if (absSample > spMax) spMax = absSample
            if (absSample > thresh) hot = TAPS_PER_PHASE

            if (hot > 0) {
                hot--
                val peak = evaluate4Phases(sc, p)
                if (peak > tpMax) {
                    tpMax = peak
                    thresh = tpMax * SAFE_INV_L1
                }
            }
            s++
        }

        // 2. Main contiguous body: direct indexing into samples[s..s-11] (zero modulo, zero copy)
        s = HISTORY_LEN
        while (s < numFrames) {
            val sample = samples[s]
            val absSample = abs(sample)
            if (absSample > spMax) spMax = absSample
            if (absSample > thresh) hot = TAPS_PER_PHASE

            if (hot > 0) {
                hot--
                val peak = evaluate4Phases(samples, s)
                if (peak > tpMax) {
                    tpMax = peak
                    thresh = tpMax * SAFE_INV_L1
                }
            }
            s++
        }

        // 3. Save last 11 samples for the next chunk
        if (numFrames >= HISTORY_LEN) {
            samples.copyInto(hist, destinationOffset = 0, startIndex = numFrames - HISTORY_LEN, endIndex = numFrames)
        } else {
            sc.copyInto(hist, destinationOffset = 0, startIndex = numFrames, endIndex = numFrames + HISTORY_LEN)
        }

        hotCountdown[ch] = hot
        truePeakLin[ch] = tpMax
        samplePeakLin[ch] = spMax
    }

    private fun evaluate4Phases(buf: DoubleArray, p: Int): Double {
        val x0 = buf[p]
        val x1 = buf[p - 1]
        val x2 = buf[p - 2]
        val x3 = buf[p - 3]
        val x4 = buf[p - 4]
        val x5 = buf[p - 5]
        val x6 = buf[p - 6]
        val x7 = buf[p - 7]
        val x8 = buf[p - 8]
        val x9 = buf[p - 9]
        val x10 = buf[p - 10]
        val x11 = buf[p - 11]

        val a0 = abs(
            P0_0 * x0 + P0_1 * x1 + P0_2 * x2 + P0_3 * x3 +
                P0_4 * x4 + P0_5 * x5 + P0_6 * x6 + P0_7 * x7 +
                P0_8 * x8 + P0_9 * x9 + P0_10 * x10 + P0_11 * x11
        )
        val a1 = abs(
            P1_0 * x0 + P1_1 * x1 + P1_2 * x2 + P1_3 * x3 +
                P1_4 * x4 + P1_5 * x5 + P1_6 * x6 + P1_7 * x7 +
                P1_8 * x8 + P1_9 * x9 + P1_10 * x10 + P1_11 * x11
        )
        val a2 = abs(
            P2_0 * x0 + P2_1 * x1 + P2_2 * x2 + P2_3 * x3 +
                P2_4 * x4 + P2_5 * x5 + P2_6 * x6 + P2_7 * x7 +
                P2_8 * x8 + P2_9 * x9 + P2_10 * x10 + P2_11 * x11
        )
        val a3 = abs(
            P3_0 * x0 + P3_1 * x1 + P3_2 * x2 + P3_3 * x3 +
                P3_4 * x4 + P3_5 * x5 + P3_6 * x6 + P3_7 * x7 +
                P3_8 * x8 + P3_9 * x9 + P3_10 * x10 + P3_11 * x11
        )

        var maxVal = if (a0 > a1) a0 else a1
        if (a2 > maxVal) maxVal = a2
        if (a3 > maxVal) maxVal = a3
        return maxVal
    }

    fun getTruePeakDb(): DoubleArray = DoubleArray(channelCount) { ch ->
        if (truePeakLin[ch] > 0.0) 20.0 * log10(truePeakLin[ch]) else Double.NEGATIVE_INFINITY
    }

    fun getSamplePeakDb(): DoubleArray = DoubleArray(channelCount) { ch ->
        if (samplePeakLin[ch] > 0.0) 20.0 * log10(samplePeakLin[ch]) else Double.NEGATIVE_INFINITY
    }

    fun reset() {
        for (ch in 0 until channelCount) {
            tail[ch].fill(0.0)
            boundaryScratch[ch].fill(0.0)
            hotCountdown[ch] = 0
            truePeakLin[ch] = 0.0
            samplePeakLin[ch] = 0.0
        }
    }
}
