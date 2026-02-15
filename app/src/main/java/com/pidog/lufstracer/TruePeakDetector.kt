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

    /** Circular buffer per channel holding the last 12 input samples. */
    private val delayLines =
        Array(channelCount) { DoubleArray(TAPS_PER_PHASE) }

    /**
     * Write cursor into each delay line. After a write-and-advance,
     * this points at the *oldest* sample in the ring.
     */
    private val writePos = IntArray(channelCount) { 0 }

    /** Running true-peak (linear) per channel. */
    private val truePeakLin = DoubleArray(channelCount) { 0.0 }

    /** Running sample-peak (linear) per channel. */
    private val samplePeakLin = DoubleArray(channelCount) { 0.0 }

    /**
     * Feed a block of interleaved-by-channel audio into the detector.
     *
     * @param channels  One [DoubleArray] per channel, each holding the
     *                  same number of samples normalised to ±1.0.
     */
    fun processBlock(channels: Array<DoubleArray>) {
        require(channels.size == channelCount) {
            "Expected $channelCount channels, got ${channels.size}"
        }

        for (ch in 0 until channelCount) {
            val samples = channels[ch]
            val ring = delayLines[ch]
            var pos = writePos[ch]
            var tpMax = truePeakLin[ch]
            var spMax = samplePeakLin[ch]

            for (s in samples.indices) {
                val sample = samples[s]

                // --- sample-peak tracking (pre-oversampling) ---
                val absSample = abs(sample)
                if (absSample > spMax) spMax = absSample

                // --- push sample into delay line ---
                ring[pos] = sample
                pos = (pos + 1) % TAPS_PER_PHASE
                // `pos` now points at the oldest sample

                // --- polyphase FIR: compute 4 interpolated values ---
                for (phase in 0 until PHASES) {
                    val coeffs = PHASE_COEFFS[phase]
                    var acc = 0.0
                    // coeffs[0] pairs with newest sample,
                    // coeffs[TAPS-1] pairs with oldest sample.
                    // newest = ring[(pos - 1 + T) % T],
                    //    i.e. ring[(pos + T - 1 - k) % T] for tap k.
                    for (t in 0 until TAPS_PER_PHASE) {
                        val idx =
                            (pos + TAPS_PER_PHASE - 1 - t) % TAPS_PER_PHASE
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
    }

    /**
     * True-peak level per channel in **dB TP**
     * (decibels relative to full scale, true-peak measurement).
     *
     * Returns [Double.NEGATIVE_INFINITY] for silent channels.
     */
    fun getTruePeakDb(): DoubleArray =
        DoubleArray(channelCount) { ch ->
            if (truePeakLin[ch] > 0.0) 20.0 * log10(truePeakLin[ch])
            else Double.NEGATIVE_INFINITY
        }

    /**
     * Sample-peak level per channel in **dB FS**.
     */
    fun getSamplePeakDb(): DoubleArray =
        DoubleArray(channelCount) { ch ->
            if (samplePeakLin[ch] > 0.0) 20.0 * log10(samplePeakLin[ch])
            else Double.NEGATIVE_INFINITY
        }

    /** True-peak as a linear ratio (1.0 = 0 dB TP). */
    fun getTruePeakLinear(): DoubleArray = truePeakLin.copyOf()

    /** Sample-peak as a linear ratio (1.0 = 0 dB FS). */
    fun getSamplePeakLinear(): DoubleArray = samplePeakLin.copyOf()

    /** Reset all internal state and peak accumulators. */
    fun reset() {
        for (ch in 0 until channelCount) {
            delayLines[ch].fill(0.0)
            writePos[ch] = 0
            truePeakLin[ch] = 0.0
            samplePeakLin[ch] = 0.0
        }
    }
}