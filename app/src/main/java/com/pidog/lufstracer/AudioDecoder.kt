package com.pidog.lufstracer

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    val lufsMetrics: LufsMetrics? = null,
    val processingTimeMs: Long = 0L
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

internal data class WavPcm16Info(
    val sampleRate: Int,
    val channelCount: Int,
    val channelMask: Int,
    val dataBytes: Long
)

/**
 * Lightweight parser for standard uncompressed 16-bit little-endian PCM WAV files.
 * Positions the stream right at the start of the `data` chunk payload if valid,
 * or returns null so callers can fall back to MediaExtractor + MediaCodec.
 */
internal object WavHeaderParser {
    private val PCM_SUBFORMAT_GUID = byteArrayOf(
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
        0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71
    )

    fun parsePcm16Header(input: InputStream): WavPcm16Info? {
        val riffHeader = ByteArray(12)
        if (!readFully(input, riffHeader, 12)) return null
        if (riffHeader[0] != 'R'.code.toByte() || riffHeader[1] != 'I'.code.toByte() ||
            riffHeader[2] != 'F'.code.toByte() || riffHeader[3] != 'F'.code.toByte() ||
            riffHeader[8] != 'W'.code.toByte() || riffHeader[9] != 'A'.code.toByte() ||
            riffHeader[10] != 'V'.code.toByte() || riffHeader[11] != 'E'.code.toByte()
        ) {
            return null
        }

        var fmtFound = false
        var sampleRate = 0
        var channelCount = 0
        var channelMask = 0

        val chunkHeader = ByteArray(8)
        var chunksScanned = 0
        while (chunksScanned++ < 64) {
            if (!readFully(input, chunkHeader, 8)) return null
            val id0 = chunkHeader[0].toInt() and 0xFF
            val id1 = chunkHeader[1].toInt() and 0xFF
            val id2 = chunkHeader[2].toInt() and 0xFF
            val id3 = chunkHeader[3].toInt() and 0xFF
            val chunkSize = readUInt32LE(chunkHeader, 4)

            // "fmt "
            if (id0 == 'f'.code && id1 == 'm'.code && id2 == 't'.code && id3 == ' '.code) {
                if (chunkSize < 16 || chunkSize > 4096) return null
                val fmtBytes = ByteArray(chunkSize.toInt())
                if (!readFully(input, fmtBytes, fmtBytes.size)) return null
                if ((chunkSize and 1L) != 0L) {
                    if (!skipFully(input, 1L)) return null
                }

                val audioFormat = readUInt16LE(fmtBytes, 0)
                channelCount = readUInt16LE(fmtBytes, 2)
                sampleRate = readUInt32LE(fmtBytes, 4).toInt()
                val bitsPerSample = readUInt16LE(fmtBytes, 14)

                if (channelCount <= 0 || sampleRate <= 0 || bitsPerSample != 16) return null

                when (audioFormat) {
                    1 -> {
                        // Standard uncompressed 16-bit PCM
                        channelMask = 0
                    }
                    0xFFFE -> {
                        // WAVE_FORMAT_EXTENSIBLE: check validBits == 16 and PCM GUID
                        if (fmtBytes.size < 40) return null
                        val validBits = readUInt16LE(fmtBytes, 18)
                        if (validBits != 16) return null
                        for (i in 0 until 16) {
                            if (fmtBytes[24 + i] != PCM_SUBFORMAT_GUID[i]) return null
                        }
                        // WAV dwChannelMask uses Windows speaker bitmask (bit 0 = FL, etc.);
                        // map directly to Android AudioFormat.CHANNEL_OUT_* (which shifts left by 2).
                        val wavMask = readUInt32LE(fmtBytes, 20).toInt()
                        channelMask = wavMask shl 2
                    }
                    else -> return null
                }
                fmtFound = true
            } else if (id0 == 'd'.code && id1 == 'a'.code && id2 == 't'.code && id3 == 'a'.code) {
                if (!fmtFound) return null
                return WavPcm16Info(
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    channelMask = channelMask,
                    dataBytes = chunkSize
                )
            } else {
                val padded = chunkSize + (chunkSize and 1L)
                if (!skipFully(input, padded)) return null
            }
        }
        return null
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var offset = 0
        while (offset < len) {
            val read = input.read(buf, offset, len - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    private fun skipFully(input: InputStream, bytes: Long): Boolean {
        var remaining = bytes
        val skipBuf = ByteArray(4096)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val toRead = minOf(remaining, skipBuf.size.toLong()).toInt()
                val read = input.read(skipBuf, 0, toRead)
                if (read < 0) return false
                remaining -= read
            }
        }
        return true
    }

    private fun readUInt16LE(buf: ByteArray, offset: Int): Int {
        return (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUInt32LE(buf: ByteArray, offset: Int): Long {
        return (buf[offset].toLong() and 0xFFL) or
            ((buf[offset + 1].toLong() and 0xFFL) shl 8) or
            ((buf[offset + 2].toLong() and 0xFFL) shl 16) or
            ((buf[offset + 3].toLong() and 0xFFL) shl 24)
    }
}

class AudioDecoder(private val context: Context) {
    companion object {
        private const val TAG = "AudioDecoder"
        private const val IDLE_TIMEOUT_US = 2000L
        // 262,144 shorts = 512 KB per buffer (~2.7s stereo @ 48 kHz, ~0.9s 5.1 @ 48 kHz)
        private const val PCM_CHUNK_SAMPLES = 262_144
        // 4 pooled buffers = 2 MB fixed RAM, zero per-chunk allocations
        private const val POOL_SIZE = 4
        private const val PROGRESS_INTERVAL_NS = 50_000_000L // 50 ms wall-clock
    }

    private class PcmChunk(
        val shortData: ShortArray?,
        val floatData: FloatArray?,
        val count: Int
    )

    suspend fun decodeUri(
        uri: Uri,
        analyzeAllChannels: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): Result<DecodedAudioInfo> = withContext(Dispatchers.IO) {
        // Fast path: direct streaming for standard 16-bit PCM WAV (skips MediaCodec Binder IPC)
        try {
            val wavResult = tryDecodeWavPcm16(uri, analyzeAllChannels, onProgress)
            if (wavResult != null) return@withContext Result.success(wavResult)
        } catch (e: CancellationException) {
            Log.d(TAG, "WAV decode cancelled")
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "WAV fast path unavailable, falling back to MediaCodec: ${e.message}")
        }

        decodeWithMediaCodec(uri, analyzeAllChannels, onProgress)
    }

    private suspend fun tryDecodeWavPcm16(
        uri: Uri,
        analyzeAllChannels: Boolean,
        onProgress: (Float) -> Unit
    ): DecodedAudioInfo? = withContext(Dispatchers.IO) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
        pfd.use { descriptor ->
            val rawStream = FileInputStream(descriptor.fileDescriptor)
            val stream = BufferedInputStream(rawStream, 256 * 1024)
            val wavInfo = WavHeaderParser.parsePcm16Header(stream) ?: return@withContext null

            val sampleRate = wavInfo.sampleRate
            val channelCount = wavInfo.channelCount
            val channelList = AudioChannelMapper.getChannelsFromMask(wavInfo.channelMask, channelCount)
            val layoutName = formatLayoutName(channelCount, channelList)

            val analyzer = LufsAnalyzer(sampleRate, channelCount, channelList, analyzeAllChannels)
            val freeBuffers = Channel<ShortArray>(POOL_SIZE)
            repeat(POOL_SIZE) { freeBuffers.trySend(ShortArray(PCM_CHUNK_SAMPLES)) }
            val filledBuffers = Channel<PcmChunk>(POOL_SIZE)

            val startNs = System.nanoTime()
            var analyzerTimeNs = 0L
            val expectedBytes = if (wavInfo.dataBytes > 0L) wavInfo.dataBytes else Long.MAX_VALUE

            val analyzerJob = launch(Dispatchers.Default) {
                var analyzedBytes = 0L
                var lastProgressNs = System.nanoTime()
                try {
                    for (chunk in filledBuffers) {
                        ensureActive()
                        val t0 = System.nanoTime()
                        chunk.shortData?.let { buf ->
                            analyzer.processPcm16(buf, chunk.count)
                            freeBuffers.trySend(buf)
                        }
                        analyzerTimeNs += System.nanoTime() - t0
                        analyzedBytes += chunk.count.toLong() * 2L
                        if (expectedBytes < Long.MAX_VALUE) {
                            val nowNs = System.nanoTime()
                            if (nowNs - lastProgressNs >= PROGRESS_INTERVAL_NS) {
                                onProgress((analyzedBytes.toFloat() / expectedBytes.toFloat()).coerceIn(0f, 0.999f))
                                lastProgressNs = nowNs
                            }
                        }
                    }
                } finally {
                    freeBuffers.close()
                }
            }

            var totalPcmBytes = 0L
            try {
                val byteBuf = ByteArray(PCM_CHUNK_SAMPLES * 2)
                var remainingBytes = expectedBytes
                var carryByte = -1

                while (remainingBytes > 0L) {
                    coroutineContext.ensureActive()
                    val offset = if (carryByte >= 0) {
                        byteBuf[0] = carryByte.toByte()
                        carryByte = -1
                        1
                    } else {
                        0
                    }
                    val maxRead = minOf((byteBuf.size - offset).toLong(), remainingBytes).toInt()
                    val bytesRead = stream.read(byteBuf, offset, maxRead)
                    if (bytesRead <= 0) break

                    remainingBytes -= bytesRead
                    val validBytes = offset + bytesRead
                    val evenBytes = validBytes and 1.inv()
                    if (evenBytes < validBytes) {
                        carryByte = byteBuf[validBytes - 1].toInt() and 0xFF
                    }

                    if (evenBytes > 0) {
                        val numShorts = evenBytes ushr 1
                        val shortBuf = freeBuffers.receive()
                        ByteBuffer.wrap(byteBuf, 0, evenBytes)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                            .get(shortBuf, 0, numShorts)
                        filledBuffers.send(PcmChunk(shortBuf, null, numShorts))
                        totalPcmBytes += evenBytes
                    }
                }

                filledBuffers.close()
                analyzerJob.join()
            } catch (e: Throwable) {
                filledBuffers.close()
                analyzerJob.cancel()
                throw e
            }

            onProgress(1.0f)
            val metrics = analyzer.calculateMetrics()
            val durationSeconds = totalPcmBytes.toDouble() / (sampleRate.toDouble() * channelCount * 2.0)
            val durationUs = (durationSeconds * 1_000_000.0).toLong()
            metrics.duration = durationSeconds

            val totalMs = (System.nanoTime() - startNs) / 1_000_000L
            Log.d(TAG, "WAV fast-path done: total=${totalMs}ms, dsp=${analyzerTimeNs / 1_000_000L}ms, bytes=$totalPcmBytes")

            DecodedAudioInfo(
                sampleRate = sampleRate,
                channelCount = channelCount,
                channelLayout = layoutName,
                channelList = channelList,
                durationUs = durationUs,
                totalPcmBytes = totalPcmBytes,
                durationSeconds = durationSeconds,
                lufsMetrics = metrics
            )
        }
    }

    private suspend fun decodeWithMediaCodec(
        uri: Uri,
        analyzeAllChannels: Boolean,
        onProgress: (Float) -> Unit
    ): Result<DecodedAudioInfo> = withContext(Dispatchers.IO) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            extractor = MediaExtractor()
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Cannot open file descriptor")
            pfd.use {
                extractor.setDataSource(it.fileDescriptor, 0, it.statSize)
            }

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
            val layoutName = formatLayoutName(channelCount, channelList)

            val analyzer = LufsAnalyzer(sampleRate, channelCount, channelList, analyzeAllChannels)

            // Fixed-memory recycling pool (4 x 512 KB = 2 MB) to eliminate GC churn
            val freeBuffers = Channel<ShortArray>(POOL_SIZE)
            repeat(POOL_SIZE) { freeBuffers.trySend(ShortArray(PCM_CHUNK_SAMPLES)) }
            val filledBuffers = Channel<PcmChunk>(POOL_SIZE)

            val startNs = System.nanoTime()
            var analyzerTimeNs = 0L
            val expectedTotalFrames = if (durationUs > 0L) {
                (durationUs.toDouble() / 1_000_000.0) * sampleRate.toDouble()
            } else {
                0.0
            }

            val analyzerJob = launch(Dispatchers.Default) {
                var analyzedFrames = 0L
                var lastProgressNs = System.nanoTime()
                try {
                    for (chunk in filledBuffers) {
                        ensureActive()
                        val t0 = System.nanoTime()
                        if (chunk.shortData != null) {
                            analyzer.processPcm16(chunk.shortData, chunk.count)
                            freeBuffers.trySend(chunk.shortData)
                        } else if (chunk.floatData != null) {
                            analyzer.processSamples(chunk.floatData)
                        }
                        analyzerTimeNs += System.nanoTime() - t0
                        analyzedFrames += (chunk.count / channelCount).toLong()
                        if (expectedTotalFrames > 0.0) {
                            val nowNs = System.nanoTime()
                            if (nowNs - lastProgressNs >= PROGRESS_INTERVAL_NS) {
                                onProgress((analyzedFrames.toDouble() / expectedTotalFrames).toFloat().coerceIn(0f, 0.999f))
                                lastProgressNs = nowNs
                            }
                        }
                    }
                } finally {
                    freeBuffers.close()
                }
            }

            var totalPcmBytes = 0L
            try {
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                val bufferInfo = MediaCodec.BufferInfo()
                var inputEos = false
                var outputEos = false
                var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

                var currentBuf = freeBuffers.receive()
                var writeIdx = 0

                suspend fun drainOutputBuffer(outputId: Int) {
                    val outputBuf = codec.getOutputBuffer(outputId)
                    if (outputBuf != null && bufferInfo.size > 0) {
                        outputBuf.position(bufferInfo.offset)
                        outputBuf.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuf.order(ByteOrder.LITTLE_ENDIAN)

                        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            // Flush any queued shorts first so sample order is preserved
                            if (writeIdx > 0) {
                                filledBuffers.send(PcmChunk(currentBuf, null, writeIdx))
                                currentBuf = freeBuffers.receive()
                                writeIdx = 0
                            }
                            val floatBuf = outputBuf.asFloatBuffer()
                            val floats = FloatArray(floatBuf.remaining())
                            floatBuf.get(floats)
                            filledBuffers.send(PcmChunk(null, floats, floats.size))
                        } else {
                            // Bulk native ShortBuffer copy (no byte-by-byte DirectByteBuffer JNI calls)
                            val shortBuf = outputBuf.asShortBuffer()
                            while (shortBuf.hasRemaining()) {
                                val toRead = minOf(shortBuf.remaining(), currentBuf.size - writeIdx)
                                shortBuf.get(currentBuf, writeIdx, toRead)
                                writeIdx += toRead
                                if (writeIdx == currentBuf.size) {
                                    filledBuffers.send(PcmChunk(currentBuf, null, writeIdx))
                                    currentBuf = freeBuffers.receive()
                                    writeIdx = 0
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

                while (!outputEos) {
                    coroutineContext.ensureActive()
                    var madeProgress = false

                    // 1. Feed all currently available input buffers without blocking
                    while (!inputEos) {
                        val inputId = codec.dequeueInputBuffer(0L)
                        if (inputId < 0) break
                        madeProgress = true
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
                            }
                        }
                    }

                    // 2. Drain all currently available output buffers without blocking
                    while (!outputEos) {
                        val outputId = codec.dequeueOutputBuffer(bufferInfo, 0L)
                        if (outputId >= 0) {
                            madeProgress = true
                            drainOutputBuffer(outputId)
                        } else if (outputId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            madeProgress = true
                            val outFmt = codec.outputFormat
                            if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }
                        } else {
                            break
                        }
                    }

                    // 3. Only wait briefly if neither input nor output made progress
                    if (!madeProgress && !outputEos) {
                        val outputId = codec.dequeueOutputBuffer(bufferInfo, IDLE_TIMEOUT_US)
                        if (outputId >= 0) {
                            drainOutputBuffer(outputId)
                        } else if (outputId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val outFmt = codec.outputFormat
                            if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }
                        }
                    }
                }

                // Push final partial buffer
                if (writeIdx > 0) {
                    filledBuffers.send(PcmChunk(currentBuf, null, writeIdx))
                } else {
                    freeBuffers.trySend(currentBuf)
                }

                filledBuffers.close()
                analyzerJob.join()
            } catch (e: Throwable) {
                filledBuffers.close()
                analyzerJob.cancel()
                throw e
            }

            onProgress(1.0f)
            val metrics = analyzer.calculateMetrics()
            metrics.duration = durationUs / 1_000_000.0

            val totalMs = (System.nanoTime() - startNs) / 1_000_000L
            Log.d(TAG, "MediaCodec ($mime) done: total=${totalMs}ms, dsp=${analyzerTimeNs / 1_000_000L}ms, bytes=$totalPcmBytes")

            Result.success(
                DecodedAudioInfo(
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    channelLayout = layoutName,
                    channelList = channelList,
                    durationUs = durationUs,
                    totalPcmBytes = totalPcmBytes,
                    durationSeconds = durationUs / 1_000_000.0,
                    lufsMetrics = metrics
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException) {
                Log.d(TAG, "Decode cancelled")
                throw e
            }
            Log.e(TAG, "Decode failed", e)
            Result.failure(e)
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) { }
            try { extractor?.release() } catch (_: Exception) { }
        }
    }

    private fun formatLayoutName(channelCount: Int, channelList: List<ChannelMetadata>): String =
        when (channelCount) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> if (channelList.any { it.name.contains("LFE") }) "5.1 Surround" else "6.0"
            8 -> "7.1 Surround"
            else -> "$channelCount Channels"
        }
}
