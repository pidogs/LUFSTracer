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
 * Input samples are assumed to be normalised to [-1.0, 1.0]
 * where 1.0 = 0 dB FS. The 12.04 dB attenuation step described
 * in the spec is skipped because we operate in floating-point.
 */
class TruePeakDetector(
    private val channelCount: Int
) {
    companion object {
        private const val PHASES = 4
        private const val TAPS_PER_PHASE = 12

        /**
         * Polyphase FIR coefficients from ITU-R BS.1770-5 Annex 2.
         * 48th-order, 4-phase interpolating filter for 4× oversampling.
         * Each sub-array has 12 taps (one per phase).
         *
         * Coefficient ordering: index 0 multiplies the newest sample,
         * index 11 multiplies the oldest sample in the delay line.
         */
        private val PHASE_COEFFS: Array<DoubleArray> = arrayOf(
            // Phase 0
            doubleArrayOf(
                0.0017089843750,
                0.0109863281250,
                -0.0196533203125,
                0.0332031250000,
                -0.0594482421875,
                0.1373291015625,
                0.9721679687500,
                -0.1022949218750,
                0.0476074218750,
                -0.0266113281250,
                0.0148925781250,
                -0.0083007812500
            ),
            // Phase 1
            doubleArrayOf(
                -0.0291748046875,
                0.0292968750000,
                -0.0517578125000,
                0.0891113281250,
                -0.1665039062500,
                0.4650878906250,
                0.7797851562500,
                -0.2003173828125,
                0.1015625000000,
                -0.0582275390625,
                0.0330810546875,
                -0.0189208984375
            ),
            // Phase 2
            doubleArrayOf(
                -0.0189208984375,
                0.0330810546875,
                -0.0582275390625,
                0.1015625000000,
                -0.2003173828125,
                0.7797851562500,
                0.4650878906250,
                -0.1665039062500,
                0.0891113281250,
                -0.0517578125000,
                0.0292968750000,
                -0.0291748046875
            ),
            // Phase 3
            doubleArrayOf(
                -0.0083007812500,
                0.0148925781250,
                -0.0266113281250,
                0.0476074218750,
                -0.1022949218750,
                0.9721679687500,
                0.1373291015625,
                -0.0594482421875,
                0.0332031250000,
                -0.0196533203125,
                0.0109863281250,
                0.0017089843750
            )
        )
    }

    private val delayLines = Array(channelCount) { DoubleArray(TAPS_PER_PHASE) }
    private val writePos = IntArray(channelCount) { 0 }
    private val truePeakLin = DoubleArray(channelCount) { 0.0 }
    private val samplePeakLin = DoubleArray(channelCount) { 0.0 }

    /** Processes a single channel safely across parallel threads */
    fun processChannel(ch: Int, samples: DoubleArray, numFrames: Int) {
        val ring = delayLines[ch]
        var pos = writePos[ch]
        var tpMax = truePeakLin[ch]
        var spMax = samplePeakLin[ch]

        for (s in 0 until numFrames) {
            val sample = samples[s]

            // Sample-peak tracking
            val absSample = abs(sample)
            if (absSample > spMax) spMax = absSample

            // Push into delay line
            ring[pos] = sample
            pos = (pos + 1) % TAPS_PER_PHASE

            // Polyphase FIR
            for (phase in 0 until PHASES) {
                val coeffs = PHASE_COEFFS[phase]
                var acc = 0.0
                for (t in 0 until TAPS_PER_PHASE) {
                    val idx = (pos + TAPS_PER_PHASE - 1 - t) % TAPS_PER_PHASE
                    acc += coeffs[t] * ring[idx]
                }
                val absInterp = abs(acc)
                if (absInterp > tpMax) tpMax = absInterp
            }
        }

        writePos[ch] = pos
        truePeakLin[ch] = tpMax
        samplePeakLin[ch] = spMax
    }

    fun getTruePeakDb(): DoubleArray = DoubleArray(channelCount) { ch ->
        if (truePeakLin[ch] > 0.0) 20.0 * log10(truePeakLin[ch]) else Double.NEGATIVE_INFINITY
    }

    fun getSamplePeakDb(): DoubleArray = DoubleArray(channelCount) { ch ->
        if (samplePeakLin[ch] > 0.0) 20.0 * log10(samplePeakLin[ch]) else Double.NEGATIVE_INFINITY
    }

    fun reset() {
        for (ch in 0 until channelCount) {
            delayLines[ch].fill(0.0)
            writePos[ch] = 0
            truePeakLin[ch] = 0.0
            samplePeakLin[ch] = 0.0
        }
    }
}