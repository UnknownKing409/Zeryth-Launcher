---
name: Screen Recorder & Video Player — full-screen, audio-sync, and audio quality fixes
description: Root causes and fixes for the full-screen exit bug, bluish overlay, and audio/video sync, speed, and pop issues in the built-in recorder/player.
---

## Full-Screen Exit Bug

**Root causes:**
1. `activityView` and `activity` were removed from the composable scope but still referenced inside `DisposableEffect(isFullScreen)` — compile error.
2. `WindowInsetsControllerCompat.show(systemBars())` on dispose bypasses the legacy `View.OnSystemUiVisibilityChangeListener` that `FullScreenAppCompatActivity.applyFullscreen()` registers, so the launcher's immersive mode is never re-armed.
3. The Dialog window's `FLAG_DIM_BEHIND` creates the bluish/grey overlay — `setDimAmount(0f)` alone is not enough; `clearFlags(FLAG_DIM_BEHIND)` is also needed.

**Why:** The launcher uses deprecated `systemUiVisibility` flags (`IMMERSIVE_STICKY | HIDE_NAVIGATION | FULLSCREEN …`). Modern `WindowInsetsControllerCompat` calls do not retrigger the legacy visibility-change listener, leaving bars permanently visible.

**How to apply:** Always restore the activity's immersive mode by writing `decorView.systemUiVisibility = LEGACY_FLAGS` directly (not via ctrl.show), and explicitly `clearFlags(FLAG_DIM_BEHIND)` on Dialog windows that should have no system dim.

---

## Audio Sped-Up / Out-of-Sync / Pops

### 1. Sped-up audio — sample rate mismatch (most impactful)
**Root cause:** `AudioPlaybackCapture` delivers samples at the device hardware-native rate (48 000 Hz on virtually all modern Android devices) regardless of the requested rate. Using 44 100 Hz in the encoder format and PTS math caused PTS to advance 8.8 % too fast → audio appeared to end ~8.8 % early → sounded sped up.

**Fix:** Always use `AUDIO_SAMPLE_RATE = 48_000` for `AudioPlaybackCapture` recordings.

**Why:** Android audio hardware is 48 kHz native. 44 100 Hz is a CD/music artifact. Apps using `AudioPlaybackCapture` should always use 48 000 Hz to avoid silent reinterpretation.

### 2. Audio pops — hardware overruns + encoder input starvation
**Root causes:**
- AudioRecord hardware ring-buffer was only ~43 ms — any OS scheduling hiccup longer than that causes an unrecoverable overrun → gap in PCM → audible pop.
- When `dequeueInputBuffer` timed out, the PCM batch was silently dropped (AudioRecord's read pointer already advanced), but `totalFrames` was not advanced → PTS jumped backward → muxer got overlapping timestamps → codec artifacts.
- `drainAudioCodec()` exited after at most one output buffer per call, leaving the output queue backlogged and all input slots occupied → cascading `dequeueInputBuffer` timeouts.

**Fix:**
- `audioHardwareBufferSize() = max(4 × minBuf, 32_768)` — ~170 ms of headroom against scheduling jitter.
- Advance `totalFrames` BEFORE the encoder check so PTS stays consistent with hardware timeline even for dropped batches.
- Retry `dequeueInputBuffer` once after a full output drain (which frees input slots) before giving up on a batch.
- `drainAudioCodec()` loops until `INFO_TRY_AGAIN_LATER` or EOS in both modes.

### 3. A/V sync drift — inaccurate first-sample PTS
**Root cause:** `audioStartNs = System.nanoTime()` was captured AFTER the first blocking `ar.read()` returned — which is ~one chunk duration (~43 ms) after the first sample was actually captured. This made audio appear to start ~43 ms late, compounding with rate mismatch to produce noticeable drift.

**Fix:** Use `AudioRecord.getTimestamp(AudioTimestamp, TIMEBASE_MONOTONIC)` (API 24+) on the first read to get the hardware `(framePosition, nanoTime)` anchor. All subsequent PTS:
```
batchStartNs = anchorNs + (totalFrames - anchorFrame) * 1_000_000_000L / SAMPLE_RATE
pts_µs = (batchStartNs - recordingStartNs) / 1_000L
```
Fallback if `getTimestamp` fails: `nanoTime()` corrected backwards by one chunk-duration.

---

## Key file locations (in repo root `ZalithLauncher/` module)
- Recorder: `src/main/java/com/movtery/zalithlauncher/game/recorder/GameRecorder.kt`
- Player overlay: `src/main/java/com/movtery/zalithlauncher/ui/components/RecordingPlayerOverlay.kt`
- Activity immersive mode: `src/main/java/com/movtery/zalithlauncher/ui/base/FullScreenAppCompatActivity.kt`
