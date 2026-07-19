---
name: Screen Recorder — frozen first frame fix
description: Root cause and fix for the frozen first video frame at the start of recordings in GameRecorder.kt (PixelCopy architecture).
---

## Root Cause

`primeVideoTrack()` exits immediately after `INFO_OUTPUT_FORMAT_CHANGED` is seen. It does NOT drain all remaining encoded output — one or more encoded black-frame buffers can be left in the codec's output buffer pool.

If those residual priming buffers are not released before the first real PixelCopy frame arrives, the encoder's output buffer slots are partially occupied. Under back-pressure, the first real frame can be delayed or dropped. The earliest video PTS in the recording then corresponds to several seconds after the muxer opened, producing a frozen frame at the start of playback.

**Why:** priming needs the codec to emit FORMAT_CHANGED, which requires at least one encoded frame. But that encoded frame is left in the output queue when priming returns early.

## Fix (in `GameRecorder.kt`)

Three changes to Phase 2 ordering:

1. **`discardVideoOutput(videoCodec!!)`** — called immediately after priming completes. Drains all residual priming output frames from the codec's buffer pool so the pool is empty before real capture begins.

2. **`startVideoEncodeJob()` + `startAudioJob()` BEFORE `isCapturing.set(true)`** — encode drain loops are active before PixelCopy frames start flowing. No real frames can be lost to a temporarily-full output queue.

3. **`isCapturing.set(true)` + `scheduleNextFrame()` AFTER jobs are running** — the first PixelCopy request is only scheduled once the drain loop is already running.

## How to Apply

Any future change to the recording startup sequence must preserve this ordering:
1. `discardVideoOutput(videoCodec!!)`
2. `startVideoEncodeJob()`
3. `startAudioJob()`
4. `isCapturing.set(true)`
5. `scheduleNextFrame()`

## Recording Status Sounds

Also added in the same fix:
- **Start sound**: `ToneGenerator(STREAM_NOTIFICATION, 70).startTone(TONE_PROP_BEEP, 160)` — one short beep after `scheduleNextFrame()`.
- **Stop sound**: `ToneGenerator(STREAM_NOTIFICATION, 70).startTone(TONE_PROP_BEEP2, 400)` — two short beeps after successful MediaStore commit in `finalise()`.
- Both use `STREAM_NOTIFICATION` (USAGE_NOTIFICATION) which is NOT captured by `AudioPlaybackCaptureConfiguration` (only USAGE_GAME / USAGE_MEDIA / USAGE_UNKNOWN are matched).
- Neither sound plays on init failure, permission denial, or cancellation.
