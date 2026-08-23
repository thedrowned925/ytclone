package com.thedrowned925.ytclone.ingest

data class IngestOptions(
    val allAudioTracks: Boolean = true,
    val subtitles: Boolean = true,
    val keepOriginal: Boolean = true,
    val createRenditions: Boolean = true,
)
