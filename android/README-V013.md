# YTClone Android v0.1.3 ingest policy

- yt-dlp updates itself from the official NIGHTLY channel at app startup and before ingest.
- A failed extraction triggers one updater retry before the job fails.
- Media workspace: `Download/YTClone/Working/<video-id>/`.
- Large media is never copied into app-private data storage.
- After GitHub Release verification and catalog publication, the working video directory is deleted.
- Media repository is fixed to `thedrowned925/ytclone`; the user only enters a GitHub token.
- One video = one GitHub Release, with 1.8 GiB chunking.
- Archive up to 2160p/4K.
- Exactly one stream per resolution: choose that resolution's highest available FPS. Examples: 1080p60 when available, otherwise 1080p; never keep separate 1080p30 and 1080p60 copies.
- Preserve all selected audio tracks, subtitles, thumbnail, channel metadata, avatar and banner in the same video Release.
