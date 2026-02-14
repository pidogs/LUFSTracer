package com.pidog.lufstracer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import android.net.Uri

data class DecodedAudioInfo(
    val sampleRate: Int,
    val channelCount: Int,
    val durationUs: Long,
    val totalPcmBytes: Long,
    val durationSeconds: Double,
    val lufsMetrics: LufsMetrics? = null
)

class AudioDecoder(private val context: Context) {

    companion object {
        private const val TAG = "AudioDecoder"
        private const val TIMEOUT_US = 10000L
        private const val BUFFER_SIZE = 8192 * 4 // Process in chunks
    }

    suspend fun decodeUri(uri: Uri): Result<DecodedAudioInfo> = withContext(Dispatchers.IO) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            extractor = MediaExtractor()
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Cannot open file descriptor")

            extractor.setDataSource(pfd.fileDescriptor, 0, pfd.statSize)
            pfd.close()

            // Find audio track
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

            if (trackIndex < 0 || format == null) {
                return@withContext Result.failure(Exception("No audio track found"))
            }

            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            Log.d(TAG, "Audio: $mime, ${sampleRate}Hz, $channelCount ch")

            // Create analyzer
            val analyzer = LufsAnalyzer(sampleRate, channelCount)

            // Configure codec
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            var totalPcmBytes = 0L

            // Reusable float buffer for chunk processing
            val floatBuffer = FloatArray(BUFFER_SIZE)

            while (!outputEos) {
                // Feed input
                if (!inputEos) {
                    val inputId = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputId >= 0) {
                        val inputBuf = codec.getInputBuffer(inputId)
                        if (inputBuf != null) {
                            val size = extractor.readSampleData(inputBuf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inputId, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEos = true
                            } else {
                                codec.queueInputBuffer(
                                    inputId, 0, size, extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                // Get output
                val outputId = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputId >= 0) {
                    val outputBuf = codec.getOutputBuffer(outputId)
                    if (outputBuf != null && bufferInfo.size > 0) {
                        outputBuf.position(bufferInfo.offset)

                        // Process as 16-bit PCM (most common)
                        val numSamples = bufferInfo.size / 2
                        var floatIdx = 0

                        for (i in 0 until numSamples) {
                            val byteIdx = i * 2
                            if (byteIdx + 1 < outputBuf.limit()) {
                                val sample = (
                                        (outputBuf.get(byteIdx + 1).toInt() shl 8) or
                                                (outputBuf.get(byteIdx).toInt() and 0xFF)
                                        ).toShort()

                                floatBuffer[floatIdx++] = sample.toFloat() / 32768.0f

                                // Process chunk when buffer full
                                if (floatIdx >= floatBuffer.size) {
                                    analyzer.processSamples(floatBuffer)
                                    floatIdx = 0
                                }
                            }
                        }

                        // Process remaining
                        if (floatIdx > 0) {
                            val remaining = FloatArray(floatIdx) { floatBuffer[it] }
                            analyzer.processSamples(remaining)
                        }

                        totalPcmBytes += bufferInfo.size
                    }
                    codec.releaseOutputBuffer(outputId, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEos = true
                    }
                }
            }

            // Calculate final metrics
            val metrics = analyzer.calculateMetrics()

            Log.d(TAG, "Done: Integrated=${String.format("%.2f", metrics.integrated)} LUFS")

            Result.success(DecodedAudioInfo(
                sampleRate = sampleRate,
                channelCount = channelCount,
                durationUs = durationUs,
                totalPcmBytes = totalPcmBytes,
                durationSeconds = durationUs / 1_000_000.0,
                lufsMetrics = metrics
            ))

        } catch (e: Exception) {
            Log.e(TAG, "Decode failed", e)
            Result.failure(e)
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Exception) { }
            try {
                extractor?.release()
            } catch (e: Exception) { }
        }
    }
}