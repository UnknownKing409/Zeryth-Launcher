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
import androidx.compose.foundation.layout.Spacer
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
        // surface colour (the purple/blue band visible at the top in screenshots)
        // does not bleed through.  Also zero out the platform dim-amount because
        // we draw our own scrim below.
        val dialogView = LocalView.current
        SideEffect {
            val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
            dialogWindow?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            dialogWindow?.setDimAmount(0f)
        }

        Box(modifier = Modifier.fillMaxSize()) {

            // Scrim — only visible in overlay (non-full-screen) mode
            if (!isFullScreen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.78f))
                        .clickable(onClick = ::dismissWithCleanup)
                )
            }

            // ── Player container ──────────────────────────────────────────────
            val playerModifier = if (isFullScreen) {
                Modifier
                    .fillMaxSize()
            } else {
                // 72 % of screen width keeps the card comfortably smaller than the
                // screen on both phones and tablets, capped at 500 dp for very wide
                // displays.
                Modifier
                    .fillMaxWidth(0.72f)
                    .widthIn(max = 500.dp)
                    .aspectRatio(16f / 9f)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
            }

            Box(modifier = playerModifier.background(Color.Black)) {

                // Video surface (ExoPlayer PlayerView, controller disabled)
                AndroidPlayerView(player = player, modifier = Modifier.fillMaxSize())

                // ── Gesture detection ─────────────────────────────────────────
                // Single tap  → toggle controls
                // Double tap  → seek ±5 s (left half = rewind, right half = forward)
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(isPlaying) {
                            detectTapGestures(
                                onTap = { controlsVisible = !controlsVisible },
                                onDoubleTap = { offset ->
                                    val isLeft = offset.x < size.width / 2f
                                    seek(if (isLeft) -5 else 5)
                                    controlsVisible = true  // briefly show controls after seek
                                }
                            )
                        }
                ) { /* measure-only, no children */ }

                // ── Seek indicator ────────────────────────────────────────────
                AnimatedVisibility(
                    visible = seekVisible,
                    enter = fadeIn() + scaleIn(initialScale = 0.80f),
                    exit  = fadeOut(tween(250)),
                    modifier = Modifier.align(Alignment.Center)
                ) {
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

                // ── Controls overlay ──────────────────────────────────────────
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
                        // ── Top bar: close | title | full-screen toggle ────────
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
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(onClick = { isFullScreen = !isFullScreen }) {
                                Icon(
                                    painter = painterResource(
                                        if (isFullScreen) R.drawable.ic_fullscreen_exit
                                        else R.drawable.ic_fullscreen
                                    ),
                                    contentDescription = if (isFullScreen) "Exit full screen"
                                                         else "Enter full screen",
                                    tint = Color.White
                                )
                            }
                        }

                        // ── Centre: play / pause / replay / buffering indicator ─
                        Box(
                            modifier = Modifier.align(Alignment.Center),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isBuffering && !isEnded) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(48.dp)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.45f))
                                        .clickable { togglePlayback() },
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
                                        tint = Color.White,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                        }

                        // ── Bottom: position | progress slider | duration ──────
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Current position
                                Text(
                                    text = formatTime(positionMs),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.widthIn(min = 40.dp)
                                )

                                // Progress slider with scrubbing support
                                Slider(
                                    value = if (durationMs > 0L)
                                        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                                    else 0f,
                                    onValueChange = { fraction ->
                                        isScrubbing = true
                                        positionMs = (fraction * durationMs).toLong()
                                        player.seekTo(positionMs)
                                    },
                                    onValueChangeFinished = { isScrubbing = false },
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor         = Color.White,
                                        activeTrackColor   = Color.White,
                                        inactiveTrackColor = Color.White.copy(alpha = 0.35f)
                                    )
                                )

                                // Total duration
                                Text(
                                    text = formatTime(durationMs),
                                    color = Color.White.copy(alpha = 0.70f),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.widthIn(min = 40.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
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
