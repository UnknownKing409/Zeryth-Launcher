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
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
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
private const val VIDEO_BIT_RATE = 6_000_000 // 6 Mbps

/**
 * Singleton that manages a gameplay video recording session.
 *
 * ## Surface-capture strategy
 * Frames are captured from the game's rendering [View] (a [SurfaceView] or
 * [TextureView]) using:
 *  - [PixelCopy.request] for [SurfaceView] — reads directly from the GPU
 *    surface buffer with no compositing step, so **all Compose overlay layers
 *    (controls, game-ball, FPS/RAM stats) are completely absent** from the capture.
 *  - [TextureView.getBitmap] for [TextureView] — similarly reads the raw texture
 *    content without overlays.
 *
 * Captured bitmaps are rendered onto [MediaRecorder]'s input Surface with
 * [android.view.Surface.lockCanvas] / [android.view.Surface.unlockCanvasAndPost],
 * and [MediaRecorder] handles all H.264 encoding and MP4 muxing internally.
 *
 * ## Pause / Resume
 * Uses [MediaRecorder.pause] / [MediaRecorder.resume] (API 24+, within our
 * minSdk 26), so the encoder gap is seamless in the output file.
 *
 * ## Output
 * Files land in `Movies/Zeryth Recordings/` via [MediaStore], making them
 * immediately visible in the device gallery without extra permissions on API 29+.
 */
object GameRecorder {
    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    // MediaRecorder and its input surface
    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var inputSurface: android.view.Surface? = null

    // Capture background thread
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    // Lifecycle flags
    private val isCapturing = AtomicBoolean(false)

    // Saved output info for MediaStore finalisation
    @Volatile private var pendingUri: android.net.Uri? = null
    @Volatile private var pendingFile: File? = null

    // ─────────────────────────────────────────────────────────────── API ──────

    /**
     * Start a new recording.
     *
     * @param context Android context (used for [MediaStore] and file creation).
     * @param withMic If true, microphone audio is mixed in via [MediaRecorder.AudioSource.MIC].
     */
    fun start(context: Context, withMic: Boolean = false) {
        if (_state.value != RecordingState.IDLE) return

        val view = GameSurfaceRegistry.getView()
        if (view == null) {
            Log.e(TAG, "No game surface registered — cannot start recording")
            return
        }

        // Even dimensions required by most encoders
        val w = (view.width.coerceAtLeast(2) / 2) * 2
        val h = (view.height.coerceAtLeast(2) / 2) * 2

        try {
            val (uri, file) = createOutputEntry(context)
            pendingUri = uri
            pendingFile = file

            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.apply {
                if (withMic) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                if (withMic) setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(w, h)
                setVideoFrameRate(FRAME_RATE)
                setVideoEncodingBitRate(VIDEO_BIT_RATE)
                setOutputFile(
                    context.contentResolver.openFileDescriptor(uri, "w")!!.fileDescriptor
                )
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaRecorder error what=$what extra=$extra")
                    cleanup()
                }
                prepare()
            }

            inputSurface = rec.surface
            recorder = rec
            rec.start()

            captureThread = HandlerThread("GameRecorder-Capture").also { it.start() }
            captureHandler = Handler(captureThread!!.looper)

            isCapturing.set(true)
            _state.value = RecordingState.RECORDING
            scheduleNextFrame()

            Log.i(TAG, "Recording started ${w}x${h} mic=$withMic")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}")
            cleanup()
        }
    }

    /** Pause the recording (encoder gap is seamless). */
    fun pause() {
        if (_state.value != RecordingState.RECORDING) return
        try {
            recorder?.pause()
            _state.value = RecordingState.PAUSED
            Log.i(TAG, "Recording paused")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause: ${e.message}")
        }
    }

    /** Resume after a [pause]. */
    fun resume() {
        if (_state.value != RecordingState.PAUSED) return
        try {
            recorder?.resume()
            _state.value = RecordingState.RECORDING
            scheduleNextFrame()   // restart the frame loop
            Log.i(TAG, "Recording resumed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume: ${e.message}")
        }
    }

    /**
     * Stop the recording, finalise the MP4, and publish it via MediaStore so it
     * appears in the system gallery.
     *
     * @param context Android context required for MediaStore update.
     */
    fun stopAndSave(context: Context) {
        val current = _state.value
        if (current == RecordingState.IDLE || current == RecordingState.STOPPING) return
        _state.value = RecordingState.STOPPING
        isCapturing.set(false)

        // Finalise on the capture thread so any in-flight PixelCopy callback completes first.
        captureHandler?.post { finalise(context) }
            ?: run { finalise(context) }     // no thread? finalise inline
    }

    // ──────────────────────────────────────────────────────── Frame capture ───

    private fun scheduleNextFrame() {
        if (!isCapturing.get() || _state.value != RecordingState.RECORDING) return
        captureHandler?.postDelayed({ captureFrame() }, (1000L / FRAME_RATE))
    }

    private fun captureFrame() {
        if (!isCapturing.get()) return
        if (_state.value != RecordingState.RECORDING) return   // paused or stopping

        val view = GameSurfaceRegistry.getView()
        val surface = inputSurface
        if (view == null || surface == null) { scheduleNextFrame(); return }

        when (view) {
            is SurfaceView -> captureFromSurfaceView(view, surface)
            is TextureView -> captureFromTextureView(view, surface)
            else           -> scheduleNextFrame()
        }
    }

    private fun captureFromSurfaceView(sv: SurfaceView, out: android.view.Surface) {
        val w = sv.width.coerceAtLeast(1)
        val h = sv.height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
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

    /** Draw a bitmap frame onto the MediaRecorder's input surface. */
    @Suppress("DEPRECATION")
    private fun drawToSurface(bmp: Bitmap, surface: android.view.Surface) {
        runCatching {
            val canvas = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) surface.lockHardwareCanvas()
                else surface.lockCanvas(null)
            } catch (_: Exception) {
                surface.lockCanvas(null)
            }
            canvas.drawBitmap(bmp, 0f, 0f, null)
            surface.unlockCanvasAndPost(canvas)
        }.onFailure { Log.w(TAG, "drawToSurface failed: ${it.message}") }
    }

    // ─────────────────────────────────────────────────────── Finalisation ────

    private fun finalise(context: Context) {
        try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            inputSurface?.release()
            inputSurface = null

            // Mark the MediaStore entry as no longer pending
            pendingUri?.let { uri ->
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                try {
                    context.contentResolver.update(uri, values, null, null)
                    Log.i(TAG, "Recording saved: $uri")
                } catch (e: Exception) {
                    Log.e(TAG, "MediaStore update failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Finalise error: ${e.message}")
        } finally {
            captureThread?.quit()
            captureThread = null
            captureHandler = null
            pendingUri = null
            pendingFile = null
            _state.value = RecordingState.IDLE
        }
    }

    private fun cleanup() {
        isCapturing.set(false)
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        captureThread?.quit()
        captureThread = null
        captureHandler = null
        _state.value = RecordingState.IDLE
    }

    // ───────────────────────────────────────────────────── MediaStore output ─

    /**
     * Creates a pending MediaStore video entry and returns its [android.net.Uri]
     * plus a [File] handle (for display in [com.movtery.zalithlauncher.ui.screens.content.RecordingsScreen]).
     */
    private fun createOutputEntry(context: Context): Pair<android.net.Uri, File> {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ZerythRec_$ts.mp4"
        val relPath = "Movies/Zeryth Recordings"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, relPath)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create MediaStore entry for recording")

        // Best-effort File reference for size queries in RecordingsScreen
        val publicMovies = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_MOVIES
        )
        val file = File(publicMovies, "Zeryth Recordings/$fileName")
        return Pair(uri, file)
    }
}
