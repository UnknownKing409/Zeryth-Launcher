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

package com.movtery.zalithlauncher.ui.components

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.movtery.zalithlauncher.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Built-in video player overlay for recording playback.
 *
 * Presents as a centred 16:9 card over a semi-transparent scrim so the launcher
 * remains visible in the background.  A Full Screen button expands the player to
 * fill the entire screen in an immersive (system-bars-hidden) mode.  Exiting full
 * screen restores the overlay card and the system bars.
 *
 * Controls auto-hide after 3 s of playback inactivity and reappear on a single tap.
 * Double-tapping the left half rewinds 5 s; double-tapping the right half
 * fast-forwards 5 s.  Consecutive double taps accumulate the seek amount and display
 * the running total (e.g. "+10 s", "+15 s") in an animated indicator.
 */
@OptIn(UnstableApi::class)
@Composable
fun RecordingPlayerOverlay(
    uri: Uri,
    title: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activityView = LocalView.current          // captured outside Dialog — refers to Activity view
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    // ── ExoPlayer ─────────────────────────────────────────────────────────────
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    // ── Playback state ────────────────────────────────────────────────────────
    var isPlaying   by remember { mutableStateOf(false) }
    var isEnded     by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var positionMs  by remember { mutableStateOf(0L) }
    var durationMs  by remember { mutableStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }

    // ── UI state ──────────────────────────────────────────────────────────────
    var controlsVisible by remember { mutableStateOf(true) }
    var isFullScreen    by remember { mutableStateOf(false) }

    // ── Seek accumulation ─────────────────────────────────────────────────────
    var seekAccumSec    by remember { mutableIntStateOf(0) }
    var seekVisible     by remember { mutableStateOf(false) }
    var seekJob: Job?   by remember { mutableStateOf(null) }

    // ── Player listener ───────────────────────────────────────────────────────
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) {
                    isEnded = true
                    isPlaying = false
                    controlsVisible = true   // always show controls when video ends
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // ── Position polling (200 ms interval) ───────────────────────────────────
    LaunchedEffect(player) {
        while (true) {
            if (!isScrubbing) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = if (player.duration > 0L) player.duration else 0L
            }
            delay(200)
        }
    }

    // ── Controls auto-hide after 3 s during active playback ──────────────────
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying && !isEnded) {
            delay(3_000)
            controlsVisible = false
        }
    }

    // ── Full-screen: hide Activity system bars only when entering immersive mode ─
    // IMPORTANT: do NOT touch system bars when isFullScreen = false (overlay mode).
    // Calling ctrl.show() on initial composition would forcibly surface the status
    // bar even when the launcher was running without it, and it would stay visible
    // after the player is dismissed (the onDispose below runs on every key change).
    DisposableEffect(isFullScreen) {
        if (!isFullScreen) return@DisposableEffect onDispose { /* nothing to restore */ }
        val window = activity?.window ?: return@DisposableEffect onDispose {}
        val ctrl = WindowCompat.getInsetsController(window, activityView)
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
        ctrl.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            // Restore bars only when leaving full-screen (key changed to false) or on dispose
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Apply an incremental seek of [deltaSec] seconds from the current position. */
    fun seek(deltaSec: Int) {
        val safeDuration = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        val newPos = (player.currentPosition + deltaSec * 1_000L).coerceIn(0L, safeDuration)
        player.seekTo(newPos)
        seekAccumSec += deltaSec
        seekVisible = true
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(800)
            seekVisible = false
            seekAccumSec = 0
        }
    }

    /** Toggle play / pause, or replay from the beginning if the video has ended. */
    fun togglePlayback() {
        when {
            isEnded  -> { player.seekTo(0); player.play(); isEnded = false }
            isPlaying -> player.pause()
            else      -> player.play()
        }
    }

    /** Format milliseconds as M:SS or H:MM:SS. */
    fun formatTime(ms: Long): String {
        val s = (ms / 1_000L).coerceAtLeast(0L)
        val h = s / 3_600; val m = (s % 3_600) / 60; val sec = s % 60
        return if (h > 0L) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    // ── Restore system bars on dismis (e.g. back-press) ──────────────────────
    fun dismissWithCleanup() {
        activity?.window?.let { w ->
            WindowCompat.getInsetsController(w, activityView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        onDismiss()
    }

    // ── Dialog overlay ────────────────────────────────────────────────────────
    Dialog(
        onDismissRequest = ::dismissWithCleanup,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true
        )
    ) {
        // Make the Dialog window itself fully transparent so its default themed
        // surface colour does not bleed through, and zero out the platform dim
        // because we draw our own scrim.
        val dialogView = LocalView.current
        SideEffect {
            val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
            dialogWindow?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            dialogWindow?.setDimAmount(0f)
        }

        Box(modifier = Modifier.fillMaxSize()) {

            if (isFullScreen) {
                // ── FULL-SCREEN mode ──────────────────────────────────────────
                // Black video surface with a semi-transparent gradient overlay
                // that auto-hides after 3 s of playback.
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidPlayerView(player = player, modifier = Modifier.fillMaxSize())
                    VideoGestureLayer(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = { offset, width ->
                            seek(if (offset < width / 2f) -5 else 5)
                            controlsVisible = true
                        }
                    )
                    SeekIndicator(seekVisible = seekVisible, seekAccumSec = seekAccumSec)
                    AnimatedVisibility(
                        visible = controlsVisible,
                        enter = fadeIn(tween(200)),
                        exit  = fadeOut(tween(300)),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0.00f to Color.Black.copy(alpha = 0.65f),
                                        0.28f to Color.Transparent,
                                        0.72f to Color.Transparent,
                                        1.00f to Color.Black.copy(alpha = 0.65f)
                                    )
                                )
                        ) {
                            // Top bar
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                                    .align(Alignment.TopStart),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = ::dismissWithCleanup) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_close),
                                        contentDescription = "Close player",
                                        tint = Color.White
                                    )
                                }
                                Text(
                                    text = title,
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(onClick = { isFullScreen = false }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_fullscreen_exit),
                                        contentDescription = "Exit full screen",
                                        tint = Color.White
                                    )
                                }
                            }
                            // Centre button
                            CentrePlayButton(
                                isBuffering = isBuffering,
                                isEnded = isEnded,
                                isPlaying = isPlaying,
                                tint = Color.White,
                                modifier = Modifier.align(Alignment.Center),
                                onClick = ::togglePlayback
                            )
                            // Bottom progress
                            ProgressRow(
                                positionMs = positionMs,
                                durationMs = durationMs,
                                textColor = Color.White,
                                secondaryTextColor = Color.White.copy(alpha = 0.70f),
                                sliderColors = SliderDefaults.colors(
                                    thumbColor         = Color.White,
                                    activeTrackColor   = Color.White,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.35f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                onScrub = { fraction ->
                                    isScrubbing = true
                                    positionMs = (fraction * durationMs).toLong()
                                    player.seekTo(positionMs)
                                },
                                onScrubFinished = { isScrubbing = false },
                                formatTime = ::formatTime
                            )
                        }
                    }
                }

            } else {
                // ── OVERLAY (card) mode ───────────────────────────────────────
                // Scrim behind the card — tapping it dismisses the player.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.72f))
                        .clickable(onClick = ::dismissWithCleanup)
                )

                // Card container — matches the launcher's BackgroundCard style.
                BackgroundCard(
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .widthIn(max = 560.dp)
                        .align(Alignment.Center)
                ) {
                    // ── Title bar (separated by a divider, launcher-style) ────
                    CardTitleLayout {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = ::dismissWithCleanup) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = "Close player"
                                )
                            }
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 6.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(onClick = { isFullScreen = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_fullscreen),
                                    contentDescription = "Enter full screen"
                                )
                            }
                        }
                    }

                    // ── Video surface (fills card width, clips to card shape) ─
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(Color.Black)
                    ) {
                        AndroidPlayerView(
                            player = player,
                            modifier = Modifier.fillMaxSize()
                        )
                        VideoGestureLayer(
                            onTap = { /* controls always visible in card mode */ },
                            onDoubleTap = { offset, width ->
                                seek(if (offset < width / 2f) -5 else 5)
                            }
                        )
                        SeekIndicator(seekVisible = seekVisible, seekAccumSec = seekAccumSec)
                        // Centre play/pause/replay — always visible in card mode
                        CentrePlayButton(
                            isBuffering = isBuffering,
                            isEnded = isEnded,
                            isPlaying = isPlaying,
                            tint = Color.White,
                            modifier = Modifier.align(Alignment.Center),
                            onClick = ::togglePlayback
                        )
                    }

                    // ── Progress row — inside card body, uses theme colours ───
                    ProgressRow(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        textColor = LocalContentColor.current,
                        secondaryTextColor = LocalContentColor.current.copy(alpha = 0.55f),
                        sliderColors = SliderDefaults.colors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        onScrub = { fraction ->
                            isScrubbing = true
                            positionMs = (fraction * durationMs).toLong()
                            player.seekTo(positionMs)
                        },
                        onScrubFinished = { isScrubbing = false },
                        formatTime = ::formatTime
                    )
                }
            }
        }
    }
}

// ── Extracted private helpers ─────────────────────────────────────────────────

/** Transparent gesture-capture layer covering the video surface. */
@Composable
private fun VideoGestureLayer(
    onTap: () -> Unit,
    onDoubleTap: (offset: Float, width: Float) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { offset ->
                        onDoubleTap(offset.x, size.width.toFloat())
                    }
                )
            }
    ) { /* measure only */ }
}

/** Animated ±Ns seek amount indicator centred over the video. */
@Composable
private fun SeekIndicator(seekVisible: Boolean, seekAccumSec: Int) {
    AnimatedVisibility(
        visible = seekVisible,
        enter = fadeIn() + scaleIn(initialScale = 0.80f),
        exit  = fadeOut(tween(250)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = if (seekAccumSec >= 0) "+${seekAccumSec}s" else "${seekAccumSec}s",
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}

/** Circular play / pause / replay / buffering button centred over the video. */
@Composable
private fun CentrePlayButton(
    isBuffering: Boolean,
    isEnded: Boolean,
    isPlaying: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (isBuffering && !isEnded) {
            CircularProgressIndicator(color = tint, modifier = Modifier.size(48.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        when {
                            isEnded   -> R.drawable.ic_replay
                            isPlaying -> R.drawable.ic_pause_filled
                            else      -> R.drawable.ic_play_arrow_filled
                        }
                    ),
                    contentDescription = when {
                        isEnded   -> "Replay"
                        isPlaying -> "Pause"
                        else      -> "Play"
                    },
                    tint = tint,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}

/** Position label + scrubable Slider + duration label. */
@Composable
private fun ProgressRow(
    positionMs: Long,
    durationMs: Long,
    textColor: Color,
    secondaryTextColor: Color,
    sliderColors: androidx.compose.material3.SliderColors,
    modifier: Modifier = Modifier,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    formatTime: (Long) -> String
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = formatTime(positionMs),
            color = textColor,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.widthIn(min = 40.dp)
        )
        Slider(
            value = if (durationMs > 0L)
                (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            else 0f,
            onValueChange = onScrub,
            onValueChangeFinished = onScrubFinished,
            modifier = Modifier.weight(1f),
            colors = sliderColors
        )
        Text(
            text = formatTime(durationMs),
            color = secondaryTextColor,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.widthIn(min = 40.dp)
        )
    }
}

// ── Private helper: ExoPlayer surface inside an AndroidView ──────────────────

@OptIn(UnstableApi::class)
@Composable
private fun AndroidPlayerView(
    player: ExoPlayer,
    modifier: Modifier = Modifier
) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        modifier = modifier
    )
}
