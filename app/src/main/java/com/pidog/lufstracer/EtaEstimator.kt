package com.pidog.lufstracer

import kotlin.math.exp

/**
 * Estimates remaining processing time using a long sliding window of exact
 * decoder progress timestamps, paired with:
 * 1. A solid post-startup warm-up period so early burst/jitter is ignored.
 * 2. A gentle early conservatism margin (+25% at 0% tapering to 0% at 85%)
 *    so estimates slightly overestimate early and creep downward rather than
 *    disappointing the user by creeping upward.
 * 3. Asymmetric exponential smoothing on the displayed finish timestamp so
 *    ETA never jumps erratically on noisy chunks.
 */
class EtaEstimator(
    private val windowMs: Long = 10_000L,
    private val minRecordIntervalMs: Long = 250L,
    private val warmupMs: Long = 1_500L,
    private val fastFileWarmupMs: Long = 500L,
    private val fastFileProgress: Double = 0.15,
    private val earlyOverestimateFactor: Double = 0.25,
    private val taperEndProgress: Double = 0.85,
    private val smoothDownTauMs: Double = 1_500.0,
    private val smoothUpTauMs: Double = 5_000.0
) {
    private val sampleTimes = ArrayDeque<Long>(64)
    private val sampleProgresses = ArrayDeque<Double>(64)

    private var lastRecordedTimeMs: Long = 0L
    private var lastRecordedProgress: Double = 0.0
    private var lastSmoothTimeMs: Long = 0L
    private var hasEstimate: Boolean = false
    private var displayedEtaEpochMs: Double = 0.0

    fun reset(nowMs: Long = 0L) {
        sampleTimes.clear()
        sampleProgresses.clear()
        lastRecordedTimeMs = 0L
        lastRecordedProgress = 0.0
        lastSmoothTimeMs = nowMs
        hasEstimate = false
        displayedEtaEpochMs = 0.0
    }

    /**
     * Updates the estimator using:
     * - [progress]: latest completed progress in `[0f, 1f]`
     * - [sampleTimeMs]: exact epoch ms when [progress] was emitted by the decoder
     * - [nowMs]: current UI clock epoch ms (used only for smooth countdown)
     *
     * Returns estimated remaining milliseconds, or `null` while warming up.
     */
    fun update(progress: Float, sampleTimeMs: Long, nowMs: Long = sampleTimeMs): Long? {
        val p = progress.toDouble().coerceIn(0.0, 1.0)
        if (p >= 1.0) {
            displayedEtaEpochMs = nowMs.toDouble()
            hasEstimate = true
            return 0L
        }

        if (p <= 0.0 || sampleTimeMs <= 0L) {
            return if (hasEstimate) (displayedEtaEpochMs.toLong() - nowMs).coerceAtLeast(0L) else null
        }

        // Record genuine decoder progress transitions using the decoder's exact timestamp
        // (eliminates aliasing between decoder chunk times and UI timer ticks).
        var recordedNewSample = false
        if (sampleTimes.isEmpty()) {
            sampleTimes.addLast(sampleTimeMs)
            sampleProgresses.addLast(p)
            lastRecordedTimeMs = sampleTimeMs
            lastRecordedProgress = p
            recordedNewSample = true
        } else if (p > lastRecordedProgress && sampleTimeMs - lastRecordedTimeMs >= minRecordIntervalMs) {
            sampleTimes.addLast(sampleTimeMs)
            sampleProgresses.addLast(p)
            lastRecordedTimeMs = sampleTimeMs
            lastRecordedProgress = p
            recordedNewSample = true

            // Keep ~windowMs of history (retain 1 sample just before the cutoff for full window span)
            val cutoff = sampleTimeMs - windowMs
            while (sampleTimes.size > 2 && sampleTimes[1] <= cutoff) {
                sampleTimes.removeFirst()
                sampleProgresses.removeFirst()
            }
        }

        if (sampleTimes.size < 2) {
            return null
        }

        val oldestTime = sampleTimes.first()
        val oldestProgress = sampleProgresses.first()
        val windowDuration = lastRecordedTimeMs - oldestTime
        val windowDeltaP = lastRecordedProgress - oldestProgress

        // Wait for a stable measurement window before displaying the first ETA
        if (!hasEstimate) {
            val warmedUp = windowDuration >= warmupMs ||
                (lastRecordedProgress >= fastFileProgress && windowDuration >= fastFileWarmupMs)
            if (!warmedUp || windowDeltaP <= 0.0) {
                return null
            }
        }

        if (recordedNewSample && windowDuration > 0L && windowDeltaP > 0.0) {
            val ratePerMs = windowDeltaP / windowDuration.toDouble()
            val rawRemainingMs = (1.0 - lastRecordedProgress) / ratePerMs
            val rawEtaEpochMs = lastRecordedTimeMs.toDouble() + rawRemainingMs

            // Conservative early multiplier: +25% at p=0 tapering smoothly to 0% by p=0.85
            val taperFraction = (1.0 - lastRecordedProgress / taperEndProgress).coerceIn(0.0, 1.0)
            val conservativeRemainingMs = rawRemainingMs * (1.0 + earlyOverestimateFactor * taperFraction)
            val conservativeEtaEpochMs = lastRecordedTimeMs.toDouble() + conservativeRemainingMs

            if (!hasEstimate) {
                displayedEtaEpochMs = conservativeEtaEpochMs
                lastSmoothTimeMs = sampleTimeMs
                hasEstimate = true
            } else {
                val dt = (sampleTimeMs - lastSmoothTimeMs).coerceAtLeast(1L).toDouble()
                when {
                    // Creeping down: smoothly glide toward the conservative estimate as it improves
                    conservativeEtaEpochMs < displayedEtaEpochMs -> {
                        val alpha = 1.0 - exp(-dt / smoothDownTauMs)
                        displayedEtaEpochMs += alpha * (conservativeEtaEpochMs - displayedEtaEpochMs)
                    }
                    // Only move ETA upward if the *unbiased* real ETA exceeds our displayed ETA;
                    // never let the artificial conservatism buffer push ETA upward.
                    rawEtaEpochMs > displayedEtaEpochMs -> {
                        val alpha = 1.0 - exp(-dt / smoothUpTauMs)
                        displayedEtaEpochMs += alpha * (rawEtaEpochMs - displayedEtaEpochMs)
                    }
                    // Otherwise displayedEta is already safely in [rawEta, conservativeEta]: hold steady!
                }
                lastSmoothTimeMs = sampleTimeMs
            }
        }

        if (!hasEstimate) return null
        return (displayedEtaEpochMs.toLong() - nowMs).coerceAtLeast(0L)
    }

    fun getEtaEpochMs(): Long = displayedEtaEpochMs.toLong()
    fun isReady(): Boolean = hasEstimate
}
