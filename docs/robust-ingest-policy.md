# Robust ingest policy

YTClone treats video/audio payload as critical and auxiliary assets (subtitles, thumbnails, channel avatar/banner) as best-effort. Auxiliary HTTP 429/403/timeout errors must never abort a successfully downloadable video. Each auxiliary stage uses bounded retry/backoff and records warnings in manifest.json. Completed media files are reused from Download/YTClone/Working on resume.
