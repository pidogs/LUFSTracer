package com.pidog.lufstracer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class TruePeakDetectorTest {

    companion object {
        private const val GOLDEN_DELTA = 1e-9
        private const val EXACT_DELTA = 1e-12
    }

    @Test
    fun silenceReturnsNegativeInfinity() {
        val detector = TruePeakDetector(channelCount = 2)
        val zeros = DoubleArray(4096)
        detector.processChannel(0, zeros, zeros.size)
        detector.processChannel(1, zeros, zeros.size)

        val tp = detector.getTruePeakDb()
        val sp = detector.getSamplePeakDb()
        assertEquals(2, tp.size)
        assertEquals(2, sp.size)
        assertEquals(Double.NEGATIVE_INFINITY, tp[0], 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, tp[1], 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, sp[0], 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, sp[1], 0.0)
    }

    @Test
    fun goldenImpulseResponse() {
        val detector = TruePeakDetector(channelCount = 1)
        val impulse = DoubleArray(64)
        impulse[10] = 1.0
        detector.processChannel(0, impulse, impulse.size)

        val tp = detector.getTruePeakDb()[0]
        val sp = detector.getSamplePeakDb()[0]
        assertEquals(0.0, sp, EXACT_DELTA)
        // Peak polyphase FIR tap is 0.97216796875 -> 20 * log10(0.97216796875)
        assertEquals(-0.24517384552767194, tp, GOLDEN_DELTA)
    }

    @Test
    fun goldenFullScale1kHzSineAt48kHz() {
        val sampleRate = 48_000
        val frames = 4_800
        val signal = DoubleArray(frames) { n ->
            sin(2.0 * PI * 1000.0 * n / sampleRate)
        }

        val detector = TruePeakDetector(channelCount = 1)
        detector.processChannel(0, signal, frames)

        val tp = detector.getTruePeakDb()[0]
        val sp = detector.getSamplePeakDb()[0]
        assertEquals(0.0, sp, GOLDEN_DELTA)
        assertEquals(0.00669594688785257, tp, GOLDEN_DELTA)
    }

    @Test
    fun goldenInterSamplePeakExceedsSamplePeak() {
        // fs/4 alternating pattern [+1/sqrt(2), +1/sqrt(2), -1/sqrt(2), -1/sqrt(2)]
        // Sample peak is ~-3.01 dBFS, while interpolated true peak exceeds 0 dBTP.
        val frames = 1024
        val invSqrt2 = 1.0 / sqrt(2.0)
        val pattern = doubleArrayOf(invSqrt2, invSqrt2, -invSqrt2, -invSqrt2)
        val signal = DoubleArray(frames) { i -> pattern[i % 4] }

        val detector = TruePeakDetector(channelCount = 1)
        detector.processChannel(0, signal, frames)

        val tp = detector.getTruePeakDb()[0]
        val sp = detector.getSamplePeakDb()[0]
        assertTrue("True peak ($tp) should exceed sample peak ($sp) by > 3 dB", tp > sp + 3.0)
        assertEquals(0.08264836300078147, tp, GOLDEN_DELTA)
        assertEquals(-3.0102999566398125, sp, GOLDEN_DELTA)
    }

    @Test
    fun chunkingInvarianceAcrossTinyAndOddSizes() {
        val sampleRate = 48_000
        val frames = 5_000
        val signal = DoubleArray(frames) { n ->
            0.5 * sin(2.0 * PI * 997.0 * n / sampleRate) +
                0.4 * sin(2.0 * PI * 7919.0 * n / sampleRate + 0.7)
        }

        val whole = TruePeakDetector(channelCount = 1)
        whole.processChannel(0, signal, frames)

        val chunked = TruePeakDetector(channelCount = 1)
        val chunkSizes = intArrayOf(1, 2, 3, 5, 7, 11, 12, 13, 17, 29, 64, 127)
        var offset = 0
        var cIdx = 0
        while (offset < frames) {
            val len = minOf(chunkSizes[cIdx % chunkSizes.size], frames - offset)
            val slice = signal.copyOfRange(offset, offset + len)
            chunked.processChannel(0, slice, len)
            offset += len
            cIdx++
        }

        assertEquals(whole.getTruePeakDb()[0], chunked.getTruePeakDb()[0], EXACT_DELTA)
        assertEquals(whole.getSamplePeakDb()[0], chunked.getSamplePeakDb()[0], EXACT_DELTA)
    }

    @Test
    fun multiChannelIndependence() {
        val frames = 2048
        val ch0 = DoubleArray(frames) { n -> 0.5 * sin(2.0 * PI * 1000.0 * n / 48_000.0) }
        val ch1 = DoubleArray(frames) { n -> 0.125 * sin(2.0 * PI * 3000.0 * n / 48_000.0) }
        val ch2 = DoubleArray(frames) { 0.0 }

        val multi = TruePeakDetector(channelCount = 3)
        multi.processChannel(0, ch0, frames)
        multi.processChannel(1, ch1, frames)
        multi.processChannel(2, ch2, frames)

        val single0 = TruePeakDetector(1).apply { processChannel(0, ch0, frames) }
        val single1 = TruePeakDetector(1).apply { processChannel(0, ch1, frames) }

        assertEquals(single0.getTruePeakDb()[0], multi.getTruePeakDb()[0], EXACT_DELTA)
        assertEquals(single1.getTruePeakDb()[0], multi.getTruePeakDb()[1], EXACT_DELTA)
        assertEquals(Double.NEGATIVE_INFINITY, multi.getTruePeakDb()[2], 0.0)

        assertEquals(single0.getSamplePeakDb()[0], multi.getSamplePeakDb()[0], EXACT_DELTA)
        assertEquals(single1.getSamplePeakDb()[0], multi.getSamplePeakDb()[1], EXACT_DELTA)
        assertEquals(Double.NEGATIVE_INFINITY, multi.getSamplePeakDb()[2], 0.0)
    }

    @Test
    fun resetRestoresInitialState() {
        val frames = 1024
        val loud = DoubleArray(frames) { n -> 0.9 * sin(2.0 * PI * 1000.0 * n / 48_000.0) }
        val quiet = DoubleArray(frames) { n -> 0.1 * sin(2.0 * PI * 1000.0 * n / 48_000.0) }

        val detector = TruePeakDetector(channelCount = 1)
        detector.processChannel(0, loud, frames)
        detector.reset()
        detector.processChannel(0, quiet, frames)

        val fresh = TruePeakDetector(channelCount = 1)
        fresh.processChannel(0, quiet, frames)

        assertEquals(fresh.getTruePeakDb()[0], detector.getTruePeakDb()[0], EXACT_DELTA)
        assertEquals(fresh.getSamplePeakDb()[0], detector.getSamplePeakDb()[0], EXACT_DELTA)
    }
}
