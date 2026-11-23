package com.pidog.lufstracer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

/**
 * Golden-value regression and invariance tests for [LufsAnalyzer].
 *
 * These tests lock down the exact current loudness, true-peak, gating,
 * channel-weighting, histogram, and chunking behavior so future refactors
 * or optimizations do not alter calculation results.
 */
class LufsAnalyzerTest {

    companion object {
        private const val GOLDEN_DELTA = 1e-9
        private const val EXACT_DELTA = 1e-12
    }

    /**
     * Explicit Xorshift32 generator so test waveforms never depend on
     * JVM/stdlib PRNG implementation changes.
     */
    private class DeterministicNoise(private var state: Int) {
        fun nextFloat(): Float {
            var x = state
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)
            state = x
            return (x.toDouble() / Int.MAX_VALUE.toDouble()).toFloat().coerceIn(-1f, 1f)
        }
    }

    private fun generateMonoSine(
        sampleRate: Int,
        durationSeconds: Double,
        freqHz: Double,
        amplitude: Float
    ): FloatArray {
        val frames = (sampleRate * durationSeconds).toInt()
        return FloatArray(frames) { n ->
            (amplitude * sin(2.0 * PI * freqHz * n / sampleRate)).toFloat()
        }
    }

    private fun generateStereoDynamicSignal(sampleRate: Int): FloatArray {
        // 8 seconds stereo with 4 distinct 2-second sections:
        // [0..2s] quiet (-36 dB) 100 Hz + 2000 Hz
        // [2..4s] loud (-6 dB) 440 Hz + 3000 Hz (Right +3 dB hotter than Left)
        // [4..6s] ultra-quiet (-86 dB, below -70 LUFS absolute gate)
        // [6..8s] medium (-16 dB) broadband deterministic noise + 800 Hz (Right -2 dB vs Left)
        val totalFrames = sampleRate * 8
        val segFrames = sampleRate * 2
        val noiseL = DeterministicNoise(0x13579BDF)
        val noiseR = DeterministicNoise(0x2468ACE1)
        val out = FloatArray(totalFrames * 2)

        for (n in 0 until totalFrames) {
            val t = n.toDouble() / sampleRate
            val (l, r) = when (n / segFrames) {
                0 -> {
                    val base = 0.015 * (0.6 * sin(2.0 * PI * 100.0 * t) + 0.4 * sin(2.0 * PI * 2000.0 * t))
                    Pair(base, base)
                }
                1 -> {
                    val sigL = 0.35 * (0.5 * sin(2.0 * PI * 440.0 * t) + 0.5 * sin(2.0 * PI * 3000.0 * t))
                    val sigR = 0.35 * 1.4125375 * (0.5 * sin(2.0 * PI * 440.0 * t + 0.4) + 0.5 * sin(2.0 * PI * 3000.0 * t))
                    Pair(sigL, sigR)
                }
                2 -> {
                    val tiny = 0.00005 * sin(2.0 * PI * 1000.0 * t)
                    Pair(tiny, tiny)
                }
                else -> {
                    val tone = 0.5 * sin(2.0 * PI * 800.0 * t)
                    val sigL = 0.16 * (tone + 0.5 * noiseL.nextFloat())
                    val sigR = 0.16 * 0.7943282 * (tone + 0.5 * noiseR.nextFloat())
                    Pair(sigL, sigR)
                }
            }
            out[n * 2] = l.toFloat()
            out[n * 2 + 1] = r.toFloat()
        }
        return out
    }

    private fun generateSurround51Signal(sampleRate: Int, lfeAmplitude: Double = 0.8): FloatArray {
        // 5 seconds, 6 channels: FL, FR, FC, LFE, SL, SR
        val totalFrames = sampleRate * 5
        val noiseSL = DeterministicNoise(0x11223344)
        val noiseSR = DeterministicNoise(0x55667788)
        val out = FloatArray(totalFrames * 6)

        for (n in 0 until totalFrames) {
            val t = n.toDouble() / sampleRate
            val env = if (n < sampleRate * 2) 0.5 else 1.0
            val fl = env * 0.25 * sin(2.0 * PI * 500.0 * t)
            val fr = env * 0.20 * sin(2.0 * PI * 1200.0 * t + 0.3)
            val fc = env * 0.30 * sin(2.0 * PI * 1000.0 * t)
            val lfe = lfeAmplitude * sin(2.0 * PI * 50.0 * t)
            val sl = env * 0.18 * (0.6 * sin(2.0 * PI * 2500.0 * t) + 0.4 * noiseSL.nextFloat())
            val sr = env * 0.15 * (0.6 * sin(2.0 * PI * 3500.0 * t) + 0.4 * noiseSR.nextFloat())

            val base = n * 6
            out[base] = fl.toFloat()
            out[base + 1] = fr.toFloat()
            out[base + 2] = fc.toFloat()
            out[base + 3] = lfe.toFloat()
            out[base + 4] = sl.toFloat()
            out[base + 5] = sr.toFloat()
        }
        return out
    }

    private fun generateBroadbandStereo(sampleRate: Int, durationSeconds: Double = 4.0): FloatArray {
        val frames = (sampleRate * durationSeconds).toInt()
        val noiseL = DeterministicNoise(0x0ABCDEF1)
        val noiseR = DeterministicNoise(0x0FEDCBA9)
        val out = FloatArray(frames * 2)

        for (n in 0 until frames) {
            val t = n.toDouble() / sampleRate
            val l = 0.12 * sin(2.0 * PI * 30.0 * t) +
                0.15 * sin(2.0 * PI * 200.0 * t) +
                0.18 * sin(2.0 * PI * 1000.0 * t) +
                0.14 * sin(2.0 * PI * 4000.0 * t) +
                0.08 * sin(2.0 * PI * 8000.0 * t) +
                0.10 * noiseL.nextFloat()
            val r = 0.10 * sin(2.0 * PI * 35.0 * t + 0.2) +
                0.14 * sin(2.0 * PI * 250.0 * t) +
                0.20 * sin(2.0 * PI * 1000.0 * t + 0.5) +
                0.12 * sin(2.0 * PI * 4200.0 * t) +
                0.07 * sin(2.0 * PI * 7500.0 * t) +
                0.10 * noiseR.nextFloat()
            out[n * 2] = l.toFloat()
            out[n * 2 + 1] = r.toFloat()
        }
        return out
    }

    private fun assertChannelEquals(
        expected: ChannelLoudness,
        actual: ChannelLoudness,
        delta: Double
    ) {
        assertEquals("channelName", expected.channelName, actual.channelName)
        assertEquals("${expected.channelName}.integrated", expected.integrated, actual.integrated, delta)
        assertEquals("${expected.channelName}.truePeak", expected.truePeak, actual.truePeak, delta)
        assertEquals("${expected.channelName}.shortTerm", expected.shortTerm, actual.shortTerm, delta)
        assertEquals("${expected.channelName}.momentary", expected.momentary, actual.momentary, delta)
        assertEquals("${expected.channelName}.samplePeak", expected.samplePeak, actual.samplePeak, delta)
        assertEquals("${expected.channelName}.isUsedInLufs", expected.isUsedInLufs, actual.isUsedInLufs)
        assertEquals("${expected.channelName}.lufsWeight", expected.lufsWeight, actual.lufsWeight, 0.0f)
    }

    private fun assertMetricsEqual(expected: LufsMetrics, actual: LufsMetrics, delta: Double) {
        assertEquals("integrated", expected.integrated, actual.integrated, delta)
        assertEquals("truePeak", expected.truePeak, actual.truePeak, delta)
        assertEquals("shortTerm", expected.shortTerm, actual.shortTerm, delta)
        assertEquals("momentary", expected.momentary, actual.momentary, delta)
        assertEquals("dynamicRange", expected.dynamicRange, actual.dynamicRange, delta)
        assertEquals("plr", expected.plr, actual.plr, delta)
        assertEquals("psr", expected.psr, actual.psr, delta)
        assertEquals("relativeThreshold", expected.relativeThreshold, actual.relativeThreshold, delta)
        assertEquals("samplePeak", expected.samplePeak, actual.samplePeak, delta)
        assertEquals("leftRightShift", expected.leftRightShift, actual.leftRightShift, delta)
        assertEquals("histogramMin", expected.histogramMin, actual.histogramMin)
        assertEquals("histogramMax", expected.histogramMax, actual.histogramMax)
        assertEquals("shortTermHistogram", expected.shortTermHistogram, actual.shortTermHistogram)
        assertEquals("duration", expected.duration, actual.duration, delta)
        assertEquals("channelMetrics.size", expected.channelMetrics.size, actual.channelMetrics.size)
        for (i in expected.channelMetrics.indices) {
            assertChannelEquals(expected.channelMetrics[i], actual.channelMetrics[i], delta)
        }
    }

    // ── Golden Regression Tests ───────────────────────────────────────────────

    @Test
    fun goldenMono1kHzSineAt48kHz() = runBlocking {
        val meta = listOf(ChannelMetadata("Mono", true, 1.0f))
        val analyzer = LufsAnalyzer(48_000, 1, meta)
        analyzer.processSamples(generateMonoSine(48_000, 5.0, 1000.0, 0.5f))

        val expected = LufsMetrics(
            integrated = -10.420713756506302,
            truePeak = -6.013903959240383,
            shortTerm = -10.420710657967486,
            momentary = -10.42046223525624,
            dynamicRange = 8.566553869826521E-6,
            plr = 4.406809797265919,
            psr = 4.406806698727103,
            relativeThreshold = -20.420713756506302,
            samplePeak = -6.020599913279624,
            leftRightShift = 0.0,
            channelMetrics = listOf(
                ChannelLoudness(
                    channelName = "Mono",
                    integrated = -10.420713756506302,
                    truePeak = -6.013903959240383,
                    shortTerm = -10.420710657967486,
                    momentary = -10.42046223525624,
                    samplePeak = -6.020599913279624,
                    isUsedInLufs = true,
                    lufsWeight = 1.0f
                )
            ),
            shortTermHistogram = listOf(HistogramBucket(-105, 18)),
            histogramMin = -105,
            histogramMax = -105,
            duration = 0.0
        )

        assertMetricsEqual(expected, analyzer.calculateMetrics(), GOLDEN_DELTA)
    }

    @Test
    fun goldenStereoDynamicMultiToneAt44100Hz() = runBlocking {
        val meta = listOf(
            ChannelMetadata("Front Left", true, 1.0f),
            ChannelMetadata("Front Right", true, 1.0f)
        )
        val analyzer = LufsAnalyzer(44_100, 2, meta)
        analyzer.processSamples(generateStereoDynamicSignal(44_100))

        val expectedNonZeroBuckets = mapOf(
            -240 to 1, -237 to 1, -235 to 1, -233 to 1, -230 to 1, -227 to 1, -225 to 1,
            -223 to 1, -222 to 1, -220 to 1, -209 to 1, -195 to 1, -184 to 1, -175 to 1,
            -168 to 1, -162 to 1, -157 to 1, -152 to 1, -148 to 1, -143 to 1, -140 to 2,
            -136 to 2, -133 to 2, -130 to 1, -129 to 1, -127 to 2, -124 to 2, -121 to 2,
            -119 to 2, -117 to 2, -116 to 10
        )
        val expectedHistogram = (-240..-116).map { key ->
            HistogramBucket(key, expectedNonZeroBuckets[key] ?: 0)
        }

        val expected = LufsMetrics(
            integrated = -12.594898577279741,
            truePeak = -6.1230296250403065,
            shortTerm = -11.50536231378315,
            momentary = -9.74593416116936,
            dynamicRange = 11.465700063288176,
            plr = 6.471868952239435,
            psr = 5.382332688742844,
            relativeThreshold = -24.20779812819614,
            samplePeak = -6.1251038695800775,
            leftRightShift = 2.7517302117978204,
            channelMetrics = listOf(
                ChannelLoudness(
                    channelName = "Front Left",
                    integrated = -17.164380256610542,
                    truePeak = -9.122496774953154,
                    shortTerm = -16.268914622780457,
                    momentary = -14.510282591892722,
                    samplePeak = -9.124971797514798,
                    isUsedInLufs = true,
                    lufsWeight = 1.0f
                ),
                ChannelLoudness(
                    channelName = "Front Right",
                    integrated = -14.412650044812722,
                    truePeak = -6.1230296250403065,
                    shortTerm = -13.270110096099849,
                    momentary = -11.51028288253227,
                    samplePeak = -6.1251038695800775,
                    isUsedInLufs = true,
                    lufsWeight = 1.0f
                )
            ),
            shortTermHistogram = expectedHistogram,
            histogramMin = -240,
            histogramMax = -116,
            duration = 0.0
        )

        assertMetricsEqual(expected, analyzer.calculateMetrics(), GOLDEN_DELTA)
    }

    @Test
    fun goldenSurround51At48kHz() = runBlocking {
        val meta = listOf(
            ChannelMetadata("Front Left", true, 1.0f),
            ChannelMetadata("Front Right", true, 1.0f),
            ChannelMetadata("Front Center", true, 1.0f),
            ChannelMetadata("LFE (Subwoofer)", false, 0.0f),
            ChannelMetadata("Side Left", true, 1.41f),
            ChannelMetadata("Side Right", true, 1.41f)
        )
        val analyzer = LufsAnalyzer(48_000, 6, meta)
        analyzer.processSamples(generateSurround51Signal(48_000))

        val expectedCounts = mapOf(
            -127 to 1, -125 to 1, -123 to 1, -122 to 1, -120 to 1, -118 to 1,
            -117 to 1, -115 to 1, -113 to 1, -112 to 1, -111 to 1, -109 to 1,
            -108 to 1, -107 to 1, -105 to 1, -104 to 1, -103 to 1, -102 to 1
        )
        val expectedHistogram = (-127..-102).map { key ->
            HistogramBucket(key, expectedCounts[key] ?: 0)
        }

        val expected = LufsMetrics(
            integrated = -11.495909107198951,
            truePeak = -1.924445780803503,
            shortTerm = -10.142255637592141,
            momentary = -9.968046295559768,
            dynamicRange = 2.334649504918591,
            plr = 9.571463326395449,
            psr = 8.217809856788639,
            relativeThreshold = -21.49590910719895,
            samplePeak = -1.9382001307312877,
            leftRightShift = -2.033846554232145,
            channelMetrics = listOf(
                ChannelLoudness(
                    channelName = "Front Left",
                    integrated = -17.683531117277386,
                    truePeak = -12.029254941963263,
                    shortTerm = -16.330108346022598,
                    momentary = -16.164015655847177,
                    samplePeak = -12.041199826559248,
                    isUsedInLufs = true,
                    lufsWeight = 1.0f
                ),
                ChannelLoudness(
                    channelName = "Front Right",
                    integrated = -19.71737767150953,
                    truePeak = -13.973265445231062,
                    shortTerm = -18.36395054009583,
                    momentary = -18.19783077348416,
                    samplePeak = -13.98027106379169,
                    isUsedInLufs = true,
                    lufsWeight = 1.0f
                ),
                ChannelLoudness(
                    channelName = "Front Center",
                    integrated = -16.377101766523506,
                    truePeak = -10.45087861780155,
                    shortTerm = -15.023675132691988,
                    momentary = -14.85761069494487,
                    samplePeak = -10.457574560460515,
                    isUsedInLufs = true,
                    lufsWeight = 1.0f
                ),
                ChannelLoudness(
                    channelName = "LFE (Subwoofer)",
                    integrated = -9.627889091111165,
                    truePeak = -1.924445780803503,
                    shortTerm = -9.626558172294283,
                    momentary = -9.626558172236617,
                    samplePeak = -1.9382001307312877,
                    isUsedInLufs = false,
                    lufsWeight = 0.0f
                ),
                ChannelLoudness(
                    channelName = "Side Left",
                    integrated = -21.03622854374097,
                    truePeak = -13.063201657716753,
                    shortTerm = -19.679876065251413,
                    momentary = -19.473451262558978,
                    samplePeak = -14.895321851811715,
                    isUsedInLufs = true,
                    lufsWeight = 1.41f
                ),
                ChannelLoudness(
                    channelName = "Side Right",
                    integrated = -21.947274014702035,
                    truePeak = -14.56025080214284,
                    shortTerm = -20.59566598916021,
                    momentary = -20.388032200105503,
                    samplePeak = -16.478913118079678,
                    isUsedInLufs = true,
                    lufsWeight = 1.41f
                )
            ),
            shortTermHistogram = expectedHistogram,
            histogramMin = -127,
            histogramMax = -102,
            duration = 0.0
        )

        assertMetricsEqual(expected, analyzer.calculateMetrics(), GOLDEN_DELTA)
    }

    @Test
    fun goldenBroadbandAcrossSampleRates() = runBlocking {
        val stereoMeta = listOf(
            ChannelMetadata("Front Left", true, 1.0f),
            ChannelMetadata("Front Right", true, 1.0f)
        )

        val expectedByRate = mapOf(
            22_050 to LufsMetrics(
                integrated = -10.038691276612198,
                truePeak = -2.4530411968287997,
                shortTerm = -10.042180614116198,
                momentary = -10.005492850617355,
                dynamicRange = 0.0010560343983332388,
                plr = 7.585650079783399,
                psr = 7.589139417287399,
                relativeThreshold = -20.038691276612198,
                samplePeak = -2.8335006289842046,
                leftRightShift = -0.38397393534301116,
                channelMetrics = listOf(
                    ChannelLoudness("Front Left", -12.86124643282898, -2.4530411968287997, -12.86098842885193, -12.822016022334067, -2.8335006289842046, true, 1.0f),
                    ChannelLoudness("Front Right", -13.24522036817199, -2.8892599670419483, -13.247954835210729, -13.192809905480962, -3.412052365135284, true, 1.0f)
                ),
                shortTermHistogram = listOf(HistogramBucket(-101, 8)),
                histogramMin = -101,
                histogramMax = -101,
                duration = 0.0
            ),
            44_100 to LufsMetrics(
                integrated = -10.030062981625157,
                truePeak = -2.355744530496101,
                shortTerm = -10.029583688175519,
                momentary = -9.992216600676374,
                dynamicRange = 0.0010499312908009983,
                plr = 7.674318451129056,
                psr = 7.673839157679418,
                relativeThreshold = -20.030062981625157,
                samplePeak = -2.748359137337645,
                leftRightShift = -0.37552804133569495,
                channelMetrics = listOf(
                    ChannelLoudness("Front Left", -12.856656573272566, -2.355744530496101, -12.85459114037864, -12.817377817123104, -2.748359137337645, true, 1.0f),
                    ChannelLoudness("Front Right", -13.232184614608261, -3.0286231630261002, -13.232604425108928, -13.188571222252449, -3.3332886521223903, true, 1.0f)
                ),
                shortTermHistogram = listOf(HistogramBucket(-101, 8)),
                histogramMin = -101,
                histogramMax = -101,
                duration = 0.0
            ),
            48_000 to LufsMetrics(
                integrated = -10.034058392514554,
                truePeak = -2.4282836813691295,
                shortTerm = -10.03376037285204,
                momentary = -10.007588988936904,
                dynamicRange = 0.0012543509006750497,
                plr = 7.605774711145425,
                psr = 7.60547669148291,
                relativeThreshold = -20.034058392514552,
                samplePeak = -2.694391358503765,
                leftRightShift = -0.36418505104555976,
                channelMetrics = listOf(
                    ChannelLoudness("Front Left", -12.866082125510898, -2.4282836813691295, -12.864539910845656, -12.843007084649582, -2.694391358503765, true, 1.0f),
                    ChannelLoudness("Front Right", -13.230267176556458, -2.8896666745939434, -13.22967999543063, -13.198496955476498, -2.986134941047892, true, 1.0f)
                ),
                shortTermHistogram = listOf(HistogramBucket(-101, 8)),
                histogramMin = -101,
                histogramMax = -101,
                duration = 0.0
            ),
            96_000 to LufsMetrics(
                integrated = -10.014929665577105,
                truePeak = -2.463273069160441,
                shortTerm = -10.014155951415976,
                momentary = -9.988702619545679,
                dynamicRange = 0.0025750743863284242,
                plr = 7.551656596416664,
                psr = 7.550882882255535,
                relativeThreshold = -20.014929665577107,
                samplePeak = -2.7226460969201813,
                leftRightShift = -0.3546921415036053,
                channelMetrics = listOf(
                    ChannelLoudness("Front Left", -12.85150354810069, -2.463273069160441, -12.850425655353343, -12.819179925152625, -2.7226460969201813, true, 1.0f),
                    ChannelLoudness("Front Right", -13.206195689604295, -2.80176648353784, -13.205478391882671, -13.186593726947947, -3.009272205386501, true, 1.0f)
                ),
                shortTermHistogram = listOf(HistogramBucket(-101, 8)),
                histogramMin = -101,
                histogramMax = -101,
                duration = 0.0
            )
        )

        for ((sr, expected) in expectedByRate) {
            val analyzer = LufsAnalyzer(sr, 2, stereoMeta)
            analyzer.processSamples(generateBroadbandStereo(sr))
            assertMetricsEqual(expected, analyzer.calculateMetrics(), GOLDEN_DELTA)
        }
    }

    // ── Chunking, Buffering, and Threading Determinism ────────────────────────

    @Test
    fun chunkingAndMisalignedFramesProduceIdenticalMetricsStereo() = runBlocking {
        val sampleRate = 44_100
        val meta = listOf(
            ChannelMetadata("Front Left", true, 1.0f),
            ChannelMetadata("Front Right", true, 1.0f)
        )
        val signal = generateStereoDynamicSignal(sampleRate)

        val monolithic = LufsAnalyzer(sampleRate, 2, meta).apply {
            processSamples(signal)
        }.calculateMetrics()

        // AudioDecoder uses BUFFER_SIZE = 8192 * 4 = 32768 floats
        val decoderChunked = LufsAnalyzer(sampleRate, 2, meta).apply {
            val chunkSize = 8192 * 4
            var offset = 0
            while (offset < signal.size) {
                val end = minOf(offset + chunkSize, signal.size)
                processSamples(signal.copyOfRange(offset, end))
                offset = end
            }
        }.calculateMetrics()

        // Irregular prime/odd chunks that split across interleaved stereo frames
        val oddChunked = LufsAnalyzer(sampleRate, 2, meta).apply {
            val sizes = intArrayOf(1, 3, 5, 7, 11, 13, 17, 19, 23, 1001, 4093, 2, 4, 16381)
            var offset = 0
            var idx = 0
            while (offset < signal.size) {
                val len = minOf(sizes[idx % sizes.size], signal.size - offset)
                processSamples(signal.copyOfRange(offset, offset + len))
                offset += len
                idx++
            }
        }.calculateMetrics()

        assertMetricsEqual(monolithic, decoderChunked, EXACT_DELTA)
        assertMetricsEqual(monolithic, oddChunked, EXACT_DELTA)
    }

    @Test
    fun chunkingAndMisalignedFramesProduceIdenticalMetricsSurround51() = runBlocking {
        val sampleRate = 48_000
        val meta = listOf(
            ChannelMetadata("Front Left", true, 1.0f),
            ChannelMetadata("Front Right", true, 1.0f),
            ChannelMetadata("Front Center", true, 1.0f),
            ChannelMetadata("LFE (Subwoofer)", false, 0.0f),
            ChannelMetadata("Side Left", true, 1.41f),
            ChannelMetadata("Side Right", true, 1.41f)
        )
        val signal = generateSurround51Signal(sampleRate)

        val monolithic = LufsAnalyzer(sampleRate, 6, meta).apply {
            processSamples(signal)
        }.calculateMetrics()

        // Chunk sizes not divisible by 6 to thoroughly exercise leftoverSamples + overlapBuffer
        val misaligned = LufsAnalyzer(sampleRate, 6, meta).apply {
            val sizes = intArrayOf(1, 5, 7, 11, 13, 19, 31, 503, 7919, 32768)
            var offset = 0
            var idx = 0
            while (offset < signal.size) {
                val len = minOf(sizes[idx % sizes.size], signal.size - offset)
                processSamples(signal.copyOfRange(offset, offset + len))
                offset += len
                idx++
            }
        }.calculateMetrics()

        assertMetricsEqual(monolithic, misaligned, EXACT_DELTA)
    }

    @Test
    fun multithreadedProcessingIsDeterministicAcrossRuns() = runBlocking {
        val sampleRate = 48_000
        val meta = listOf(
            ChannelMetadata("Front Left", true, 1.0f),
            ChannelMetadata("Front Right", true, 1.0f),
            ChannelMetadata("Front Center", true, 1.0f),
            ChannelMetadata("LFE (Subwoofer)", false, 0.0f),
            ChannelMetadata("Side Left", true, 1.41f),
            ChannelMetadata("Side Right", true, 1.41f)
        )
        val signal = generateSurround51Signal(sampleRate)

        val baseline = LufsAnalyzer(sampleRate, 6, meta).apply {
            processSamples(signal)
        }.calculateMetrics()

        repeat(4) {
            val rerun = LufsAnalyzer(sampleRate, 6, meta).apply {
                processSamples(signal)
            }.calculateMetrics()
            assertMetricsEqual(baseline, rerun, 0.0)
        }
    }

    // ── Edge Cases, Channel Weighting, and Mathematical Invariants ────────────

    @Test
    fun emptyAndSubBlockInputsReturnEmptyMetrics() = runBlocking {
        val stereoMeta = listOf(
            ChannelMetadata("Front Left", true, 1.0f),
            ChannelMetadata("Front Right", true, 1.0f)
        )
        val expectedEmpty = LufsMetrics(
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

        // No samples at all
        val noSamples = LufsAnalyzer(48_000, 2, stereoMeta)
        assertMetricsEqual(expectedEmpty, noSamples.calculateMetrics(), 0.0)
        assertEquals(0.0, noSamples.calculateLrShift(), 0.0)

        // Empty array
        val emptyArr = LufsAnalyzer(48_000, 2, stereoMeta).apply {
            processSamples(FloatArray(0))
        }
        assertMetricsEqual(expectedEmpty, emptyArr.calculateMetrics(), 0.0)

        // Shorter than 400ms (blockSize = 19200 frames -> feed 19199 frames)
        val subBlock = LufsAnalyzer(48_000, 2, stereoMeta).apply {
            processSamples(FloatArray(19_199 * 2) { 0.5f })
        }
        assertMetricsEqual(expectedEmpty, subBlock.calculateMetrics(), 0.0)
    }

    @Test
    fun channelWeightingByNameAndPositionalFallback() = runBlocking {
        val signal = generateSurround51Signal(48_000)

        // Empty channelList triggers positional 6-channel fallback:
        // ch 3 -> LFE (0.0), ch 4 & 5 -> Surround (1.41), others -> 1.0
        val positionalMetrics = LufsAnalyzer(48_000, 6, emptyList()).apply {
            processSamples(signal)
        }.calculateMetrics()

        assertEquals(listOf("Ch 1", "Ch 2", "Ch 3", "Ch 4", "Ch 5", "Ch 6"),
            positionalMetrics.channelMetrics.map { it.channelName })
        assertEquals(listOf(true, true, true, false, true, true),
            positionalMetrics.channelMetrics.map { it.isUsedInLufs })
        assertEquals(listOf(1.0f, 1.0f, 1.0f, 0.0f, 1.41f, 1.41f),
            positionalMetrics.channelMetrics.map { it.lufsWeight })

        // Custom name detection: "Sub", "Surround", "Rear"
        val customMeta = listOf(
            ChannelMetadata("Left", true, 1.0f),
            ChannelMetadata("Right", true, 1.0f),
            ChannelMetadata("Center", true, 1.0f),
            ChannelMetadata("Sub Bass", false, 0.0f),
            ChannelMetadata("Surround L", true, 1.41f),
            ChannelMetadata("Rear R", true, 1.41f)
        )
        val namedMetrics = LufsAnalyzer(48_000, 6, customMeta).apply {
            processSamples(signal)
        }.calculateMetrics()

        assertFalse(namedMetrics.channelMetrics[3].isUsedInLufs)
        assertEquals(0.0f, namedMetrics.channelMetrics[3].lufsWeight, 0.0f)
        assertEquals(1.41f, namedMetrics.channelMetrics[4].lufsWeight, 0.0f)
        assertEquals(1.41f, namedMetrics.channelMetrics[5].lufsWeight, 0.0f)
        assertEquals(positionalMetrics.integrated, namedMetrics.integrated, EXACT_DELTA)
    }

    @Test
    fun lfeChannelDoesNotAffectCombinedLoudness() = runBlocking {
        val meta = listOf(
            ChannelMetadata("Front Left", true, 1.0f),
            ChannelMetadata("Front Right", true, 1.0f),
            ChannelMetadata("Front Center", true, 1.0f),
            ChannelMetadata("LFE (Subwoofer)", false, 0.0f),
            ChannelMetadata("Side Left", true, 1.41f),
            ChannelMetadata("Side Right", true, 1.41f)
        )

        val silentLfe = LufsAnalyzer(48_000, 6, meta).apply {
            processSamples(generateSurround51Signal(48_000, lfeAmplitude = 0.0))
        }.calculateMetrics()

        val loudLfe = LufsAnalyzer(48_000, 6, meta).apply {
            processSamples(generateSurround51Signal(48_000, lfeAmplitude = 0.95))
        }.calculateMetrics()

        assertEquals(silentLfe.integrated, loudLfe.integrated, EXACT_DELTA)
        assertEquals(silentLfe.shortTerm, loudLfe.shortTerm, EXACT_DELTA)
        assertEquals(silentLfe.momentary, loudLfe.momentary, EXACT_DELTA)
        assertEquals(silentLfe.relativeThreshold, loudLfe.relativeThreshold, EXACT_DELTA)
        assertEquals(silentLfe.dynamicRange, loudLfe.dynamicRange, EXACT_DELTA)
        assertEquals(silentLfe.shortTermHistogram, loudLfe.shortTermHistogram)
        // Overall peak tracks the max across all channels including LFE
        assertTrue(loudLfe.truePeak > silentLfe.truePeak)
    }

    @Test
    fun gainScalingShiftsLoudnessLinearlyAndPreservesRelativeMetrics() = runBlocking {
        val sampleRate = 48_000
        val meta = listOf(
            ChannelMetadata("Front Left", true, 1.0f),
            ChannelMetadata("Front Right", true, 1.0f)
        )
        val baseSignal = generateStereoDynamicSignal(sampleRate)
        val doubledSignal = FloatArray(baseSignal.size) { i -> baseSignal[i] * 2.0f }

        val m1 = LufsAnalyzer(sampleRate, 2, meta).apply { processSamples(baseSignal) }.calculateMetrics()
        val m2 = LufsAnalyzer(sampleRate, 2, meta).apply { processSamples(doubledSignal) }.calculateMetrics()

        val expectedGainDb = 20.0 * log10(2.0) // +6.020599913279624 dB

        assertEquals(expectedGainDb, m2.integrated - m1.integrated, 1e-5)
        assertEquals(expectedGainDb, m2.shortTerm - m1.shortTerm, 1e-5)
        assertEquals(expectedGainDb, m2.momentary - m1.momentary, 1e-5)
        assertEquals(expectedGainDb, m2.truePeak - m1.truePeak, 1e-5)
        assertEquals(expectedGainDb, m2.samplePeak - m1.samplePeak, 1e-5)
        assertEquals(expectedGainDb, m2.relativeThreshold - m1.relativeThreshold, 1e-5)

        // Gain-invariant metrics
        assertEquals(m1.dynamicRange, m2.dynamicRange, 1e-5)
        assertEquals(m1.plr, m2.plr, 1e-5)
        assertEquals(m1.psr, m2.psr, 1e-5)
        assertEquals(m1.leftRightShift, m2.leftRightShift, 1e-5)
    }

    @Test
    fun kWeightingFrequencyResponseOrdering() = runBlocking {
        val sampleRate = 48_000
        val meta = listOf(ChannelMetadata("Mono", true, 1.0f))

        suspend fun lufsOfFreq(freqHz: Double): Double {
            val analyzer = LufsAnalyzer(sampleRate, 1, meta)
            analyzer.processSamples(generateMonoSine(sampleRate, 4.0, freqHz, 0.5f))
            return analyzer.calculateMetrics().integrated
        }

        val lufs30Hz = lufsOfFreq(30.0)
        val lufs1kHz = lufsOfFreq(1000.0)
        val lufs4kHz = lufsOfFreq(4000.0)

        // Stage 2 high-pass (~38 Hz) attenuates 30 Hz; Stage 1 high-shelf (~1.68 kHz, +4 dB) boosts 4 kHz
        assertTrue("4 kHz ($lufs4kHz) should read louder than 1 kHz ($lufs1kHz)", lufs4kHz > lufs1kHz + 2.0)
        assertTrue("1 kHz ($lufs1kHz) should read louder than 30 Hz ($lufs30Hz)", lufs1kHz > lufs30Hz + 3.0)
    }

    @Test
    fun relativeGateExcludesQuietPassagesAboveAbsoluteGate() = runBlocking {
        val sampleRate = 48_000
        val meta = listOf(ChannelMetadata("Mono", true, 1.0f))

        // 4s loud (-10.4 LUFS) + 4s quiet (~-30.4 LUFS, well above -70 LUFS absolute gate
        // but well below the ~-23.4 LUFS relative gate)
        val loud = generateMonoSine(sampleRate, 4.0, 1000.0, 0.5f)
        val quiet = generateMonoSine(sampleRate, 4.0, 1000.0, 0.05f)
        val combined = FloatArray(loud.size + quiet.size)
        loud.copyInto(combined, 0)
        quiet.copyInto(combined, loud.size)

        val loudOnlyMetrics = LufsAnalyzer(sampleRate, 1, meta).apply {
            processSamples(loud)
        }.calculateMetrics()

        val combinedMetrics = LufsAnalyzer(sampleRate, 1, meta).apply {
            processSamples(combined)
        }.calculateMetrics()

        // Because the quiet section is gated out by the relative threshold,
        // integrated loudness should remain close to the loud-only section (not dropped by ~3 dB).
        assertTrue(
            "Relative threshold (${combinedMetrics.relativeThreshold}) should sit between quiet and loud",
            combinedMetrics.relativeThreshold < loudOnlyMetrics.integrated - 10.0
        )
        assertEquals(loudOnlyMetrics.integrated, combinedMetrics.integrated, 0.25)
    }
}
