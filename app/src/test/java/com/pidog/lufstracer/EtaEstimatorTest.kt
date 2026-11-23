package com.pidog.lufstracer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EtaEstimatorTest {

    @Test
    fun constantSpeedOverestimatesEarlyThenCreepsDownSmoothly() {
        val t0 = 1_000_000L
        val totalMs = 60_000L // 60s file
        val trueFinish = t0 + totalMs
        val est = EtaEstimator()
        est.reset(t0)

        // Warmup (< 1.5s of measured decoding) returns null
        assertNull(est.update(0.002f, t0 + 100L, t0 + 100L))
        assertNull(est.update(0.015f, t0 + 900L, t0 + 900L))
        assertFalse(est.isReady())

        var prevEta = Long.MAX_VALUE
        var firstEta = 0L
        for (elapsed in 100L..59_500L step 100L) {
            val p = elapsed.toFloat() / totalMs.toFloat()
            val rem = est.update(p, t0 + elapsed, t0 + elapsed)
            if (rem != null) {
                val eta = est.getEtaEpochMs()
                if (firstEta == 0L) {
                    firstEta = eta
                    // First estimate should conservatively overestimate (by ~20-25%)
                    assertTrue(
                        "Expected early overestimate > trueFinish + 8s, got +${(firstEta - trueFinish) / 1000}s",
                        firstEta > trueFinish + 8_000L
                    )
                } else {
                    // Under steady speed, ETA should never jump upward—only creep downward smoothly
                    assertTrue(
                        "ETA jumped up at elapsed=$elapsed: prev=$prevEta now=$eta",
                        eta <= prevEta + 50L
                    )
                }
                prevEta = eta
            }
        }

        // Near the end (> 85% progress), estimate converges closely onto the real finish time
        assertTrue(
            "Final ETA should converge near trueFinish, error=${abs(prevEta - trueFinish)}ms",
            abs(prevEta - trueFinish) < 1_000L
        )
    }

    @Test
    fun noisyChunkJitterDoesNotCauseWildEtaJumps() {
        val t0 = 2_000_000L
        val totalMs = 60_000L
        val est = EtaEstimator()
        est.reset(t0)

        // Simulate realistic noisy progress where chunk completions alternate fast/slow
        // AND UI timer polls on an unsynchronized 100ms grid
        var lastDecoderTime = t0
        var lastProgress = 0.0
        var prevEta = 0L
        var maxSingleStepJumpMs = 0L

        for (t in 100L..55_000L step 100L) {
            // Decoder finishes chunks at irregular intervals (every 200ms or 400ms with +/-40% jitter)
            val jitterPhase = ((t / 100L) % 5).toInt()
            if (jitterPhase == 0 || jitterPhase == 2) {
                val decoderNow = t0 + t - (if (jitterPhase == 0) 35L else 12L)
                val jitterFactor = if (jitterPhase == 0) 1.35 else 0.70
                val deltaT = decoderNow - lastDecoderTime
                lastProgress = (lastProgress + (deltaT.toDouble() / totalMs) * jitterFactor).coerceAtMost(0.995)
                lastDecoderTime = decoderNow
            }

            val rem = est.update(lastProgress.toFloat(), lastDecoderTime, t0 + t)
            if (rem != null) {
                val eta = est.getEtaEpochMs()
                if (prevEta != 0L) {
                    val jump = abs(eta - prevEta)
                    if (jump > maxSingleStepJumpMs) maxSingleStepJumpMs = jump
                }
                prevEta = eta
            }
        }

        assertTrue(
            "Max single-tick ETA jump under heavy jitter should stay calm (< 2000ms), was ${maxSingleStepJumpMs}ms",
            maxSingleStepJumpMs < 2_000L
        )
    }

    @Test
    fun earlyConservatismAbsorbsModerateThermalThrottleWithoutClimbing() {
        val t0 = 5_000_000L
        val est = EtaEstimator()
        est.reset(t0)

        // First 6s: 20% faster turbo burst (rate = 1.2 / 60s = 2%/s -> 12% done)
        var progress = 0.0
        for (t in 100L..6_000L step 100L) {
            progress += 0.002
            est.update(progress.toFloat(), t0 + t, t0 + t)
        }
        val etaAfterWarmup = est.getEtaEpochMs()

        // Remaining 88% at sustained speed (rate = 1.67%/s -> takes ~52.8s -> true finish ~58.8s)
        var maxEta = etaAfterWarmup
        for (t in 6_100L..58_000L step 100L) {
            progress = (progress + 0.0016667).coerceAtMost(0.999)
            est.update(progress.toFloat(), t0 + t, t0 + t)
            val eta = est.getEtaEpochMs()
            if (eta > maxEta) maxEta = eta
        }

        // Because the early +25% margin absorbed the 20% throttle, ETA should barely ever
        // exceed the initial promise (within ~1.5s) instead of climbing steadily for 50s.
        assertTrue(
            "ETA climbed too much above initial estimate: initial=${etaAfterWarmup - t0}ms max=${maxEta - t0}ms",
            maxEta - etaAfterWarmup < 2_000L
        )
    }

    @Test
    fun staleProgressCountsDownSmoothlyBetweenDecoderUpdates() {
        val t0 = 3_000_000L
        val est = EtaEstimator()
        est.reset(t0)

        // Warm up over 2.5s at steady 1%/sec
        for (t in 100L..2_500L step 100L) {
            est.update(t / 100_000f, t0 + t, t0 + t)
        }
        assertTrue(est.isReady())
        val etaBeforePause = est.getEtaEpochMs()
        val remAt2500 = est.update(0.025f, t0 + 2_500L, t0 + 2_500L)!!

        // Decoder hasn't emitted a new chunk for 400ms; UI timer ticks 4 times
        val remAt2600 = est.update(0.025f, t0 + 2_500L, t0 + 2_600L)!!
        val remAt2700 = est.update(0.025f, t0 + 2_500L, t0 + 2_700L)!!
        val remAt2800 = est.update(0.025f, t0 + 2_500L, t0 + 2_800L)!!

        assertEquals(etaBeforePause, est.getEtaEpochMs())
        assertEquals(remAt2500 - 100L, remAt2600)
        assertEquals(remAt2500 - 200L, remAt2700)
        assertEquals(remAt2500 - 300L, remAt2800)
    }

    @Test
    fun ignoresStartupDelayAndHandlesFastFilesAndCompletion() {
        val t0 = 4_000_000L
        val est = EtaEstimator()
        est.reset(t0)

        // 1.0s codec startup with progress == 0
        for (t in 100L..1_000L step 100L) {
            assertNull(est.update(0f, 0L, t0 + t))
        }

        // Fast file hits 20% in 600ms -> warms up early without waiting 1.5s
        est.update(0.03f, t0 + 1_050L, t0 + 1_100L)
        val rem = est.update(0.20f, t0 + 1_650L, t0 + 1_700L)
        assertNotNull("Fast file should warm up once progress >= 15% and window >= 500ms", rem)
        assertTrue(rem!! > 0L)

        // Completion pins to 0 remaining
        assertEquals(0L, est.update(1.0f, t0 + 3_500L, t0 + 3_500L))
        assertEquals(t0 + 3_500L, est.getEtaEpochMs())
    }
}
