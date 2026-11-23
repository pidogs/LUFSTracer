# LUFS Tracer

LUFS Tracer is an Android app that measures ITU-R BS.1770 loudness and true peak on local audio files. Everything runs offline on the phone using Kotlin and Jetpack Compose.

Check master levels or surround stems right on Android without moving files to a desktop DAW or uploading them anywhere.

## What it measures

- **Integrated loudness.** Two-stage K-weighting filters feed 400 ms blocks stepping every 100 ms. An absolute gate drops blocks below -70 LUFS, and a relative gate drops blocks more than 10 LU below the first-pass mean.
- **True peak and sample peak.** `TruePeakDetector` runs the ITU-R BS.1770-5 Annex 2 48th-order, 4-phase polyphase FIR filter at 4x oversampling to report dBTP next to raw dBFS sample peaks.
- **Short-term and momentary max.** Reports the loudest 3 s sliding window and the loudest 400 ms block in LUFS.
- **Dynamic range, PLR, and PSR.** Computes the 95th minus 10th percentile spread of 3 s short-term loudness in LU, plus peak-to-loudness and peak-to-short-term ratios.
- **Balance and thresholds.** Reports the relative gate threshold in dBFS and the L/R loudness shift in dB for files with two or more channels.
- **Short-term histogram.** A Compose Canvas plots 3 s short-term loudness in 0.1 dB buckets and draws a dashed line at integrated loudness.
- **Per-channel tabs.** Switch between combined results and individual tabs for mono, stereo, 5.1, or 7.1 tracks. Front and center channels carry a 1.0x weight, surround channels carry 1.41x, and LFE channels stay at 0.0x in combined loudness. LFE tabs still report their own measurements, and overall true peak includes LFE.

## Formats and decoding

The document picker accepts MP3, WAV, MP4/M4A, AAC, FLAC, and OGG. Android's `MediaExtractor` and `MediaCodec` decode compressed formats and handle both 16-bit integer and 32-bit float PCM output.

Uncompressed 16-bit little-endian PCM WAV files bypass `MediaCodec`. `WavHeaderParser` reads the RIFF header, including `WAVE_FORMAT_EXTENSIBLE` speaker masks, and reads raw PCM off the file descriptor in 256 KB buffered chunks. Skipping Binder IPC cuts decode overhead on large WAVs. Any WAV variant the parser does not handle, such as 24-bit PCM or ADPCM, falls back to `MediaCodec`.

## How the analyzer stays fast

Decoding long lossless or surround files on a phone gets slow if you allocate arrays per chunk or run an oversampling filter on every quiet sample. LUFS Tracer keeps the hot path small and predictable:

- Four pooled 512 KB `ShortArray` buffers, 2 MB total, pass decoded PCM from the I/O thread to the `Dispatchers.Default` analyzer coroutine so the pipeline avoids per-chunk allocations.
- Channels run concurrently in 4,096-frame tiles, or 32 KB of doubles per channel, which fits inside L1 cache.
- Both K-weighting biquads and the 100 ms sub-block sum-of-squares run in one fused loop with no intermediate arrays for standard sample rates. Odd rates where 400 ms does not equal four times 100 ms, like 11,025 Hz, fall back to an overlap buffer.
- `TruePeakDetector` skips the 48-tap FIR whenever a 12-sample window cannot beat the current peak. Because the maximum phase L1 norm of the filter is 2.022827, any sample below `currentPeak * 0.494` cannot raise the interpolated peak. Unit tests verify this against brute-force evaluation to `1e-12` dB.

## Caching and progress tracking

Before decoding a file, `AudioViewModel` hashes its first 100 KB with SHA-256 and checks `lufs_tracer_cache.db`. A cache hit loads the saved `DecodedAudioInfo` without decoding again. The top-bar overflow menu lets you pause the cache until app restart when you want a fresh run, or clear both the SQLite database and the recent files list. Up to 50 recent analyses stay in `lufs_history.json` on the home screen with their integrated LUFS, true peak, timestamp, and processing duration.

VBR MP3 files often carry inaccurate `KEY_DURATION` metadata, so `AudioDecoder` wraps the file in `TrackingFileDataSource` to measure real byte position. `EtaEstimator` tracks throughput over a 10 s sliding window, waits through startup jitter, adds a 25% early buffer that tapers to zero by 85%, and applies asymmetric exponential smoothing. Estimates start conservative and glide down instead of bouncing around or climbing after CPU boost clocks drop.

While decoding runs, the screen stays awake and the app posts a low-priority progress notification if you grant notification permission. You can cancel from the active screen or with the system back button.

## Trade-offs and notes

- **100 KB cache hashes.** Hashing only the first 100 KB keeps cache lookups fast on multi-gigabyte WAVs, though two files with identical opening 100 KB bytes will collide. Pause the DB from the overflow menu to force a fresh scan on an edited file.
- **Storage permission.** Android 11 and newer prompt for `MANAGE_EXTERNAL_STORAGE` before opening the picker. Android 8 through 10 request `READ_EXTERNAL_STORAGE`.
- **Dynamic range calculation.** Dynamic range measures the raw 95th minus 10th percentile of all 3 s short-term values and does not apply EBU Tech 3342 relative gating to the short-term series.

## Building and testing

The app requires Android 8.0 or newer, with min SDK 26 and compile/target SDK 36. It builds with Gradle 9.4.1, Android Gradle Plugin 9.2.1, Kotlin 2.2.10, and a JDK 21 Gradle daemon.

Point `sdk.dir` in `local.properties` at your Android SDK directory, then build a debug APK:

```bash
./gradlew assembleDebug
```

Run the unit tests:

```bash
./gradlew testDebugUnitTest
```

`LufsAnalyzerTest`, `TruePeakDetectorTest`, and `EtaEstimatorTest` check golden outputs across mono, stereo, and 5.1 surround signals at 22,050 Hz, 44,100 Hz, 48,000 Hz, and 96,000 Hz, plus odd chunk boundaries, PCM-16 equivalence, and multithreaded determinism.