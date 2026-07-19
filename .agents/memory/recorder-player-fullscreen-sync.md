---
name: Screen Recorder & Video Player — full-screen and audio-sync fixes
description: Root causes and fixes for the full-screen exit bug (status bar stuck visible, bluish overlay) and audio/video sync drift in the built-in recorder/player.
---

## Full-Screen Exit Bug

**Root causes:**
1. `activityView` and `activity` were removed from the composable scope but still referenced inside `DisposableEffect(isFullScreen)` — compile error.
2. `WindowInsetsControllerCompat.show(systemBars())` on dispose bypasses the legacy `View.OnSystemUiVisibilityChangeListener` that `FullScreenAppCompatActivity.applyFullscreen()` registers, so the launcher's immersive mode is never re-armed.
3. The Dialog window's `FLAG_DIM_BEHIND` creates the bluish/grey overlay — `setDimAmount(0f)` alone is not enough; `clearFlags(FLAG_DIM_BEHIND)` is also needed.

**Why:** The launcher uses deprecated `systemUiVisibility` flags (`IMMERSIVE_STICKY | HIDE_NAVIGATION | FULLSCREEN …`). Modern `WindowInsetsControllerCompat` calls do not retrigger the legacy visibility-change listener, leaving bars permanently visible.

**How to apply:** Always restore the activity's immersive mode by writing `decorView.systemUiVisibility = LEGACY_FLAGS` directly (not via ctrl.show), and explicitly `clearFlags(FLAG_DIM_BEHIND)` on Dialog windows that should have no system dim.

---

## Audio / Video Sync Drift

**Root cause:** Audio PTS used a sample-count counter starting at 0 from `ar.startRecording()`. Video PTS was normalised to 0 from the *first encoded video frame*, which arrives 100–300 ms later due to encoder startup latency. This caused audio to lead video by that margin.

**Fix:** Introduce `recordingStartNs = System.nanoTime()` captured once after both codecs start. Both streams reference this anchor:
- Video: `rawUs - recordingStartNs/1000 - totalPausedUs`
- Audio: `(audioStartNs - recordingStartNs)/1000 + sampleCount * 1_000_000 / SAMPLE_RATE`
  where `audioStartNs` is captured on the first PCM read.

**Why:** `System.nanoTime()` is the same clock domain used by the MediaCodec surface internally, so both streams are guaranteed to share the same zero.

---

## Key file locations (in repo root `ZalithLauncher/` module)
- Recorder: `src/main/java/com/movtery/zalithlauncher/game/recorder/GameRecorder.kt`
- Player overlay: `src/main/java/com/movtery/zalithlauncher/ui/components/RecordingPlayerOverlay.kt`
- Activity immersive mode: `src/main/java/com/movtery/zalithlauncher/ui/base/FullScreenAppCompatActivity.kt`
