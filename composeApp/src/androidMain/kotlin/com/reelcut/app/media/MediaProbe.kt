package com.reelcut.app.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.reelcut.app.data.AudioClip
import com.reelcut.app.data.AudioKind
import com.reelcut.app.data.Clip
import com.reelcut.app.data.ClipKind
import com.reelcut.app.data.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaInfo(val durationUs: Long, val width: Int, val height: Int, val hasAudio: Boolean)

object MediaProbe {

    /** Reads duration, display size (after rotation) and whether there is audio. Null if not a playable video. */
    fun probe(context: Context, uri: Uri): MediaInfo? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return null
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return null
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) {
                val t = w; w = h; h = t
            }
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            MediaInfo(durMs * 1000, w, h, hasAudio)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** EXIF rotation of a photo in degrees (0, 90, 180, 270). */
    fun photoRotation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    }.getOrDefault(0)

    /** Photo size as displayed (EXIF rotation applied). */
    fun photoSize(context: Context, uri: Uri): Pair<Int, Int>? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return@runCatching null
        if (photoRotation(context, uri) % 180 == 0) opts.outWidth to opts.outHeight
        else opts.outHeight to opts.outWidth
    }.getOrNull()

    /** Decodes a photo at roughly [maxSide] pixels on its long side, upright. */
    fun decodePhoto(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        if (long <= 0) return@runCatching null
        var sample = 1
        while (long / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return@runCatching null
        val rot = photoRotation(context, uri)
        if (rot == 0) raw else {
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
        }
    }.getOrNull()

    fun isImage(context: Context, uri: Uri): Boolean =
        runCatching { context.contentResolver.getType(uri)?.startsWith("image/") == true }.getOrDefault(false)

    /** Turns picked URIs (videos or photos) into clips, keeping read access across restarts. Unreadable files are skipped. */
    suspend fun clipsFrom(context: Context, refs: List<String>): List<Clip> = withContext(Dispatchers.IO) {
        refs.map(Uri::parse).mapNotNull { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            if (mime?.startsWith("image/") == true) {
                val (w, h) = photoSize(context, uri) ?: return@mapNotNull null
                Clip(
                    id = ProjectRepository.newId(),
                    sourceUri = uri.toString(),
                    sourceDurationUs = Clip.MAX_PHOTO_US,
                    width = w,
                    height = h,
                    hasAudio = false,
                    trimStartUs = 0,
                    trimEndUs = Clip.DEFAULT_PHOTO_US,
                    kind = ClipKind.PHOTO,
                    mimeType = mime,
                )
            } else {
                val info = probe(context, uri) ?: return@mapNotNull null
                if (info.durationUs < 100_000L) return@mapNotNull null
                Clip(
                    id = ProjectRepository.newId(),
                    sourceUri = uri.toString(),
                    sourceDurationUs = info.durationUs,
                    width = info.width,
                    height = info.height,
                    hasAudio = info.hasAudio,
                    trimStartUs = 0,
                    trimEndUs = info.durationUs,
                    kind = ClipKind.VIDEO,
                    mimeType = mime,
                )
            }
        }
    }

    /** Name shown for a picked file: its display name without the extension. */
    private fun displayName(context: Context, uri: Uri): String {
        val raw = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "Audio"
        return raw.substringBeforeLast('.', raw).ifBlank { "Audio" }.take(60)
    }

    /** Duration in microseconds of a sound file, or null if it can't be played or has no sound. */
    fun audioDurationUs(context: Context, uri: Uri): Long? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes") return null
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.times(1000)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Turns picked sound files into audio clips, keeping read access across restarts. Unreadable files are skipped. */
    suspend fun audioFrom(context: Context, refs: List<String>): List<AudioClip> = withContext(Dispatchers.IO) {
        refs.map(Uri::parse).mapNotNull { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val dur = audioDurationUs(context, uri) ?: return@mapNotNull null
            if (dur < AudioClip.MIN_US) return@mapNotNull null
            AudioClip(
                id = ProjectRepository.newId(),
                kind = AudioKind.MUSIC,
                name = displayName(context, uri),
                sourceUri = uri.toString(),
                sourceDurationUs = dur,
                trimEndUs = dur,
            )
        }
    }

    /** True if the source file can still be opened. */
    fun isReadable(context: Context, uri: String): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    }.getOrDefault(false)
}
