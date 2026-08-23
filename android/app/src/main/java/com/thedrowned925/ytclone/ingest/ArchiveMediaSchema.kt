package com.thedrowned925.ytclone.ingest

/**
 * Bump only when the physical media representation in GitHub Releases changes.
 * v2 remuxes downloaded adaptive streams losslessly into Matroska so Media3 can
 * build a reliable seek map while still streaming through HTTP byte ranges.
 */
object ArchiveMediaSchema {
    const val VERSION = 2
    const val VIDEO_CONTAINER = "mkv"
    const val AUDIO_CONTAINER = "mka"

    fun stateKey(url: String): String = "media-v$VERSION|$url"
}
