package com.pidog.lufstracer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.AudioFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import android.net.Uri

data class ChannelMetadata(
    val name: String,
    val isUsedInLufs: Boolean,
    val lufsWeight: Float
)

data class DecodedAudioInfo(
    val sampleRate: Int,
    val channelCount: Int,
    val channelLayout: String,
    val channelList: List<ChannelMetadata>,
    val durationUs: Long,
    val totalPcmBytes: Long,
    val durationSeconds: Double,
    val lufsMetrics: LufsMetrics? = null
)

object AudioChannelMapper {
    private val CHANNEL_MAP = linkedMapOf(
        AudioFormat.CHANNEL_OUT_FRONT_LEFT to Pair("Front Left", 1.0f),
        AudioFormat.CHANNEL_OUT_FRONT_RIGHT to Pair("Front Right", 1.0f),
        AudioFormat.CHANNEL_OUT_FRONT_CENTER to Pair("Front Center", 1.0f),
        AudioFormat.CHANNEL_OUT_LOW_FREQUENCY to Pair("LFE (Subwoofer)", 0.0f),
        AudioFormat.CHANNEL_OUT_BACK_LEFT to Pair("Back Left", 1.41f),
        AudioFormat.CHANNEL_OUT_BACK_RIGHT to Pair("Back Right", 1.41f),
        AudioFormat.CHANNEL_OUT_FRONT_LEFT_OF_CENTER to Pair("Front Left Center", 1.0f),
        AudioFormat.CHANNEL_OUT_FRONT_RIGHT_OF_CENTER to Pair("Front Right Center", 1.0f),
        AudioFormat.CHANNEL_OUT_BACK_CENTER to Pair("Back Center", 1.41f),
        AudioFormat.CHANNEL_OUT_SIDE_LEFT to Pair("Side Left", 1.41f),
        AudioFormat.CHANNEL_OUT_SIDE_RIGHT to Pair("Side Right", 1.41f),
        AudioFormat.CHANNEL_OUT_TOP_CENTER to Pair("Top Center", 1.0f)
    )

    fun getChannelsFromMask(mask: Int, count: Int): List<ChannelMetadata> {
        val list = mutableListOf<ChannelMetadata>()
        if (mask <= 0) {
            return when (count) {
                1 -> listOf(ChannelMetadata("Mono", true, 1.0f))
                2 -> listOf(ChannelMetadata("Front Left", true, 1.0f), ChannelMetadata("Front Right", true, 1.0f))
                else -> List(count) { ChannelMetadata("Channel ${it + 1}", true, 1.0f) }
            }
        }
        for ((bit, info) in CHANNEL_MAP) {
            if ((mask and bit) != 0) {
                list.add(ChannelMetadata(info.first, info.second > 0f, info.second))
            }
        }
        while (list.size < count) {
            list.add(ChannelMetadata("Channel ${list.size + 1}", true, 1.0f))
        }
        return list.take(count)
    }
}

class AudioDecoder(private val context: Context) {
    companion object {
        private const val TAG = "AudioDecoder"
        private const val TIMEOUT_US = 10000L
        private const val BUFFER_SIZE = 8192 * 4
    }

    suspend fun decodeUri(
        uri: Uri,
        analyzeAllChannels: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): Result<DecodedAudioInfo> = withContext(Dispatchers.IO) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            extractor = MediaExtractor()
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Cannot open file descriptor")
            extractor.setDataSource(pfd.fileDescriptor, 0, pfd.statSize)
            pfd.close()

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = fmt
                    break
                }
            }

            if (trackIndex < 0 || format == null) return@withContext Result.failure(Exception("No audio track found"))

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val channelMask = if (format.containsKey(MediaFormat.KEY_CHANNEL_MASK)) format.getInteger(MediaFormat.KEY_CHANNEL_MASK) else 0

            val channelList = AudioChannelMapper.getChannelsFromMask(channelMask, channelCount)
            val layoutName = when (channelCount) {
                1 -> "Mono"
                2 -> "Stereo"
                6 -> if (channelList.any { it.name.contains("LFE") }) "5.1 Surround" else "6.0"
                8 -> "7.1 Surround"
                else -> "$channelCount Channels"
            }

            val analyzer = LufsAnalyzer(sampleRate, channelCount, channelList, analyzeAllChannels)

            // MULTITHREADING PIPELINE:
            // Creates an asynchronous tube allowing decoder and analyzer to run at the same time
            val audioChannel = Channel<FloatArray>(capacity = 16)
            val analyzerJob = launch(Dispatchers.Default) {
                for (buffer in audioChannel) {
                    analyzer.processSamples(buffer)
                }
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            var totalPcmBytes = 0L
            val floatBuffer = FloatArray(BUFFER_SIZE)
            var lastProgressUpdate = 0L
            var floatIdx = 0

            while (!outputEos) {
                if (!inputEos) {
                    val inputId = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputId >= 0) {
                        val inputBuf = codec.getInputBuffer(inputId)
                        if (inputBuf != null) {
                            val size = extractor.readSampleData(inputBuf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            } else {
                                val sampleTime = extractor.sampleTime
                                codec.queueInputBuffer(inputId, 0, size, sampleTime, 0)
                                extractor.advance()
                                if (durationUs > 0 && sampleTime - lastProgressUpdate > 100_000L) {
                                    onProgress((sampleTime.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f))
                                    lastProgressUpdate = sampleTime
                                }
                            }
                        }
                    }
                }

                val outputId = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputId >= 0) {
                    val outputBuf = codec.getOutputBuffer(outputId)
                    if (outputBuf != null && bufferInfo.size > 0) {
                        outputBuf.position(bufferInfo.offset)
                        val numSamples = bufferInfo.size / 2

                        for (i in 0 until numSamples) {
                            val byteIdx = i * 2
                            if (byteIdx + 1 < outputBuf.limit()) {
                                val sample = ((outputBuf.get(byteIdx + 1).toInt() shl 8) or (outputBuf.get(byteIdx).toInt() and 0xFF)).toShort()
                                floatBuffer[floatIdx++] = sample.toFloat() / 32768.0f

                                if (floatIdx >= floatBuffer.size) {
                                    // Send buffer into asynchronous tube instead of blocking decoding loop
                                    audioChannel.send(floatBuffer.copyOf())
                                    floatIdx = 0
                                }
                            }
                        }
                        totalPcmBytes += bufferInfo.size
                    }
                    codec.releaseOutputBuffer(outputId, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEos = true
                    }
                }
            }

            // Push leftovers
            if (floatIdx > 0) {
                audioChannel.send(floatBuffer.copyOfRange(0, floatIdx))
            }

            // Wait for mathematical analysis to catch up & finish
            audioChannel.close()
            analyzerJob.join()

            onProgress(1.0f)
            val metrics = analyzer.calculateMetrics()
            metrics.duration = durationUs / 1_000_000.0

            Result.success(DecodedAudioInfo(
                sampleRate = sampleRate, channelCount = channelCount, channelLayout = layoutName,
                channelList = channelList, durationUs = durationUs, totalPcmBytes = totalPcmBytes,
                durationSeconds = durationUs / 1_000_000.0, lufsMetrics = metrics
            ))

        } catch (e: Exception) {
            Log.e(TAG, "Decode failed", e)
            Result.failure(e)
        } finally {
            try { codec?.stop(); codec?.release() } catch (e: Exception) { }
            try { extractor?.release() } catch (e: Exception) { }
        }
    }
}