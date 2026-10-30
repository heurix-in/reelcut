package com.reelcut.app.media

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.reelcut.app.data.Clip
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

    /** Turns picked URIs into clips, keeping read access across restarts. Unreadable files are skipped. */
    suspend fun clipsFrom(context: Context, uris: List<Uri>): List<Clip> = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
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
            )
        }
    }

    /** True if the source file can still be opened. */
    fun isReadable(context: Context, uri: String): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    }.getOrDefault(false)
}
