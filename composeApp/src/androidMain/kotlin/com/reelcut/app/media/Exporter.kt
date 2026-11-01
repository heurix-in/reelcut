package com.reelcut.app.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.reelcut.app.data.Canvas
import com.reelcut.app.engine.ExportFailed
import com.reelcut.app.data.Clip
import com.reelcut.app.data.ExportSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Exports with Media3 Transformer (hardware H.264 + AAC), then saves to Movies/Reelcut. */
class Exporter(private val context: Context) {

    /** Returns the saved video's content URI. Cancelling the coroutine cancels the export and deletes the partial file. */
    suspend fun export(
        clips: List<Clip>,
        canvas: Canvas,
        muteOriginal: Boolean,
        settings: ExportSettings,
        layers: List<CompositionBuilder.Layer>,
        onProgress: (Int) -> Unit,
    ): Uri {
        val out = File(context.cacheDir, "export/render.mp4").apply {
            parentFile?.mkdirs()
            delete()
        }
        try {
            render(clips, canvas, muteOriginal, settings, layers, out, onProgress)
            return withContext(Dispatchers.IO) { saveToGallery(out) }
        } finally {
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { out.delete() }
        }
    }

    private suspend fun render(
        clips: List<Clip>,
        canvas: Canvas,
        muteOriginal: Boolean,
        settings: ExportSettings,
        layers: List<CompositionBuilder.Layer>,
        out: File,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.Main) {
        val composition = CompositionBuilder.exportComposition(clips, canvas, settings, muteOriginal, layers)
        val size = CompositionBuilder.outputSize(clips, canvas, settings)
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(CompositionBuilder.bitrate(size, settings)).build()
            )
            .setEnableFallback(true)
            .build()
        val result = CompletableDeferred<ExportException?>()
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    result.complete(null)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    result.complete(exportException)
                }
            })
            .build()

        transformer.start(composition, out.absolutePath)
        try {
            coroutineScope {
                val poller = launch {
                    val holder = ProgressHolder()
                    while (isActive) {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(holder.progress)
                        }
                        delay(200)
                    }
                }
                val error = result.await()
                poller.cancel()
                if (error != null) throw ExportFailed(friendly(error), error)
                onProgress(100)
            }
        } catch (e: CancellationException) {
            transformer.cancel()
            throw e
        }
    }

    private fun friendly(e: ExportException): String = when (e.errorCode) {
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
        ExportException.ERROR_CODE_IO_NO_PERMISSION -> "A source video could not be opened. It may have been moved or deleted."
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED -> "This phone could not encode at these settings. Try 720p or 30 fps."
        ExportException.ERROR_CODE_DECODER_INIT_FAILED,
        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "One of the clips uses a format this phone cannot decode."
        else -> "Export failed (${e.errorCodeName})."
    }

    private fun saveToGallery(file: File): Uri {
        val name = "Reelcut_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Reelcut")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: throw ExportFailed("Could not create the video in your gallery.")
            try {
                resolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
                    ?: throw ExportFailed("Could not write the video to your gallery.")
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e as? ExportFailed ?: ExportFailed("Could not save the video.", e)
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Reelcut")
            dir.mkdirs()
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                @Suppress("DEPRECATION")
                put(MediaStore.Video.Media.DATA, dest.absolutePath)
            }
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: Uri.fromFile(dest)
        }
    }
}
