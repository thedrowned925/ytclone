# YTClone 925

Android-first personal video platform: a private, fast, installable YouTube-style experience for one owner/viewer.

## Current V1 foundation

- React + Vite mobile-first UI
- Installable PWA shell for Android
- YouTube-style home feed and category chips
- Search screen
- Channel and Library tabs
- Continue-watching progress UI
- Full-screen watch sheet
- Quality selector: Auto / 1080p / 720p / 480p / 360p
- Favorite, Watch Later and Download actions
- YouTube URL / local file ingest UI
- Multi-audio metadata model
- Local uploader/ingest engine (heavy processing is NOT done in GitHub Actions)
- YouTube/local source ingest with yt-dlp + ffmpeg/ffprobe
- Subtitle and available alternate audio-track preservation
- GitHub Releases publisher with resumable per-asset uploads
- Automatic 1.8 GiB storage chunks for files that exceed the safe asset size
- `storage-manifest.json` mapping chunks back to one logical media file
- Trusted API virtual Range streaming across Release chunks
- Storage-provider abstraction prepared for GitHub Releases and future providers
- CI build workflow for lightweight build/check tasks only

## Architecture

```text
Android / PWA
    |
    +-- Home / Search / Shorts / Channels / Library
    +-- Player / PiP / background playback / downloads
    |
Trusted API
    |
    +-- owner auth
    +-- video catalog
    +-- playback/download URLs
    +-- virtual HTTP Range layer
    |       |
    |       +-- video.1080p.mp4.part0001
    |       +-- video.1080p.mp4.part0002
    |       +-- ...presented to the player as ONE file
    |
Local YTClone Uploader (owner PC)
    |
    +-- yt-dlp source import
    +-- metadata + automatic channel mapping
    +-- thumbnail/subtitle import
    +-- all useful alternate audio tracks
    +-- local ffmpeg/ffprobe processing
    +-- quality renditions
    +-- GitHub Release publisher
    |
StorageProvider
    |
    +-- GitHub Releases (first provider)
    +-- R2/B2/etc. later without changing the UI
```

## Why transcoding is local

GitHub Actions is not the media-processing machine. Long transcodes/downloads can be slow or fail and should not control whether a video archive can be completed. The desktop/local uploader performs heavy work on the owner's computer, keeps its work directory, and uploads only completed assets. GitHub Actions stays limited to lightweight CI/build checks.

## Ingest flow

1. Paste a supported source URL or choose a local video.
2. Read title, channel, thumbnail, duration, description, subtitles and available audio variants.
3. Find or create the matching channel in YTClone.
4. Keep the original file if configured.
5. Preserve alternate/dub audio tracks as separate assets.
6. Generate only useful renditions (never upscale): 1080p / 720p / 480p / 360p.
7. Any logical file larger than 1.8 GiB is uploaded as multiple Release assets.
8. Write `storage-manifest.json` so the API can reconstruct each logical file.
9. Upload to a draft Release. Already-complete same-size assets are skipped on retry.
10. Publish only after the package is complete.

## Chunked Release storage

GitHub Releases limits each individual asset to under 2 GiB. YTClone deliberately uses a lower chunk ceiling:

```text
CHUNK_SIZE = 1.8 GiB
```

A large logical file may therefore look like this in GitHub:

```text
original.mkv.part0001
original.mkv.part0002
original.mkv.part0003
video.1080p.mp4.part0001
video.1080p.mp4.part0002
audio.01.tr.turkce.m4a
manifest.json
storage-manifest.json
```

The Android/PWA player never needs to know these are chunks. It requests a normal logical video with HTTP Range. The trusted API maps that requested byte range to the relevant Release chunk(s) and returns one continuous response.

## Audio model

Each archived video can have:

- a default audio track embedded in the playable rendition
- additional source audio/dub/language tracks stored independently
- language, title/label and channel metadata for each track
- independent audio-track downloads
- a future player audio selector without re-importing the source

## Security rule

Never ship GitHub tokens or storage credentials in browser/Android frontend code. Private Release assets must be accessed through the trusted API. The local uploader reads its GitHub credential from the environment, not from committed source code.

## Web app local development

```bash
npm install
npm run dev
```

Production build:

```bash
npm run build
```

## Local uploader engine

Requirements on the owner PC:

- Node.js 20+
- yt-dlp
- FFmpeg + ffprobe

Check tools:

```bash
cd uploader
npm run check
```

Process locally without uploading:

```bash
npm run ingest -- "video.mkv" --no-upload
```

Process a supported URL and publish to a media repository:

```bash
set YTCLONE_GITHUB_TOKEN=YOUR_TOKEN
set YTCLONE_MEDIA_REPO=owner/media-repo
npm run ingest -- "https://youtube.com/watch?v=..."
```

The CLI is the ingest engine; it is intended to sit behind the YTClone Uploader desktop GUI later.

## Trusted API

Environment:

```text
YTCLONE_GITHUB_TOKEN=...
YTCLONE_MEDIA_REPO=owner/media-repo
YTCLONE_APP_TOKEN=optional-single-owner-api-token
PORT=8787
```

Current media routes:

```text
GET /api/videos/:id/play?quality=auto
GET /api/videos/:id/download?quality=original
GET /api/videos/:id/audio/:trackId
```

All playback/download routes support HTTP byte-range streaming through chunked Release assets.

## Next milestones

- Real catalog/channel index instead of mock feed
- owner authentication suitable for Android/PWA
- desktop GUI around the local uploader engine
- persistent jobs, retry/resume UI and processing queue
- hardware-accelerated encode detection (NVENC / Quick Sync / AMF where available)
- real HTML5/HLS-capable player UI
- persistent watch history and resume
- Android offline download manager
- Picture-in-Picture and background audio
- player audio-track selector
- playlists, subscriptions-like channel library and Shorts
- optional Capacitor APK/AAB packaging
