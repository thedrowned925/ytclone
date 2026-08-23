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
- Storage-provider abstraction prepared for GitHub Releases
- CI build workflow

## Target architecture

```text
Android / PWA
    |
    +-- Home / Search / Shorts / Channels / Library
    +-- Player / PiP / background playback / downloads
    |
Trusted API
    |
    +-- auth (single owner)
    +-- video catalog
    +-- playback/download signed URLs
    +-- ingest jobs
    |
Media pipeline
    |
    +-- source import
    +-- metadata + channel mapping
    +-- thumbnail/subtitle import
    +-- FFmpeg qualities
    |
StorageProvider
    |
    +-- GitHub Releases (first provider)
    +-- R2/B2/etc. later without changing the UI
```

## Planned ingest flow

1. Paste a supported source URL or choose a local video.
2. Read title, channel, thumbnail, duration, description and subtitles when available.
3. Find or create the matching channel in YTClone.
4. Keep the original file if configured.
5. Generate only useful renditions (never upscale): 1080p / 720p / 480p / 360p.
6. Upload media assets through the configured storage provider.
7. Publish the catalog record and show processing state in the app.

## Security rule

Never ship GitHub tokens or storage credentials in browser/Android frontend code. Private Release assets must be accessed through a trusted backend/worker.

## Local development

```bash
npm install
npm run dev
```

Production build:

```bash
npm run build
```

## Next milestones

- Real JSON/catalog data instead of mock feed
- Owner authentication
- GitHub Releases API worker
- FFmpeg ingest worker
- YouTube metadata/channel import pipeline
- Real HTML5/HLS player
- persistent watch history and resume
- Android offline download manager
- Picture-in-Picture and background audio
- playlists, subscriptions-like channel library and Shorts
- optional Capacitor APK/AAB packaging
