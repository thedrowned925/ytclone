package com.thedrowned925.ytclone.ingest

import android.content.Context
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RenditionEngine(private val context: Context) {
    data class Rendition(val height: Int, val file: File)

    suspend fun createRenditions(
        sourceVideo: File,
        sourceHeight: Int,
        outputDir: File,
        onStage: (height: Int, index: Int, total: Int) -> Unit,
    ): List<Rendition> {
        val heights = listOf(1080, 720, 480, 360).filter { it <= sourceHeight }
        if (heights.isEmpty()) return emptyList()

        val outputs = mutableListOf<Rendition>()
        for ((index, height) in heights.withIndex()) {
            val output = File(outputDir, "video.${height}p.mp4")
            onStage(height, index, heights.size)

            if (output.exists() && output.length() > 0L) {
                outputs += Rendition(height, output)
                continue
            }

            output.delete()
            exportVideoOnly(sourceVideo, output, height)
            outputs += Rendition(height, output)
        }
        return outputs
    }

    private suspend fun exportVideoOnly(input: File, output: File, height: Int) {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val effects = Effects(
                    /* audioProcessors = */ emptyList(),
                    /* videoEffects = */ listOf<Effect>(Presentation.createForHeight(height)),
                )
                val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
                    .setRemoveAudio(true)
                    .setEffects(effects)
                    .build()

                lateinit var transformer: Transformer
                transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(
                        object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, result: ExportResult) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }

                            override fun onError(
                                composition: Composition,
                                result: ExportResult,
                                exception: ExportException,
                            ) {
                                output.delete()
                                if (continuation.isActive) continuation.resumeWithException(exception)
                            }
                        },
                    )
                    .build()

                continuation.invokeOnCancellation {
                    transformer.cancel()
                    output.delete()
                }
                transformer.start(item, output.absolutePath)
            }
        }
    }
}
