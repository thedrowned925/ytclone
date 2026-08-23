# YTClone 925

YTClone is a single-owner, Android-native personal YouTube-style platform. The primary product is one Android app that can watch the library, import supported video URLs, preserve audio/subtitle variants, create useful quality renditions on-device, chunk large files, and publish them to GitHub Releases.

The old React/PWA code remains in the repository as an early UI prototype/fallback. It is no longer the primary architecture.

## Android-first product

The native project lives in `android/` and is built with Kotlin + Jetpack Compose.

Current native foundation:

- YouTube-style Android navigation and dark UI shell
- YouTube app `Share -> YTClone` support for text/video URLs
- Android-native yt-dlp import pipeline
- title/channel/description/date/thumbnail metadata import
- manual + automatic subtitle preservation when enabled
- available alternate audio/dub/language track preservation
- separate video and audio storage model
- Media3 Transformer quality generation using Android codecs
- 1080p / 720p / 480p / 360p renditions without upscaling
- Media3 background playback service
- WorkManager persistent ingest queue + foreground progress notification
- secure GitHub repo/token settings inside the Android app
- GitHub token encrypted at rest with Android Keystore AES/GCM
- direct GitHub Release publisher running on Android
- automatic 1.8 GiB chunking without making physical chunk copies on disk
- resumable per-asset uploads
- multi-Release overflow when one video would exceed the safe asset count
- `storage-manifest.json` logical-file mapping
- Android Media3 DataSource that reads many GitHub Release chunks as one seekable logical file
- Media3 source merging for selected video quality + selected audio track
- lightweight GitHub Actions APK build only; no media download/transcode/upload in Actions

## Primary architecture

```text
YTClone Android APK
|
+-- Jetpack Compose UI
|   +-- Home
|   +-- Shorts
|   +-- Add / Archive
|   +-- Channels
|   +-- Library
|   +-- Settings
|
+-- Media3 / ExoPlayer
|   +-- background playback
|   +-- selected video quality
|   +-- selected audio/dub track
|   +-- GitHubChunkDataSource
|
+-- Android ingest queue
|   +-- yt-dlp in-process
|   +-- metadata / thumbnail / subtitles
|   +-- all useful audio variants
|   +-- Media3 Transformer renditions
|   +-- checkpoint / retry
|
+-- Android GitHub storage client
    +-- draft Releases
    +-- 1.8 GiB chunks
    +-- resume existing assets
    +-- storage-manifest.json
    +-- publish when complete
```

No PC is required by the target architecture. The earlier desktop uploader/server implementation remains useful as a development/fallback tool, but Android is expected to perform the full normal workflow.

## Android ingest flow

1. In YouTube, tap **Share -> YTClone**, or paste a supported URL into YTClone.
2. YTClone reads the source metadata and available formats with yt-dlp on the phone.
3. It downloads a high-quality source video stream.
4. It preserves the default and available alternate audio/dub/language streams as separate files.
5. It preserves subtitles if enabled.
6. Media3 Transformer creates only useful Android-friendly H.264 renditions at or below the source resolution.
7. Video and audio remain separate so changing language does not duplicate every video quality.
8. Files larger than 1.8 GiB are uploaded to GitHub Releases as byte chunks directly from the source file.
9. `storage-manifest.json` records the Release, asset, logical offset and size for every chunk.
10. Upload retries skip same-name/same-size assets that already completed.
11. If a single video would approach the Release asset-count ceiling, YTClone continues into `r002`, `r003`, and so on.
12. Releases stay draft until all required files are present, then they are published.

## Chunk size

YTClone deliberately stays below GitHub's per-asset ceiling:

```text
CHUNK_SIZE_BYTES = 1,932,735,283
CHUNK_SIZE = floor(1.8 GiB)
```

A large logical file may be stored as:

```text
video.1080p.mp4.part0001
video.1080p.mp4.part0002
video.1080p.mp4.part0003
...
```

The Android player does not treat these as separate videos. `GitHubChunkDataSource` maps Media3 byte-range reads/seeks onto the correct Release asset and returns a continuous logical stream.

## Audio model

Playable video renditions are video-only. Audio tracks are separate logical media assets.

Example:

```text
video.1080p.mp4
video.720p.mp4
video.480p.mp4
video.360p.mp4

audio.001.m4a   # original/default
audio.002.m4a   # Turkish dub
audio.003.m4a   # English/alternate
```

Media3 merges the selected video quality and selected audio track at playback time. This avoids storing the same audio repeatedly inside every quality rendition.

## Android background work

Heavy media work is not performed by GitHub Actions. Android uses WorkManager plus foreground notifications for ingest jobs. Existing downloads/renditions and already-uploaded Release assets are reused on retry whenever possible.

A source file that the user chose not to archive is still kept locally until its derived renditions have been successfully uploaded. It is removed only after successful publication, preventing an interrupted upload from forcing a complete re-download.

## Security

GitHub credentials are never committed to the repository or hardcoded into the APK.

The Android settings screen stores:

- media repository (`owner/repo`)
- a fine-grained GitHub token

The token is encrypted on the device with an AES/GCM key stored in Android Keystore. It is decrypted only when the app needs to call GitHub.

## Building Android

The CI workflow `.github/workflows/android.yml` uses:

```text
JDK 17
Gradle 9.5.0
Android Gradle Plugin 9.3.0
compileSdk 37
Kotlin / Compose compiler 2.3.21
Compose BOM 2026.08.00
Media3 1.11.0
```

It runs only an Android build and uploads the debug APK as a workflow artifact. It does not run yt-dlp, media encoding, or GitHub media uploads.

From a configured local Android/Gradle environment:

```bash
gradle -p android :app:assembleDebug
```

## Current next milestones

- native catalog that syncs published videos/channels from GitHub Releases
- full Compose watch screen using the chunk DataSource
- quality and audio-track selectors in the player UI
- Picture-in-Picture and polished background playback controls
- watch history / resume position
- offline downloads and storage-management UI
- processing queue/history UI with pause/cancel/retry
- channel pages, playlists and Shorts
- automatic channel artwork/banner import where available
- release/debug APK verification and iterative device testing

## Legacy prototype/fallback

The root React/Vite app, `uploader/`, and `server/` folders are retained because they contain useful prototypes and fallback tooling. New product work should target `android/` unless a task explicitly concerns the legacy web/desktop path.
