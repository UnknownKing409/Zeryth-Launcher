/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.recorder

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "GameRecorder"
private const val FRAME_RATE = 30
private const val VIDEO_BIT_RATE = 6_000_000   // 6 Mbps
private const val AUDIO_SAMPLE_RATE = 44_100
private const val AUDIO_BIT_RATE = 128_000
private const val AUDIO_CHANNELS = 2            // stereo

/**
 * Singleton that manages a gameplay video recording session.
 *
 * ## Surface-capture strategy (video — unchanged)
 * Frames are captured from the game's rendering [View] (a [SurfaceView] or
 * [TextureView]) using [PixelCopy.request] / [TextureView.getBitmap], then drawn
 * onto a [MediaCodec] H.264 encoder's input surface.  All Compose overlay layers
 * are absent from the capture.
 *
 * ## Audio — internal game audio via AudioPlaybackCapture
 * Instead of [android.media.MediaRecorder.AudioSource.MIC], audio is captured from
 * the device's internal playback stream using [AudioPlaybackCaptureConfiguration]
 * backed by the caller-supplied [MediaProjection] token.  This records actual
 * Minecraft sounds and music rather than ambient room noise.
 *
 * The raw PCM read from [AudioRecord] is encoded to AAC in real time by a second
 * [MediaCodec] instance.  Both the H.264 video track and the AAC audio track are
 * written to an MP4 container by [MediaMuxer].  The muxer is started only after
 * both tracks have confirmed their output format, which avoids partial-header writes.
 *
 * ## Pause / Resume
 * [pause] freezes the frame-capture loop and stops reading from [AudioRecord].
 * Wall-clock paused duration is accumulated in [totalPausedUs] and subtracted from
 * every video presentation timestamp so the output file has no timestamp gap.
 * Audio presentation timestamps are derived from the running sample count, which
 * naturally skips paused periods.
 *
 * ## Elapsed timer
 * [elapsedMs] is a [StateFlow] that ticks every ~250 ms, pauses when recording is
 * paused, and resets to 0 on stop.
 *
 * ## Output
 * Files land in `Movies/Zeryth Recordings/` via [MediaStore].
 */
object GameRecorder {

    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    // ── Elapsed timer ─────────────────────────────────────────────────────────
    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val timerScope  = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val encodeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var timerJob:       Job? = null
    private var videoEncodeJob: Job? = null
    private var audioJob:       Job? = null

    @Volatile private var accumulatedMs = 0L
    @Volatile private var resumeTimeMs  = 0L

    // ── Video pipeline ────────────────────────────────────────────────────────
    private var videoCodec:     MediaCodec? = null
    @Volatile private var inputSurface: android.view.Surface? = null
    @Volatile private var videoTrackIndex = -1

    // ── Audio pipeline ────────────────────────────────────────────────────────
    private var audioRecord:    AudioRecord?  = null
    private var audioCodec:     MediaCodec?   = null
    @Volatile private var audioTrackIndex = -1

    // ── Muxer ─────────────────────────────────────────────────────────────────
    private var muxer:          MediaMuxer?   = null
    @Volatile private var muxerStarted = false
    private val muxerLock = Any()

    // ── MediaProjection ───────────────────────────────────────────────────────
    private var mediaProjection: MediaProjection? = null

    // ── Capture thread ────────────────────────────────────────────────────────
    private var captureThread:  HandlerThread? = null
    private var captureHandler: Handler?       = null
    private val isCapturing = AtomicBoolean(false)

    // ── Timestamp tracking (for pause-gap removal) ────────────────────────────
    @Volatile private var firstVideoTimestampUs = Long.MIN_VALUE
    @Volatile private var totalPausedUs         = 0L
    @Volatile private var pauseStartMs          = 0L

    // ── MediaStore ────────────────────────────────────────────────────────────
    @Volatile private var pendingUri:  android.net.Uri? = null
    @Volatile private var pendingFile: File?            = null

    // ─────────────────────────────────────────────────────────────── API ──────

    /**
     * Start a new recording session.
     *
     * @param context    Android context (used for [MediaStore] and file creation).
     * @param projection A [MediaProjection] token obtained from
     *                   [android.media.projection.MediaProjectionManager.getMediaProjection].
     *                   Used to configure [AudioPlaybackCaptureConfiguration] so that
     *                   actual game audio is captured instead of microphone audio.
     *                   The caller is responsible for releasing this projection when
     *                   recording stops; [GameRecorder] calls [MediaProjection.stop]
     *                   inside [cleanup].
     */
    fun start(context: Context, projection: MediaProjection) {
        if (_state.value != RecordingState.IDLE) return

        val view = GameSurfaceRegistry.getView()
        if (view == null) {
            Log.e(TAG, "No game surface registered — cannot start recording")
            return
        }

        val w = (view.width.coerceAtLeast(2)  / 2) * 2
        val h = (view.height.coerceAtLeast(2) / 2) * 2

        try {
            val (uri, file) = createOutputEntry(context)
            pendingUri  = uri
            pendingFile = file

            mediaProjection = projection

            // ── Reset shared state ────────────────────────────────────────────
            muxerStarted          = false
            videoTrackIndex       = -1
            audioTrackIndex       = -1
            firstVideoTimestampUs = Long.MIN_VALUE
            totalPausedUs         = 0L

            // ── MediaMuxer → MP4 ──────────────────────────────────────────────
            val fd = context.contentResolver.openFileDescriptor(uri, "w")!!.fileDescriptor
            muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // ── H.264 video codec (surface-based input) ───────────────────────
            val videoFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE,    VIDEO_BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE,  FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1-s keyframe interval
            }
            videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { c ->
                c.configure(videoFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = c.createInputSurface()
                c.start()
            }

            // ── AAC audio codec ───────────────────────────────────────────────
            val audioFmt = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE,       AUDIO_BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, audioBufferSize())
            }
            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { c ->
                c.configure(audioFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                c.start()
            }

            // ── AudioRecord via AudioPlaybackCapture ──────────────────────────
            audioRecord = buildAudioRecord(projection)

            // ── Capture thread (PixelCopy callbacks) ──────────────────────────
            captureThread  = HandlerThread("GameRecorder-Capture").also { it.start() }
            captureHandler = Handler(captureThread!!.looper)

            isCapturing.set(true)
            _state.value = RecordingState.RECORDING

            accumulatedMs = 0L
            resumeTimeMs  = System.currentTimeMillis()
            _elapsedMs.value = 0L
            startTimerTick()

            startVideoEncodeJob()
            startAudioJob()
            scheduleNextFrame()

            Log.i(TAG, "Recording started ${w}x${h} — audio via AudioPlaybackCapture")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}")
            cleanup()
        }
    }

    /** Pause the active recording (frame loop and audio read both freeze). */
    fun pause() {
        if (_state.value != RecordingState.RECORDING) return
        try {
            isCapturing.set(false)
            pauseStartMs   = System.currentTimeMillis()
            accumulatedMs += System.currentTimeMillis() - resumeTimeMs
            timerJob?.cancel(); timerJob = null
            _state.value = RecordingState.PAUSED
            Log.i(TAG, "Recording paused at ${accumulatedMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause: ${e.message}")
        }
    }

    /** Resume after a [pause]. */
    fun resume() {
        if (_state.value != RecordingState.PAUSED) return
        try {
            // Accumulate pause wall-clock duration for video timestamp correction.
            totalPausedUs += (System.currentTimeMillis() - pauseStartMs) * 1_000L
            isCapturing.set(true)
            resumeTimeMs = System.currentTimeMillis()
            startTimerTick()
            _state.value = RecordingState.RECORDING
            scheduleNextFrame()
            Log.i(TAG, "Recording resumed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume: ${e.message}")
        }
    }

    /**
     * Stop the recording, finalise the MP4, and publish it via MediaStore so it
     * appears in the device gallery.
     *
     * @param context Android context required for MediaStore update.
     */
    fun stopAndSave(context: Context) {
        val current = _state.value
        if (current == RecordingState.IDLE || current == RecordingState.STOPPING) return
        _state.value = RecordingState.STOPPING
        isCapturing.set(false)
        timerJob?.cancel(); timerJob = null
        // Drain and mux on the capture thread so any in-flight PixelCopy completes first.
        captureHandler?.post { finalise(context) }
            ?: run { finalise(context) }
    }

    // ──────────────────────────────────────── Video encoder drain job ─────────

    private fun startVideoEncodeJob() {
        videoEncodeJob = encodeScope.launch {
            val bufInfo = MediaCodec.BufferInfo()
            val codec   = videoCodec ?: return@launch
            while (isActive) {
                val idx = codec.dequeueOutputBuffer(bufInfo, 10_000L)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxerLock) {
                            videoTrackIndex = muxer!!.addTrack(codec.outputFormat)
                            tryStartMuxerLocked()
                        }
                    }
                    idx >= 0 -> {
                        val isConfig = bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val isEos    = bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM  != 0
                        if (!isConfig && bufInfo.size > 0) {
                            val adjusted = adjustVideoTimestampUs(bufInfo.presentationTimeUs)
                            if (adjusted >= 0) {
                                val buf = codec.getOutputBuffer(idx)!!
                                bufInfo.presentationTimeUs = adjusted
                                synchronized(muxerLock) {
                                    if (muxerStarted)
                                        muxer!!.writeSampleData(videoTrackIndex, buf, bufInfo)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (isEos) break
                    }
                }
            }
        }
    }

    // ────────────────────────────────────── Audio capture + encode job ────────

    private fun startAudioJob() {
        audioJob = encodeScope.launch {
            val ar      = audioRecord  ?: return@launch
            val ac      = audioCodec   ?: return@launch
            val bufSize = audioBufferSize()
            val pcmBuf  = ByteArray(bufSize)
            var sampleCount = 0L    // running sample-frame count for PTS generation

            ar.startRecording()
            try {
                while (isActive &&
                    _state.value != RecordingState.STOPPING &&
                    _state.value != RecordingState.IDLE
                ) {
                    if (_state.value == RecordingState.PAUSED) {
                        delay(30L)
                        continue
                    }

                    val read = ar.read(pcmBuf, 0, bufSize)
                    if (read <= 0) continue

                    // Feed raw PCM to AAC encoder
                    val inputIdx = ac.dequeueInputBuffer(5_000L)
                    if (inputIdx >= 0) {
                        val inBuf = ac.getInputBuffer(inputIdx)!!
                        inBuf.clear()
                        inBuf.put(pcmBuf, 0, read)
                        // PTS in µs derived from the running sample count (always monotonic,
                        // skips paused periods naturally because we don't increment while paused).
                        val pts = sampleCount * 1_000_000L / AUDIO_SAMPLE_RATE
                        ac.queueInputBuffer(inputIdx, 0, read, pts, 0)
                        // 16-bit stereo PCM: 4 bytes per sample-frame
                        sampleCount += read.toLong() / (2 * AUDIO_CHANNELS)
                    }

                    drainAudioCodec(ac, endOfStream = false)
                }
            } finally {
                // Signal EOS to the AAC encoder and flush its remaining output
                val eosIdx = ac.dequeueInputBuffer(5_000L)
                if (eosIdx >= 0) {
                    ac.queueInputBuffer(eosIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drainAudioCodec(ac, endOfStream = true)
                runCatching { ar.stop() }
            }
        }
    }

    /**
     * Drain encoded AAC output from the audio [MediaCodec] and write it to the muxer.
     *
     * When [endOfStream] is false the drain is non-blocking (timeout 0) and returns
     * after at most one buffer so the capture loop stays responsive.  When [endOfStream]
     * is true it blocks until the EOS buffer arrives.
     */
    private fun drainAudioCodec(ac: MediaCodec, endOfStream: Boolean) {
        val bufInfo = MediaCodec.BufferInfo()
        while (true) {
            val idx = ac.dequeueOutputBuffer(bufInfo, if (endOfStream) 10_000L else 0L)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        audioTrackIndex = muxer!!.addTrack(ac.outputFormat)
                        tryStartMuxerLocked()
                    }
                }
                idx >= 0 -> {
                    val isConfig = bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val isEos    = bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM  != 0
                    if (!isConfig && bufInfo.size > 0) {
                        val buf = ac.getOutputBuffer(idx)!!
                        synchronized(muxerLock) {
                            if (muxerStarted)
                                muxer!!.writeSampleData(audioTrackIndex, buf, bufInfo)
                        }
                    }
                    ac.releaseOutputBuffer(idx, false)
                    // In non-EOS drain: process one buffer then yield back to caller.
                    // In EOS drain: keep looping until the EOS sentinel arrives.
                    if (isEos || !endOfStream) return
                }
                else -> return  // INFO_TRY_AGAIN_LATER or no more output
            }
        }
    }

    // ───────────────────────────────── Muxer start (call under muxerLock) ─────

    /**
     * Start the [MediaMuxer] once both the video and audio tracks have reported their
     * output format.  Must be called while holding [muxerLock].
     */
    private fun tryStartMuxerLocked() {
        if (videoTrackIndex >= 0 && audioTrackIndex >= 0 && !muxerStarted) {
            muxer!!.start()
            muxerStarted = true
            Log.i(TAG, "MediaMuxer started (video=$videoTrackIndex, audio=$audioTrackIndex)")
        }
    }

    // ──────────────────────────────────── Video timestamp normalisation ────────

    /**
     * Normalise a raw presentation timestamp from the video [MediaCodec] surface.
     *
     * Raw timestamps correspond to the wall-clock time at which each frame was
     * drawn ([System.nanoTime] / 1000).  We subtract:
     *  - the first frame's timestamp so that the output starts at 0, and
     *  - the accumulated paused duration so that gaps in the frame stream do not
     *    produce timestamp jumps in the output file.
     *
     * Returns a negative value for frames that arrive before both tracks are ready
     * (the caller must discard those).
     */
    private fun adjustVideoTimestampUs(rawUs: Long): Long {
        if (firstVideoTimestampUs == Long.MIN_VALUE) firstVideoTimestampUs = rawUs
        return rawUs - firstVideoTimestampUs - totalPausedUs
    }

    // ──────────────────────────────────────────── Frame capture loop ──────────

    private fun scheduleNextFrame() {
        if (!isCapturing.get() || _state.value != RecordingState.RECORDING) return
        captureHandler?.postDelayed({ captureFrame() }, 1000L / FRAME_RATE)
    }

    private fun captureFrame() {
        if (!isCapturing.get()) return
        if (_state.value != RecordingState.RECORDING) return

        val view    = GameSurfaceRegistry.getView()
        val surface = inputSurface
        if (view == null || surface == null) { scheduleNextFrame(); return }

        when (view) {
            is SurfaceView -> captureFromSurfaceView(view, surface)
            is TextureView -> captureFromTextureView(view, surface)
            else           -> scheduleNextFrame()
        }
    }

    private fun captureFromSurfaceView(sv: SurfaceView, out: android.view.Surface) {
        val bmp = Bitmap.createBitmap(sv.width.coerceAtLeast(1), sv.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888)
        PixelCopy.request(sv, bmp, { result ->
            if (result == PixelCopy.SUCCESS) drawToSurface(bmp, out)
            bmp.recycle()
            scheduleNextFrame()
        }, captureHandler!!)
    }

    private fun captureFromTextureView(tv: TextureView, out: android.view.Surface) {
        val bmp = tv.getBitmap(tv.width.coerceAtLeast(1), tv.height.coerceAtLeast(1))
        if (bmp != null) { drawToSurface(bmp, out); bmp.recycle() }
        scheduleNextFrame()
    }

    @Suppress("DEPRECATION")
    private fun drawToSurface(bmp: Bitmap, surface: android.view.Surface) {
        runCatching {
            val canvas = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) surface.lockHardwareCanvas()
                else surface.lockCanvas(null)
            } catch (_: Exception) { surface.lockCanvas(null) }
            canvas.drawBitmap(bmp, 0f, 0f, null)
            surface.unlockCanvasAndPost(canvas)
        }.onFailure { Log.w(TAG, "drawToSurface failed: ${it.message}") }
    }

    // ────────────────────────────────────────────────────────── Timer ─────────

    private fun startTimerTick() {
        timerJob?.cancel()
        timerJob = timerScope.launch {
            while (isActive) {
                _elapsedMs.value = accumulatedMs + (System.currentTimeMillis() - resumeTimeMs)
                delay(250L)
            }
        }
    }

    // ──────────────────────────────────── AudioRecord construction ────────────

    /**
     * Build an [AudioRecord] configured to capture internal device audio playback
     * (game sounds, music) via [AudioPlaybackCaptureConfiguration].
     *
     * We match USAGE_GAME, USAGE_MEDIA, and USAGE_UNKNOWN to maximise the chance
     * of capturing Minecraft's OpenAL output regardless of how the JVM process
     * reports its audio usage attribute.
     *
     * Note: [android.Manifest.permission.RECORD_AUDIO] is still required by the
     * [AudioRecord] constructor even though no microphone is accessed.
     */
    @Suppress("MissingPermission")
    private fun buildAudioRecord(projection: MediaProjection): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
            .build()

        return AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(audioBufferSize())
            .build()
    }

    private fun audioBufferSize(): Int = maxOf(
        AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ),
        8192
    )

    // ──────────────────────────────────────────────────────── Finalise ─────────

    private fun finalise(context: Context) {
        try {
            // Signal EOS to the video codec through its input surface; the
            // videoEncodeJob will drain the remaining frames then exit.
            runCatching { videoCodec?.signalEndOfInputStream() }

            // Cancel the audio job — its finally block will flush the AAC encoder.
            audioJob?.cancel()

            // Wait up to 5 s for both encode jobs to finish draining.
            val deadline = System.currentTimeMillis() + 5_000L
            while ((videoEncodeJob?.isActive == true || audioJob?.isActive == true)
                && System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(50L)
            }

            // Stop and release the muxer.
            synchronized(muxerLock) {
                if (muxerStarted) {
                    runCatching { muxer?.stop() }
                    muxerStarted = false
                }
                runCatching { muxer?.release() }
                muxer = null
            }

            // Publish the MediaStore entry (clear IS_PENDING).
            pendingUri?.let { uri ->
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                runCatching { context.contentResolver.update(uri, values, null, null) }
                Log.i(TAG, "Recording saved: $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Finalise error: ${e.message}")
        } finally {
            cleanup()
        }
    }

    private fun cleanup() {
        isCapturing.set(false)

        videoEncodeJob?.cancel(); videoEncodeJob = null
        audioJob?.cancel();       audioJob       = null

        runCatching { videoCodec?.stop()    }
        runCatching { videoCodec?.release() }
        videoCodec = null

        runCatching { inputSurface?.release() }
        inputSurface = null

        runCatching { audioRecord?.stop()    }
        runCatching { audioRecord?.release() }
        audioRecord = null

        runCatching { audioCodec?.stop()    }
        runCatching { audioCodec?.release() }
        audioCodec = null

        synchronized(muxerLock) {
            if (muxerStarted) { runCatching { muxer?.stop() }; muxerStarted = false }
            runCatching { muxer?.release() }
            muxer = null
        }

        mediaProjection?.stop()
        mediaProjection = null

        captureThread?.quit()
        captureThread  = null
        captureHandler = null

        timerJob?.cancel(); timerJob = null
        _elapsedMs.value  = 0L
        accumulatedMs     = 0L
        videoTrackIndex   = -1
        audioTrackIndex   = -1
        firstVideoTimestampUs = Long.MIN_VALUE
        totalPausedUs     = 0L
        pendingUri        = null
        pendingFile       = null

        _state.value = RecordingState.IDLE
    }

    // ──────────────────────────────────────────────── MediaStore output ────────

    private fun createOutputEntry(context: Context): Pair<android.net.Uri, File> {
        val ts       = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ZerythRec_$ts.mp4"
        val relPath  = "Movies/Zeryth Recordings"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE,    "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, relPath)
            put(MediaStore.Video.Media.IS_PENDING,   1)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        ) ?: throw IOException("Failed to create MediaStore entry for recording")

        val publicMovies = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_MOVIES
        )
        return Pair(uri, File(publicMovies, "Zeryth Recordings/$fileName"))
    }
}
