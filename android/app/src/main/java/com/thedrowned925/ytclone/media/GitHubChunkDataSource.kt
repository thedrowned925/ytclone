package com.thedrowned925.ytclone.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.thedrowned925.ytclone.storage.GitHubReleaseReader
import java.io.EOFException
import java.io.IOException
import kotlin.math.min

class GitHubChunkDataSource(
    private val reader: GitHubReleaseReader,
    private val primaryReleaseTag: String,
    private val fixedLogicalName: String? = null,
) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var logicalName: String = ""
    private var logicalFile: GitHubReleaseReader.LogicalFile? = null
    private var logicalPosition = 0L
    private var bytesRemaining = 0L
    private var currentSlice: GitHubReleaseReader.OpenedSlice? = null
    private var currentSliceRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        logicalName = fixedLogicalName
            ?: Uri.decode(dataSpec.uri.lastPathSegment.orEmpty()).takeIf(String::isNotBlank)
            ?: throw IOException("ytclone URI logicalName içermiyor: ${dataSpec.uri}")
        val file = reader.logicalFile(primaryReleaseTag, logicalName)
        logicalFile = file

        if (dataSpec.position < 0 || dataSpec.position > file.sizeBytes) {
            throw IOException("Okuma konumu dosya dışında: ${dataSpec.position}/${file.sizeBytes}")
        }

        logicalPosition = dataSpec.position
        val available = file.sizeBytes - logicalPosition
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else min(available, dataSpec.length)
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        ensureSlice()
        val slice = currentSlice ?: return C.RESULT_END_OF_INPUT
        val toRead = min(length.toLong(), min(bytesRemaining, currentSliceRemaining)).toInt()
        val read = slice.input.read(buffer, offset, toRead)
        if (read < 0) throw EOFException("GitHub chunk beklenenden erken bitti: $logicalName")
        logicalPosition += read
        bytesRemaining -= read
        currentSliceRemaining -= read
        bytesTransferred(read)
        if (currentSliceRemaining == 0L) closeCurrentSlice()
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closeCurrentSlice()
        uri = null
        logicalFile = null
        logicalName = ""
        bytesRemaining = 0L
        logicalPosition = 0L
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun ensureSlice() {
        if (currentSlice != null && currentSliceRemaining > 0) return
        closeCurrentSlice()
        val file = logicalFile ?: error("DataSource açılmadı")
        val part = file.parts.firstOrNull { logicalPosition >= it.offset && logicalPosition < it.offset + it.sizeBytes }
            ?: throw IOException("Storage manifest byte $logicalPosition için chunk içermiyor")
        val assetOffset = logicalPosition - part.offset
        val wanted = min(bytesRemaining, part.sizeBytes - assetOffset)
        if (wanted <= 0) return
        currentSlice = reader.openPartSlice(part, assetOffset, wanted)
        currentSliceRemaining = wanted
    }

    private fun closeCurrentSlice() {
        currentSlice?.close()
        currentSlice = null
        currentSliceRemaining = 0L
    }

    class Factory(
        private val reader: GitHubReleaseReader,
        private val primaryReleaseTag: String,
        private val fixedLogicalName: String? = null,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = GitHubChunkDataSource(reader, primaryReleaseTag, fixedLogicalName)
    }
}
